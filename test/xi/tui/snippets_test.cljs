(ns xi.tui.snippets-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.snippets :as snippets]))

(deftest expand-test
  (testing "built-in snippets"
    (is (= "continue" (snippets/expand "c")))
    (is (= "in a recent change" (snippets/expand "rec"))))

  (testing "unknown trigger returns nil"
    (is (nil? (snippets/expand "xyz")))))

(deftest expand-at-test
  (testing "trigger at the start of the text"
    (is (= {:text "continue" :caret 8} (snippets/expand-at "c" 1))))

  (testing "trigger after a space or a newline, text after the caret kept"
    (is (= {:text "ok continue now" :caret 11} (snippets/expand-at "ok c now" 4)))
    (is (= {:text "a\ncontinue" :caret 10} (snippets/expand-at "a\nc" 3))))

  (testing "no expansion mid-word, for unknown words or an empty word"
    (is (nil? (snippets/expand-at "abc" 3)))
    (is (nil? (snippets/expand-at "xyz" 3)))
    (is (nil? (snippets/expand-at "c " 2)))
    (is (nil? (snippets/expand-at "" 0)))))

(deftest add-remove-test
  (testing "add and expand custom snippet"
    (snippets/add! "thx" "thanks")
    (is (= "thanks" (snippets/expand "thx"))))

  (testing "remove snippet"
    (is (true? (snippets/remove! "thx")))
    (is (nil? (snippets/expand "thx"))))

  (testing "remove non-existent returns false"
    (is (false? (snippets/remove! "nope")))))
