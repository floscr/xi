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
  "Addresses to listen on: an explicit `host` (--host), else XI_HOST — each a
   comma-separated list — else DEFAULT_HOST. Blank values count as unset.
   Extra addresses only widen: loopback is always bound alongside them, so
   `XI_HOST=100.64.0.2` serves localhost and the Tailscale address. 0.0.0.0 (or
   ::) already covers every address and stands alone."
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
    :dismissed/changed
    ;; an extension's per-user state may carry session flags (for-user)
    :user/ext-set
    :session/deleted
    :read-state/changed
    :prompt/submit :agent/session-init :agent/turn-end :client/disconnect
    :ui/dialog-open :ui/dialog-response})

(defn- ext-event?
  "An event of an extension (`:ext.<id>/…`)."
  [ev]
  (some-> (:type ev) namespace (.startsWith "ext.")))

(def ^:private server-only-types
  "Event types only the server itself may dispatch. A client's events go
   through the same dispatch (they are the wire protocol), so without this a
   connected client could install a user's record or what an extension keeps
   about them, rewrite a room's presence, or register itself under another
   identity. They are dropped at the socket."
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
    :dismissed/toggle :session/delete :session/mark-read
    :rooms/prune})

(defn- gen-client-id []
  (str "c-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1e6)) 36)))

;; ── Lobby payload (impure: reads saved sessions from disk) ────────────────────

(defn- lobby-sessions
  "Saved sessions from disk for the lobby/home view — a curated subset of
   the full summaries (id, name, cwd, mtime, source). An agent server lists
   only its agent's sessions."
  [agent-id]
  (->> (if agent-id
         (session/list-personal-agent-sessions agent-id)
         (session/list-all-sessions))
       (mapv #(select-keys % [:session-id :name :cwd :last-accessed :timestamp :source]))))

(defn- saved-sessions
  "All saved-session summaries, minus those shadowed by a live room's
   provider-session-id (Claude CLI ids) — prevents a duplicate card during
   the first agent turn before Xi's own :session/sync has run. A live room's
   superseded ids (a fork's previous Claude sessions, not yet persisted by
   that sync either) shadow their transcripts the same way."
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
  "Max saved sessions carried in the lobby broadcast. The drawer shows 25
   and the home surfaces show recents; the all-sessions view fetches the
   full list on demand (:sessions/all). Favorites and live rooms' sessions
   always ride along regardless of age, so the capped list stays complete
   for every always-visible surface."
  100)

(defn- cap-sessions
  "Trim a newest-first summary list to the recent cap, keeping every session
   in keep-ids (live rooms, and anything any user's extensions flagged — the
   list is shared by all users, see `for-user`). Preserves order."
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
  "Wall-clock ms when this server process booted. Sent in the lobby payload so
   clients can tell which sessions have been active during the current server
   run (last response at/after this time) vs. carried over from disk."
  (js/Date.now))

;; ── Claude subscription usage ───────────────────────────────────────────

(def ^:private claude-usage
  "Latest Claude subscription usage reading (nil until the first successful
   fetch). Rides along on the lobby broadcast so the sidebar footer can show
   how much of the 5h/weekly windows is used."
  (atom nil))

(defn- read-claude-token
  "OAuth access token from <claude-config-dir>/.credentials.json (kept fresh
   by Claude Code). nil when the file is missing/unreadable (e.g. the demo
   server's redirected HOME)."
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
  "Wall-clock ms of the last fetch attempt — throttles on-demand refreshes
   (sidebar opens) so drawer-happy clients can't hammer the OAuth endpoint."
  (atom 0))

(defn- fetch-claude-usage!
  "Fetch subscription usage from the OAuth endpoint (the same one Claude
   Code's /usage screen calls) into the claude-usage atom; calls on-change
   when the reading differs from the previous one. Failures (no credentials,
   expired token, network) keep the last reading."
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
  "mtime (ms) of the Claude credentials file, nil when it is missing."
  []
  (try (.-mtimeMs (.statSync (js/require "node:fs")
                             (str (session/claude-config-dir) "/.credentials.json")))
       (catch :default _ nil)))

(defn- watch-claude-credentials!
  "Refetch usage as soon as the credentials file changes (an account switch
   via claude-swap or a token refresh) instead of waiting for the next
   5-minute tick, which would leave the sidebar on the previous account's
   reading, or none, in between."
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
  "The user the client `cid` acts as (root when it is not connected)."
  [st cid]
  (util/user-id (get-in st [:connection :clients cid :user])))

(defn- read-state-of
  "The {session-id seen-response-count} in a user's `stored` state. A user who
   never marked a chat starts from the old global file, so the upgrade doesn't
   turn every chat unread."
  [stored]
  (or (:read-state stored) (session/load-legacy-read-state)))

(defn- for-user
  "The lobby/session-list `payload` as `user` sees it: the chats they hid
   from Recent tagged :dismissed?, the flags their extensions keep tagged on
   every session (xi.user-state/session-flags, e.g. :favorite?), and (for a
   lobby) their own read markers and the model their next chat starts with."
  [payload user]
  (let [stored (user-store/load-state user)
        flags  (user-state/session-flags stored)
        lobby? (= :lobby/state (:type payload))]
    (cond-> (-> payload
                (update :sessions session/annotate-dismissed
                        (set (:dismissed stored)))
                (update :sessions user-state/annotate-session-flags flags))
      ;; a live room hides its saved session from the list, so its summary
      ;; carries the flags too
      lobby?                    (update :rooms user-state/annotate-session-flags flags)
      lobby?                    (assoc :read (read-state-of stored))
      (and lobby? (:preferred-model stored))
      (assoc :model (:preferred-model stored)))))

(defn- lobby-base
  "The :lobby/state payload map: live rooms + saved sessions (+ the server's
   default :model, so a deferred TUI client can render the same launch header
   pre-join as the room it will create). The same for every user; `for-user`
   adds what differs.
   Filters out external (Claude/Pi) sessions whose id matches a live room's
   provider-session-id — prevents a duplicate card during the first agent
   turn before Xi's own :session/sync has run."
  [st agent-id model]
  (let [rooms    (rm/room-summaries st)
        ;; Cap the broadcast list (recent + favorites + live) — the full list
        ;; can be thousands of summaries, and every lobby-relevant event would
        ;; ship all of them to every client. An agent server stays uncapped:
        ;; its home view is the only listing surface and its corpus is small.
        sessions (cond-> (saved-sessions st agent-id)
                   (nil? agent-id)
                   (cap-sessions (into (user-store/all-flagged-session-ids)
                                       (keep :session-id)
                                       rooms)))
        ;; Response counts ride along so clients don't each round-trip a
        ;; :session/counts query for every session on every lobby refresh —
        ;; one count pass per broadcast instead of one per client.
        counts   (session/count-session-responses
                  (into [] (keep :session-id) sessions)
                  {:personal-agent? (some? agent-id)})]
    (cond-> {:type       :lobby/state
             :rooms      rooms
             :sessions   sessions
             :counts     counts
             :profiles   (users/public-profiles rooms)
             :user-ids   (users/declared-ids)
             :started-at server-started-at}
      model    (assoc :model model)
      ;; Clients key their "no projects" views on this.
      agent-id (assoc :agent-id agent-id)
      @claude-usage   (assoc :claude-usage @claude-usage))))

(defn- lobby-payload
  "The encoded :lobby/state for one `user` (see `lobby-base`, `for-user`)."
  [st agent-id model user]
  (wire/encode (for-user (lobby-base st agent-id model) user)))

;; ── Static file serving (resources/public, SPA fallback) ──────────────────────

(defn- resolve-public-dir
  "Locate resources/public — prefer a path relative to the compiled script
   (target/main.js), fall back to cwd. Resolved once at server start."
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
  "TLS cert/key for serving https:// (and wss:// on the same port). Sourced
   from the XI_TLS_CERT / XI_TLS_KEY env paths, else the default
   ~/.config/xi/tls/xi.{crt,key}. Returns nil (plain HTTP) when no cert is
   found. HTTPS matters because iOS Safari only treats a secure origin's
   localStorage as durable — over plain HTTP a home-screen PWA's storage
   bucket gets evicted, wiping the client-key and forcing a re-pair."
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
  "Serve a file from public-dir; SPA-fallback to index.html for extensionless
   router paths (e.g. /chat/...). Returns a Promise<Response>.

   icon-variant selects which favicon this instance serves at the shared
   /apple-touch-icon.png URL: \"personal\" and \"green\" map to their
   -<variant>.png sibling, everything else (incl. \"desktop\") keeps the base
   file. Each host only ever runs one variant, so URL-level caching stays
   consistent."
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
  "Build the WS server's effect handlers and starter. Sockets and effects
   share one closure; the app is wired in via :start! (the app needs the
   effects at create time, the server needs dispatch!/state/add-tap! from
   the app).

   opts:
     :server-opts        {:model :effort} — defaults for rooms provisioned here.
     :providers          provider-id → provider map — model listing for web
                         clients (each provider's :list-models!).
     :agent-id           run as a named agent (xi.agent-profile): rooms get
                         the profile's system prompt instead of AGENTS.md and
                         its :tools allowlist, sessions live in the agent's
                         dir, and the lobby carries :agent-id (the room's
                         [:agent :agent-id] drives the rest). nil = coding
                         server.
     :ext-system-prompt-parts  (fn [cwd] → [{:source :text}]) — extension
                         system prompt parts with source attribution.
     :room-ext-init      map of ext-id → initial room-scoped state, or a 0-arg
                         fn returning it (read per provisioned room, so a live
                         extension reload shows up in new rooms), seeded into
                         each provisioned room's [:ext] (mirrors to clients
                         via the :room/joined snapshot).
     :ext                the composed extension map (ext/compose) — the
                         server consumes :roomless-events, :no-broadcast,
                         :lobby-relevant (unioned onto its base sets) and
                         :server-fx-fns (instantiated with a send! that
                         encodes + delivers an event map to one client).

   Returns {:fx {…} :start! (fn [app {:keys [port]}] → {:port :stop!})}."
  [{:keys [server-opts providers agent-id ext-system-prompt-parts room-ext-init ext]}]
  (let [sockets (js/Map.)
        agent?  (some? agent-id)
        ;; Which favicon this instance serves at /apple-touch-icon.png:
        ;; XI_ICON ("desktop", "personal" or "green"), default "desktop".
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
        ;; Impurely provision a room map for a fresh (or resumed) room —
        ;; session + system prompt are read per-cwd from disk. Shared by the
        ;; :room/setup effect (WS join) and the HTTP /api/rooms endpoint.
        ;; An agent server reads its profile here, per room, so a config.edn
        ;; edit applies to the next room without a restart.
        ;; Returns {:cwd :session :room}.
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
                          (session/load-session summary)
                          ;; a fresh session records who opened it (the
                          ;; joining client's user, or the server's own for
                          ;; rooms it provisions itself)
                          (cond-> (session/create-session
                                   cwd (cond-> {:user (util/user-id user)}
                                         agent? (assoc :agent agent-id)))
                            ;; A client asked for a session that has nothing on
                            ;; disk — a blank chat that outlived its room (server
                            ;; restart). Keep its id: minting a new one made every
                            ;; reconnect re-pin the client to yet another blank
                            ;; session, and left its /chat/<id> URL pointing at a
                            ;; session that never joined. The id ends up in a file
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

      ;; Persist one piece of a user's UI state, then tell every connected
      ;; device of that user (the sender too: it converges on what the
      ;; server stored). Invalid writes were already dropped by the handler.
      :user-state/save
      (fn [{:keys [get-state dispatch!]} {:keys [user key value]}]
        (when (user-store/set-key! user key value)
          ;; keep the user's record in app state current for extensions
          (dispatch! {:type :user/ui-set :user user :key key :value value})
          (doseq [[cid client] (get-in (get-state) [:connection :clients])
                  :when (= user (:user client))]
            (send-event! cid {:type :user-state/changed :key key :value value}))))

      ;; Provision a room: session + AGENTS.md are per-cwd (impure), then
      ;; re-enter the pure path via :room/create + :room/attach. With
      ;; :session-id, resume a saved session into the new room instead of
      ;; starting fresh (the :session/resumed broadcast fills the client's
      ;; mirror right after the empty :room/joined snapshot).
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
            (let [;; Clip long tool outputs before they cross the wire — every
                  ;; client caps tool results at render, so a resumed transcript
                  ;; must not ship thousands of unshown lines.
                  messages (vec (session/truncate-message-results
                                 (session/read-session-messages summary)))
                  msg-count (count messages)
                  ;; Hash the wire form (what the client caches + renders) so
                  ;; client + server (same cljs, so equal EDN hashes match) can
                  ;; detect an unchanged session. When the joining client
                  ;; already cached this exact hash we skip broadcasting the
                  ;; history over the (slow mobile) wire and send a tiny
                  ;; :session/current instead — the server still fills its own
                  ;; room mirror via the non-broadcast :session/resumed so
                  ;; multi-client correctness holds.
                  msg-hash (hash messages)
                  current? (= cached-msg-hash msg-hash)
                  ;; Incremental resume: when not fully current, check whether
                  ;; the client's cache is a clean PREFIX of the on-disk
                  ;; session — i.e. its cached hash equals the hash of our
                  ;; first cached-msg-count messages. Sessions are append-only
                  ;; on disk (written at turn boundaries), so a matching prefix
                  ;; means we can ship just the new tail and let the client
                  ;; append it, instead of re-sending the whole transcript.
                  ;; /compact (which rewrites history) breaks the prefix →
                  ;; hash mismatch → falls back to a full resume below.
                  prefix?  (and (not current?)
                                (integer? cached-msg-count)
                                (< 0 cached-msg-count msg-count)
                                (= cached-msg-hash (hash (subvec messages 0 cached-msg-count))))]
              ;; The server mirror always gets the full history (multi-client
              ;; correctness); we only skip BROADCASTING it when the joining
              ;; client can be served a smaller targeted payload instead.
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
              ;; Auto-resume an agent whose turn was cut off by a hard restart:
              ;; the session was marked interrupted while its spinner was up and
              ;; never cleared (a completed turn's :session/sync would have).
              ;; Clear the marker and re-drive it with a "continue" prompt —
              ;; unless the transcript shows the turn finished anyway (the
              ;; response landed after the server died): a "continue" then
              ;; reads as a go-ahead to whatever the agent last proposed.
              (when (:interrupted-at summary)
                (session/clear-interrupted! (:filepath summary))
                (when-not (session/turn-completed? summary)
                  (dispatch! {:type :prompt/submit :room-id room-id :text "continue"})))))))

      ;; Open a new chat seeded with a user message (the :chat/start event,
      ;; see xi.server.room-manager). The room runs in the background like an
      ;; /api/rooms one; the requesting client, if any, is sent to it.
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

      ;; Send the full lobby payload (rooms + saved sessions) to one client.
      :lobby/send
      (fn [{:keys [state]} {:keys [client-id]}]
        (send! client-id (lobby-payload state agent-id (:model server-opts)
                                        (client-user state client-id))))

      ;; Reply to an unread-count query: assistant-turn counts per session.
      :session/counts-reply
      (fn [_ {:keys [client-id session-ids]}]
        (send! client-id (wire/encode {:type   :session/counts-result
                                       :counts (session/count-session-responses
                                                session-ids
                                                {:personal-agent? agent?})})))

      ;; Full (uncapped) saved-session list + counts — fetched on demand by
      ;; the all-sessions view, since the lobby broadcast only carries the
      ;; capped recent list.
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

      ;; AGENTS.md files for a cwd, so a virtual (not-yet-created) web chat
      ;; can show the same launch-header facts as the room it will become.
      :cwd/agents-files-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode {:type         :cwd/agents-files-result
                                       :cwd          cwd
                                       :agents-files (when-not agent?
                                                       (system-prompt/find-agents-md cwd))})))

      ;; Model list for web clients.
      :models/web-list-reply
      (fn [_ {:keys [client-id]}]
        (fx/web-model-list-reply-fx
         providers
         (fn [event] (send! client-id (wire/encode event)))))

      ;; Content search over saved sessions (names + conversation text).
      :session/content-search-reply
      (fn [_ {:keys [client-id key query cwd]}]
        (send! client-id (wire/encode {:type        :session/content-search-result
                                       :key         key
                                       :query       query
                                       :session-ids (session/content-search
                                                     cwd query
                                                     {:personal-agent? agent?})})))

      ;; Full-text search with result summaries + match snippets, for the
      ;; command palette's in-panel session search (scoped to a project cwd;
      ;; nil = all sessions). A blank query lists the newest sessions instead.
      :session/web-search-reply
      (fn [_ {:keys [client-id query cwd]}]
        (send! client-id
               (wire/encode
                {:type     :session/web-search-result
                 :query    query
                 :sessions (->> (if (str/blank? query)
                                  (session/recent-sessions
                                   cwd {:personal-agent? agent?})
                                  (session/search-sessions
                                   cwd query {:personal-agent? agent?}))
                                (take 50)
                                (mapv #(select-keys % [:session-id :name :cwd
                                                       :last-accessed :timestamp
                                                       :snippet])))})))

      ;; Toggle a session's dismissed (hidden-from-recent) flag for `user`,
      ;; then fan a fresh lobby out (:dismissed/changed is lobby-relevant, so
      ;; the tap rebroadcasts; each user's lobby carries their own flags).
      :dismissed/toggle-reply
      (fn [{:keys [dispatch!]} {:keys [session-id user]}]
        (user-store/toggle-dismissed! user session-id)
        (dispatch! {:type :dismissed/changed}))

      ;; Permanently delete a saved session's on-disk file, then fan a fresh
      ;; lobby out so every device drops the card (:session/deleted is
      ;; lobby-relevant, so the tap rebroadcasts without the gone session).
      :session/delete-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (when-let [summary (or (session/find-session-by-id session-id)
                               (session/find-personal-agent-session-by-id session-id))]
          (session/delete-session! summary))
        (dispatch! {:type :session/deleted}))

      ;; Persist a session's seen-count for `user` at its current
      ;; (authoritative) response count, then fan a fresh lobby out so every
      ;; device of that user clears the dot (:read-state/changed is
      ;; lobby-relevant, so the tap rebroadcasts).
      :session/mark-read-reply
      (fn [{:keys [dispatch!]} {:keys [session-id user]}]
        (let [n (get (session/count-session-responses
                      [session-id] {:personal-agent? agent?})
                     session-id 0)]
          (user-store/mark-read! user (read-state-of (user-store/load-state user))
                                 session-id n)
          (dispatch! {:type :read-state/changed})))

      ;; Combined working-tree diff for a CWD (roomless git-status view).
      :diff/web-load-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode {:type :diff/web-load-result
                                       :cwd  cwd
                                       :text (diff-git/all-git-changes-text cwd)})))

      ;; Commits made during a session (base..HEAD) for the web commit bar.
      :commits/web-load-reply
      (fn [_ {:keys [client-id cwd created]}]
        (let [base (diff-git/session-base-commit cwd created)]
          (send! client-id (wire/encode {:type    :commits/web-load-result
                                         :commits (or (diff-git/session-commits-list cwd base) [])}))))

      ;; Directory listing for the web file browser (drill-down navigation).
      :files/web-list-reply
      (fn [_ {:keys [client-id cwd path]}]
        (send! client-id (wire/encode (assoc (files/list-dir path cwd)
                                             :type :files/web-list-result))))

      ;; One file's contents for the web file viewer (the :file tab).
      :file/web-read-reply
      (fn [_ {:keys [client-id cwd path]}]
        (send! client-id (wire/encode (assoc (files/read-file path cwd)
                                             :type :file/web-read-result))))

      ;; Flat file list for the web fuzzy file finder (Ctrl/Cmd+P).
      :files/web-tree-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode (assoc (files/list-files cwd)
                                             :type :files/web-tree-result))))

      ;; Commands running server-side may emit TUI-owned effects; the
      ;; mirroring client re-derives whitelisted ones locally
      ;; (xi.client.ws-transport), the rest are no-ops here.
      :app/quit       (fn [_ _] nil)
      :app/reload     (fn [_ _] nil)
      :clipboard/copy (fn [_ _] nil)}

     ;; Extension server-fx — reply-to-client effect handlers instantiated
     ;; with the send! capability (ext/compose :server-fx-fns).
     ext-fx)

     :start!
     (fn [{:keys [dispatch! state add-tap!]} {:keys [port host]}]
       (let [port (or port DEFAULT_PORT)
             hosts (resolve-hosts host)
             public-dir (resolve-public-dir)
             ;; An agent server is single-user/local: skip HTTPS (no iOS
             ;; PWA durable-storage concern) and skip client-key pairing.
             tls        (when-not agent? (resolve-tls))
             ;; Pairing requests awaiting approval: code → #js {:cid :key :name
             ;; :platform}, mirrored to ~/.config/xi/pending-clients.edn for
             ;; `bb serve:approve`. Stale entries from a previous run are
             ;; cleared below.
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
                     ;; clients.edn's assignment for this device wins over the
                     ;; user the client claimed in :auth/hello; neither → root
                     user (util/user-id (or (auth/user-for-key (.. ws -data -clientKey))
                                            (.. ws -data -clientUser)))]
                 (set! (.. ws -data -authed) true)
                 (set! (.. ws -data -user) user)
                 ;; the user's record (declared profile + stored state) goes
                 ;; into app state, where extensions read it (xi.users)
                 (dispatch! (users/loaded-event user))
                 (dispatch! {:type :client/connect :client-id cid
                             :client (cond-> {:kind :remote :user user}
                                       ;; pid + platform (from :auth/hello): the
                                       ;; room's driving pid goes to MCP servers
                                       ;; as _meta (xi.ext.mcp/call-meta)
                                       (.. ws -data -clientPid)
                                       (assoc :pid (.. ws -data -clientPid))
                                       (.. ws -data -clientPlatform)
                                       (assoc :platform (.. ws -data -clientPlatform)))})
                 (send-event! cid {:type :auth/ok :user user})
                 ;; the user's UI state (theme, layout, …): follows them to
                 ;; every device (xi.user-state)
                 (send-event! cid {:type :user-state/state :user user
                                   :state (user-state/client-view (user-store/load-state user))})
                 ;; the operator's keyboard shortcuts (config.edn :keys): the
                 ;; browser has no file to read, so they ride on the admit
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
                 ;; Stash identity on the socket so admit! (which may run later,
                 ;; after pairing approval) can record it into the client entry.
                 (when pid (set! (.. ws -data -clientPid) pid))
                 (when platform (set! (.. ws -data -clientPlatform) platform))
                 (when (string? client-key) (set! (.. ws -data -clientKey) client-key))
                 (when user (set! (.. ws -data -clientUser) user))
                 (cond
                   (authed? ws) nil

                   ;; An agent server has no pairing — admit every client.
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
             ;; `bb serve:approve` moves a pending entry into clients.edn on
             ;; disk; admit waiting sockets once their key shows up there.
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
             ;; Last broadcast lobby payload (encoded). Many lobby-relevant
             ;; events produce a byte-identical lobby; skipping those saves
             ;; every client a decode + re-render + localStorage rewrite.
             last-lobby (atom {})
             broadcast-lobby!
             (fn [st]
               ;; Push to every connected client, not just roomless ones: the
               ;; recent-sessions drawer lives on every page, so clients
               ;; attached to a room still need fresh lobby state to keep its
               ;; list and unread/active/dialog markers live. One payload per
               ;; user (hidden chats and read markers are theirs); the shared
               ;; part is built once. `last-lobby` is {user payload}.
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
             ;; Coalesce lobby broadcasts: lobby-relevant events arrive in
             ;; bursts (prompt/submit → session-init → dialog events, turn
             ;; ends across rooms), and each broadcast re-reads the session
             ;; listing and re-encodes a payload per client. One trailing-edge
             ;; timer per burst; state is read fresh at fire time.
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
             ;; Refresh Claude subscription usage now and every 5 minutes; a
             ;; changed reading rides the coalesced lobby broadcast out to
             ;; every connected client (the sidebar footer renders it).
             _ (fetch-claude-usage! schedule-lobby-broadcast!)
             _ (js/setInterval #(fetch-claude-usage! schedule-lobby-broadcast!)
                               (* 5 60 1000))
             _ (watch-claude-credentials! schedule-lobby-broadcast!)
             ;; HTTP API: programmatically create a room (and optionally kick
             ;; off a turn) so an external service can spawn a background
             ;; agent session and hand back a web-client URL to open it.
             ;; POST /api/rooms {prompt?, cwd?, model?}. Auth: same client-key
             ;; trust as WS (via Authorization: Bearer <key> or
             ;; X-Xi-Client-Key), skipped on an agent server. The room
             ;; runs clientless (busy rooms keep running) and its session
             ;; persists on disk, so /chat/<session-id> resumes it later.
             ;; Read-only status of prior background sessions by id: `running`
             ;; (a live room mid-turn), `error` (its last turn aborted or the
             ;; server was hard-killed mid-turn), `complete` (finished
             ;; normally), or `unknown`. Background rooms are reaped the moment
             ;; their turn ends, so a finished/aborted session has no live room
             ;; — status then comes from the persisted session metadata
             ;; (:aborted-at / :interrupted-at). A service that dispatched
             ;; sessions polls this to show their state.
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
                                  ;; no client: the room is the server operator's
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
                         ;; Same-host Origin only (port ignored — shadow's
                         ;; dev-http serves the page on 8100). Non-browser
                         ;; clients send no Origin; key auth still gates them.
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
                   #js {;; 100MB — multiple base64-encoded images per prompt
                        :maxPayloadLength (* 100 1024 1024)

                        ;; Compress frames (RFC 7692). The lobby payload is
                        ;; transit text that deflates ~8-10x — a large win for
                        ;; phone/PWA clients on cellular links.
                        :perMessageDeflate true

                        :open
                        (fn [^js ws]
                          ;; Register the socket but tell the app nothing —
                          ;; :client/connect + lobby happen at admit! after
                          ;; the :auth/hello key check.
                          (.set sockets (.. ws -data -cid) ws))

                        :message
                        (fn [^js ws data]
                          (let [cid (.. ws -data -cid)]
                            (if-let [ev (wire/decode data)]
                              (let [room-id (get-in @state [:connection :clients cid :room-id])
                                    ev (cond-> (assoc ev :client-id cid)
                                         ;; the sender's resolved user (admit!) — handlers
                                         ;; and every mirror attribute the event to it
                                         (authed? ws) (assoc :user (.. ws -data -user))
                                         ;; an extension event a connected client sent
                                         ;; (a click in a browser half) is the user's
                                         ;; doing; see xi.ext.user.guard/guard-handler
                                         (ext-event? ev) (assoc ::user-guard/user-initiated true))]
                                (cond
                                  ;; ─ Transport-level auth, never dispatched ─
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

                                  ;; ─ On-demand Claude usage refresh (sent when
                                  ;; the sidebar drawer opens). Transport-level,
                                  ;; never dispatched; throttled to 30s — the
                                  ;; changed reading rides the lobby broadcast. ─
                                  (= :usage/refresh (:type ev))
                                  (when (> (- (js/Date.now) @claude-usage-fetched-at)
                                           (* 30 1000))
                                    (fetch-claude-usage! schedule-lobby-broadcast!))

                                  (or (pre-join-types (:type ev))
                                      (roomless-types (:type ev)))
                                  (dispatch! ev)

                                  room-id
                                  (dispatch! (assoc ev :room-id room-id))

                                  ;; an extension's own event needs no room: the
                                  ;; sidebar and the home view are roomless
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
                            ;; Dispatch before dropping the socket — cleanup
                            ;; effects may still broadcast (sends are no-ops
                            ;; on the closed socket).
                            (when (authed? ws)
                              (dispatch! {:type :client/disconnect :client-id cid}))
                            (.delete sockets cid)))}}
             ;; 7474 always stays plain ws:// so the local TUI and existing
             ;; web clients keep working. TLS (for the iOS PWA's durable
             ;; storage) is served on a SECOND port sharing the same handlers,
             ;; since Bun binds one protocol per port.
             tls-port (or (some-> (aget js/process.env "XI_TLS_PORT") js/parseInt)
                          DEFAULT_TLS_PORT)
             ;; One listener per bind address (resolve-hosts), sharing the handlers.
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

         ;; The transport is a tap: every processed room event is echoed to
         ;; that room's clients (sender included — clients never apply their
         ;; own input locally). Lobby clients get room-list refreshes.
         (add-tap!
          (fn [event st]
            (let [room-id (:room-id event)]
              ;; :no-broadcast? lets a single event opt out at runtime — the
              ;; hash-matched :session/resumed fills the server mirror without
              ;; re-sending the full history to the (already-current) client.
              (when (and room-id
                         (not (no-broadcast (:type event)))
                         (not (:no-broadcast? event)))
                ;; Originator-only events (e.g. the diff viewer) go to just
                ;; the client that asked, so a client-local view doesn't flip
                ;; every other connected client. Fall back to a room broadcast
                ;; when no originator rode along on the event.
                (if (and (originator-only (:type event)) (:client-id event))
                  (when (contains? (set (rm/clients-in-room st room-id))
                                   (:client-id event))
                    (send! (:client-id event) (wire/encode event)))
                  (when-let [cids (seq (rm/clients-in-room st room-id))]
                    (let [payload (wire/encode event)]
                      (doseq [cid cids] (send! cid payload)))))))
            ;; A new prompt into a session its sender hid un-hides it for
            ;; them — fresh activity belongs back in Recent. Clear the flag
            ;; before the lobby broadcast below (:prompt/submit is
            ;; lobby-relevant) so the rebroadcast carries the updated state.
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
