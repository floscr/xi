(ns xi.server.ws
  "Bun WebSocket server — hosts N rooms over one server app.

   Events are the wire protocol: clients send the same event maps a local
   app would dispatch (:input/submit, :agent/abort, …) and the server
   broadcasts every room-scoped event it processes back to that room's
   clients, who mirror state with the same pure reducers
   (xi.client.ws-transport). The server owns provider effects + the agent
   loop; clients are renderers + input.

   Handshake (auth is transport-level, never dispatched into the app):
     connect → client sends {:type :auth/hello :client-key … :client-name … :platform …}
     server  → {:type :auth/ok} when the key is approved (clients.edn or the
               local ~/.config/xi/client-key — see xi.auth), else
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

   Deferred to later phases: :visibility tracking."
  (:require [xi.auth :as auth]
            [xi.ext.diff.git :as diff-git]
            [xi.fx :as fx]
            [xi.server.files :as files]
            [xi.server.room-manager :as rm]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.wire :as wire]))

(def DEFAULT_PORT 7474)
(def DEFAULT_TLS_PORT 7443)

(def ^:private base-no-broadcast
  "Room-scoped event types that are connection bookkeeping, not room state.
   Extensions add theirs via :no-broadcast."
  #{:room/join :room/attach :room/leave :room/list :agent/session-init})

(def ^:private base-lobby-relevant
  "Events after which lobby (roomless) clients get a fresh :lobby/state.
   Extensions add theirs via :lobby-relevant."
  #{:room/create :room/close :room/attach :room/leave :favorites/changed
    :dismissed/changed
    :session/deleted
    :read-state/changed
    :prompt/submit :agent/session-init :agent/turn-end :client/disconnect
    :ui/dialog-open :ui/dialog-response})

(def ^:private pre-join-types
  "Event types a client may send before joining a room."
  #{:room/join :room/leave :room/list})

(def ^:private base-roomless-types
  "Event types processed regardless of room membership (connection-level
   bookkeeping that uses :client-id, not :room-id). Extensions add theirs
   via :roomless-events."
  #{:client/update :session/counts :sessions/all :models/web-list
    :session/content-search :session/web-search
    :diff/web-load :commits/web-load :files/web-list :file/web-read
    :favorites/toggle :dismissed/toggle :session/delete :session/mark-read
    :rooms/prune})

