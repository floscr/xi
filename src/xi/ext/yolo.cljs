(ns xi.ext.yolo
  "Yolo mode — run tool calls that would otherwise ask, toggled with /yolo.

   Two levels: :sane answers every ask except the guarded ones (a rule
   action's `:guard`: writes outside the project, secret leaks, git push, …)
   and, through the xi.rules.defaults/yolo-sane bundle, denies removing system
   directories; :relentless answers every ask but `:guard :always` (server
   restarts).

   This extension owns the room-scoped level ([:rooms rid :ext :yolo]
   {:level :sane|:relentless}), the /yolo command and the prompt badge. /yolo
   dispatches :ext.yolo/set, whose reducer `set-level` stores the level and
   appends the status line; the server echoes that event, so the TUI mirror
   and the web half (xi.ext.yolo.web) apply the same reducer and every client
   shows the same state. The level is saved with the chat (:persist-room,
   xi.ext.persist), so it survives the room being reaped between turns and a
   server restart.

   The skipping itself is not here: xi.ext.core/create-dialogs resolves a
   :confirm dialog to yes without opening it when xi.dialog/auto-confirm?
   says the level answers it, so the rules engine's asks, the clj gate's own
   prompts and sub-agent spawns all run. Hardened-tier asks are exempt.
   Asks already open when the level is raised are allowed then."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.dialog :as dialog]))

(def ^:private ext-id :yolo)

(def ^:private levels #{:sane :relentless})

(defn level
  "The room's yolo level: :sane, :relentless or nil (off)."
  [state room-id]
  (levels (:level (state/room-ext state room-id ext-id))))

(defn enabled? [state room-id]
  (some? (level state room-id)))

(defn- answer-open-asks
  "Allow every dialog already open in the room that the new level would not
   have opened, through the same :ui/dialog-response a click sends."
  [st room-id]
  (into []
        (keep (fn [d]
                (when (dialog/auto-confirm? st room-id d)
                  [:app/dispatch {:type :ui/dialog-response :room-id room-id
                                  :dialog-id (:id d) :value true}])))
        (get-in st [:rooms room-id :ui :dialogs])))

(defn- status-text [level]
  (str "Yolo mode: "
       (case level
         :sane       (str "ON (tool calls run without asking, except writes outside "
                          "the project, secrets, git push and server restarts; "
                          "removing system directories is denied)")
         :relentless "RELENTLESS (every tool call runs without asking, except server restarts)"
         "OFF")))

(defn status-entry
  "The history line for a level change; :text for the TUI, the rest for the
   web client's banner (xi.commands ns doc)."
  [level]
  (merge {:kind :status :text (status-text level) :icon :zap}
         (case level
           :sane       {:title "Yolo mode on" :tone :warning
                        :detail "Tool calls run without asking."
                        :rows [{:label "Asks" :text "writes outside the project · secrets · git push · server restarts"}
                               {:label "Denied" :text "removing system directories" :tone :danger}]}
           :relentless {:title "Yolo mode relentless" :tone :danger
                        :detail "Every tool call runs without asking."
                        :rows [{:label "Asks" :text "server restarts"}]}
           {})))

(defn set-level
  "Reducer for :ext.yolo/set {:room-id :level}, :level nil being off; shared
   with the web half. Also allows the asks already waiting that the new level
   answers (answer-open-asks). Off removes the key rather than storing nil:
   xi.ext.persist then drops the chat's saved slice, and a hydrate only fills
   keys the room lacks."
  [st {:keys [room-id level]}]
  (when (state/get-room st room-id)
    (let [level (levels level)
          st'   (-> st
                    (update-in [:rooms room-id :ext ext-id]
                               #(if level (assoc % :level level) (dissoc % :level)))
                    (update-in [:rooms room-id :history] conj (status-entry level)))
          fx    (answer-open-asks st' room-id)]
      (cond-> {:state st'}
        (seq fx) (assoc :effects fx)))))

(defn- requested-level
  "The level `/yolo <args>` asks for → [:ok level] (nil = off) or [:error text].
   A bare /yolo toggles: off → :sane, any level → off."
  [current args]
  (case (str/lower-case (str/trim (or args "")))
    ""           [:ok (when-not current :sane)]
    "sane"       [:ok :sane]
    "relentless" [:ok :relentless]
    "off"        [:ok nil]
    [:error "Usage: /yolo [sane|relentless|off]"]))

(defn- yolo-command
  "/yolo — set the level through :ext.yolo/set so the change is an event
   every client mirrors. The state is returned unchanged so a client mirror
   of the command (effects stripped) is a no-op."
  [st {:keys [room-id args]}]
  (when (state/get-room st room-id)
    (let [[tag v] (requested-level (level st room-id) args)]
      {:state   st
       :effects [[:app/dispatch
                  (if (= :ok tag)
                    {:type :ext.yolo/set :room-id room-id :level v}
                    {:type :ui/status :room-id room-id :text v})]]})))

(defn- prompt-badge [state]
  (when-let [room (state/active-room state)]
    (case (level state (:id room))
      :sane       " 🔥"
      :relentless " 🔥🔥"
      nil)))

(def extension
  {:id           ext-id
   :persist-room [:level]
   :handlers     {:ext.yolo/set set-level}
   :commands     [{:name        "yolo"
                   :description "Toggle yolo mode (run tool calls without asking)"
                   :handler     yolo-command
                   :subcommands [{:name "sane"       :description "Run tool calls without asking, except risky ones"}
                                 {:name "relentless" :description "Run every tool call without asking"}
                                 {:name "off"        :description "Ask again"}]}]
   :prompt-badge prompt-badge})
