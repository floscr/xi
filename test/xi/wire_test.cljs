(ns xi.wire-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.wire :as wire]))

(deftest roundtrip
  (let [ev {:type :agent/tool-result :room-id "r1" :id "t1"
            :content [{:type "text" :text "hí ✓ \"quoted\""}]
            :is-error false
            :event/id 42 :event/ts 1700000000000}]
    (is (= ev (wire/decode (wire/encode ev)))))
  (testing ":remote? never travels"
    (is (= {:type :prompt/submit :text "hi"}
           (wire/decode (wire/encode {:type :prompt/submit :text "hi" :remote? true})))))
  (testing "nested menu events survive (menus carry dispatchable events)"
    (let [ev {:type :ui/menu-open :room-id "r1"
              :menu {:id :resume :prompt "resume> "
                     :items [{:label "x" :event {:type :command/run :name "resume" :args "1"}}]}}]
      (is (= ev (wire/decode (wire/encode ev)))))))

(deftest decode-rejects-garbage
  (is (nil? (wire/decode "{")))
  (is (nil? (wire/decode "42")))
  (is (nil? (wire/decode "[:not :a :map]")))
  (is (nil? (wire/decode "{:no-type 1}")))
  (is (nil? (wire/decode "{:type \"not-a-keyword\"}"))))
