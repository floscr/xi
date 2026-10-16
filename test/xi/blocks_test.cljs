(ns xi.blocks-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.blocks :as b]))

(defn- spec [kind data] (:spec (b/normalize kind data)))
(defn- err [kind data] (:error (b/normalize kind data)))

(defn- record [kind data]
  (:record (b/make-record {:id "b-1" :kind kind :title "T" :data data
                           :source {:type :tool :name "ui_block"} :at 1})))

(deftest unknown-kind-and-bad-data
  (is (str/includes? (err "nope" {}) "unknown kind"))
  (is (str/includes? (err "checklist" "{not json") "not valid JSON"))
  (is (= "data must be an object" (err "table" [1 2])))
  (testing "a JSON string is read back"
    (is (= ["a"] (map :label (:items (spec "checklist" "{\"items\": [\"a\"]}")))))))

(deftest checklist
  (let [s (spec "checklist" {:items ["Build" {:id "t" :label "Test" :done true :note "ci"}]})]
    (is (= [{:id "1" :label "Build" :done false}
            {:id "t" :label "Test" :done true :note "ci"}]
           (:items s)))
    (is (= "- [ ] Build\n- [x] Test — ci" (b/body-text {:kind "checklist" :spec s :state {}}))))
  (is (= "item ids must be unique" (err "checklist" {:items [{:id "a"} {:id "a"}]})))
  (is (str/includes? (err "checklist" {:items (repeat 501 "x")}) "too many items")))

(deftest table
  (testing "object rows take their columns from the first row"
    (let [s (spec "table" {:rows [{:name "a" :n 1} {:name "bb" :n 22}]})]
      (is (= ["name" "n"] (map :label (:columns s))))
      (is (= [["a" "1"] ["bb" "22"]] (:rows s)))
      (is (= "name  n\n-------\na     1\nbb    22"
             (b/body-text {:kind "table" :spec s})))))
  (testing "short rows are padded, right alignment kept"
    (let [s (spec "table" {:columns ["a" {:key "b" :label "B" :align "right"}] :rows [["x"] ["y" 10]]})]
      (is (= [["x" ""] ["y" "10"]] (:rows s)))
      (is (= "right" (:align (second (:columns s)))))))
  (is (str/includes? (err "table" {:columns ["a"] :rows (repeat 10001 ["x"])}) "table too large")))

(deftest keyvalue-progress-callout-steps
  (is (= [{:key "Name" :value "xi"}] (:pairs (spec "keyvalue" {:Name "xi"}))))
  (is (= [{:key "a" :value "1"}] (:pairs (spec "keyvalue" {:pairs [["a" 1]]}))))
  (is (= {:value 30 :max 60 :label "Tests"} (spec "progress" {:value 30 :max 60 :label "Tests"})))
  (is (= 50 (b/percent (spec "progress" {:value 30 :max 60}))))
  (is (= {:value 100 :max 100} (spec "progress" {:value 150})))
  (is (str/includes? (err "progress" {:value "lots"}) "value must be a number"))
  (is (= {:tone "warn" :body "careful"} (spec "callout" {:tone "WARN" :body "careful"})))
  (is (str/includes? (err "callout" {:tone "loud" :body "x"}) "tone must be one of"))
  (is (= "✓ a\n✗ b — boom"
         (b/body-text {:kind "steps"
                       :spec (spec "steps" {:items [{:label "a" :status "done"}
                                                    {:label "b" :status "failed" :note "boom"}]})}))))

(deftest chart
  (let [s (spec "chart" {:values [1 5 3]})]
    (is (= "bar" (:type s)))
    (is (= ["1" "2" "3"] (:labels s)))
    (is (= [{:name "" :values [1 5 3]}] (:series s))))
  (is (= "▁█▅" (b/sparkline [1 5 3])))
  (is (str/includes? (err "chart" {:type "pie" :values [1]}) "type must be one of"))
  (is (str/includes? (err "chart" {}) "needs series or values")))

(deftest size-cap
  (is (str/includes? (err "callout" {:body (apply str (repeat 40000 "x"))}) "block too large")))

(deftest safe-ids
  (is (= "release-steps" (b/safe-id "Release Steps!")))
  (is (nil? (b/safe-id "  !! "))))

