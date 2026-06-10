(ns xi.server.ws
  "Bun WebSocket server — hosts N rooms over one server app.

   Events are the wire protocol: clients send the same event maps a local
   app would dispatch (:input/submit, :agent/abort, …) and the server
   broadcasts every room-scoped event it processes back to that room's
   clients, who mirror state with the same pure reducers
   (xi.client.ws-transport). The server owns provider effects + the agent
   loop; clients are renderers + input.

   Handshake:
     connect → server sends {:type :lobby/state :rooms […]}
     client  → {:type :room/join :target \"new\"|\"latest\"|room-id :cwd …}
     server  → {:type :room/joined :room-id … :room <snapshot>}
     normal event flow begins; {:type :room/leave} returns to the lobby.

   Incoming room events get :room-id forced to the sender's joined room —
   clients can't address rooms they're not in. Sockets live in the
   create-server closure (runtime resources, not app state).

   Deferred to later phases: :visibility tracking, dictation, session
   lists in the lobby payload (web client, phase 7); personal-agent mode
   (extensions, phase 6)."
  (:require [xi.server.room-manager :as rm]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.wire :as wire]))

(def DEFAULT_PORT 7474)

(def ^:private no-broadcast
  "Room-scoped event types that are connection bookkeeping, not room state."
  #{:room/join :room/attach :room/leave :room/list})

(def ^:private lobby-relevant
  "Events after which lobby (roomless) clients get a fresh :lobby/state."
  #{:room/create :room/close :room/attach :room/leave
    :prompt/submit :agent/turn-end :client/disconnect})

(def ^:private pre-join-types
  "Event types a client may send before joining a room."
  #{:room/join :room/leave :room/list})

(defn- gen-client-id []
  (str "c-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1e6)) 36)))

(defn create-server
  "Build the WS server's effect handlers and starter. Sockets and effects
   share one closure; the app is wired in via :start! (the app needs the
   effects at create time, the server needs dispatch!/state/add-tap! from
   the app).

   opts:
     :server-opts        {:model :effort} — defaults for rooms provisioned here.
     :ext-system-prompt  (fn [cwd] → str|nil) — extension system prompt,
                         appended to the room's AGENTS.md prompt.
     :room-ext-init      map of ext-id → initial room-scoped state, seeded
                         into each provisioned room's [:ext] (mirrors to
                         clients via the :room/joined snapshot).

   Returns {:fx {…} :start! (fn [app {:keys [port]}] → {:port :stop!})}."
  [{:keys [server-opts ext-system-prompt room-ext-init]}]
  (let [sockets (js/Map.)
        send!   (fn [client-id payload]
                  (when-let [ws (.get sockets client-id)]
                    (try (.send ws payload) (catch :default _ nil))))]
    {:fx
     {:ws/send-to
      (fn [_ {:keys [client-id event]}]
        (send! client-id (wire/encode event)))

      ;; Provision a room: session + AGENTS.md are per-cwd (impure), then
      ;; re-enter the pure path via :room/create + :room/attach.
      :room/setup
      (fn [{:keys [dispatch!]} {:keys [client-id room-id cwd]}]
        (let [cwd (or cwd (.cwd js/process))
              system (system-prompt/combine
                      (system-prompt/load-agents-md cwd)
                      (when ext-system-prompt (ext-system-prompt cwd)))]
          (dispatch! {:type :room/create
                      :room-id room-id
                      :room {:model        (:model server-opts)
                             :effort       (:effort server-opts)
                             :cwd          cwd
                             :system       system
                             :agents-files (system-prompt/find-agents-md cwd)
                             :session      (session/create-session cwd)
                             :ext          room-ext-init
                             :created      (js/Date.now)}})
          (dispatch! {:type :room/attach :client-id client-id :room-id room-id})))

      ;; Commands running server-side may emit TUI-owned effects; the
      ;; mirroring client re-derives whitelisted ones locally
      ;; (xi.client.ws-transport), the rest are no-ops here.
      :app/quit       (fn [_ _] nil)
      :app/reload     (fn [_ _] nil)
      :clipboard/copy (fn [_ _] nil)}

     :start!
     (fn [{:keys [dispatch! state add-tap!]} {:keys [port]}]
       (let [port (or port
                      (some-> (aget js/process.env "XI_PORT") js/parseInt)
                      DEFAULT_PORT)
             lobby-payload
             (fn [st] (wire/encode {:type :lobby/state
                                    :rooms (rm/room-summaries st)}))
             broadcast-lobby!
             (fn [st]
               (let [payload (lobby-payload st)]
                 (doseq [[cid client] (get-in st [:connection :clients])]
                   (when-not (:room-id client)
                     (send! cid payload)))))
             server
             (js/Bun.serve
              #js {:port port
                   :fetch
                   (fn [^js req ^js srv]
                     (if (.upgrade srv req #js {:data #js {:cid (gen-client-id)}})
                       js/undefined
                       (js/Response. "Xi WebSocket server. Connect via ws://."
                                     #js {:status 200})))
                   :websocket
                   #js {;; 100MB — multiple base64-encoded images per prompt
                        :maxPayloadLength (* 100 1024 1024)

                        :open
                        (fn [^js ws]
                          (let [cid (.. ws -data -cid)]
                            (.set sockets cid ws)
                            (dispatch! {:type :client/connect :client-id cid
                                        :client {:kind :remote}})
                            (send! cid (lobby-payload @state))))

                        :message
                        (fn [^js ws data]
                          (let [cid (.. ws -data -cid)]
                            (if-let [ev (wire/decode data)]
                              (let [room-id (get-in @state [:connection :clients cid :room-id])
                                    ev (assoc ev :client-id cid)]
                                (cond
                                  (pre-join-types (:type ev))
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
                            ;; Dispatch before dropping the socket — cleanup
                            ;; effects may still broadcast (sends are no-ops
                            ;; on the closed socket).
                            (dispatch! {:type :client/disconnect :client-id cid})
                            (.delete sockets cid)))}})]

         ;; The transport is a tap: every processed room event is echoed to
         ;; that room's clients (sender included — clients never apply their
         ;; own input locally). Lobby clients get room-list refreshes.
         (add-tap!
          (fn [event st]
            (let [room-id (:room-id event)]
              (when (and room-id (not (no-broadcast (:type event))))
                (when-let [cids (seq (rm/clients-in-room st room-id))]
                  (let [payload (wire/encode event)]
                    (doseq [cid cids] (send! cid payload))))))
            (when (lobby-relevant (:type event))
              (broadcast-lobby! st))))

         (js/console.error (str "[ws] Listening on ws://localhost:" port))
         {:port  port
          :stop! (fn [] (.stop server))}))}))
