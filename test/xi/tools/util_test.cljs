(ns xi.tools.util-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tools.util :as util]))

(deftest unified-diff-identical
  (testing "identical texts produce empty diff"
    (is (= "" (util/unified-diff "hello" "hello")))))

(deftest unified-diff-single-line-change
  (testing "single line replacement"
    (let [result (util/unified-diff "hello" "world")]
      (is (re-find #"- hello" result))
      (is (re-find #"\+ world" result)))))

(deftest unified-diff-addition
  (testing "line addition"
    (let [old-text "line1\nline2\nline3"
          new-text "line1\nline2\nnew-line\nline3"
          result (util/unified-diff old-text new-text)]
      (is (re-find #"\+ new-line" result)))))

(deftest unified-diff-deletion
  (testing "line deletion"
    (let [old-text "line1\nline2\nline3"
          new-text "line1\nline3"
          result (util/unified-diff old-text new-text)]
      (is (re-find #"- line2" result)))))

(deftest unified-diff-context-lines
  (testing "context lines are included"
    (let [old-text "a\nb\nc\nd\ne"
          new-text "a\nb\nX\nd\ne"
          result (util/unified-diff old-text new-text)]
      ;; b and d should appear as context around the change
      (is (re-find #"b" result))
      (is (re-find #"d" result)))))
