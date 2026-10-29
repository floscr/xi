(ns xi.chat-settings-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.chat-settings :as cs]
            [xi.core.state :as state]))

(defn- room
  "A room `user` is in, on `model`, with yolo slice `yolo`."
  [id user model yolo]
  (-> (state/make-room id {:model model :ext {:yolo yolo}})
      (assoc :members {(str "c-" id) {:user user}})))

(def ^:private st
  (-> (state/initial-state {:mode :server :user "root"})
      (assoc-in [:rooms "a"] (room "a" "alice" "sonnet" {}))
      (assoc-in [:rooms "b"] (room "b" "alice" "opus" {:level :sane}))
      (assoc-in [:rooms "c"] (room "c" "bob" "sonnet" {}))
      (assoc-in [:rooms "d"] (room "d" "alice" "sonnet" {:disabled? true}))
      (assoc-in [:rooms "e"] (state/make-room "e" {:model "sonnet"}))
      (assoc-in [:users "alice" :ui :chat-defaults] {:model "opus"})))

(defn- run [ev]
  ((get cs/handlers :chat-settings/apply) st (merge {:type :chat-settings/apply :user "alice"} ev)))

(defn- commands [{:keys [effects]}]
  (keep (fn [[fx ev]] (when (= :command/run (:type ev)) [(:room-id ev) (:name ev) (:args ev)]))
        effects))

(defn- saved [{:keys [effects]}]
  (some (fn [[fx ev]] (when (= :user-state/save fx) ev)) effects))

(deftest this-chat
  (let [r (run {:key :model :value "haiku" :scope :chat :room-id "a"})]
    (is (= [["a" "model" "haiku"]] (commands r)))
    (is (nil? (saved r)) "the default stays")))

(deftest default-for-new-chats
  (let [r (run {:key :yolo :value :sane :scope :default :room-id "a"})]
    (is (= [["a" "yolo" "sane"]] (commands r)) "this chat too")
    (is (= {:user "alice" :key :chat-defaults :value {:model "opus" :yolo :sane}}
           (saved r))))
  (testing "off removes the key"
    (is (= {:model "opus"}
           (:value (saved (run {:key :yolo :value :off :scope :default})))))))

(deftest all-open-chats
  (let [r (run {:key :yolo :value :sane :scope :all :room-id "a"})]
    (is (= [["a" "yolo" "sane"]] (commands r))
        "b is already sane, d disabled it, c is bob's")
    (is (= :sane (get-in (saved r) [:value :yolo]))))
  (is (= #{"e"}
         (set (map first (commands ((get cs/handlers :chat-settings/apply) st
                                    {:key :model :value "opus" :scope :all :user "root"})))))
      "a room someone else is in is not root's; one nobody is in is its last prompter's"))

(deftest only-the-senders-chats
  (is (empty? (commands (run {:key :yolo :value :relentless :scope :chat :room-id "c"})))
      "a client cannot reach a room it is not in"))

(deftest seed-ext
  (is (= {:yolo {:level :sane}} (cs/seed-ext {:yolo {}} {:yolo :sane})))
  (is (= {:yolo {:disabled? true}} (cs/seed-ext {:yolo {:disabled? true}} {:yolo :sane}))
      "an agent profile's :yolo false wins")
  (is (= {:yolo {}} (cs/seed-ext {:yolo {}} {:model "opus"}))))

(deftest menu-bindings-pick-the-selected-value
  (let [[alt] (cs/menu-bindings :model)
        ev    (merge (:event alt) {:user "alice" :room-id "a" :selected {:label "haiku" :value "haiku"}})
        r     ((get cs/handlers :chat-settings/apply) st ev)]
    (is (= [["a" "model" "haiku"]] (commands r)))
    (is (= "haiku" (get-in (saved r) [:value :model]))))
  (testing "Tab pushes the scope menu"
    (let [tab (last (cs/menu-bindings :yolo))
          {[[_ push]] :effects} ((get cs/handlers :chat-settings/scopes) st
                                 (merge (:event tab) {:room-id "a" :selected {:value :sane}}))]
      (is (= :ui/menu-push (:type push)))
      (is (= [:chat :default :all]
             (map #(get-in % [:event :scope]) (get-in push [:menu :items]))))
      (is (every? #(= :sane (get-in % [:event :value])) (get-in push [:menu :items]))))))

(deftest settings-menu
  (let [{[[_ push]] :effects} ((get cs/handlers :chat-settings/menu) st {:room-id "b" :user "alice"})
        [model yolo] (get-in push [:menu :items])]
    (is (= "opus" (:description model)))
    (is (= "sane" (:description yolo)))
    (is (= ["off  (default)" "sane  ✓" "relentless"]
           (map :label (get-in yolo [:drill :menu :items])))))
  (testing "no yolo row where an agent disabled it"
    (let [{[[_ push]] :effects} ((get cs/handlers :chat-settings/menu) st {:room-id "d" :user "alice"})]
      (is (= ["Model"] (map :label (get-in push [:menu :items])))))))
