(ns xi.ext.done-notify
  "Desktop notification when an agent turn completes or a dialog needs a
   decision.

   Toggled with Ctrl+Shift+N; shows a 🔔 badge in the prompt while enabled.
   When a turn ends (and isn't aborted / draining a queued prompt) the
   chained :agent/turn-end handler emits a [:notify/desktop …] effect that
   spawns dunstify. The notification carries a default action (bound to
   middle-click in dunstrc) that focuses the room's terminal window and
   switches to its xmonad workspace — either via an exact WINDOWID (xterm /
   urxvt export it) or, failing that, by matching the deterministic terminal
   title `Xi: <label>` that the TUI always sets (alacritty & friends don't
   export WINDOWID). xmonad's EWMH activate hook does the workspace switch.

   A confirm dialog opening (an MCP tool approval, git commit approval,
   guarded command, worktree removal, …) blocks the agent until answered,
   so the chained :ui/dialog-open handler *always* fires a desktop
   notification — regardless of the turn-end bell — so the prompt isn't
   missed.

   State is room-scoped ([:rooms rid :ext :done-notify] {:enabled? bool}) so
   the toggle (forwarded in client mode) mirrors back and the badge renders
   from the same state the server enforces."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def ^:private ext-id :done-notify)

(def ^:private window-id (aget js/process.env "WINDOWID"))

(defn- enabled? [state room-id]
  (boolean (:enabled? (state/room-ext state room-id ext-id))))

(defn- room-title [room]
  (or (get-in room [:session :name]) "Agent turn complete"))

(defn- focus-label
  "The terminal-title label the TUI sets for this room (session name, else the
   cwd basename) — see xi.client.tui/terminal-title. Used to locate the
   window by title when WINDOWID isn't available."
  [room]
  (or (get-in room [:session :name])
      (some-> (:cwd room) (str/split #"/") last)))

(defn- regex-escape
  "Escape POSIX-ERE metacharacters so a label with e.g. parens matches
   literally in `xdotool search --name`."
  [s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" (fn [m] (str "\\" m))))

(defn- focus-window!
  "Bring the room's terminal window forward, switching xmonad workspace via
   the EWMH activate hook. Prefer an exact WINDOWID; otherwise match the
   terminal title \"Xi: <label>\". The trailing $ anchors on the label end so
   the busy ⟳ prefix still matches."
  [label]
  (cond
    window-id
    (js/Bun.spawn #js ["xdotool" "windowactivate" window-id]
                  #js {:stdout "ignore" :stderr "ignore"})
    (seq label)
    (js/Bun.spawn #js ["xdotool" "search" "--name"
                       (str "Xi: " (regex-escape label) "$") "windowactivate"]
                  #js {:stdout "ignore" :stderr "ignore"})))

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
      {:effects [[:notify/desktop {:title (room-title room)
                                   :label (focus-label room)}]]})))

(defn- notify-desktop
  "Spawn dunstify; on its default action (middle-click, per dunstrc) focus the
   room's terminal window and switch to its workspace."
  [_ {:keys [title label]}]
  (try
    (let [focusable? (or window-id (seq label))
          args (cond-> #js ["-a" "Xi" "--stack-tag" "xi-done"]
                 focusable? (.concat #js ["-A" "default,Focus terminal"]))]
      (if focusable?
        (let [proc (js/Bun.spawn (.concat #js ["dunstify"] args #js [title])
                                 #js {:stdout "pipe" :stderr "ignore"})]
          (-> (.text (.-stdout proc))
              (.then (fn [out]
                       (when (= (.trim out) "default")
                         (focus-window! label))))))
        (js/Bun.spawn (.concat #js ["dunstify"] args #js [title])
                      #js {:stdout "ignore" :stderr "ignore"})))
    (catch :default _e nil)))

(defn- on-dialog-open
  "Chained after the base :ui/dialog-open. A confirm dialog blocks the
   agent until it's answered, so always notify — independent of the
   turn-end bell toggle."
  [st {:keys [room-id dialog]}]
  (when-let [room (and (= :confirm (:type dialog))
                       (state/get-room st room-id))]
    {:effects [[:notify/desktop
                {:title (str "Approval needed: " (or (:message dialog) "confirm"))
                 :label (focus-label room)}]]}))

(defn- prompt-badge [state]
  (when-let [room (state/active-room state)]
    (when (enabled? state (:id room)) " 🔔")))

(def extension
  {:id           ext-id
   :init         {:room {:enabled? false}}
   :handlers     {:ext.done-notify/toggle toggle
                  :agent/turn-end          on-turn-end
                  :ui/dialog-open          on-dialog-open}
   :fx           {:notify/desktop notify-desktop}
   :keybindings  [{:key "ctrl+shift+n" :event {:type :ext.done-notify/toggle}}]
   :prompt-badge prompt-badge})
