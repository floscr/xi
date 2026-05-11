(ns xi.tools.edit-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tools.edit :as edit]))

(deftest apply-edit-basic-replacement
  (testing "replaces exact match"
    (is (= {:ok "hello world"}
           (edit/apply-edit "hello foo" {:oldText "foo" :newText "world"})))))

(deftest apply-edit-not-found
  (testing "returns error when text not found"
    (let [result (edit/apply-edit "hello world" {:oldText "xyz" :newText "abc"})]
      (is (:error result))
      (is (re-find #"Could not find" (:error result))))))

(deftest apply-edit-multiple-occurrences
  (testing "returns error when text appears more than once"
    (let [result (edit/apply-edit "foo bar foo" {:oldText "foo" :newText "baz"})]
      (is (:error result))
      (is (re-find #"multiple occurrences" (:error result))))))

(deftest apply-edit-empty-replacement
  (testing "can delete text by replacing with empty string"
    (is (= {:ok "hello "}
           (edit/apply-edit "hello world" {:oldText "world" :newText ""})))))

(deftest apply-edit-multiline
  (testing "works across multiple lines"
    (is (= {:ok "line1\nnew-line\nline3"}
           (edit/apply-edit "line1\nold-line\nline3"
                           {:oldText "old-line" :newText "new-line"})))))

(deftest apply-edit-preserves-surrounding
  (testing "does not alter text outside the match"
    (let [content "prefix--target--suffix"
          result (edit/apply-edit content {:oldText "--target--" :newText "--replaced--"})]
      (is (= {:ok "prefix--replaced--suffix"} result)))))

(deftest apply-edit-special-chars
  (testing "handles regex-special characters in text"
    (is (= {:ok "result = fn(x)"}
           (edit/apply-edit "result = fn(y)" {:oldText "fn(y)" :newText "fn(x)"})))))
