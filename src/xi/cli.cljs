(ns xi.cli
  "Xi entry point.
   Normal:    bun target/main.js              → runtime + WS server + TUI
   Connect:   bun target/main.js --connect 7474  → TUI connected to remote runtime"
  (:require [xi.runtime :as runtime]
            [xi.client.tui :as tui-client]
            [xi.client.ws-transport :as ws-transport]
            [xi.server.ws :as ws]))

(defn- parse-args
  "Parse CLI args. Returns {:mode :local} or {:mode :connect :port N}."
  []
  (let [args (vec (drop 2 (js->clj js/process.argv)))]
    (loop [i 0]
      (if (>= i (count args))
        {:mode :local}
        (let [arg (nth args i)]
          (cond
            (= "--connect" arg)
            (let [port (when (< (inc i) (count args))
                         (js/parseInt (nth args (inc i)) 10))]
              (if (and port (not (js/isNaN port)))
                {:mode :connect :port port}
                (do (js/console.error "Usage: xi --connect <port>")
                    (js/process.exit 1))))

            :else (recur (inc i))))))))

(defn- start-local!
  "Start local runtime with WS server and TUI."
  []
  (let [rt (runtime/create! {})
        _ws (ws/start! rt)
        transport {:dispatch! (fn [cmd] (runtime/dispatch! rt cmd))
                   :busy? (fn [] (runtime/busy? rt))}
        client (tui-client/create! {:transport transport})]
    (runtime/connect! rt client)))

(defn- start-connect!
  "Connect TUI to a remote runtime over WebSocket."
  [port]
  (let [;; We need the on-event fn before creating the transport,
        ;; but the TUI client needs the transport. Break the cycle with an atom.
        event-handler (atom nil)

        transport (ws-transport/create!
                   {:port port
                    :on-event (fn [event] (when-let [f @event-handler] (f event)))
                    :on-open (fn []
                               ;; Trigger header render with info from first :ready event
                               nil)
                    :on-close (fn []
                                (js/console.error
                                 (str "\nDisconnected from ws://localhost:" port))
                                (js/process.exit 1))})

        client (tui-client/create! {:transport transport})]

    ;; Wire up the event handler
    (reset! event-handler (:on-event client))

    ;; Render initial header (model will show when :ready event arrives)
    ((:on-connect client) {:model nil})))

(defn main []
  (let [{:keys [mode port]} (parse-args)]
    (case mode
      :local (start-local!)
      :connect (start-connect! port))))
