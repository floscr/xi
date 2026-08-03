(ns xi.provider.claude-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.provider.claude :as claude]))

(def ^:private cap #'claude/cap-tool-result-content)
(def ^:private max-chars @#'claude/MAX_TOOL_RESULT_CHARS)

(defn- total-text [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map #(count (:text %)))
       (reduce + 0)))

(deftest small-result-passes-through
  (testing "under-budget content is returned unchanged"
    (let [content [{:type "text" :text "hello world"}]]
      (is (= content (cap content))))))

(deftest single-huge-line-is-trimmed
  (testing "a one-line dump far over budget is trimmed under the cap"
    (let [big (apply str (repeat (* max-chars 3) "x"))
          content [{:type "text" :text big}]
          out (cap content)]
      (is (<= (total-text out) max-chars))
      (is (re-find #"Xi truncated" (:text (first out))))
      ;; head + tail are both preserved (drop the middle, not the ends).
      (is (< (total-text out) (count big))))))

(deftest image-blocks-pass-through
  (testing "non-text blocks are untouched even when text is trimmed"
    (let [big (apply str (repeat (* max-chars 2) "y"))
          img {:type "image" :source {:type "base64" :data "AAAA"}}
          content [{:type "text" :text big} img]
          out (cap content)]
      (is (some #(= img %) out))
      (is (<= (total-text out) max-chars)))))
