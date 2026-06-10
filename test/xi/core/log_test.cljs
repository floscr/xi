(ns xi.core.log-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.log :as log]))

(deftest elision
  (testing "long strings truncated, nested structures walked"
    (let [big   (apply str (repeat 500 "x"))
          event {:type :tool/result :text big :nested {:img [big "small"]}}
          e     (log/elide-value event)]
      (is (str/includes? (:text e) "…(+300 chars)"))
      (is (str/includes? (first (get-in e [:nested :img])) "…(+300 chars)"))
      (is (= "small" (second (get-in e [:nested :img]))))))
  (testing "short values untouched"
    (is (= {:a "hi" :b 1} (log/elide-value {:a "hi" :b 1})))))

(deftest prepare-entry
  (testing "delta events drop text, keep char count"
    (let [e (log/prepare-entry {:type :agent/text-delta :room-id "a" :text "hello"} [])]
      (is (nil? (:text e)))
      (is (= 5 (:log/chars e)))))
  (testing "effect types recorded"
    (let [e (log/prepare-entry {:type :prompt/submit :room-id "a"}
                               [[:provider/start-turn {:big "payload"}]])]
      (is (= [:provider/start-turn] (:log/effects e))))))

(deftest ring-buffer
  (testing "wraps at capacity, oldest first"
    (let [ring (log/create-ring 3)]
      (doseq [i (range 5)]
        (log/append! ring {:type :n :i i}))
      (is (= [2 3 4] (mapv :i (log/entries ring))))))
  (testing "consecutive deltas coalesce into one entry"
    (let [ring (log/create-ring 10)]
      (log/append! ring (log/prepare-entry {:type :agent/text-delta :room-id "a" :text "abc"} []))
      (log/append! ring (log/prepare-entry {:type :agent/text-delta :room-id "a" :text "defg"} []))
      (log/append! ring (log/prepare-entry {:type :agent/text-delta :room-id "b" :text "zz"} []))
      (let [[e1 e2] (log/entries ring)]
        (is (= 2 (count (log/entries ring))))
        (is (= 2 (:log/coalesced e1)))
        (is (= 7 (:log/chars e1)))
        (is (= "b" (:room-id e2))))))
  (testing "non-delta event breaks coalescing"
    (let [ring (log/create-ring 10)]
      (log/append! ring (log/prepare-entry {:type :agent/text-delta :room-id "a" :text "ab"} []))
      (log/append! ring (log/prepare-entry {:type :agent/busy :room-id "a" :busy? false} []))
      (log/append! ring (log/prepare-entry {:type :agent/text-delta :room-id "a" :text "cd"} []))
      (is (= 3 (count (log/entries ring)))))))
