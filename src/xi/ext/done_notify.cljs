(ns xi.ext.done-notify
  "Desktop notification on agent turn completion via dunstify.
   Toggle with Ctrl+Shift+N; shows 🔔 badge in prompt when enabled.
   Middle-click notification to focus the terminal window."
  (:require [xi.state.session :as state.session]))

(defonce ^:private enabled (atom false))
(def ^:private window-id (aget js/process.env "WINDOWID"))

(defn toggle!
  "Toggle notification on/off. Returns new state."
  []
  (swap! enabled not))

(defn enabled? [] @enabled)

(defn- notify [state]
  (when @enabled
    (try
      (let [title (or (state.session/session-title state) "Agent turn complete")
            args (cond-> #js ["-a" "Xi"
                              "--stack-tag" "xi-done"]
                   ;; Add default action for middle-click → focus terminal
                   window-id
                   (.concat #js ["-A" "default,Focus terminal"]))]
        (if window-id
          ;; Spawn dunstify with --wait, then focus terminal on action
          (let [proc (js/Bun.spawn
                      (.concat #js ["dunstify"] args #js [title])
                      #js {:stdout "pipe" :stderr "ignore"})]
            ;; Read stdout for action result asynchronously
            (-> (.text (.-stdout proc))
                (.then (fn [out]
                         (when (= (.trim out) "default")
                           (js/Bun.spawn
                            #js ["xdotool" "windowactivate" window-id]
                            #js {:stdout "ignore" :stderr "ignore"}))))))
          ;; No window ID — fire and forget
          (js/Bun.spawn
           (.concat #js ["dunstify"] args #js [title])
           #js {:stdout "ignore" :stderr "ignore"})))
      (catch :default _e nil))))

(defn- prompt-badge [_state]
  (when @enabled "🔔"))

(def extension
  {:name "done-notify"
   :hooks {:agent-end notify
           :prompt-badge prompt-badge}})
