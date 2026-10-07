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

(deftest table-cells-carry-plain-text-column-labels
  (let [out (md/render "| **Name** | `Default` |\n| --- | ---: |\n| a | 1 |")
        tds (find-all (find-tag out :tbody) :td)]
    (testing "each td gets its header's text, markup stripped, for list mode"
      (is (= ["Name" "Default"] (mapv #(:data-label (second %)) tds))))
    (testing "alignment attrs are kept alongside the label"
      (is (= {:text-align "right"} (:style (second (second tds))))))))

(deftest table-has-table-list-mode-toggle
  (let [out   (md/render "| A |\n| --- |\n| 1 |")
        items (->> (find-all out :button)
                   (filter #(some #{"button-group-item"} (:class (second %)))))]
    (is (= 2 (count items)))
    (is (every? #(fn? (get-in (second %) [:on :click])) items))))

(deftest soft-breaks-collapse-by-default-and-render-as-br-with-hard-breaks
  (testing "a single newline inside a paragraph stays plain text by default"
    (let [p (find-tag (md/render "line one\nline two") :p)]
      (is (= [:p "line one\nline two"] (vec (remove map? p))))
      (is (nil? (find-tag p :br)))))
  (testing "with :hard-breaks? each newline becomes a [:br]"
    (let [p (find-tag (md/render "one\ntwo\nthree" {:hard-breaks? true}) :p)]
      (is (= [:p "one" [:br] "two" [:br] "three"] (vec (remove map? p))))))
  (testing "breaks inside emphasis are kept"
    (let [strong (find-tag (md/render "**a\nb**" {:hard-breaks? true}) :strong)]
      (is (= [:strong "a" [:br] "b"] strong))))
  (testing "a blank line still splits paragraphs and adds no extra <br>"
    (let [out (md/render "a\n\nb" {:hard-breaks? true})]
      (is (= 2 (count (find-all out :p))))
      (is (empty? (find-all out :br))))))

(deftest nested-list-renders-inside-li
  (let [[li-a li-b] (filter #(and (vector? %) (= :li (first %)))
                            (find-tag (md/render "- A\n  - a1\n  - a2\n- B") :ul))]
    (testing "the nested list sits inside its parent's li"
      (is (= 3 (count (find-all li-a :li))))
      (is (some? (find-tag (vec (rest li-a)) :ul))))
    (testing "an item without children stays flat"
      (is (nil? (find-tag (vec (rest li-b)) :ul))))))