(deftest checklist-actions-and-carry
  (let [r (record "checklist" {:items [{:id "a" :label "A"} {:id "b" :label "B" :done true}]})
        {r2 :record send? :send?} (b/act r {:action "toggle" :target "a" :value true} "alice" 2)]
    (is (false? send?))
    (is (true? (b/checked? (:state r2) {:id "a" :done false})))
    (is (= 1 (:unread r2)))
    (is (= [{:action "toggle" :target "a" :value true :user "alice" :at 2}] (:actions r2)))
    (testing "illegal actions are refused"
      (is (nil? (b/act r {:action "toggle" :target "zzz" :value true} "alice" 2)))
      (is (nil? (b/act r {:action "toggle" :target "a" :value "yes"} "alice" 2)))
      (is (nil? (b/act (record "table" {:rows [["x"]]}) {:action "toggle"} "alice" 2))))
    (testing "an update keeps toggles unless the agent re-marked the item"
      (let [{r3 :record} (b/act r2 {:action "toggle" :target "b" :value false} "alice" 3)
            {r4 :record} (b/update-record r3 {:data {:items [{:id "a" :label "A"}
                                                             {:id "b" :label "B" :done true}
                                                             {:id "c" :label "C"}]}
                                              :at 4})
            {r5 :record} (b/update-record r3 {:data {:items [{:id "a" :label "A" :done true}]} :at 5})]
        (is (= {"a" true "b" false} (get-in r4 [:state :checked])))
        (is (= {} (get-in r5 [:state :checked])))))))

(deftest choice-actions
  (let [r (record "choice" {:options ["Red" "Blue"]})]
    (is (nil? (b/act r {:action "submit"} "u" 1)) "nothing selected yet")
    (is (nil? (b/act r {:action "select" :value ["1" "2"]} "u" 1)) "single choice")
    (let [{r2 :record} (b/act r {:action "select" :value ["2"]} "u" 1)
          {r3 :record send? :send?} (b/act r2 {:action "submit"} "u" 2)]
      (is (true? send?))
      (is (get-in r3 [:state :submitted?]))
      (is (= "(  ) Red" (str/replace (first (str/split-lines (b/body-text r3))) "( )" "(  )")))
      (is (str/starts-with? (b/action-message r3 "submit")
                            "<block-action id=\"b-1\" kind=\"choice\" action=\"submit\">")))))

(deftest form-actions
  (let [r (record "form" {:fields [{:name "title" :required true}
                                   {:name "kind" :type "select" :options ["bug" "feature"]}
                                   {:name "urgent" :type "checkbox"}]})]
    (is (nil? (b/act r {:action "submit" :value {}} "u" 1)) "required title missing")
    (is (nil? (b/act r {:action "set" :target "kind" :value "nope"} "u" 1)))
    (let [{r2 :record send? :send?} (b/act r {:action "submit"
                                              :value {:title "Crash" :kind "1" :urgent true}} "u" 1)]
      (is send?)
      (is (= "title:   Crash\nkind:    bug\nurgent:  [x]" (b/body-text r2))))))

(deftest cards-actions
  (let [r (record "cards" {:cards [{:id "pr1" :title "PR 1" :actions [{:id "merge" :label "Merge" :send true}
                                                                      {:id "later" :label "Later"}]}]})]
    (is (true? (:send? (b/act r {:action "card" :target "pr1" :value "merge"} "u" 1))))
    (is (false? (:send? (b/act r {:action "card" :target "pr1" :value "later"} "u" 1))))
    (is (nil? (b/act r {:action "card" :target "pr1" :value "delete"} "u" 1)))))

(deftest state-text-lists-unread-actions
  (let [r (record "checklist" {:items ["A" "B"]})
        r (:record (b/act r {:action "toggle" :target "1" :value true} "alice" 2))
        t (b/state-text r)]
    (is (str/includes? t "- [x] A"))
    (is (str/includes? t "1 new action:\n- alice: toggle 1 → true"))
    (is (str/includes? (b/state-text (assoc r :unread 0)) "No new actions."))))

(deftest actions-log-is-capped
  (let [r (record "checklist" {:items ["A"]})
        r (reduce (fn [r i] (:record (b/act r {:action "toggle" :target "1" :value (even? i)} "u" i)))
                  r (range 60))]
    (is (= b/max-actions (count (:actions r))))
    (is (= 59 (:at (last (:actions r)))))))

(deftest catalog-lists-every-kind
  (let [c (b/catalog {"gtd/board" "a board"})]
    (doseq [k (b/kind-names)] (is (str/includes? c (str "- " k ": "))))
    (is (str/includes? c "- gtd/board: a board"))))
