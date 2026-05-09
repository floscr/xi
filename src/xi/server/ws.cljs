(ns xi.server.ws
  "WebSocket server for remote clients.
   Protocol: JSON messages over WebSocket.
   
   Handshake:
     1. Client connects
     2. Client sends {:type :join :session \"latest\"|\"new\"|<session-id>}
     3. Server responds {:type :session-joined :session-id <id>}
     4. Normal event/command flow begins
   
   Events flow out, commands flow in.
   Works from browsers, CLI tools, any WebSocket client."
  (:require [xi.runtime :as runtime]
            [xi.server.session-manager :as sm]))

(def ^:private DEFAULT_PORT 7474)

(defn start!
  "Start a WebSocket server attached to a session manager.
   opts:
     :port - port number (default: 7474, or XI_PORT env var)
   Returns a map with :server, :port, :stop!, :manager."
  [manager & [opts]]
  (let [port (or (:port opts)
                 (some-> (aget js/process.env "XI_PORT") js/parseInt)
                 DEFAULT_PORT)

        ;; Track WS → {session-id, runtime-client} mapping
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
                     ;; Client connected but not yet joined a session.
                     ;; Send a prompt to join.
                     (.send ws (js/JSON.stringify
                                (clj->js {:type :waiting-for-join
                                          :sessions (sm/list-sessions manager)}))))

                   :message
                   (fn [^js ws ^js data]
                     (try
                       (let [raw (js->clj (js/JSON.parse data) :keywordize-keys true)
                             msg (update raw :type keyword)]
                         (if-let [state (get @conn-state ws)]
                           ;; Already joined — dispatch command to runtime
                           (let [{:keys [session-id]} state
                                 session (sm/get-session manager session-id)]
                             (when session
                               (runtime/dispatch! (:runtime session) msg)))

                           ;; Not yet joined — expect a :join message
                           (if (= :join (:type msg))
                             (let [mode (case (:session msg)
                                          "new" :new
                                          "latest" :latest
                                          (or (:session msg) :latest))
                                   session-opts (when-let [cwd (:cwd msg)] {:cwd cwd})
                                   session-id (sm/join-session! manager mode session-opts)
                                   session (sm/get-session manager session-id)
                                   rt (:runtime session)

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
                               (sm/add-client! manager session-id ws)
                               (swap! conn-state assoc ws
                                      {:session-id session-id
                                       :rt-client connected})

                               ;; Confirm join
                               (.send ws (js/JSON.stringify
                                          (clj->js {:type :session-joined
                                                    :session-id session-id}))))

                             ;; Unknown pre-join message
                             (.send ws (js/JSON.stringify
                                        #js {:type "error"
                                             :text "Send {\"type\":\"join\",\"session\":\"latest\"} first"})))))
                       (catch :default e
                         (.send ws (js/JSON.stringify
                                    #js {:type "error"
                                         :text (str "Invalid JSON: " (.-message e))})))))

                   :close
                   (fn [^js ws _code _reason]
                     (when-let [{:keys [session-id rt-client]} (get @conn-state ws)]
                       (when-let [session (sm/get-session manager session-id)]
                         (runtime/disconnect! (:runtime session) rt-client))
                       (sm/remove-client! manager session-id ws)
                       (swap! conn-state dissoc ws)))}})]

    (js/console.error (str "[ws] Listening on ws://localhost:" port))

    {:server server
     :port port
     :manager manager
     :stop! (fn []
              (.stop server))}))
