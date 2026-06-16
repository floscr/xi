(ns xi.markdown.parse-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.markdown.parse :as parse]))

(deftest parses-basic-pipe-table
  (let [blocks (parse/parse "| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 25 |")
        [tag attrs data] (first blocks)]
    (testing "produces a single :table block"
      (is (= 1 (count blocks)))
      (is (= :table tag)))
    (testing "header cells parsed as inline tokens"
      (is (= [["Name"] ["Age"]] (:header data))))
    (testing "body rows parsed"
      (is (= [[["Alice"] ["30"]] [["Bob"] ["25"]]] (:rows data))))
    (testing "default alignment"
      (is (= [:none :none] (:align attrs))))))

(deftest parses-alignment-markers
  (let [blocks (parse/parse "| L | C | R |\n| :-- | :-: | --: |\n| a | b | c |")
        [_ attrs] (first blocks)]
    (is (= [:left :center :right] (:align attrs)))))

(deftest table-without-leading-trailing-pipes
  (let [blocks (parse/parse "a | b\n--- | ---\n1 | 2")
        [tag data] [(ffirst blocks) (nth (first blocks) 2)]]
    (is (= :table tag))
    (is (= [["a"] ["b"]] (:header data)))
    (is (= [[["1"] ["2"]]] (:rows data)))))

(deftest table-with-surrounding-blocks
  (let [blocks (parse/parse "Before\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\nAfter")
        tags (mapv first blocks)]
    (is (= [:paragraph :table :paragraph] tags))))

(deftest non-table-pipes-stay-paragraph
  (let [blocks (parse/parse "this | has | pipes but no separator")]
    (is (= [:paragraph] (mapv first blocks)))))
