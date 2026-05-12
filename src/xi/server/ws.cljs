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
            [xi.server.room-manager :as rm]))

(def ^:private DEFAULT_PORT 7474)

(defn start!
  "Start a WebSocket server attached to a room manager.
   opts:
     :port - port number (default: 7474, or XI_PORT env var)
   Returns a map with :server, :port, :stop!, :manager."
  [manager & [opts]]
  (let [port (or (:port opts)
                 (some-> (aget js/process.env "XI_PORT") js/parseInt)
                 DEFAULT_PORT)

        ;; Track WS → {room-id, runtime-client} mapping
        conn-state (atom {})

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
                     (.send ws (js/JSON.stringify
                                (clj->js {:type :waiting-for-join
                                          :rooms (rm/list-rooms manager)}))))

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
                               (.send ws (js/JSON.stringify
                                          (clj->js {:type :waiting-for-join
                                                    :rooms (rm/list-rooms manager)}))))
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
                               (rm/add-client! manager room-id ws)
                               (swap! conn-state assoc ws
                                      {:room-id room-id
                                       :rt-client connected})

                               ;; Confirm join
                               (.send ws (js/JSON.stringify
                                          (clj->js {:type :room-joined
                                                    :room-id room-id}))))

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
                     (when-let [{:keys [room-id rt-client]} (get @conn-state ws)]
                       (when-let [room (rm/get-room manager room-id)]
                         (runtime/disconnect! (:runtime room) rt-client))
                       (rm/remove-client! manager room-id ws)
                       (swap! conn-state dissoc ws)))}})]

    (js/console.error (str "[ws] Listening on ws://localhost:" port))

    {:server server
     :port port
     :manager manager
     :stop! (fn []
              (.stop server))}))
