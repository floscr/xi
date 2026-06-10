(ns xi.ext.done-notify
  "Desktop notification when an agent turn completes.

   Toggled with Ctrl+Shift+N; shows a 🔔 badge in the prompt while enabled.
   When a turn ends (and isn't aborted / draining a queued prompt) the
   chained :agent/turn-end handler emits a [:notify/desktop …] effect that
   spawns dunstify. If a terminal window id is known (WINDOWID), the
   notification carries a default action so a middle-click focuses the
   terminal again.

   State is room-scoped ([:rooms rid :ext :done-notify] {:enabled? bool}) so
   the toggle (forwarded in client mode) mirrors back and the badge renders
   from the same state the server enforces."
  (:require [xi.core.state :as state]))

(def ^:private ext-id :done-notify)

(def ^:private window-id (aget js/process.env "WINDOWID"))

(defn- enabled? [state room-id]
  (boolean (:enabled? (state/room-ext state room-id ext-id))))

(defn- room-title [room]
  (or (get-in room [:session :name]) "Agent turn complete"))

(defn- toggle
  "Ctrl+Shift+N → flip room-scoped :enabled? and report the new state."
  [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [st' (update-in st [:rooms room-id :ext ext-id :enabled?] not)
          on? (get-in st' [:rooms room-id :ext ext-id :enabled?])]
      {:state (update-in st' [:rooms room-id :history] conj
                         {:kind :status
                          :text (str "Desktop notifications: " (if on? "ON" "OFF"))})})))

(defn- on-turn-end
  "Chained after the base :agent/turn-end. Notify only when enabled, the
   turn wasn't aborted, and no queued prompt is about to drain (state here
   reflects the base handler having already dropped one queued prompt)."
  [st {:keys [room-id aborted?]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (enabled? st room-id)
               (not aborted?)
               (empty? (get-in room [:agent :queued])))
      {:effects [[:notify/desktop {:title (room-title room)}]]})))

(defn- notify-desktop
  "Spawn dunstify; on action (middle-click) focus the terminal window."
  [_ {:keys [title]}]
  (try
    (let [args (cond-> #js ["-a" "Xi" "--stack-tag" "xi-done"]
                 window-id (.concat #js ["-A" "default,Focus terminal"]))]
      (if window-id
        (let [proc (js/Bun.spawn (.concat #js ["dunstify"] args #js [title])
                                 #js {:stdout "pipe" :stderr "ignore"})]
          (-> (.text (.-stdout proc))
              (.then (fn [out]
                       (when (= (.trim out) "default")
                         (js/Bun.spawn #js ["xdotool" "windowactivate" window-id]
                                       #js {:stdout "ignore" :stderr "ignore"}))))))
        (js/Bun.spawn (.concat #js ["dunstify"] args #js [title])
                      #js {:stdout "ignore" :stderr "ignore"})))
    (catch :default _e nil)))

(defn- prompt-badge [state]
  (when-let [room (state/active-room state)]
    (when (enabled? state (:id room)) " 🔔")))

(def extension
  {:id           ext-id
   :init         {:room {:enabled? false}}
   :handlers     {:ext.done-notify/toggle toggle
                  :agent/turn-end          on-turn-end}
   :fx           {:notify/desktop notify-desktop}
   :keybindings  [{:key "ctrl+shift+n" :event {:type :ext.done-notify/toggle}}]
   :prompt-badge prompt-badge})
