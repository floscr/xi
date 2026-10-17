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
  (is (= "data must be an object" (err "choice" [1 2])))
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

(deftest size-cap
  (is (str/includes? (err "checklist" {:items [(apply str (repeat 40000 "x"))]}) "block too large")))

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
      (is (nil? (b/act (record "hiccup" ["p" "x"]) {:action "toggle"} "alice" 2))))
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

(deftest static-kinds-are-gone
  (doseq [k ["table" "keyvalue" "steps" "progress" "callout" "chart" "form" "cards"]]
    (is (str/includes? (err k {}) "unknown kind") (str k " is markdown or hiccup now"))))

(deftest catalog-lists-every-kind
  (let [c (b/catalog {"gtd/board" "a board"})]
    (doseq [k (b/kind-names)] (is (str/includes? c (str "- " k ": "))))
    (is (str/includes? c "- gtd/board: a board"))))

(def ^:private board-kind
  {"deploys/board" {:describe "a deploy board"
                     :actions ["approve"] :send ["ship"]
                     :normalize (fn [d] (update d :envs vec))
                     :text (fn [spec state] (str "envs: " (count (:envs spec))
                                                 (when-let [l (:last state)] (str ", last " (:action l)))))}})

(deftest extension-kinds
  (testing "normalize, rules and text come from the kind"
    (let [{r :record} (b/make-record {:id "x" :kind "deploys/board" :data {:envs ["a" "b"]}
                                      :at 1 :extra board-kind})]
      (is (= {:envs ["a" "b"]} (:spec r)))
      (is (= {:actions ["approve"] :send ["ship"]} (:rules r)))
      (is (= "envs: 2" (b/body-text r)))
      (is (b/interactive? r))
      (testing "actions: declared ones only; :send ones post a message"
        (is (nil? (b/act r {:action "delete"} "u" 2)))
        (is (false? (:send? (b/act r {:action "approve" :value "a"} "u" 2))))
        (is (true? (:send? (b/act r {:action "ship"} "u" 2))))
        (is (= {:action "approve" :value "a"}
               (get-in (b/act r {:action "approve" :value "a"} "u" 2) [:record :state :last]))))
      (testing "an update refreshes the text form"
        (is (= "envs: 1" (b/body-text (:record (b/update-record r {:data {:envs ["a"]} :at 3
                                                                    :extra board-kind}))))))))
  (testing "an unknown kind names the extension kinds too"
    (is (str/includes? (:error (b/normalize "nope" {} board-kind)) "deploys/board")))
  (testing "non-plain data from :normalize is refused"
    (is (str/includes? (:error (b/normalize "x/y" {} {"x/y" {:normalize (fn [_] {:f identity})}}))
                       "plain data")))
  (is (str/includes? (b/catalog board-kind) "- deploys/board: a deploy board"))
  (is (= "deploys/board" (last (b/kind-names board-kind)))))

(deftest app-views-are-the-hosts-kind
  (is (not (some #{"app"} (b/kind-names))) "not offered to the model")
  (is (not (str/includes? (b/catalog) "- app:")))
  (let [{r :record} (b/make-record {:id "a" :kind "app" :at 1
                                    :data {:resource-uri "ui://x/v" :tool-input {:q 1}
                                           :tool-result {:content [{:type "text" :text "ok"}]}}})]
    (is (= "App view ui://x/v\nok" (b/body-text r)))
    (is (not (b/interactive? r)) "users' controls don't act on it; the host notes what the view did")
    (let [r2 (b/note r {:action "context" :value "selected prod"} 2)]
      (is (= "selected prod" (get-in r2 [:state :model-context])))
      (is (= 1 (:unread r2)))
      (is (= "app" (:user (last (:actions r2)))))))
  (is (str/includes? (:error (b/normalize "app" {:resource-uri "https://x"})) "ui://"))
  (testing "a structured result too large to keep is dropped, not the view"
    (let [{:keys [spec]} (b/normalize "app" {:resource-uri "ui://x/v"
                                             :tool-result {:structured-content {:blob (apply str (repeat 40000 "x"))}}})]
      (is (:truncated (:tool-result spec)))
      (is (nil? (get-in spec [:tool-result :structured-content]))))))

(deftest action-messages-parse-back
  (let [r (:record (b/act (record "choice" {:options ["Red" "Blue"]}) {:action "select" :value ["2"]} "u" 1))
        m (b/action-message r "submit")]
    (is (= {:id "b-1" :kind "choice" :action "submit" :body "( ) Red\n(x) Blue"}
           (b/parse-action-message m))))
  (is (nil? (b/parse-action-message "just a message")))
  (is (nil? (b/parse-action-message "see <block-action id=\"x\" kind=\"y\" action=\"z\">a</block-action> above"))
      "only a whole message is one"))
