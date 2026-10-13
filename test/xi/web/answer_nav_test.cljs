(ns xi.web.answer-nav-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.answer-nav :as answer-nav]))

(deftest turn-ends-test
  (testing "the last text before each user message, and the trailing one"
    (is (= [3 7] (answer-nav/turn-ends [:user :text :work :text :user :text :work :text] false))))
  (testing "text followed by a tool call is not an answer"
    (is (= [] (answer-nav/turn-ends [:user :text :work :user :text :work] false))))
  (testing "a running turn's trailing text may still be followed by work"
    (is (= [3] (answer-nav/turn-ends [:user :work :text :text :user :text] true))))
  (testing "a turn without text has no answer"
    (is (= [1] (answer-nav/turn-ends [:user :text :user :user] false))))
  (is (= [] (answer-nav/turn-ends [] false))))

(deftest target-test
  (let [tops [-300 -120 -2 3 40 500]]
    (testing "next is the first post below the reference line"
      (is (= 4 (answer-nav/target tops :next))))
    (testing "prev is the last post above it"
      (is (= 1 (answer-nav/target tops :prev))))
    (testing "a post within the slack counts as on the line"
      (is (= 2 (answer-nav/target [-2 3 40] :next)))
      (is (nil? (answer-nav/target [-2 3] :prev)))))
  (testing "nil past either end"
    (is (nil? (answer-nav/target [-50 -10 0] :next)))
    (is (nil? (answer-nav/target [0 10 50] :prev)))
    (is (nil? (answer-nav/target [] :next)))))
