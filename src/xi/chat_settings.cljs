(ns xi.chat-settings
  "Chat settings with three scopes: this chat, the default for new chats, and
   all of the user's open chats.

   A chat's value is changed by running the setting's command in its room
   (/model, /yolo), so that command stays the one place a value changes and
   every client mirrors it. The default lives in the user's state at
   :chat-defaults (xi.user-state); new rooms start from it (`seed-ext`, read
   by xi.server.ws and xi.cli).

     :chat-settings/apply {:key :value :scope :room-id}
        :chat     run the command in :room-id
        :default  also store the default
        :all      store the default and run it in every open chat of the user

   The TUI reaches the scopes from the model menu (Alt+Enter, Tab:
   `menu-bindings`); the web from its palette and the yolo flame
   (xi.web.views)."
  (:require [xi.core.state :as state]
            [xi.ext.yolo :as yolo]))

(def settings
  "key → {:label :command}; `values` lists the choices (the model list is
   fetched instead)."
  {:model {:label "Model"     :command "model"}
   :yolo  {:label "Yolo mode" :command "yolo"}})

(def yolo-levels [:off :sane :relentless])

(def scopes
  [{:scope :chat    :label "This chat"}
   {:scope :default :label "Default for new chats"}
   {:scope :all     :label "All open chats"}])

(defn chat-value
  "The setting's value in room `room-id`; yolo is :off while off or disabled."
  [st room-id k]
  (case k
    :model (get-in st [:rooms room-id :agent :model])
    :yolo  (or (yolo/level st room-id) :off)
    nil))

(defn user-defaults
  "The loaded chat defaults of `user` ({:model :yolo}, each optional)."
  [st user]
  (or (get-in st [:users user :ui :chat-defaults]) {}))

(defn with-default
  "`defaults` with `k` set to `v`; yolo :off and nil remove the key."
  [defaults k v]
  (if (or (nil? v) (= :off v))
    (dissoc defaults k)
    (assoc defaults k v)))

(defn seed-ext
  "A new room's extension state `ext` with the user's default yolo level,
   unless the agent profile disabled yolo."
  [ext defaults]
  (let [level (:yolo defaults)]
    (cond-> ext
      (and (#{:sane :relentless} level)
           (not (get-in ext [:yolo :disabled?])))
      (assoc-in [:yolo :level] level))))

(defn- command-args [v]
  (if (keyword? v) (name v) v))

(defn- applies?
  "Would running the setting change room `rid`? Not when it already has the
   value, nor for yolo in an agent's chat that disabled it."
  [st rid k v]
  (and (some? (state/get-room st rid))
       (not= v (chat-value st rid k))
       (not (and (= :yolo k) (yolo/disabled? st rid)))))

(defn user-rooms
  "The open chats of `user`: rooms they are in, and rooms nobody is in that
   they prompted last (xi.core.state/turn-user)."
  [st user]
  (vec (for [[rid room] (:rooms st)
             :let [users (state/room-users room)]
             :when (if (seq users)
                     (some #{user} users)
                     (= user (state/turn-user st rid)))]
         rid)))

(defn- apply-setting
  [st {:keys [key value scope room-id] :as ev}]
  (when-let [{:keys [command]} (get settings key)]
    (let [user  (state/event-user st ev)
          ;; roomless on the server (a new chat has no room yet): only the
          ;; sender's own chats are targets
          mine  (user-rooms st user)
          rids  (if (= :all scope)
                  mine
                  (filter #{room-id} mine))
          runs  (for [rid rids :when (applies? st rid key value)]
                  [:app/dispatch {:type :command/run :room-id rid :name command
                                  :args (command-args value) :user user}])
          save  (when (#{:default :all} scope)
                  [:user-state/save {:user user :key :chat-defaults
                                     :value (with-default (user-defaults st user) key value)}])]
      {:effects (cond-> (vec runs) save (conj save))})))

;; ── TUI menus ───────────────────────────────────────────────────────────────

(def ^:private alt-enter-keys
  "Alt+Enter as legacy terminals and the kitty keyboard protocol send it."
  [(str (char 27) "\r") (str (char 27) "[13;3u")])

(defn menu-bindings
  "Key bindings of a value menu for setting `k` (items carry :value): Alt+Enter
   makes the selected value the default, Tab opens its scope menu."
  [k]
  (conj (mapv (fn [key]
                {:key key :selected? true
                 :event {:type :chat-settings/apply :key k :scope :default}})
              alt-enter-keys)
        {:key "\t" :selected? true
         :event {:type :chat-settings/scopes :key k}}))

(defn- selected-value
  "The value of an event from `menu-bindings`: its own, else the picked item's."
  [{:keys [value selected]}]
  (if (some? value) value (:value selected)))

(defn- apply-selected [st ev]
  (apply-setting st (assoc ev :value (selected-value ev))))

(defn- scopes-menu
  "Push the scope menu for the value picked in a setting's menu."
  [st {:keys [room-id key] :as ev}]
  (let [v (selected-value ev)]
    (when (and (state/get-room st room-id) (contains? settings key) (some? v))
      {:effects [[:app/dispatch
                  {:type :ui/menu-push :room-id room-id
                   :menu {:id :chat-settings/scopes
                          :prompt (str (get-in settings [key :label]) " " (command-args v) "> ")
                          :items (mapv (fn [{:keys [scope label]}]
                                         {:label label
                                          :event {:type :chat-settings/apply :room-id room-id
                                                  :key key :value v :scope scope}})
                                       scopes)}}]]})))

(defn- value-label
  "A menu row's label: the value, marked when it is the chat's or the default."
  [v current default]
  (cond-> (command-args v)
    (= v current) (str "  ✓")
    (= v default) (str "  (default)")))

(defn- yolo-menu [st room-id user]
  {:id :chat-settings/yolo
   :prompt "yolo> "
   :items (mapv (fn [level]
                  {:label (value-label level (chat-value st room-id :yolo)
                                       (or (:yolo (user-defaults st user)) :off))
                   :value level
                   :event {:type :command/run :room-id room-id
                           :name "yolo" :args (name level)}})
                yolo-levels)
   :key-bindings (menu-bindings :yolo)})

(defn- settings-menu
  "`:chat-settings/menu` — the TUI palette's Chat settings: each setting with
   the chat's value, drilling into its values."
  [st {:keys [room-id] :as ev}]
  (when (state/get-room st room-id)
    (let [user (state/event-user st ev)
          row  (fn [k v drill]
                 {:label (get-in settings [k :label]) :description (some-> v command-args)
                  :drill drill})]
      {:effects [[:app/dispatch
                  {:type :ui/menu-push :room-id room-id
                   :menu {:id :chat-settings
                          :prompt "chat settings> "
                          :items (cond-> [(row :model (chat-value st room-id :model)
                                               {:type :command/run :room-id room-id :name "model"})]
                                   (not (yolo/disabled? st room-id))
                                   (conj (row :yolo (chat-value st room-id :yolo)
                                              {:type :ui/menu-push :room-id room-id
                                               :menu (yolo-menu st room-id user)})))}}]]})))

(def handlers
  {:chat-settings/apply  apply-selected
   :chat-settings/scopes scopes-menu
   :chat-settings/menu   settings-menu})
