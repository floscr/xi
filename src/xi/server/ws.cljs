(ns xi.server.ws
  "WebSocket server for remote clients.
   Protocol: JSON messages over WebSocket.

   Handshake:
     1. Client connects
     2. Client sends {:type :join :room \"latest\"|\"new\"|<room-id>}
     3. Server responds {:type :room-joined :room-id <id>}
     4. Normal event/command flow begins

   Events flow out, commands flow in.
   Works from browsers, CLI tools, any WebSocket client."
  (:require [xi.runtime :as runtime]
            [xi.server.room-manager :as rm]
            [xi.session :as session]))

(def ^:private DEFAULT_PORT 7474)

(defn- waiting-for-join-msg
  "Build the waiting-for-join handshake payload."
  [manager personal-agent?]
  (let [rooms (rm/list-rooms manager)
        ;; Build set of session-ids that have active rooms
        active-sessions (into #{}
                              (keep :session-id)
                              rooms)]
    (cond-> {:type :waiting-for-join
             :rooms rooms
             :active-sessions (vec active-sessions)}
      personal-agent?
      (-> (assoc :sessions (session/list-personal-agent-sessions))
          (assoc :personal-agent? true)))))

(defn start!
  "Start a WebSocket server attached to a room manager.
   opts:
     :port - port number (default: 7474, or XI_PORT env var)
   Returns a map with :server, :port, :stop!, :manager."
  [manager & [opts]]
  (let [port (or (:port opts)
                 (some-> (aget js/process.env "XI_PORT") js/parseInt)
                 DEFAULT_PORT)
        personal-agent? (:personal-agent? (:opts manager))

        ;; Track WS → {room-id, runtime-client} mapping
        conn-state (atom {})

        ;; Lobby clients: connected WS not in any room (receive room updates)
        lobby-clients (atom #{})

        ;; Rooms already subscribed for lobby broadcasts (avoid duplicates)
        subscribed-rooms (atom #{})

        ;; Push current room state to all lobby clients
        broadcast-lobby!
        (fn broadcast-lobby! []
          (when (seq @lobby-clients)
            (let [rooms (rm/list-rooms manager)
                  active-sids (into #{} (keep :session-id) rooms)
                  payload (js/JSON.stringify
                           (clj->js
                            (cond-> {:type :rooms-updated
                                     :rooms rooms
                                     :active-sessions (vec active-sids)}
                              personal-agent?
                              (assoc :sessions (session/list-personal-agent-sessions)
                                     :personal-agent? true))))]
              (doseq [ws @lobby-clients]
                (try (.send ws payload) (catch :default _ nil))))))

        server
        (js/Bun.serve
         #js {:port port

              :fetch
              (fn [^js req ^js server]
                (let [upgraded (.upgrade server req)]
                  (if upgraded
                    js/undefined
                    (js/Response. "Xi WebSocket server. Connect via ws://." #js {:status 200}))))

              :websocket
              #js {:open
                   (fn [^js ws]
                     ;; Client connected but not yet joined a room.
                     ;; Send a prompt to join.
                     (swap! lobby-clients conj ws)
                     (.send ws (js/JSON.stringify
                                (clj->js (waiting-for-join-msg manager personal-agent?)))))

                   :message
                   (fn [^js ws ^js data]
                     (try
                       (let [raw (js->clj (js/JSON.parse data) :keywordize-keys true)
                             msg (update raw :type keyword)]
                         (if-let [state (get @conn-state ws)]
                           ;; Already joined
                           (if (= :leave (:type msg))
                             ;; Leave current room, go back to room list
                             (let [{:keys [room-id rt-client]} state]
                               (when-let [room (rm/get-room manager room-id)]
                                 (runtime/disconnect! (:runtime room) rt-client))
                               (rm/remove-client! manager room-id ws)
                               (swap! conn-state dissoc ws)
                               (swap! lobby-clients conj ws)
                               (.send ws (js/JSON.stringify
                                          (clj->js (waiting-for-join-msg manager personal-agent?))))
                               ;; Notify other lobby clients about the room change
                               (broadcast-lobby!))
                             ;; Normal command — dispatch to runtime
                             (let [{:keys [room-id]} state
                                   room (rm/get-room manager room-id)]
                               (when room
                                 (runtime/dispatch! (:runtime room) msg))))

                           ;; Not yet joined — expect a :join message
                           (if (= :join (:type msg))
                             (let [target (or (:room msg) (:session msg))
                                   mode (case target
                                          "new" :new
                                          "latest" :latest
                                          (or target :latest))
                                   room-opts (when-let [cwd (:cwd msg)] {:cwd cwd})
                                   room-id (rm/join-room! manager mode room-opts)
                                   room (rm/get-room manager room-id)
                                   rt (:runtime room)

                                   ;; Create a runtime client for this WS
                                   rt-client
                                   {:on-event
                                    (fn [event]
                                      (try
                                        (.send ws (js/JSON.stringify (clj->js event)))
                                        (catch :default _e nil)))
                                    :on-disconnect
                                    (fn [] nil)}

                                   connected (runtime/connect! rt rt-client)]

                               ;; Track this connection
                               (swap! lobby-clients disj ws)
                               (rm/add-client! manager room-id ws)
                               (swap! conn-state assoc ws
                                      {:room-id room-id
                                       :rt-client connected})

                               ;; Track session-id on room + cleanup idle rooms after turn
                               (runtime/subscribe! rt :turn-end
                                 (fn [event]
                                   ;; Persist session-id on room
                                   (when-let [sid (:session-id event)]
                                     (let [sess @(:sess rt)
                                           xi-id (:id sess)]
                                       (when xi-id
                                         (rm/set-room-session! manager room-id xi-id))))
                                   ;; Auto-cleanup: agent just finished, if no clients left, destroy
                                   (when-let [room (rm/get-room manager room-id)]
                                     (when (zero? (count @(:clients room)))
                                       (rm/destroy-room! manager room-id)
                                       (broadcast-lobby!)))))

                               ;; Per-room subscription: push busy state to lobby clients
                               (when-not (contains? @subscribed-rooms room-id)
                                 (swap! subscribed-rooms conj room-id)
                                 (runtime/subscribe! rt :busy-changed
                                   (fn [_] (broadcast-lobby!))))

                               ;; Confirm join + notify lobby of room change
                               (.send ws (js/JSON.stringify
                                          (clj->js {:type :room-joined
                                                    :room-id room-id})))
                               (broadcast-lobby!))

                             ;; Unknown pre-join message
                             (.send ws (js/JSON.stringify
                                        #js {:type "error"
                                             :text "Send {\"type\":\"join\",\"room\":\"latest\"} first"})))))
                       (catch :default e
                         (.send ws (js/JSON.stringify
                                    #js {:type "error"
                                         :text (str "Invalid JSON: " (.-message e))})))))

                   :close
                   (fn [^js ws _code _reason]
                     (swap! lobby-clients disj ws)
                     (when-let [{:keys [room-id rt-client]} (get @conn-state ws)]
                       (when-let [room (rm/get-room manager room-id)]
                         (runtime/disconnect! (:runtime room) rt-client))
                       (rm/remove-client! manager room-id ws)
                       (swap! conn-state dissoc ws)
                       ;; Auto-cleanup: if room has no clients and agent is idle, destroy it
                       (when-let [room (rm/get-room manager room-id)]
                         (when (and (zero? (count @(:clients room)))
                                    (not (runtime/busy? (:runtime room))))
                           (rm/destroy-room! manager room-id)))
                       (broadcast-lobby!)))}})]

    (js/console.error (str "[ws] Listening on ws://localhost:" port))

    {:server server
     :port port
     :manager manager
     :stop! (fn []
              (.stop server))}))
