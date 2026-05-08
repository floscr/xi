(ns xi.client.ws-transport
  "WebSocket transport — connects a TUI client to a remote Xi runtime.
   Implements the transport protocol {:dispatch! fn, :busy? fn}.
   
   On connect, sends a :join message to enter a session.
   Waits for :session-joined before forwarding events."
  (:require [xi.runtime.commands :as commands]))

(defn create!
  "Connect to a remote Xi runtime over WebSocket.
   Returns a transport map and calls on-event for each received event.

   opts:
     :port     - port number
     :session  - \"latest\", \"new\", or a session-id (default: \"latest\")
     :on-event - callback for events from the server
     :on-open  - callback when connected
     :on-close - callback when disconnected"
  [opts]
  (let [port (:port opts)
        url (str "ws://localhost:" port)
        session-mode (or (:session opts) "latest")
        busy (atom false)
        joined (atom false)
        on-event (:on-event opts)

        ws (js/WebSocket. url)]

    (.addEventListener ws "open"
                       (fn [_]
                         (js/console.error (str "[ws] Connected to " url ", joining session (" session-mode ")..."))
                         ;; Send join handshake
                         (.send ws (js/JSON.stringify
                                    (clj->js {:type :join
                                              :session session-mode})))))

    (.addEventListener ws "close"
                       (fn [_]
                         (js/console.error "[ws] Disconnected")
                         (when-let [f (:on-close opts)] (f))))

    (.addEventListener ws "error"
                       (fn [^js e]
                         (js/console.error (str "[ws] Connection error: " (.-message e)))
                         (when-let [f (:on-close opts)] (f))))

    (.addEventListener ws "message"
                       (fn [^js e]
                         (try
                           (let [raw (js->clj (js/JSON.parse (.-data e)) :keywordize-keys true)
                                 event (update raw :type keyword)]
                             (case (:type event)
                               ;; Handshake: waiting for join (server acknowledges connection)
                               :waiting-for-join
                               nil ;; We already sent join in on-open

                               ;; Handshake complete
                               :session-joined
                               (do (reset! joined true)
                                   (js/console.error (str "[ws] Joined session " (:session-id event)))
                                   (when-let [f (:on-open opts)] (f)))

                               ;; Normal event flow
                               (do
                                 ;; Track busy state locally
                                 (when (= :busy-changed (:type event))
                                   (reset! busy (:busy event)))
                                 ;; Forward to TUI
                                 (when on-event
                                   (on-event event)))))
                           (catch :default err
                             (js/console.error "[ws] Bad message:" err)))))

    {:dispatch!
     (fn [command]
       (let [parsed (if (string? command)
                      (commands/parse-input command)
                      command)
             json (js/JSON.stringify (clj->js parsed))]
         (.send ws json)
         (js/Promise.resolve nil)))

     :busy?
     (fn [] @busy)

     :close!
     (fn [] (.close ws))}))
