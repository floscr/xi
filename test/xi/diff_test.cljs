(ns xi.diff-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.diff :as diff]))

(def sample
  (str "diff --git a/foo.txt b/foo.txt\n"
       "--- a/foo.txt\n"
       "+++ b/foo.txt\n"
       "@@ -1,3 +1,4 @@\n"
       " line one\n"
       "-old line\n"
       "+new line\n"
       "+added line\n"
       " line three\n"
       "diff --git a/bar.txt b/bar.txt\n"
       "--- a/bar.txt\n"
       "+++ b/bar.txt\n"
       "@@ -10,2 +10,2 @@\n"
       "-bar old\n"
       "+bar new\n"))

;; ── diff-rows ──

(deftest diff-rows-assigns-sequential-indices
  (testing "code lines get sequential :sel-idx, headers get nil"
    (let [rows (diff/diff-rows (diff/parse-diff-text sample))
          line-rows (filter #(= :line (:row %)) rows)
          headers   (filter #(#{:file :hunk} (:row %)) rows)]
      (is (= [0 1 2 3 4 5 6] (map :sel-idx line-rows))
          "7 code lines across both files, 0-indexed")
      (is (every? nil? (map :sel-idx headers))
          "file and hunk headers carry no selection index"))))

(deftest diff-rows-tracks-filenames
  (testing "each line row records its file"
    (let [rows (diff/diff-rows (diff/parse-diff-text sample))
          line-files (->> rows (filter #(= :line (:row %))) (map :filename))]
      (is (= (concat (repeat 5 "foo.txt") (repeat 2 "bar.txt"))
             line-files)))))

;; ── selection-range ──

(deftest selection-range-normalizes
  (is (= [3 7] (diff/selection-range {:anchor 3 :head 7})))
  (is (= [3 7] (diff/selection-range {:anchor 7 :head 3})) "swaps when reversed")
  (is (= [5 5] (diff/selection-range {:anchor 5 :head 5})))
  (is (nil? (diff/selection-range {:anchor nil :head 4})))
  (is (nil? (diff/selection-range nil))))

;; ── selected-snippet ──

(deftest selected-snippet-single-line
  (testing "one selected line emits its sign + text under the filename"
    (let [rows (diff/diff-rows (diff/parse-diff-text sample))
          snip (diff/selected-snippet rows [2 2])]
      (is (= "foo.txt\n+new line" snip)))))

(deftest selected-snippet-range-within-file
  (testing "a contiguous range emits all signed lines once, under one header"
    (let [rows (diff/diff-rows (diff/parse-diff-text sample))
          snip (diff/selected-snippet rows [0 4])]
      (is (= (str "foo.txt\n"
                  " line one\n"
                  "-old line\n"
                  "+new line\n"
                  "+added line\n"
                  " line three")
             snip)))))

(deftest selected-snippet-spans-files
  (testing "a range crossing files groups under each filename"
    (let [rows (diff/diff-rows (diff/parse-diff-text sample))
          snip (diff/selected-snippet rows [3 5])]
      (is (= (str "foo.txt\n"
                  "+added line\n"
                  " line three\n\n"
                  "bar.txt\n"
                  "-bar old")
             snip)))))
