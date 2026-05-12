(ns xi.tui.snippets-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.snippets :as snippets]))

(deftest expand-test
  (testing "built-in snippets"
    (is (= "continue" (snippets/expand "c")))
    (is (= "in a recent change" (snippets/expand "rec"))))

  (testing "unknown trigger returns nil"
    (is (nil? (snippets/expand "xyz")))))

(deftest add-remove-test
  (testing "add and expand custom snippet"
    (snippets/add! "thx" "thanks")
    (is (= "thanks" (snippets/expand "thx"))))

  (testing "remove snippet"
    (is (true? (snippets/remove! "thx")))
    (is (nil? (snippets/expand "thx"))))

  (testing "remove non-existent returns false"
    (is (false? (snippets/remove! "nope")))))
