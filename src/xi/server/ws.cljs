(ns xi.server.ws
  "WebSocket server for remote clients.
   Protocol: JSON messages over WebSocket.
   Events flow out, commands flow in.
   Works from browsers, CLI tools, any WebSocket client."
  (:require [xi.runtime :as runtime]))

(def ^:private DEFAULT_PORT 7474)

(defn start!
  "Start a WebSocket server attached to a runtime.
   opts:
     :port - port number (default: 7474, or XI_PORT env var)
   Returns a map with :server, :port, :stop!."
  [rt & [opts]]
  (let [port (or (:port opts)
                 (some-> (aget js/process.env "XI_PORT") js/parseInt)
                 DEFAULT_PORT)

        ;; Track connected clients so we can clean up
        conn->client (atom {})

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
                     (let [client
                           {:on-event
                            (fn [event]
                              (try
                                (.send ws (js/JSON.stringify (clj->js event)))
                                (catch :default _e nil)))

                            :on-disconnect
                            (fn [] nil)}

                           connected (runtime/connect! rt client)]
                       (swap! conn->client assoc ws connected)))

                   :message
                   (fn [^js ws ^js data]
                     (try
                       (let [raw (js->clj (js/JSON.parse data) :keywordize-keys true)
                             ;; JSON roundtrip turns keyword values into strings.
                             ;; Restore :type to a keyword so dispatch works.
                             cmd (update raw :type keyword)]
                         (runtime/dispatch! rt cmd))
                       (catch :default e
                         (.send ws (js/JSON.stringify
                                    #js {:type "error"
                                         :text (str "Invalid JSON: " (.-message e))})))))

                   :close
                   (fn [^js ws _code _reason]
                     (when-let [client (get @conn->client ws)]
                       (runtime/disconnect! rt client)
                       (swap! conn->client dissoc ws)))}})]

    (js/console.error (str "[ws] Listening on ws://localhost:" port))

    {:server server
     :port port
     :stop! (fn []
              (.stop server))}))
