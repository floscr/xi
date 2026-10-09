(ns xi.server.ws
  "Bun WebSocket server — hosts N rooms over one server app.

   Events are the wire protocol: clients send the same event maps a local
   app would dispatch (:input/submit, :agent/abort, …) and the server
   broadcasts every room-scoped event it processes back to that room's
   clients, who mirror state with the same pure reducers
   (xi.client.ws-transport). The server owns provider effects + the agent
   loop; clients are renderers + input.

   Handshake (auth is transport-level, never dispatched into the app):
     connect → client sends {:type :auth/hello :client-key … :client-name …
               :platform … :user …}
     server  → {:type :auth/ok :user …} when the key is approved (clients.edn
               or the local ~/.config/xi/client-key — see xi.auth), else
               {:type :auth/pending :code \"1234\"} and the connection is
               parked: every other event is answered with {:type :auth/required}.
               Pending requests are broadcast to authed clients as
               {:type :auth/request …}; any of them may answer with
               {:type :auth/approve|:auth/deny :code …}, or the user runs
               `bb serve:approve <code>` (the server polls clients.edn while
               requests are pending). Denied clients get {:type :auth/denied}.
     then    → server sends {:type :lobby/state :rooms […]}
     client  → {:type :room/join :target \"new\"|\"latest\"|room-id :cwd …}
     server  → {:type :room/joined :room-id … :room <snapshot>}
     normal event flow begins; {:type :room/leave} returns to the lobby.

   WS upgrades with a cross-host Origin header are refused — browsers attach
   Origin and WebSockets are not subject to CORS, so without this any webpage
   could open ws://localhost:7474.

   Incoming room events get :room-id forced to the sender's joined room —
   clients can't address rooms they're not in. Sockets live in the
   create-server closure (runtime resources, not app state).

   Users: every admitted connection belongs to a user id — the one
   clients.edn assigns to its key (`xi clients user`), else the one it claimed
   in :auth/hello, else root (xi.util/user-id). It is recorded on the
   client entry (:client/connect), echoed in :auth/ok, and stamped as :user
   on every event the client sends, so room handlers and every mirror see the
   same sender. No authentication: the claim is taken at face value (device
   pairing is the trust check); roles are for extensions.

   Deferred to later phases: :visibility tracking."
  (:require [clojure.string :as str]
            [xi.agent-profile :as profile]
            [xi.auth :as auth]
            [xi.core.state :as core-state]
            [xi.ext.diff.git :as diff-git]
            [xi.ext.user.guard :as user-guard]
            [xi.fx :as fx]
            [xi.server.files :as files]
            [xi.server.room-manager :as rm]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.user-config :as user-config]
            [xi.user-state :as user-state]
            [xi.user-state.store :as user-store]
            [xi.users :as users]
            [xi.util :as util]
            [xi.wire :as wire]))

(def DEFAULT_PORT 7474)
(def DEFAULT_TLS_PORT 7443)

(def DEFAULT_HOST
  "Address the server binds: all interfaces, so localhost, 127.0.0.1, a
   Tailscale address and the LAN all reach it."
  "0.0.0.0")

(def ^:private LOOPBACK_HOST "127.0.0.1")

(def ^:private uuid-re
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn resolve-hosts
  "Addresses to listen on: `host` (--host), else XI_HOST, each comma-separated,
   else DEFAULT_HOST. Loopback is always bound alongside; 0.0.0.0 / :: stand
   alone."
  [host]
  (let [clean (fn [v] (when (string? v) (not-empty (.trim v))))
        listed (->> (str/split (or (clean host)
                                   (clean (aget js/process.env "XI_HOST"))
                                   "")
                              #",")
                    (keep clean)
                    (map #(if (= "localhost" %) LOOPBACK_HOST %)))]
    (if (or (empty? listed) (some #{"0.0.0.0" "::"} listed))
      [DEFAULT_HOST]
      (vec (distinct (cons LOOPBACK_HOST listed))))))

(def ^:private base-no-broadcast
  "Room-scoped event types that are connection bookkeeping, not room state.
   Extensions add theirs via :no-broadcast."
  #{:room/join :room/attach :room/leave :room/list :agent/session-init})

(def ^:private base-lobby-relevant
  "Events after which lobby (roomless) clients get a fresh :lobby/state.
   Extensions add theirs via :lobby-relevant."
  #{:room/create :room/close :room/attach :room/leave
    :dismissed/changed :pinned/changed
    :user/ext-set
    :session/deleted
    :read-state/changed
    :prompt/submit :agent/session-init :agent/turn-end :client/disconnect
    :ui/dialog-open :ui/dialog-response
    :ui/buffer-set :ui/buffer-open :ui/diff-open :ui/buffer-close :ui/buffers-close-all
    :session/buffer-close
    :room/presence})

(defn- ext-event?
  [ev]
  (some-> (:type ev) namespace (.startsWith "ext.")))

(def ^:private server-only-types
  "Event types only the server may dispatch; a client's copies are dropped at
   the socket (they would let it install a user's record, rewrite presence, or
   register as another identity)."
  #{:user/loaded :user/ui-set :user/ext-set :room/presence
    :client/connect :client/disconnect})

(def ^:private pre-join-types
  "Event types a client may send before joining a room."
  #{:room/join :room/leave :room/list})

(def ^:private base-roomless-types
  "Event types processed regardless of room membership (connection-level
   bookkeeping that uses :client-id, not :room-id). Extensions add theirs
   via :roomless-events."
  #{:client/update :user-state/set :session/counts :sessions/all :models/web-list
    :cwd/agents-files :session/content-search :session/web-search
    :diff/web-load :commits/web-load :files/web-list :file/web-read
    :dismissed/toggle :pinned/toggle :session/delete :session/mark-read
    :session/buffer-close
    :rooms/prune})

(defn- gen-client-id []
  (str "c-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1e6)) 36)))

;; ── Lobby payload (impure: reads saved sessions from disk) ────────────────────

(defn- lobby-sessions
  "Saved sessions from disk for the lobby: a curated subset of the full
   summaries. An agent server lists only its agent's sessions."
  [agent-id]
  (->> (if agent-id
         (session/list-personal-agent-sessions agent-id)
         (session/list-all-sessions))
       (mapv #(select-keys % [:session-id :name :cwd :last-accessed :last-opened :timestamp :source]))))

(defn- saved-sessions
  "All saved-session summaries, minus those shadowed by a live room's
   provider-session-id or superseded ids (a duplicate card would show during
   the first turn before :session/sync runs)."
  [st agent-id]
  (let [live-pids (into #{}
                        (comp (mapcat (fn [[_ room]]
                                        (let [sess (:session room)]
                                          (cons (:provider-session-id sess)
                                                (:superseded-cli-session-ids sess)))))
                              (remove nil?))
                        (:rooms st))]
    (cond->> (lobby-sessions agent-id)
      (seq live-pids)
      (filterv #(not (contains? live-pids (:session-id %)))))))

(def ^:private lobby-session-cap
  "Max saved sessions in the lobby broadcast; favorites and live rooms'
   sessions always ride along. The all-sessions view fetches the rest on
   demand."
  100)

(defn- cap-sessions
  "Trim a newest-first summary list to the cap, keeping every session in
   keep-ids. Preserves order."
  [sessions keep-ids]
  (if (<= (count sessions) lobby-session-cap)
    sessions
    (let [recent (into #{}
                       (comp (take lobby-session-cap) (keep :session-id))
                       sessions)]
      (filterv (fn [s]
                 (or (contains? recent (:session-id s))
                     (contains? keep-ids (:session-id s))))
               sessions))))

(def ^:private server-started-at
  "Wall-clock ms when this server booted, sent in the lobby so clients can tell
   sessions active during this run from ones carried over from disk."
  (js/Date.now))

;; ── Claude subscription usage ───────────────────────────────────────────

(def ^:private claude-usage
  "Latest Claude subscription usage reading (nil until the first fetch); rides
   on the lobby broadcast."
  (atom nil))

(defn- read-claude-token
  "OAuth access token from <claude-config-dir>/.credentials.json, nil when missing."
  []
  (try
    (let [fs   (js/require "node:fs")
          path (str (session/claude-config-dir) "/.credentials.json")]
      (when (.existsSync fs path)
        (some-> (.readFileSync fs path "utf8")
                (js/JSON.parse)
                (aget "claudeAiOauth")
                (aget "accessToken"))))
    (catch :default _ nil)))

(def ^:private claude-usage-fetched-at
  "Wall-clock ms of the last fetch attempt, throttling on-demand refreshes."
  (atom 0))

(defn- fetch-claude-usage!
  "Fetch subscription usage from the OAuth endpoint Claude Code's /usage uses;
   calls on-change when the reading differs. Failures keep the last reading."
  [on-change]
  (reset! claude-usage-fetched-at (js/Date.now))
  (when-let [token (read-claude-token)]
    (-> (js/fetch "https://api.anthropic.com/api/oauth/usage"
                  #js {:headers #js {"Authorization"  (str "Bearer " token)
                                     "anthropic-beta" "oauth-2025-04-20"}})
        (.then (fn [^js res] (when (.-ok res) (.json res))))
        (.then (fn [^js data]
                 (when data
                   (let [{:keys [five_hour seven_day limits]}
                         (js->clj data :keywordize-keys true)
                         pct   (fn [u] (some-> u js/Math.round (min 100)))
                         usage {:session  (pct (:utilization five_hour))
                                :weekly   (pct (:utilization seven_day))
                                :severity (or (some #(when (= "session" (:kind %))
                                                       (:severity %))
                                              limits)
                                              "normal")
                                :session-resets-at (:resets_at five_hour)
                                :weekly-resets-at  (:resets_at seven_day)}]
                     (when (and (:session usage) (not= usage @claude-usage))
                       (reset! claude-usage usage)
                       (on-change))))))
        (.catch (fn [_] nil)))))

(defn- claude-credentials-mtime
  []
  (try (.-mtimeMs (.statSync (js/require "node:fs")
                             (str (session/claude-config-dir) "/.credentials.json")))
       (catch :default _ nil)))

(defn- watch-claude-credentials!
  "Refetch usage as soon as the credentials file changes (account switch, token refresh)."
  [on-change]
  (let [seen (atom (claude-credentials-mtime))]
    (js/setInterval
     (fn []
       (let [m (claude-credentials-mtime)]
         (when (not= m @seen)
           (reset! seen m)
           (fetch-claude-usage! on-change))))
     10000)))

(defn- client-user
  [st cid]
  (util/user-id (get-in st [:connection :clients cid :user])))

(defn- read-state-of
  "The {session-id seen-response-count} in a user's `stored` state; a user who
   never marked a chat starts from the old global file."
  [stored]
  (or (:read-state stored) (session/load-legacy-read-state)))

(defn- for-user
  "The lobby/session-list `payload` as `user` sees it: their dismissed / pinned
   / extension flags on every session, and (for a lobby) their read markers and
   preferred model."
  [payload user]
  (let [stored (user-store/load-state user)
        flags  (assoc (user-state/session-flags stored)
                      :pinned? (set (filter string? (:pinned stored))))
        lobby? (= :lobby/state (:type payload))]
    (cond-> (-> payload
                (update :sessions session/annotate-dismissed
                        (set (:dismissed stored)))
                (update :sessions user-state/annotate-session-flags flags))
      lobby?                    (update :rooms user-state/annotate-session-flags flags)
      lobby?                    (assoc :read (read-state-of stored))
      (and lobby? (:preferred-model stored))
      (assoc :model (:preferred-model stored)))))

(defn- lobby-base
  "The :lobby/state payload shared by every user: live rooms + saved sessions +
   the server's default :model; `for-user` adds what differs."
  [st agent-id model]
  (let [rooms    (rm/room-summaries st)
        ;; An agent server stays uncapped: its corpus is small.
        sessions (cond-> (saved-sessions st agent-id)
                   (nil? agent-id)
                   (cap-sessions (into (user-store/all-flagged-session-ids)
                                       (keep :session-id)
                                       rooms)))
        counts   (session/count-session-responses
                  (into [] (keep :session-id) sessions)
                  {:personal-agent? (some? agent-id)})]
    (cond-> {:type       :lobby/state
             :rooms      rooms
             :sessions   sessions
             :counts     counts
             :buffers    (rm/session-buffers st)
             :profiles   (users/public-profiles rooms)
             :user-ids   (users/declared-ids)
             :started-at server-started-at}
      model    (assoc :model model)
      agent-id (assoc :agent-id agent-id)
      @claude-usage   (assoc :claude-usage @claude-usage))))

(defn- lobby-payload
  [st agent-id model user]
  (wire/encode (for-user (lobby-base st agent-id model) user)))

;; ── Static file serving (resources/public, SPA fallback) ──────────────────────

(defn- resolve-public-dir
  "resources/public, relative to the compiled script, else cwd. Resolved once at start."
  []
  (let [path (js/require "node:path")
        fs   (js/require "node:fs")
        script-dir (when (exists? js/__dirname) js/__dirname)
        candidates (cond-> []
                     script-dir (conj (.resolve path script-dir ".." "resources" "public"))
                     true       (conj (.resolve path (.cwd js/process) "resources" "public")))]
    (or (some (fn [d] (when (.existsSync fs (.join path d "index.html")) d)) candidates)
        (.resolve path (.cwd js/process) "resources" "public"))))

(defn- resolve-tls
  "TLS cert/key from XI_TLS_CERT / XI_TLS_KEY, else
   ~/.config/xi/tls/xi.{crt,key}; nil for plain HTTP. HTTPS matters because iOS
   only keeps a secure origin's localStorage durable (the client-key)."
  []
  (let [fs   (js/require "node:fs")
        path (js/require "node:path")
        os   (js/require "node:os")
        dfl  (.join path (.homedir os) ".config" "xi" "tls")
        cert (or (aget js/process.env "XI_TLS_CERT") (.join path dfl "xi.crt"))
        key  (or (aget js/process.env "XI_TLS_KEY")  (.join path dfl "xi.key"))
        explicit? (boolean (or (aget js/process.env "XI_TLS_CERT")
                               (aget js/process.env "XI_TLS_KEY")))]
    (cond
      (and (.existsSync fs cert) (.existsSync fs key))
      #js {:cert (.readFileSync fs cert) :key (.readFileSync fs key)}

      explicit?
      (do (js/console.error
           (str "[ws] XI_TLS_CERT/XI_TLS_KEY set but file(s) missing — "
                "serving plain HTTP (cert=" cert " key=" key ")"))
          nil)

      :else nil)))

(defn- serve-static
  "Serve a file from public-dir, SPA-falling back to index.html for
   extensionless router paths. `icon-variant` (\"personal\", \"green\") picks
   the favicon served at /apple-touch-icon.png."
  [public-dir ^js req icon-variant]
  (let [path (js/require "node:path")
        url  (js/URL. (.-url req))
        pathname (js/decodeURIComponent (.-pathname url))
        rel  (if (= "/" pathname) "index.html" (.replace pathname #"^/+" ""))
        rel  (if (and (= rel "apple-touch-icon.png")
                      (contains? #{"personal" "green"} icon-variant))
               (str "apple-touch-icon-" icon-variant ".png")
               rel)
        ;; Normalize + contain to public-dir (no path traversal)
        full (.normalize path (.join path public-dir rel))
        index (.join path public-dir "index.html")
        has-ext? (re-find #"\.[a-zA-Z0-9]+$" rel)
        ;; SPA route prefixes — always fall back to index.html
        spa-route? (re-find #"^(chat)(/|$)" rel)
        ;; No Cache-Control means iOS/WebKit applies aggressive heuristic
        ;; caching, so edited CSS/JS can stay stale for a long time. Force
        ;; revalidation on every request to keep the PWA in sync with builds.
        serve (fn [file status]
                (js/Response. (js/Bun.file file)
                              #js {:status status
                                   :headers #js {"Cache-Control" "no-cache"}}))]
    (if-not (.startsWith full public-dir)
      (js/Promise.resolve (js/Response. "Forbidden" #js {:status 403}))
      (-> (.exists (js/Bun.file full))
          (.then (fn [exists?]
                   (cond
                     exists?              (serve full 200)
                     (or spa-route?
                         (not has-ext?))  (serve index 200)
                     :else               (js/Response. "Not found" #js {:status 404}))))))))

(defn create-server
  "Build the WS server's effect handlers and starter; the app is wired in via
   :start!. opts: :server-opts {:model :effort} room defaults; :providers id →
   provider map (model listing); :agent-id run as a named agent
   (xi.agent-profile; nil = coding server); :ext-system-prompt-parts (fn [cwd]
   → parts); :room-ext-init ext-id → initial room-scoped state (or a 0-arg fn,
   read per room); :ext the composed extension map (xi.ext.core/compose).
   Returns {:fx :start!}, :start! being (fn [app {:keys [port]}] → {:port
   :stop!})."
  [{:keys [server-opts providers agent-id ext-system-prompt-parts room-ext-init ext]}]
  (let [sockets (js/Map.)
        agent?  (some? agent-id)
        ;; XI_ICON: "desktop" (default), "personal" or "green".
        icon-variant (or (some-> (aget js/process.env "XI_ICON")
                                  (.trim)
                                  (as-> v (when (pos? (.-length v)) v)))
                         "desktop")
        send!   (fn [client-id payload]
                  (when-let [ws (.get sockets client-id)]
                    (try (.send ws payload) (catch :default _ nil))))
        send-event!    (fn [client-id event] (send! client-id (wire/encode event)))
        no-broadcast   (into base-no-broadcast (:no-broadcast ext))
        originator-only (into #{} (:originator-only ext))
        lobby-relevant (into base-lobby-relevant (:lobby-relevant ext))
        roomless-types (into base-roomless-types (:roomless-events ext))
        ext-fx         (apply merge {}
                              (map (fn [f] (f {:send! send-event!}))
                                   (:server-fx-fns ext)))
        ;; Provision {:cwd :session :room} for a fresh or resumed room (session
        ;; + system prompt read per cwd; an agent profile read per room).
        ;; Shared by :room/setup and the HTTP /api/rooms endpoint.
        build-room
        (fn [{:keys [cwd summary model effort user session-id]}]
          (let [cwd (or (:cwd summary) cwd (.cwd js/process))
                prof (when agent? (profile/load agent-id))
                system-parts (if prof
                               (profile/system-parts prof)
                               (into (system-prompt/load-agents-parts cwd)
                                     (when ext-system-prompt-parts
                                       (ext-system-prompt-parts cwd))))
                system (system-prompt/parts->system system-parts)
                session (if summary
                          (do (session/touch-summary! summary)
                              (session/load-session summary))
                          (cond-> (session/create-session
                                   cwd (cond-> {:user (util/user-id user)}
                                         agent? (assoc :agent agent-id)))
                            ;; A session id with nothing on disk (a blank chat
                            ;; that outlived its room) keeps its id, so the
                            ;; client's URL stays valid. It ends up in a file
                            ;; path, so only a well-formed uuid is honoured.
                            (and session-id (re-matches uuid-re session-id))
                            (assoc :id session-id)))]
            {:cwd     cwd
             :session session
             :room    {:model        (or model
                                         (:preferred-model (user-store/load-state user))
                                         (:model server-opts))
                       :effort       (or effort (:effort server-opts))
                       :cwd          cwd
                       :system       system
                       :system-parts system-parts
                       :agents-files (when-not agent?
                                       (system-prompt/find-agents-md cwd))
                       :session      session
                       :ext          (cond-> (if (fn? room-ext-init) (room-ext-init) room-ext-init)
                                       prof (merge (profile/room-ext prof)))
                       :agent-id     agent-id
                       :only-tools   (:tools prof)
                       :created      (js/Date.now)}}))]
    {:fx
     (merge
     {:ws/send-to
      (fn [_ {:keys [client-id event]}]
        (send! client-id (wire/encode event)))

      ;; Persist one piece of a user's UI state, then tell every device of
      ;; that user (the sender too, so it converges on what was stored).
      :user-state/save
      (fn [{:keys [get-state dispatch!]} {:keys [user key value]}]
        (when (user-store/set-key! user key value)
          (dispatch! {:type :user/ui-set :user user :key key :value value})
          (doseq [[cid client] (get-in (get-state) [:connection :clients])
                  :when (= user (:user client))]
            (send-event! cid {:type :user-state/changed :key key :value value}))))

      ;; Provision a room, then re-enter the pure path via :room/create +
      ;; :room/attach. With :session-id, resume a saved session into it.
      :room/setup
      (fn [{:keys [dispatch! get-state]} {:keys [client-id room-id cwd model session-id cached-msg-hash cached-msg-count join-token]}]
        (let [summary (when session-id
                        (if agent?
                          (session/find-personal-agent-session-by-id session-id agent-id)
                          (session/find-session-by-id session-id)))
              user    (get-in (get-state) [:connection :clients client-id :user])
              {:keys [session room]} (build-room {:cwd cwd :summary summary :model model :user user
                                                  :session-id session-id})]
          (dispatch! {:type :room/create :room-id room-id :room room})
          (dispatch! (cond-> {:type :room/attach :client-id client-id :room-id room-id}
                       join-token (assoc :join-token join-token)))
          (when summary
            ;; The client's cache is compared by the hash of the wire form
            ;; (same cljs on both sides): an identical hash gets a tiny
            ;; :session/current, a clean prefix (sessions are append-only on
            ;; disk; /compact breaks it) gets only the new tail, else the full
            ;; resume. The server mirror always gets the full history.
            (let [messages (vec (session/truncate-message-results
                                 (session/read-session-messages summary)))
                  msg-count (count messages)
                  msg-hash (hash messages)
                  current? (= cached-msg-hash msg-hash)
                  prefix?  (and (not current?)
                                (integer? cached-msg-count)
                                (< 0 cached-msg-count msg-count)
                                (= cached-msg-hash (hash (subvec messages 0 cached-msg-count))))]
              (dispatch! {:type :session/resumed :room-id room-id
                          :session session :summary summary
                          :messages messages :msg-hash msg-hash :msg-count msg-count
                          :no-broadcast? (or current? prefix?)})
              (cond
                current?
                (dispatch! {:type :session/current :room-id room-id
                            :session-id session-id :msg-hash msg-hash :msg-count msg-count})
                prefix?
                (dispatch! {:type :session/resumed-tail :room-id room-id
                            :session-id session-id
                            :base-hash cached-msg-hash :base-count cached-msg-count
                            :messages (subvec messages cached-msg-count)
                            :msg-hash msg-hash :msg-count msg-count}))
              ;; Auto-resume a turn cut off by a hard restart, unless the
              ;; transcript shows it finished anyway.
              (when (:interrupted-at summary)
                (session/clear-interrupted! (:filepath summary))
                (when-not (session/turn-completed? summary)
                  (dispatch! {:type :prompt/submit :room-id room-id :text "continue"})))))))

      ;; Open a background chat seeded with a user message (:chat/start,
      ;; xi.server.room-manager); the requesting client, if any, is sent to it.
      :chat/start
      (fn [{:keys [dispatch!]} {:keys [client-id cwd text]}]
        (let [room-id (str "r-" (.toString (js/Date.now) 36)
                           "-" (.toString (rand-int 1000000) 36))
              {:keys [session room]} (build-room {:cwd cwd})]
          (dispatch! {:type :room/create :room-id room-id :room room})
          (dispatch! {:type :prompt/submit :room-id room-id :text text})
          (when client-id
            (send-event! client-id {:type       :route/navigate
                                    :page       :chat
                                    :session-id (:id session)}))))

      :lobby/send
      (fn [{:keys [state]} {:keys [client-id]}]
        (send! client-id (lobby-payload state agent-id (:model server-opts)
                                        (client-user state client-id))))

      :session/counts-reply
      (fn [_ {:keys [client-id session-ids]}]
        (send! client-id (wire/encode {:type   :session/counts-result
                                       :counts (session/count-session-responses
                                                session-ids
                                                {:personal-agent? agent?})})))

      ;; The uncapped session list, for the all-sessions view.
      :sessions/all-reply
      (fn [{:keys [state]} {:keys [client-id]}]
        (let [sessions (saved-sessions state agent-id)
              counts   (session/count-session-responses
                        (into [] (keep :session-id) sessions)
                        {:personal-agent? agent?})]
          (send! client-id (wire/encode
                            (-> {:type     :sessions/all-result
                                 :sessions sessions
                                 :counts   counts}
                                (for-user (client-user state client-id)))))))

      ;; AGENTS.md files for a cwd, for a virtual web chat's launch header.
      :cwd/agents-files-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode {:type         :cwd/agents-files-result
                                       :cwd          cwd
                                       :agents-files (when-not agent?
                                                       (system-prompt/find-agents-md cwd))})))

      :models/web-list-reply
      (fn [_ {:keys [client-id]}]
        (fx/web-model-list-reply-fx
         providers
         (fn [event] (send! client-id (wire/encode event)))))

      :session/content-search-reply
      (fn [_ {:keys [client-id key query cwd]}]
        (send! client-id (wire/encode {:type        :session/content-search-result
                                       :key         key
                                       :query       query
                                       :session-ids (session/content-search
                                                     cwd query
                                                     {:personal-agent? agent?})})))

      ;; Palette session search: summaries + snippets, scoped to `cwd` (nil =
      ;; all). A blank query lists the newest sessions.
      :session/web-search-reply
      (fn [_ {:keys [client-id query cwd names-only?]}]
        (send! client-id
               (wire/encode
                {:type     :session/web-search-result
                 :query    query
                 :sessions (->> (if (str/blank? query)
                                  (session/recent-sessions
                                   cwd {:personal-agent? agent?})
                                  (session/search-sessions
                                   cwd query {:personal-agent? agent?
                                              :names-only? names-only?}))
                                (take 50)
                                (mapv #(select-keys % [:session-id :name :cwd
                                                       :last-accessed :timestamp
                                                       :snippet])))})))

      ;; The *-changed events below are lobby-relevant, so the tap rebroadcasts
      ;; each user's lobby with their own flags.
      :dismissed/toggle-reply
      (fn [{:keys [dispatch!]} {:keys [session-id user]}]
        (user-store/toggle-dismissed! user session-id)
        (dispatch! {:type :dismissed/changed}))

      :pinned/toggle-reply
      (fn [{:keys [dispatch!]} {:keys [session-id user]}]
        (user-store/toggle-pinned! user session-id)
        (dispatch! {:type :pinned/changed}))

      :session/delete-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (when-let [summary (or (session/find-session-by-id session-id)
                               (session/find-personal-agent-session-by-id session-id))]
          (session/delete-session! summary))
        (dispatch! {:type :session/deleted}))

      :session/mark-read-reply
      (fn [{:keys [dispatch!]} {:keys [session-id user]}]
        (let [n (get (session/count-session-responses
                      [session-id] {:personal-agent? agent?})
                     session-id 0)]
          (user-store/mark-read! user (read-state-of (user-store/load-state user))
                                 session-id n)
          (dispatch! {:type :read-state/changed})))

      :diff/web-load-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode {:type :diff/web-load-result
                                       :cwd  cwd
                                       :text (diff-git/all-git-changes-text cwd)})))

      :commits/web-load-reply
      (fn [_ {:keys [client-id cwd created]}]
        (let [base (diff-git/session-base-commit cwd created)]
          (send! client-id (wire/encode {:type    :commits/web-load-result
                                         :commits (or (diff-git/session-commits-list cwd base) [])}))))

      :files/web-list-reply
      (fn [_ {:keys [client-id cwd path]}]
        (send! client-id (wire/encode (assoc (files/list-dir path cwd)
                                             :type :files/web-list-result))))

      :file/web-read-reply
      (fn [_ {:keys [client-id cwd path]}]
        (send! client-id (wire/encode (assoc (files/read-file path cwd)
                                             :type :file/web-read-result))))

      :files/web-tree-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode (assoc (files/list-files cwd)
                                             :type :files/web-tree-result))))

      ;; TUI-owned effects a server-side command may emit; the mirroring
      ;; client re-derives them locally (xi.client.ws-transport).
      :app/quit       (fn [_ _] nil)
      :app/reload     (fn [_ _] nil)
      :clipboard/copy (fn [_ _] nil)}

     ext-fx)

     :start!
     (fn [{:keys [dispatch! state add-tap!]} {:keys [port host]}]
       (let [port (or port DEFAULT_PORT)
             hosts (resolve-hosts host)
             public-dir (resolve-public-dir)
             ;; An agent server is single-user/local: no HTTPS, no pairing.
             tls        (when-not agent? (resolve-tls))
             ;; Pairing requests awaiting approval: code → #js {:cid :key :name
             ;; :platform}, mirrored to pending-clients.edn for `bb serve:approve`.
             pending (js/Map.)
             _ (auth/write-pending! {})
             authed? (fn [^js ws] (true? (.. ws -data -authed)))
             notify-authed!
             (fn [event]
               (let [payload (wire/encode event)]
                 (doseq [^js ws (es6-iterator-seq (.values sockets))]
                   (when (authed? ws)
                     (send! (.. ws -data -cid) payload)))))
             admit!
             (fn [^js ws]
               (let [cid  (.. ws -data -cid)
                     ;; clients.edn's device assignment wins over the claimed user.
                     user (util/user-id (or (auth/user-for-key (.. ws -data -clientKey))
                                            (.. ws -data -clientUser)))]
                 (set! (.. ws -data -authed) true)
                 (set! (.. ws -data -user) user)
                 (dispatch! (users/loaded-event user))
                 (dispatch! {:type :client/connect :client-id cid
                             :client (cond-> {:kind :remote :user user}
                                       (.. ws -data -clientPid)
                                       (assoc :pid (.. ws -data -clientPid))
                                       (.. ws -data -clientPlatform)
                                       (assoc :platform (.. ws -data -clientPlatform)))})
                 (send-event! cid {:type :auth/ok :user user :client-id cid})
                 (send-event! cid {:type :user-state/state :user user
                                   :state (user-state/client-view (user-store/load-state user))})
                 (send-event! cid {:type :keys/config :keys (user-config/keys-config)})
                 (send! cid (lobby-payload @state agent-id (:model server-opts) user))))
             resolve-pending!
             (fn [code approved?]
               (when-let [^js e (.get pending code)]
                 (.delete pending code)
                 (auth/remove-pending! code)
                 (when-let [^js ws (.get sockets (.-cid e))]
                   (set! (.. ws -data -pendingCode) nil)
                   (if approved?
                     (admit! ws)
                     (do (send-event! (.-cid e) {:type :auth/denied :reason "denied"})
                         (try (.close ws) (catch :default _ nil)))))
                 (notify-authed! {:type :auth/resolved :code code :approved? approved?})))
             handle-hello!
             (fn [^js ws {:keys [client-key client-name platform pid user]}]
               (let [cid (.. ws -data -cid)]
                 ;; Stashed on the socket for admit!, which may run after pairing.
                 (when pid (set! (.. ws -data -clientPid) pid))
                 (when platform (set! (.. ws -data -clientPlatform) platform))
                 (when (string? client-key) (set! (.. ws -data -clientKey) client-key))
                 (when user (set! (.. ws -data -clientUser) user))
                 (cond
                   (authed? ws) nil

                   agent? (admit! ws)

                   (not (and (string? client-key) (>= (count client-key) 16)))
                   (send-event! cid {:type :auth/denied :reason "invalid client key"})

                   (auth/approved? client-key)
                   (do (auth/touch! client-key {:name client-name :platform platform})
                       (admit! ws))

                   :else
                   (let [code (or (.. ws -data -pendingCode)
                                  (auth/gen-code (set (es6-iterator-seq (.keys pending)))))]
                     (.set pending code #js {:cid cid :key client-key
                                             :name (or client-name "unknown")
                                             :platform (or platform "unknown")})
                     (set! (.. ws -data -pendingCode) code)
                     (auth/add-pending! code {:client-key   client-key
                                              :client-name  client-name
                                              :platform     platform
                                              :requested-at (js/Date.now)})
                     (send-event! cid {:type :auth/pending :code code})
                     (js/console.error (str "[ws] pairing request from "
                                            (or client-name "unknown")
                                            " (" (or platform "?") ")"
                                            " — approve with: bb serve:approve " code))
                     (notify-authed! {:type :auth/request :code code
                                      :client-name client-name :platform platform})))))
             ;; Admit waiting sockets once `bb serve:approve` lands their key
             ;; in clients.edn.
             auth-poll
             (js/setInterval
              (fn []
                (when (pos? (.-size pending))
                  (let [approved (auth/approved-clients)]
                    (doseq [code (vec (es6-iterator-seq (.keys pending)))]
                      (let [^js e (.get pending code)]
                        (when (and e (contains? approved (.-key e)))
                          (resolve-pending! code true)))))))
              2000)
             ;; {user encoded-payload} of the last broadcast; byte-identical
             ;; lobbies are not re-sent.
             last-lobby (atom {})
             ;; To every connected client (the drawer lives on every page), one
             ;; payload per user over a shared base.
             broadcast-lobby!
             (fn [st]
               (let [by-user (group-by #(client-user st %)
                                       (keys (get-in st [:connection :clients])))]
                 (when (seq by-user)
                   (let [base (lobby-base st agent-id (:model server-opts))
                         sent @last-lobby]
                     (reset! last-lobby
                             (into {}
                                   (map (fn [[user cids]]
                                          (let [payload (wire/encode (for-user base user))]
                                            (when (not= payload (get sent user))
                                              (doseq [cid cids] (send! cid payload)))
                                            [user payload])))
                                   by-user))))))
             ;; Lobby-relevant events arrive in bursts; one trailing-edge timer
             ;; per burst, state read fresh at fire time.
             lobby-timer (atom nil)
             schedule-lobby-broadcast!
             (fn []
               (when (nil? @lobby-timer)
                 (reset! lobby-timer
                         (js/setTimeout
                          (fn []
                            (reset! lobby-timer nil)
                            (broadcast-lobby! @state))
                          150))))
             _ (fetch-claude-usage! schedule-lobby-broadcast!)
             _ (js/setInterval #(fetch-claude-usage! schedule-lobby-broadcast!)
                               (* 5 60 1000))
             _ (watch-claude-credentials! schedule-lobby-broadcast!)
             ;; GET /api/rooms/status?ids=…: "running" (a live room mid-turn),
             ;; "error" (aborted or interrupted, from the persisted metadata,
             ;; since finished background rooms are reaped), "complete", "unknown".
             session-status
             (fn [id]
               (let [busy? (some (fn [[_ room]]
                                   (let [s (:session room)]
                                     (and (or (= id (:id s))
                                              (= id (:provider-session-id s))
                                              (= id (:cli-session-id s)))
                                          (get-in room [:agent :busy?]))))
                                 (:rooms @state))]
                 (if busy?
                   "running"
                   (if-let [sm (session/find-session-by-id id)]
                     (if (or (:aborted-at sm) (:interrupted-at sm))
                       "error" "complete")
                     "unknown"))))
             ;; POST /api/rooms {prompt?, cwd?, model?} creates a clientless
             ;; room (optionally starting a turn) and returns its web URL. Auth:
             ;; the client-key as Authorization: Bearer or X-Xi-Client-Key.
             handle-api!
             (fn [^js req pathname]
               (let [headers (.-headers req)
                     method  (.-method req)
                     key (or (some-> (.get headers "authorization")
                                     (.replace #"(?i)^bearer\s+" ""))
                             (.get headers "x-xi-client-key"))
                     authed? (or agent? (auth/approved? key))
                     json-resp (fn [status obj]
                                 (js/Response. (js/JSON.stringify obj)
                                               #js {:status  status
                                                    :headers #js {"Content-Type" "application/json"}}))]
                 (cond
                   (and (= pathname "/api/rooms/status") (= "GET" method))
                   (if-not authed?
                     (json-resp 401 #js {:error "unauthorized"})
                     (let [url (js/URL. (.-url req))
                           ids (->> (some-> (.get (.-searchParams url) "ids")
                                            (.split ","))
                                    seq
                                    (map #(.trim %))
                                    (filter #(pos? (.-length %)))
                                    distinct)
                           statuses (reduce (fn [o id] (aset o id (session-status id)) o)
                                            #js {} ids)]
                       (json-resp 200 #js {:statuses statuses})))

                   (not= pathname "/api/rooms")
                   (json-resp 404 #js {:error "not found"})

                   (not= "POST" method)
                   (json-resp 405 #js {:error "method not allowed"})

                   (not authed?)
                   (json-resp 401 #js {:error "unauthorized"})

                   :else
                   (do
                     (-> (.json req)
                         (.then
                          (fn [^js body]
                            (let [prompt (some-> (aget body "prompt") str)
                                  cwd    (some-> (aget body "cwd") str)
                                  model  (some-> (aget body "model") str)
                                  room-id (str "r-" (.toString (js/Date.now) 36)
                                               "-" (.toString (rand-int 1000000) 36))
                                  {:keys [session room]} (build-room {:cwd cwd :model model
                                                                      :user (get-in @state [:connection :user])})
                                  session-id (:id session)
                                  host (or (.get headers "host") (str "localhost:" port))]
                              (dispatch! {:type :room/create :room-id room-id :room room})
                              (when (seq prompt)
                                (dispatch! {:type :prompt/submit :room-id room-id :text prompt}))
                              (js/Response. (js/JSON.stringify
                                             #js {:room-id    room-id
                                                  :session-id session-id
                                                  :cwd        (:cwd room)
                                                  :url        (str "http://" host "/chat/" session-id)})
                                            #js {:status  200
                                                 :headers #js {"Content-Type" "application/json"}}))))
                         (.catch
                          (fn [err]
                            (js/Response. (js/JSON.stringify #js {:error (str err)})
                                          #js {:status  400
                                               :headers #js {"Content-Type" "application/json"}}))))))))
             opts
             #js {:port     port
                   :fetch
                   (fn [^js req ^js srv]
                     (let [headers  (.-headers req)
                           pathname (try (.-pathname (js/URL. (.-url req)))
                                         (catch :default _ ""))
                           upgrade? (some-> (.get headers "upgrade")
                                            (.toLowerCase)
                                            (= "websocket"))]
                       (cond
                         (.startsWith pathname "/api/")
                         (handle-api! req pathname)

                         (not upgrade?)
                         (serve-static public-dir req icon-variant)

                         :else
                         ;; Same-host Origin only (port ignored: shadow's dev-http
                         ;; serves the page on 8100). Key auth gates the rest.
                         (let [origin-host (some-> (.get headers "origin")
                                                   (as-> o (try (.-hostname (js/URL. o))
                                                                (catch :default _ "<invalid>"))))
                               req-host    (try (.-hostname (js/URL. (str "http://" (.get headers "host"))))
                                                (catch :default _ nil))]
                           (cond
                             (and origin-host (not= origin-host req-host))
                             (js/Response. "Forbidden origin" #js {:status 403})

                             (.upgrade srv req #js {:data #js {:cid (gen-client-id)
                                                               :authed false
                                                               :pendingCode nil}})
                             js/undefined

                             :else
                             (js/Response. "Upgrade failed" #js {:status 400}))))))
                   :websocket
                   #js {;; several base64 images per prompt
                        :maxPayloadLength (* 100 1024 1024)
                        ;; the transit lobby payload deflates ~8-10x
                        :perMessageDeflate true

                        ;; The app learns of the client at admit!, after :auth/hello.
                        :open
                        (fn [^js ws]
                          (.set sockets (.. ws -data -cid) ws))

                        :message
                        (fn [^js ws data]
                          (let [cid (.. ws -data -cid)]
                            (if-let [ev (wire/decode data)]
                              (let [room-id (get-in @state [:connection :clients cid :room-id])
                                    ;; Push addressing (:to-users / :to-client) is a
                                    ;; server half's alone; an extension event from a
                                    ;; client is user-initiated (xi.ext.user.guard).
                                    ev (cond-> (-> ev
                                                   (assoc :client-id cid)
                                                   (dissoc :to-users :to-client))
                                         (authed? ws) (assoc :user (.. ws -data -user))
                                         (ext-event? ev) (assoc ::user-guard/user-initiated true))]
                                (cond
                                  ;; Transport-level auth, never dispatched.
                                  (= :auth/hello (:type ev))
                                  (handle-hello! ws ev)

                                  (not (authed? ws))
                                  (send! cid (wire/encode {:type :auth/required}))

                                  (server-only-types (:type ev))
                                  (send! cid (wire/encode {:type :error
                                                           :text (str "Not a client event: " (:type ev))}))

                                  (= :auth/approve (:type ev))
                                  (when-let [^js e (.get pending (:code ev))]
                                    (auth/approve! (.-key e) {:name (.-name e)
                                                              :platform (.-platform e)})
                                    (resolve-pending! (:code ev) true))

                                  (= :auth/deny (:type ev))
                                  (resolve-pending! (:code ev) false)

                                  ;; Transport-level, throttled to 30s.
                                  (= :usage/refresh (:type ev))
                                  (when (> (- (js/Date.now) @claude-usage-fetched-at)
                                           (* 30 1000))
                                    (fetch-claude-usage! schedule-lobby-broadcast!))

                                  (or (pre-join-types (:type ev))
                                      (roomless-types (:type ev)))
                                  (dispatch! ev)

                                  room-id
                                  (dispatch! (assoc ev :room-id room-id))

                                  ;; An extension's own event needs no room.
                                  (ext-event? ev)
                                  (dispatch! ev)

                                  :else
                                  (send! cid (wire/encode
                                              {:type :error
                                               :text "Join a room first: {:type :room/join :target \"latest\"}"}))))
                              (send! cid (wire/encode {:type :error
                                                       :text "Unreadable event"})))))

                        :close
                        (fn [^js ws _code _reason]
                          (let [cid (.. ws -data -cid)]
                            (when-let [code (.. ws -data -pendingCode)]
                              (.delete pending code)
                              (auth/remove-pending! code)
                              (notify-authed! {:type :auth/resolved :code code :approved? false}))
                            ;; Dispatch before dropping the socket: cleanup effects
                            ;; may still broadcast.
                            (when (authed? ws)
                              (dispatch! {:type :client/disconnect :client-id cid}))
                            (.delete sockets cid)))}}
             ;; TLS is served on a second port sharing the handlers (Bun binds
             ;; one protocol per port); 7474 stays plain ws://.
             tls-port (or (some-> (aget js/process.env "XI_TLS_PORT") js/parseInt)
                          DEFAULT_TLS_PORT)
             servers (mapv #(js/Bun.serve (js/Object.assign #js {:hostname %} opts))
                           hosts)
             tls-servers (when tls
                           (mapv #(js/Bun.serve
                                   #js {:port      tls-port
                                        :hostname  %
                                        :tls       tls
                                        :fetch     (.-fetch opts)
                                        :websocket (.-websocket opts)})
                                 hosts))]

         ;; The transport is a tap: every processed room event is echoed to the
         ;; room's clients, sender included (clients never apply their own input
         ;; locally). :no-broadcast? lets one event opt out at runtime;
         ;; originator-only events go to the asking client alone.
         (add-tap!
          (fn [event st]
            (let [room-id (:room-id event)]
              (when (and room-id
                         (not (no-broadcast (:type event)))
                         (not (:no-broadcast? event)))
                (if (and (originator-only (:type event)) (:client-id event))
                  (when (contains? (set (rm/clients-in-room st room-id))
                                   (:client-id event))
                    (send! (:client-id event) (wire/encode event)))
                  (when-let [cids (seq (rm/clients-in-room st room-id))]
                    (let [payload (wire/encode event)]
                      (doseq [cid cids] (send! cid payload)))))))
            ;; A user extension's push (:to-users / :to-client) goes out wrapped
            ;; as :user-ext/push for the web half's reducer (xi.web.user-ext).
            (when (and (ext-event? event)
                       (or (:to-users event) (:to-client event)))
              (let [users   (set (:to-users event))
                    targets (if (seq users)
                              (keep (fn [[cid c]] (when (contains? users (:user c)) cid))
                                    (get-in st [:connection :clients]))
                              [(:to-client event)])
                    payload (wire/encode
                             {:type   :user-ext/push
                              :ext-id (keyword (subs (namespace (:type event)) 4))
                              :event  (dissoc event :to-users :to-client :client-id
                                              ::user-guard/user-initiated)})]
                (doseq [cid targets :when cid] (send! cid payload))))
            ;; A new prompt un-hides the session for its sender, before the lobby
            ;; rebroadcast below.
            (when (= :prompt/submit (:type event))
              (when-let [sid (get-in st [:rooms (:room-id event) :session :id])]
                (user-store/undismiss! (core-state/event-user st event) sid)))
            (when (lobby-relevant (:type event))
              (schedule-lobby-broadcast!))))

         (js/console.error (str "[ws] Listening on ws://" (str/join "," hosts) ":" port
                                (when tls-servers
                                  (str " + wss://" (str/join "," hosts) ":" tls-port))
                                (when-not (every? #{"127.0.0.1" "::1"} hosts)
                                  " (reachable from the network)")))
         {:port  port
          :stop! (fn []
                   (js/clearInterval auth-poll)
                   (when-let [t @lobby-timer]
                     (js/clearTimeout t)
                     (reset! lobby-timer nil))
                   (doseq [^js s (concat servers tls-servers)]
                     (.stop s)))}))}))
