(ns xi.client.ws-transport
  "WebSocket transport — connects a TUI client to a remote Xi runtime.
   Implements the transport protocol {:dispatch! fn, :busy? fn}."
  (:require [xi.runtime.commands :as commands]))

(defn create!
  "Connect to a remote Xi runtime over WebSocket.
   Returns a transport map and calls on-event for each received event.

   opts:
     :port     - port number
     :on-event - callback for events from the server
     :on-open  - callback when connected
     :on-close - callback when disconnected"
  [opts]
  (let [port (:port opts)
        url (str "ws://localhost:" port)
        busy (atom false)
        on-event (:on-event opts)

        ws (js/WebSocket. url)]

    (.addEventListener ws "open"
                       (fn [_]
                         (js/console.error (str "[ws] Connected to " url))
                         (when-let [f (:on-open opts)] (f))))

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
                                 ;; JSON roundtrip turns keyword values into strings.
                                 ;; Restore :type to a keyword so case dispatch works.
                                 event (update raw :type keyword)]
                             ;; Track busy state locally
                             (when (= :busy-changed (:type event))
                               (reset! busy (:busy event)))
                             ;; Forward to TUI
                             (when on-event
                               (on-event event)))
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
