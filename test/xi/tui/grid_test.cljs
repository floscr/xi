(ns xi.tui.grid-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.grid :as grid]))

(deftest line->row-plain-text
  (testing "plain text fills cells correctly"
    (let [row (grid/line->row "abc" 5)]
      (is (= "a" (aget (aget row 0) 0)))
      (is (= "" (aget (aget row 0) 1)))
      (is (= "b" (aget (aget row 1) 0)))
      (is (= "c" (aget (aget row 2) 0)))
      ;; Padding
      (is (= " " (aget (aget row 3) 0)))
      (is (= " " (aget (aget row 4) 0))))))

(deftest line->row-ansi-colors
  (testing "ANSI SGR codes are captured as cell styles"
    (let [row (grid/line->row "\033[31mhi\033[0m!" 4)]
      ;; 'h' has red style
      (is (= "h" (aget (aget row 0) 0)))
      (is (= "\033[31m" (aget (aget row 0) 1)))
      ;; 'i' inherits red style
      (is (= "i" (aget (aget row 1) 0)))
      (is (= "\033[31m" (aget (aget row 1) 1)))
      ;; '!' after reset has no style
      (is (= "!" (aget (aget row 2) 0)))
      (is (= "" (aget (aget row 2) 1))))))

(deftest line->row-truncation
  (testing "lines longer than width are truncated"
    (let [row (grid/line->row "abcdef" 3)]
      (is (= 3 (.-length row)))
      (is (= "a" (aget (aget row 0) 0)))
      (is (= "b" (aget (aget row 1) 0)))
      (is (= "c" (aget (aget row 2) 0))))))

(deftest line->row-empty
  (testing "empty line produces all-space row"
    (let [row (grid/line->row "" 3)]
      (is (= 3 (.-length row)))
      (is (= " " (aget (aget row 0) 0)))
      (is (= " " (aget (aget row 1) 0)))
      (is (= " " (aget (aget row 2) 0))))))

(deftest frame->grid-basic
  (testing "frame->grid creates correct dimensions"
    (let [g (grid/frame->grid ["ab" "cd"] 3 3)]
      (is (= 3 (:width g)))
      (is (= 3 (:height g)))
      ;; Row 0
      (is (= "a" (aget (aget (:cells g) 0) 0 0)))
      (is (= "b" (aget (aget (:cells g) 0) 1 0)))
      (is (= " " (aget (aget (:cells g) 0) 2 0)))
      ;; Row 1
      (is (= "c" (aget (aget (:cells g) 1) 0 0)))
      ;; Row 2 (padded)
      (is (= " " (aget (aget (:cells g) 2) 0 0))))))

(deftest make-grid-dimensions
  (testing "make-grid creates correct sized grid"
    (let [g (grid/make-grid 4 2)]
      (is (= 4 (:width g)))
      (is (= 2 (:height g)))
      (is (= 2 (.-length (:cells g))))
      (is (= 4 (.-length (aget (:cells g) 0)))))))

(deftest line->row-stacked-styles
  (testing "multiple SGR codes accumulate"
    (let [row (grid/line->row "\033[1m\033[31mx" 1)]
      (is (= "x" (aget (aget row 0) 0)))
      (is (= "\033[1m\033[31m" (aget (aget row 0) 1))))))
