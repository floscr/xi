(ns xi.ext.pushover
  "Pushover push notifications for server and standalone modes.

   Sends a push when the user is unlikely to be watching, on either:
     - an agent turn completing, or
     - a confirm dialog opening (git commit approval, guarded command,
       worktree removal, …) that needs a decision to proceed.
   The user is deemed unlikely to be watching when:
     standalone — only when the done-notify bell (🔔) is enabled.
     server     — only when no visible client is attached to the room.
   In server mode the notification carries a deep link (PUSHOVER_URL +
   /chat/<session-id>) so tapping it opens the chat.

   A per-room push toggle (Ctrl+Shift+P, 📲 badge) cycles a tri-state mode:
     :auto (default) — the away-only behavior above,
     :on             — force pushes even while watching (ping me even here),
     :off            — never push (fully mute this room).
   Mirrors the done-notify bell's toggle mechanics.

   `extension` is a factory: it returns nil (and compose drops it) unless
   both PUSHOVER_USER_KEY and PUSHOVER_APP_TOKEN are set, so the seam is a
   no-op when pushover isn't configured.

   Env vars:
     PUSHOVER_USER_KEY  — Pushover user key   (required)
     PUSHOVER_APP_TOKEN — Pushover app token  (required)
     PUSHOVER_URL       — base URL for deep links (server mode, optional)"
  (:require [xi.core.state :as state]))

(def ^:private API_URL "https://api.pushover.net/1/messages.json")

(def ^:private ext-id :pushover)

(defn- push-mode
  "The room's push mode: :auto (default — notify only when away), :on (force
   a notification even while watching) or :off (never notify)."
  [st room-id]
  (or (:mode (state/room-ext st room-id ext-id)) :auto))

(defn- visible-clients-in-room [st room-id]
  (count (filter (fn [[_ client]]
                   (and (= room-id (:room-id client)) (:visible? client)))
                 (get-in st [:connection :clients]))))

(defn- should-notify? [st room-id]
  (case (push-mode st room-id)
    :off false
    :on  true
    ;; :auto — notify only when the user is deemed not to be watching.
    (case (state/mode st)
      ;; Standalone: piggyback on the done-notify bell toggle.
      :standalone (boolean (:enabled? (state/room-ext st room-id :done-notify)))
      ;; Server: notify only when nobody is watching the room.
      :server     (zero? (visible-clients-in-room st room-id))
      false)))

(def ^:private next-mode
  "Cycle order for the push toggle: auto → on → off → auto."
  {:auto :on, :on :off, :off :auto})

(defn- mode-label [mode]
  (case mode
    :on   "ON (always)"
    :off  "OFF"
    "AUTO (only when away)"))

(defn toggle
  "Ctrl+Shift+P (TUI) / palette (web) → cycle the room-scoped push mode
   (auto → on → off) and report the new state. Pure, so the web reuses it to
   mirror the server's broadcast echo."
  [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [nxt (next-mode (push-mode st room-id))
          st' (assoc-in st [:rooms room-id :ext ext-id :mode] nxt)]
      {:state (update-in st' [:rooms room-id :history] conj
                         {:kind :status
                          :text (str "Push notifications: " (mode-label nxt))})})))

(defn- prompt-badge [state]
  (when-let [room (state/active-room state)]
    (when (= :on (push-mode state (:id room))) " 📲")))

(defn- deep-link-url
  "Server-mode deep link to the room's chat (PUSHOVER_URL + /chat/<sid>),
   or nil in standalone / when unconfigured."
  [st room]
  (let [base-url (aget js/process.env "PUSHOVER_URL")
        sid      (get-in room [:session :id])]
    (when (and (= :server (state/mode st)) base-url sid)
      (str base-url "/chat/" sid))))

(defn- on-turn-end
  "Chained after the base :agent/turn-end. Emits a [:notify/pushover …] when
   the turn truly ended (not aborted, no queued prompt) and nobody is
   watching (see should-notify?)."
  [st {:keys [room-id aborted?]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (not aborted?)
               (empty? (get-in room [:agent :queued]))
               (should-notify? st room-id))
      (let [url (deep-link-url st room)]
        {:effects [[:notify/pushover
                    (cond-> {:title (or (get-in room [:session :name])
                                        "Agent turn complete")}
                      url (assoc :url url))]]}))))

(defn- on-dialog-open
  "Chained after the base :ui/dialog-open. A confirm dialog (git commit
   approval, guarded command, worktree removal, …) needs a decision before
   the agent can proceed — emit a [:notify/pushover …] when nobody is
   watching so the prompt isn't missed."
  [st {:keys [room-id dialog]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (= :confirm (:type dialog))
               (should-notify? st room-id))
      (let [url (deep-link-url st room)]
        {:effects [[:notify/pushover
                    (cond-> {:title (str "Approval needed: "
                                         (or (:message dialog) "confirm"))}
                      url (assoc :url url))]]}))))

(defn- notify-pushover [_ {:keys [title url]}]
  (let [user  (aget js/process.env "PUSHOVER_USER_KEY")
        token (aget js/process.env "PUSHOVER_APP_TOKEN")
        body  (cond-> {:token token :user user :message title :title "Xi"}
                url (assoc :url url :url_title "Open chat"))]
    (-> (js/fetch API_URL
                  #js {:method  "POST"
                       :headers #js {"Content-Type" "application/json"}
                       :body    (js/JSON.stringify (clj->js body))})
        (.then (fn [^js res]
                 (when-not (.-ok res)
                   (-> (.text res)
                       (.then (fn [b]
                                (js/console.error
                                 (str "[pushover] API error " (.-status res) ": " b))))))))
        (.catch (fn [e]
                  (js/console.error "[pushover] error:" (.-message e)))))))

(defn create
  "Factory — returns the pushover extension map, or nil when unconfigured."
  [_ctx]
  (when (and (aget js/process.env "PUSHOVER_USER_KEY")
             (aget js/process.env "PUSHOVER_APP_TOKEN"))
    {:id           ext-id
     :init         {:room {:mode :auto}}
     :handlers     {:ext.pushover/toggle toggle
                    :agent/turn-end      on-turn-end
                    :ui/dialog-open      on-dialog-open}
     :fx           {:notify/pushover notify-pushover}
     :keybindings  [{:key "ctrl+shift+p" :event {:type :ext.pushover/toggle}}]
     :prompt-badge prompt-badge}))
