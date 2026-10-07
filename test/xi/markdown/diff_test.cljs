(ns xi.markdown.diff-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.markdown.diff :as md-diff]))

(deftest markdown-path?-test
  (is (md-diff/markdown-path? "docs/guide/README.md"))
  (is (md-diff/markdown-path? "x/NOTES.MDX"))
  (is (not (md-diff/markdown-path? "src/xi/diff.cljs")))
  (is (not (md-diff/markdown-path? "md")))
  (is (not (md-diff/markdown-path? nil))))

(deftest edit-script-test
  (testing "common items are kept, changes read old-then-new"
    (is (= [[:= :a] [:- :b] [:+ :x] [:= :c]]
           (md-diff/edit-script [:a :b :c] [:a :x :c]))))
  (testing "pure insert / delete"
    (is (= [[:+ 1] [:+ 2]] (md-diff/edit-script [] [1 2])))
    (is (= [[:- 1]] (md-diff/edit-script [1] []))))
  (testing "interleaved changes group deletions first within a run"
    (is (= [[:- :a] [:- :b] [:+ :x] [:+ :y] [:= :c]]
           (md-diff/edit-script [:a :b :c] [:x :y :c]))))
  (testing "key fn: := carries b's item"
    (is (= [[:= {:k 1 :side :b}]]
           (md-diff/edit-script [{:k 1 :side :a}] [{:k 1 :side :b}] :k)))))

(def tool-diff
  (str/join "\n"
            ["docs/x.md"
             "..."
             "  Intro paragraph."
             "  "
             "- Old **bold** text."
             "+ New **bold** text."
             "..."
             "  ## Heading"
             "+ "
             "+ - added item"
             "[file-hash: 1234abcd]"]))

(deftest tool-diff-hunks-test
  (is (= [[{:type :context :text "Intro paragraph."}
           {:type :context :text ""}
           {:type :delete :text "Old **bold** text."}
           {:type :add :text "New **bold** text."}]
          [{:type :context :text "## Heading"}
           {:type :add :text ""}
           {:type :add :text "- added item"}]]
         (md-diff/tool-diff-hunks tool-diff))))

(deftest diff-segments-test
  (let [segs (md-diff/diff-segments (md-diff/tool-diff-hunks tool-diff))]
    (is (= [:ctx :change :gap :ctx :change] (map :kind segs)))
    (testing "a changed paragraph is one deleted + one added block"
      (let [{:keys [del add]} (second segs)]
        (is (= [:paragraph] (map (comp first :block) del)))
        (is (= [:paragraph] (map (comp first :block) add)))))
    (testing "context blocks are parsed markdown"
      (is (= :heading (-> segs (nth 3) :items first :block first))))))

(deftest list-items-diff-one-by-one
  (let [hunk [{:type :context :text "- one"}
              {:type :delete :text "- two"}
              {:type :add :text "- TWO"}
              {:type :context :text "- three"}]
        items (md-diff/hunk-items hunk)]
    (is (= [:ctx :del :add :ctx] (map :status items)))
    (is (every? #(= :ul (first (:block %))) items))
    (testing "ordered items keep their number"
      (is (= [1 2 2 3]
             (map :start (md-diff/hunk-items
                          (mapv #(update % :text str/replace #"^- " "1. ") hunk))))))))

(deftest whitespace-only-hunks-drop-out
  (is (= [] (md-diff/diff-segments [[{:type :context :text "Para"}
                                     {:type :add :text ""}]]))))

(deftest mark-words-test
  (testing "only the differing words are wrapped"
    (let [[d a] (md-diff/mark-words [[:p "The quick brown fox"]]
                                    [[:p "The quick red fox"]])]
      ;; a marked leaf is replaced by the seq of its pieces
      (is (= [:p (list "The quick " [:span {:class "md-diff-word--del"} "brown"] " fox")]
             (first d)))
      (is (= [:p (list "The quick " [:span {:class "md-diff-word--add"} "red"] " fox")]
             (first a)))))
  (testing "marks span nested inline elements and keep attrs"
    (let [[_ a] (md-diff/mark-words [[:p "use " [:code {:class "c"} "foo"] " now"]]
                                    [[:p "use " [:code {:class "c"} "bar"] " now"]])]
      (is (= [:p "use " [:code {:class "c"} (list [:span {:class "md-diff-word--add"} "bar"])] " now"]
             (first a)))))
  (testing "adjacent changed words merge into one mark"
    (let [[_ a] (md-diff/mark-words [[:p "a b c d e f"]] [[:p "a X Y d e f"]])]
      (is (= [:p (list "a " [:span {:class "md-diff-word--add"} "X Y"] " d e f")]
             (first a)))))
  (testing "a block that grew a lot still gets its insertion marked"
    (let [[d a] (md-diff/mark-words [[:p "keep this"]]
                                    [[:p "keep one two three four five six this"]])]
      (is (= [[:p "keep this"]] d))
      (is (= [:p (list "keep " [:span {:class "md-diff-word--add"} "one two three four five six"] " this")]
             (first a)))))
  (testing "a wholesale rewrite gets no word marks"
    (let [old [[:p "alpha beta gamma delta"]]
          new [[:p "one two three four"]]]
      (is (= [old new] (md-diff/mark-words old new)))))
  (testing "one empty side leaves both untouched"
    (is (= [[] [[:p "x"]]] (md-diff/mark-words [] [[:p "x"]])))))
