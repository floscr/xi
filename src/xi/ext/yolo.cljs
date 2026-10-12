(ns xi.ext.yolo
  "Yolo mode — run every tool call that would otherwise ask, toggled with /yolo.

   This extension owns the room-scoped flag ([:rooms rid :ext :yolo]
   {:enabled? bool}), its toggle and the prompt badge. /yolo dispatches
   :ext.yolo/set, whose reducer `set-enabled` flips the flag and appends the
   status line; the server echoes that event, so the TUI mirror and the web
   half (xi.ext.yolo.web) apply the same reducer and every client shows the
   same state. The flag is saved with the chat (:persist-room, xi.ext.persist),
   so it survives the room being reaped between turns and a server restart.

   The skipping itself is not here: xi.ext.core/create-dialogs resolves a
   :confirm dialog to yes without opening it while the flag is on
   (xi.dialog/auto-confirm?), so the rules engine's asks, the clj gate's own
   prompts and sub-agent spawns all run. Hardened-tier asks are exempt.
   Asks already open when it is turned on are allowed then."
  (:require [xi.core.state :as state]
            [xi.dialog :as dialog]))

(def ^:private ext-id :yolo)

(defn enabled? [state room-id]
  (boolean (:enabled? (state/room-ext state room-id ext-id))))

(defn- answer-open-asks
  "Allow every dialog already open in the room that yolo would not have
   opened, through the same :ui/dialog-response a click sends."
  [st room-id]
  (into []
        (keep (fn [d]
                (when (dialog/auto-confirm? st room-id d)
                  [:app/dispatch {:type :ui/dialog-response :room-id room-id
                                  :dialog-id (:id d) :value true}])))
        (get-in st [:rooms room-id :ui :dialogs])))

(defn set-enabled
  "Reducer for :ext.yolo/set {:room-id :enabled?}; shared with the web half.
   Turning it on also allows the asks already waiting (answer-open-asks).
   Off removes the key rather than storing false: xi.ext.persist then drops
   the chat's saved slice, and a hydrate only fills keys the room lacks."
  [st {:keys [room-id enabled?]}]
  (when (state/get-room st room-id)
    (let [st' (-> st
                  (update-in [:rooms room-id :ext ext-id]
                             #(if enabled? (assoc % :enabled? true) (dissoc % :enabled?)))
                  (update-in [:rooms room-id :history] conj
                             {:kind :status
                              :text (str "Yolo mode: "
                                         (if enabled?
                                           "ON (tool calls run without asking)"
                                           "OFF"))}))
          fx  (when enabled? (answer-open-asks st' room-id))]
      (cond-> {:state st'}
        (seq fx) (assoc :effects fx)))))

(defn- toggle
  "/yolo — flip the flag through :ext.yolo/set so the change is an event
   every client mirrors. The state is returned unchanged so a client mirror
   of the command (effects stripped) is a no-op."
  [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state   st
     :effects [[:app/dispatch {:type     :ext.yolo/set
                               :room-id  room-id
                               :enabled? (not (enabled? st room-id))}]]}))

(defn- prompt-badge [state]
  (when-let [room (state/active-room state)]
    (when (enabled? state (:id room)) " 🔥")))

(def extension
  {:id           ext-id
   :persist-room [:enabled?]
   :handlers     {:ext.yolo/set set-enabled}
   :commands     [{:name "yolo"
                   :description "Toggle yolo mode (run tool calls without asking)"
                   :handler toggle}]
   :prompt-badge prompt-badge})
