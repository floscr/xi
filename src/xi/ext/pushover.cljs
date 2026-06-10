(ns xi.ext.pushover
  "Pushover push notifications for server and standalone modes.

   Sends a push when an agent turn completes and the user is unlikely to be
   watching:
     standalone — only when the done-notify bell (🔔) is enabled.
     server     — only when no visible client is attached to the room.
   In server mode the notification carries a deep link (PUSHOVER_URL +
   /chat/<session-id>) so tapping it opens the chat.

   `extension` is a factory: it returns nil (and compose drops it) unless
   both PUSHOVER_USER_KEY and PUSHOVER_APP_TOKEN are set, so the seam is a
   no-op when pushover isn't configured.

   Env vars:
     PUSHOVER_USER_KEY  — Pushover user key   (required)
     PUSHOVER_APP_TOKEN — Pushover app token  (required)
     PUSHOVER_URL       — base URL for deep links (server mode, optional)"
  (:require [xi.core.state :as state]))

(def ^:private API_URL "https://api.pushover.net/1/messages.json")

(defn- visible-clients-in-room [st room-id]
  (count (filter (fn [[_ client]]
                   (and (= room-id (:room-id client)) (:visible? client)))
                 (get-in st [:connection :clients]))))

(defn- should-notify? [st room-id]
  (case (state/mode st)
    ;; Standalone: piggyback on the done-notify bell toggle.
    :standalone (boolean (:enabled? (state/room-ext st room-id :done-notify)))
    ;; Server: notify only when nobody is watching the room.
    :server     (zero? (visible-clients-in-room st room-id))
    false))

(defn- on-turn-end
  "Chained after the base :agent/turn-end. Emits a [:notify/pushover …] when
   the turn truly ended (not aborted, no queued prompt) and nobody is
   watching (see should-notify?)."
  [st {:keys [room-id aborted?]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (not aborted?)
               (empty? (get-in room [:agent :queued]))
               (should-notify? st room-id))
      (let [base-url (aget js/process.env "PUSHOVER_URL")
            sid      (get-in room [:session :id])
            url      (when (and (= :server (state/mode st)) base-url sid)
                       (str base-url "/chat/" sid))]
        {:effects [[:notify/pushover
                    (cond-> {:title (or (get-in room [:session :name])
                                        "Agent turn complete")}
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

(defn extension
  "Factory — returns the pushover extension map, or nil when unconfigured."
  []
  (when (and (aget js/process.env "PUSHOVER_USER_KEY")
             (aget js/process.env "PUSHOVER_APP_TOKEN"))
    {:id       :pushover
     :handlers {:agent/turn-end on-turn-end}
     :fx       {:notify/pushover notify-pushover}}))
