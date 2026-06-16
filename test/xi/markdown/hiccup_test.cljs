(ns xi.markdown.hiccup-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.markdown.hiccup :as md]))

(defn- find-tag
  "Depth-first search for the first hiccup vector with the given tag."
  [node tag]
  (when (vector? node)
    (if (= (first node) tag)
      node
      (some #(find-tag % tag) node))))

(deftest renders-table-as-html-table
  (let [out (md/render "| A | B |\n| --- | ---: |\n| 1 | 2 |")
        table (find-tag out :table)
        thead (find-tag table :thead)
        tbody (find-tag table :tbody)]
    (testing "produces a <table> with thead and tbody"
      (is (some? table))
      (is (some? thead))
      (is (some? tbody)))
    (testing "right alignment becomes a text-align style"
      (let [ths (filter #(and (vector? %) (= :th (first %)))
                        (tree-seq vector? seq thead))
            right-th (second ths)]
        (is (= {:style {:text-align "right"}} (second right-th)))))))