(defn- gen-client-id []
  (str "c-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1e6)) 36)))

;; ── Lobby payload (impure: reads saved sessions from disk) ────────────────────

(defn- lobby-sessions
  "Saved sessions from disk for the lobby/home view — a curated subset of
   the full summaries (id, name, cwd, mtime, source)."
  [personal-agent?]
  (->> (if personal-agent?
         (session/list-personal-agent-sessions)
         (session/list-all-sessions))
       (mapv #(select-keys % [:session-id :name :cwd :last-accessed :timestamp :source :favorite? :dismissed?]))))

(defn- saved-sessions
  "All saved-session summaries, minus those shadowed by a live room's
   provider-session-id (Claude CLI ids) — prevents a duplicate card during
   the first agent turn before Xi's own :session/sync has run."
  [st personal-agent?]
  (let [live-pids (into #{}
                        (keep (fn [[_ room]]
                                (get-in room [:session :provider-session-id])))
                        (:rooms st))]
    (cond->> (lobby-sessions personal-agent?)
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
  "Trim a newest-first summary list to the recent cap, keeping all favorites
   and any session in keep-ids (live rooms). Preserves order."
  [sessions keep-ids]
  (if (<= (count sessions) lobby-session-cap)
    sessions
    (let [recent (into #{}
                       (comp (take lobby-session-cap) (keep :session-id))
                       sessions)]
      (filterv (fn [s]
                 (or (:favorite? s)
                     (contains? recent (:session-id s))
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

(defn- lobby-payload
  "The :lobby/state wire payload: live rooms + saved sessions (+ the server's
   default :model, so a deferred TUI client can render the same launch header
   pre-join as the room it will create).
   Filters out external (Claude/Pi) sessions whose id matches a live room's
   provider-session-id — prevents a duplicate card during the first agent
   turn before Xi's own :session/sync has run."
  [st personal-agent? model]
  (let [rooms    (rm/room-summaries st)
        ;; Cap the broadcast list (recent + favorites + live) — the full list
        ;; can be thousands of summaries, and every lobby-relevant event would
        ;; ship all of them to every client. Personal-agent mode stays uncapped:
        ;; its home view is the only listing surface and its corpus is small.
        sessions (cond-> (saved-sessions st personal-agent?)
                   (not personal-agent?)
                   (cap-sessions (into #{} (keep :session-id) rooms)))
        ;; Response counts ride along so clients don't each round-trip a
        ;; :session/counts query for every session on every lobby refresh —
        ;; one count pass per broadcast instead of one per client.
        counts   (session/count-session-responses
                  (into [] (keep :session-id) sessions)
                  {:personal-agent? personal-agent?})]
    (wire/encode (cond-> {:type       :lobby/state
                          :rooms      rooms
                          :sessions   sessions
                          :counts     counts
                          :started-at server-started-at
                          :read       (session/load-read-state)}
                   model           (assoc :model model)
                   personal-agent? (assoc :personal-agent? true)
                   @claude-usage   (assoc :claude-usage @claude-usage)))))

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
   /apple-touch-icon.png URL: \"personal\" and \"hetzner\" map to their
   -<variant>.png sibling, everything else (incl. \"desktop\") keeps the base
   file. Each host only ever runs one variant, so URL-level caching stays
   consistent."
  [public-dir ^js req icon-variant]
  (let [path (js/require "node:path")
        url  (js/URL. (.-url req))
        pathname (js/decodeURIComponent (.-pathname url))
        rel  (if (= "/" pathname) "index.html" (.replace pathname #"^/+" ""))
        rel  (if (and (= rel "apple-touch-icon.png")
                      (contains? #{"personal" "hetzner"} icon-variant))
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
     :personal-agent?    provision personal-assistant rooms: PA system prompt
                         instead of AGENTS.md, sessions in the PA dir, and the
                         provider restricted to web_search (the room's
                         [:agent :personal-agent?] flag drives the rest).
     :ext-system-prompt-parts  (fn [cwd] → [{:source :text}]) — extension
                         system prompt parts with source attribution.
     :room-ext-init      map of ext-id → initial room-scoped state, seeded
                         into each provisioned room's [:ext] (mirrors to
                         clients via the :room/joined snapshot).
     :ext                the composed extension map (ext/compose) — the
                         server consumes :roomless-events, :no-broadcast,
                         :lobby-relevant (unioned onto its base sets) and
                         :server-fx-fns (instantiated with a send! that
                         encodes + delivers an event map to one client).

   Returns {:fx {…} :start! (fn [app {:keys [port]}] → {:port :stop!})}."
  [{:keys [server-opts providers personal-agent? ext-system-prompt-parts room-ext-init ext]}]
  (let [sockets (js/Map.)
        ;; Which favicon this instance serves at /apple-touch-icon.png. XI_ICON
        ;; overrides (e.g. hetzner--xi sets "hetzner"); otherwise personal-agent
        ;; hosts get the warm "personal" icon and coding hosts the "desktop" one.
        icon-variant (or (some-> (aget js/process.env "XI_ICON")
                                  (.trim)
                                  (as-> v (when (pos? (.-length v)) v)))
                         (if personal-agent? "personal" "desktop"))
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
        ;; Returns {:cwd :session :room}.
        build-room
        (fn [{:keys [cwd summary model effort]}]
          (let [cwd (or (:cwd summary) cwd (.cwd js/process))
                system-parts (if personal-agent?
                               [{:source "personal-agent"
                                 :text   system-prompt/PERSONAL_AGENT_PROMPT}]
                               (into (system-prompt/load-agents-parts cwd)
                                     (when ext-system-prompt-parts
                                       (ext-system-prompt-parts cwd))))
                system (system-prompt/parts->system system-parts)
                session (if summary
                          (session/load-session summary)
                          (session/create-session
                           cwd (when personal-agent? {:personal-agent? true})))]
            {:cwd     cwd
             :session session
             :room    {:model        (or model (:model server-opts))
                       :effort       (or effort (:effort server-opts))
                       :cwd          cwd
                       :system       system
                       :system-parts system-parts
                       :agents-files (when-not personal-agent?
                                       (system-prompt/find-agents-md cwd))
                       :session      session
                       :ext          room-ext-init
                       :personal-agent? personal-agent?
                       :created      (js/Date.now)}}))]
    {:fx
     (merge
     {:ws/send-to
      (fn [_ {:keys [client-id event]}]
        (send! client-id (wire/encode event)))

      ;; Provision a room: session + AGENTS.md are per-cwd (impure), then
      ;; re-enter the pure path via :room/create + :room/attach. With
      ;; :session-id, resume a saved session into the new room instead of
      ;; starting fresh (the :session/resumed broadcast fills the client's
      ;; mirror right after the empty :room/joined snapshot).
      :room/setup
      (fn [{:keys [dispatch!]} {:keys [client-id room-id cwd model session-id cached-msg-hash cached-msg-count join-token]}]
        (let [summary (when session-id
                        (if personal-agent?
                          (session/find-personal-agent-session-by-id session-id)
                          (session/find-session-by-id session-id)))
              {:keys [session room]} (build-room {:cwd cwd :summary summary :model model})]
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

      ;; Send the full lobby payload (rooms + saved sessions) to one client.
      :lobby/send
      (fn [{:keys [state]} {:keys [client-id]}]
        (send! client-id (lobby-payload state personal-agent? (:model server-opts))))

      ;; Reply to an unread-count query: assistant-turn counts per session.
      :session/counts-reply
      (fn [_ {:keys [client-id session-ids]}]
        (send! client-id (wire/encode {:type   :session/counts-result
                                       :counts (session/count-session-responses
                                                session-ids
                                                {:personal-agent? personal-agent?})})))

      ;; Full (uncapped) saved-session list + counts — fetched on demand by
      ;; the all-sessions view, since the lobby broadcast only carries the
      ;; capped recent list.
      :sessions/all-reply
      (fn [{:keys [state]} {:keys [client-id]}]
        (let [sessions (saved-sessions state personal-agent?)
              counts   (session/count-session-responses
                        (into [] (keep :session-id) sessions)
                        {:personal-agent? personal-agent?})]
          (send! client-id (wire/encode {:type     :sessions/all-result
                                         :sessions sessions
                                         :counts   counts}))))

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
                                                     {:personal-agent? personal-agent?})})))

      ;; Full-text search with result summaries + match snippets, for the
      ;; command palette's in-panel session search (scoped to a project cwd).
      :session/web-search-reply
      (fn [_ {:keys [client-id query cwd]}]
        (send! client-id
               (wire/encode
                {:type     :session/web-search-result
                 :query    query
                 :sessions (->> (session/search-sessions
                                 cwd query {:personal-agent? personal-agent?})
                                (take 30)
                                (mapv #(select-keys % [:session-id :name :cwd
                                                       :last-accessed :timestamp
                                                       :snippet])))})))

      ;; Toggle a session bookmark, then fan a fresh lobby out to every client
      ;; (the :favorites/changed dispatch is lobby-relevant, so the tap
      ;; rebroadcasts with updated :favorite? flags).
      :favorites/toggle-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (session/toggle-favorite! session-id)
        (dispatch! {:type :favorites/changed}))

      ;; Toggle a session's dismissed (hidden-from-recent) flag, then fan a
      ;; fresh lobby out to every client (:dismissed/changed is lobby-relevant,
      ;; so the tap rebroadcasts with updated :dismissed? flags).
      :dismissed/toggle-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (session/toggle-dismissed! session-id)
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

      ;; Persist a session's seen-count at its current (authoritative) response
      ;; count, then fan a fresh lobby out so every device clears the dot
      ;; (:read-state/changed is lobby-relevant, so the tap rebroadcasts).
      :session/mark-read-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (let [n (get (session/count-session-responses
                      [session-id] {:personal-agent? personal-agent?})
                     session-id 0)]
          (session/mark-session-read! session-id n)
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
     (fn [{:keys [dispatch! state add-tap!]} {:keys [port]}]
       (let [port (or port DEFAULT_PORT)
             public-dir (resolve-public-dir)
             ;; Personal-agent mode is single-user/local: skip HTTPS (no iOS
             ;; PWA durable-storage concern) and skip client-key pairing.
             tls        (when-not personal-agent? (resolve-tls))
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
               (let [cid (.. ws -data -cid)]
                 (set! (.. ws -data -authed) true)
                 (dispatch! {:type :client/connect :client-id cid
                             :client (cond-> {:kind :remote}
                                       ;; pid + platform (from :auth/hello) let
                                       ;; chrome-mcp scope to the client's
                                       ;; terminal workspace (xi.ext.chrome-mcp.guard)
                                       (.. ws -data -clientPid)
                                       (assoc :pid (.. ws -data -clientPid))
                                       (.. ws -data -clientPlatform)
                                       (assoc :platform (.. ws -data -clientPlatform)))})
                 (send-event! cid {:type :auth/ok})
                 (send! cid (lobby-payload @state personal-agent? (:model server-opts)))))
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
             (fn [^js ws {:keys [client-key client-name platform pid]}]
               (let [cid (.. ws -data -cid)]
                 ;; Stash identity on the socket so admit! (which may run later,
                 ;; after pairing approval) can record it into the client entry.
                 (when pid (set! (.. ws -data -clientPid) pid))
                 (when platform (set! (.. ws -data -clientPlatform) platform))
                 (cond
                   (authed? ws) nil

                   ;; Personal-agent mode has no pairing — admit every client.
                   personal-agent? (admit! ws)

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
             last-lobby (atom nil)
             broadcast-lobby!
             (fn [st]
               ;; Push to every connected client, not just roomless ones: the
               ;; recent-sessions drawer lives on every page, so clients
               ;; attached to a room still need fresh lobby state to keep its
               ;; list and unread/active/dialog markers live.
               (let [cids (keys (get-in st [:connection :clients]))]
                 (when (seq cids)
                   (let [payload (lobby-payload st personal-agent? (:model server-opts))]
                     (when (not= payload @last-lobby)
                       (reset! last-lobby payload)
                       (doseq [cid cids] (send! cid payload)))))))
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
             ;; HTTP API: programmatically create a room (and optionally kick
             ;; off a turn) so an external service can spawn a background
             ;; agent session and hand back a web-client URL to open it.
             ;; POST /api/rooms {prompt?, cwd?, model?}. Auth: same client-key
             ;; trust as WS (via Authorization: Bearer <key> or
             ;; X-Xi-Client-Key), skipped in personal-agent mode. The room
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
                     authed? (or personal-agent? (auth/approved? key))
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
                                  {:keys [session room]} (build-room {:cwd cwd :model model})
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
             #js {:port port
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
                                    ev (assoc ev :client-id cid)]
                                (cond
                                  ;; ─ Transport-level auth, never dispatched ─
                                  (= :auth/hello (:type ev))
                                  (handle-hello! ws ev)

                                  (not (authed? ws))
                                  (send! cid (wire/encode {:type :auth/required}))

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
             server (js/Bun.serve opts)
             tls-server (when tls
                          (js/Bun.serve
                           #js {:port      tls-port
                                :tls       tls
                                :fetch     (.-fetch opts)
                                :websocket (.-websocket opts)}))]

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
            ;; A new prompt into a hidden (dismissed) session un-hides it —
            ;; fresh activity belongs back in Recent. Clear the flag before
            ;; the lobby broadcast below (:prompt/submit is lobby-relevant)
            ;; so the rebroadcast carries the updated :dismissed? state.
            (when (= :prompt/submit (:type event))
              (when-let [sid (get-in st [:rooms (:room-id event) :session :id])]
                (session/undismiss! sid)))
            (when (lobby-relevant (:type event))
              (schedule-lobby-broadcast!))))

         (js/console.error (str "[ws] Listening on ws://localhost:" port
                                (when tls-server
                                  (str " + wss://localhost:" tls-port))))
         {:port  port
          :stop! (fn []
                   (js/clearInterval auth-poll)
                   (when-let [t @lobby-timer]
                     (js/clearTimeout t)
                     (reset! lobby-timer nil))
                   (.stop server)
                   (when tls-server (.stop tls-server)))}))}))
