(ns xi.compaction-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.compaction :as compaction]))

(deftest compact-summarize-is-defined
  (testing "summarize function exists and is callable"
    ;; summarize requires a live SDK session so we cannot unit-test it.
    (is (fn? compaction/summarize))))
