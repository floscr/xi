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

(defn- find-all
  "Depth-first collect every hiccup vector with the given tag."
  [node tag]
  (when (vector? node)
    (concat (when (= (first node) tag) [node])
            (mapcat #(find-all % tag) node))))

(deftest linkify-splits-bare-urls
  (testing "plain string with no URL is returned unchanged"
    (is (= ["just text"] (md/linkify "just text"))))
  (testing "a bare URL becomes an anchor node"
    (is (= [[:a {:href "https://example.com" :target "_blank"
                 :rel "noopener noreferrer"} "https://example.com"]]
           (md/linkify "https://example.com"))))
  (testing "surrounding text is preserved around the anchor"
    (is (= ["see "
            [:a {:href "http://a.co" :target "_blank"
                 :rel "noopener noreferrer"} "http://a.co"]
            " now"]
           (md/linkify "see http://a.co now")))))

(deftest links-inside-code-block-are-clickable
  (testing "a fenced code block with a URL yields a clickable anchor"
    (let [out   (md/render "```\ncurl https://example.com/x\n```")
          anchors (find-all out :a)]
      (is (= 1 (count anchors)))
      (is (= "https://example.com/x" (:href (second (first anchors))))))))

(deftest links-inside-inline-code-are-clickable
  (testing "inline code containing a URL yields a clickable anchor"
    (let [out     (md/render "run `curl https://example.com`")
          anchors (find-all out :a)]
      (is (= 1 (count anchors)))
      (is (= "https://example.com" (:href (second (first anchors))))))))

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
