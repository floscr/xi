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

(deftest markdown-link-test
  (testing "[text](url) still parses to a :link token"
    (is (= [[:link {:url "https://example.com" :text "site"}]]
           (parse/parse-inline "[site](https://example.com)")))))

(deftest bare-url-autolink-test
  (testing "a standalone bare URL becomes a :link token (text == url)"
    (is (= [[:link {:url "https://example.com" :text "https://example.com"}]]
           (parse/parse-inline "https://example.com"))))

  (testing "http (not just https) is supported"
    (is (= [[:link {:url "http://example.com" :text "http://example.com"}]]
           (parse/parse-inline "http://example.com"))))

  (testing "a URL embedded mid-sentence is split out from surrounding text"
    (is (= ["see " [:link {:url "https://x.com" :text "https://x.com"}] " now"]
           (parse/parse-inline "see https://x.com now"))))

  (testing "trailing sentence punctuation is not part of the URL"
    (is (= [[:link {:url "https://x.com" :text "https://x.com"}] "."]
           (parse/parse-inline "https://x.com.")))
    (is (= ["(" [:link {:url "https://x.com" :text "https://x.com"}] ")"]
           (parse/parse-inline "(https://x.com)"))))

  (testing "balanced parens inside a URL are preserved"
    (is (= [[:link {:url "https://en.wikipedia.org/wiki/Foo_(bar)"
                    :text "https://en.wikipedia.org/wiki/Foo_(bar)"}]]
           (parse/parse-inline "https://en.wikipedia.org/wiki/Foo_(bar)"))))

  (testing "a scheme embedded in a word is not treated as a URL"
    (is (= ["xhttps://example.com"]
           (parse/parse-inline "xhttps://example.com"))))

  (testing "non-URL words starting with h are left as plain text"
    (is (= ["hello there"]
           (parse/parse-inline "hello there")))))

(deftest nested-lists
  (testing "indented items become child blocks of the item above"
    (let [[block :as blocks] (parse/parse "- **A:**\n  - a1\n  - a2\n- **B:**\n  - b1")
          [tag items children] block]
      (is (= 1 (count blocks)))
      (is (= :ul tag))
      (is (= 2 (count items)))
      (is (= [[:ul [["a1"] ["a2"]]] ] (first children)))
      (is (= [[:ul [["b1"]]]] (second children)))))
  (testing "flat lists keep the two-element shape"
    (is (= [[:ul [["a"] ["b"]]]] (parse/parse "- a\n- b"))))
  (testing "ordered parents with bullet children, and a blank line before the child"
    (let [[[tag _ children]] (parse/parse "1. one\n\n   - x\n2. two")]
      (is (= :ol tag))
      (is (= [[:ul [["x"]]]] (first children)))
      (is (nil? (second children)))))
  (testing "text after the list is not swallowed"
    (is (= 2 (count (parse/parse "- a\n  - b\n\nafter"))))))
