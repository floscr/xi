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

   Deferred to later phases: :visibility tracking, dictation."
  (:require [xi.auth :as auth]
            [xi.ext.diff.git :as diff-git]
            [xi.fx :as fx]
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
  #{:client/update :session/counts :models/web-list :session/content-search
    :diff/web-load :favorites/toggle :session/mark-read :rooms/prune})

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
       (mapv #(select-keys % [:session-id :name :cwd :last-accessed :timestamp :source :favorite?]))))

(def ^:private server-started-at
  "Wall-clock ms when this server process booted. Sent in the lobby payload so
   clients can tell which sessions have been active during the current server
   run (last response at/after this time) vs. carried over from disk."
  (js/Date.now))

(defn- lobby-payload
  "The :lobby/state wire payload: live rooms + saved sessions (+ the server's
   default :model, so a deferred TUI client can render the same launch header
   pre-join as the room it will create).
   Filters out external (Claude/Pi) sessions whose id matches a live room's
   provider-session-id — prevents a duplicate card during the first agent
   turn before Xi's own :session/sync has run."
  [st personal-agent? model]
  (let [rooms    (rm/room-summaries st)
        ;; Provider session ids currently held by live rooms (Claude CLI ids)
        ;; must be hidden from the saved-session list to avoid a duplicate card.
        live-pids (into #{}
                        (keep (fn [[_ room]]
                                (get-in room [:session :provider-session-id])))
                        (:rooms st))
        sessions (cond->> (lobby-sessions personal-agent?)
                   (seq live-pids)
                   (filterv #(not (contains? live-pids (:session-id %)))))]
    (wire/encode (cond-> {:type       :lobby/state
                          :rooms      rooms
                          :sessions   sessions
                          :started-at server-started-at
                          :read       (session/load-read-state)}
                   model           (assoc :model model)
                   personal-agent? (assoc :personal-agent? true)))))

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
   router paths (e.g. /chat/...). Returns a Promise<Response>."
  [public-dir ^js req]
  (let [path (js/require "node:path")
        url  (js/URL. (.-url req))
        pathname (js/decodeURIComponent (.-pathname url))
        rel  (if (= "/" pathname) "index.html" (.replace pathname #"^/+" ""))
        ;; Normalize + contain to public-dir (no path traversal)
        full (.normalize path (.join path public-dir rel))
        index (.join path public-dir "index.html")
        has-ext? (re-find #"\.[a-zA-Z0-9]+$" rel)
        ;; SPA route prefixes — always fall back to index.html
        spa-route? (re-find #"^(chat|gtd)(/|$)" rel)
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
  [{:keys [server-opts personal-agent? ext-system-prompt-parts room-ext-init ext]}]
  (let [sockets (js/Map.)
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
                                   (:server-fx-fns ext)))]
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
      (fn [{:keys [dispatch!]} {:keys [client-id room-id cwd session-id]}]
        (let [summary (when session-id
                        (if personal-agent?
                          (session/find-personal-agent-session-by-id session-id)
                          (session/find-session-by-id session-id)))
              cwd (or (:cwd summary) cwd (.cwd js/process))
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
          (dispatch! {:type :room/create
                      :room-id room-id
                      :room {:model        (:model server-opts)
                             :effort       (:effort server-opts)
                             :cwd          cwd
                             :system       system
                             :system-parts system-parts
                             :agents-files (when-not personal-agent?
                                             (system-prompt/find-agents-md cwd))
                             :session      session
                             :ext          room-ext-init
                             :personal-agent? personal-agent?
                             :created      (js/Date.now)}})
          (dispatch! {:type :room/attach :client-id client-id :room-id room-id})
          (when summary
            (dispatch! {:type :session/resumed :room-id room-id
                        :session session :summary summary
                        :messages (session/read-session-messages summary)}))))

      ;; Send the full lobby payload (rooms + saved sessions) to one client.
      :lobby/send
      (fn [{:keys [state]} {:keys [client-id]}]
        (send! client-id (lobby-payload state personal-agent? (:model server-opts))))

      ;; Reply to an unread-count query: assistant-turn counts per session.
      :session/counts-reply
      (fn [_ {:keys [client-id session-ids]}]
        (send! client-id (wire/encode {:type   :session/counts-result
                                       :counts (session/count-session-responses
                                                session-ids)})))

      ;; Model list for web clients.
      :models/web-list-reply
      (fn [_ {:keys [client-id]}]
        (fx/web-model-list-reply-fx
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

      ;; Toggle a session bookmark, then fan a fresh lobby out to every client
      ;; (the :favorites/changed dispatch is lobby-relevant, so the tap
      ;; rebroadcasts with updated :favorite? flags).
      :favorites/toggle-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (session/toggle-favorite! session-id)
        (dispatch! {:type :favorites/changed}))

      ;; Persist a session's seen-count at its current (authoritative) response
      ;; count, then fan a fresh lobby out so every device clears the dot
      ;; (:read-state/changed is lobby-relevant, so the tap rebroadcasts).
      :session/mark-read-reply
      (fn [{:keys [dispatch!]} {:keys [session-id]}]
        (let [n (get (session/count-session-responses [session-id]) session-id 0)]
          (session/mark-session-read! session-id n)
          (dispatch! {:type :read-state/changed})))

      ;; Combined working-tree diff for a CWD (roomless git-status view).
      :diff/web-load-reply
      (fn [_ {:keys [client-id cwd]}]
        (send! client-id (wire/encode {:type :diff/web-load-result
                                       :cwd  cwd
                                       :text (diff-git/all-git-changes-text cwd)})))

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
       (let [port (or port
                      (some-> (aget js/process.env "XI_PORT") js/parseInt)
                      DEFAULT_PORT)
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
                             :client {:kind :remote}})
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
             (fn [^js ws {:keys [client-key client-name platform]}]
               (let [cid (.. ws -data -cid)]
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
             broadcast-lobby!
             (fn [st]
               ;; Push to every connected client, not just roomless ones: the
               ;; recent-sessions drawer lives on every page, so clients
               ;; attached to a room still need fresh lobby state to keep its
               ;; list and unread/active/dialog markers live.
               (let [cids (keys (get-in st [:connection :clients]))]
                 (when (seq cids)
                   (let [payload (lobby-payload st personal-agent? (:model server-opts))]
                     (doseq [cid cids] (send! cid payload))))))
             opts
             #js {:port port
                   :fetch
                   (fn [^js req ^js srv]
                     (let [headers  (.-headers req)
                           upgrade? (some-> (.get headers "upgrade")
                                            (.toLowerCase)
                                            (= "websocket"))]
                       (if-not upgrade?
                         (serve-static public-dir req)
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
              (when (and room-id (not (no-broadcast (:type event))))
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
            (when (lobby-relevant (:type event))
              (broadcast-lobby! st))))

         (js/console.error (str "[ws] Listening on ws://localhost:" port
                                (when tls-server
                                  (str " + wss://localhost:" tls-port))))
         {:port  port
          :stop! (fn []
                   (js/clearInterval auth-poll)
                   (.stop server)
                   (when tls-server (.stop tls-server)))}))}))
