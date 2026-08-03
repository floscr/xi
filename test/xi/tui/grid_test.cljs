(ns xi.tui.grid-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.tui.grid :as grid]
            [xi.tui.terminal :as term]))

(defn- capture-diff
  "Run emit-diff! against captured terminal output, returning the emitted string."
  [new-grid old-grid]
  (let [out (atom "")]
    (with-redefs [term/write! (fn [s] (swap! out str s))]
      (grid/emit-diff! new-grid old-grid))
    @out))

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

(deftest line->row-wide-chars
  (testing "wide emoji occupies base cell plus continuation cell"
    (let [row (grid/line->row "✅x" 5)]
      ;; base cell holds the glyph
      (is (= "✅" (aget (aget row 0) 0)))
      (is (not (aget (aget row 0) 2)))
      ;; continuation cell mirrors the glyph, marked as continuation
      (is (= "✅" (aget (aget row 1) 0)))
      (is (true? (aget (aget row 1) 2)))
      ;; next char lands at column 2
      (is (= "x" (aget (aget row 2) 0)))))
  (testing "surrogate-pair emoji is kept whole"
    (let [row (grid/line->row "📎a" 5)]
      (is (= "📎" (aget (aget row 0) 0)))
      (is (true? (aget (aget row 1) 2)))
      (is (= "a" (aget (aget row 2) 0)))))
  (testing "VS16 upgrades preceding narrow char to wide"
    ;; ⚠️ = U+26A0 + U+FE0F
    (let [row (grid/line->row "⚠️a" 5)]
      (is (= "⚠️" (aget (aget row 0) 0)))
      (is (true? (aget (aget row 1) 2)))
      (is (= "⚠️" (aget (aget row 1) 0)) "continuation mirrors full glyph")
      (is (= "a" (aget (aget row 2) 0)))))
  (testing "wide char carries style into both cells"
    (let [row (grid/line->row "\033[32m✅\033[0m" 4)]
      (is (= "\033[32m" (aget (aget row 0) 1)))
      (is (= "\033[32m" (aget (aget row 1) 1)))))
  (testing "wide char that cannot fit in the last column is blanked"
    (let [row (grid/line->row "a✅" 2)]
      (is (= "a" (aget (aget row 0) 0)))
      (is (= " " (aget (aget row 1) 0)))))
  (testing "combining mark attaches to preceding cell"
    (let [row (grid/line->row "e\u0301x" 4)]
      (is (= "e\u0301" (aget (aget row 0) 0)))
      (is (= "x" (aget (aget row 1) 0))))))

(deftest emit-diff-clears-trailing-orphans
  (testing "a shorter line erases to end-of-line so a longer previous line leaves no orphans"
    ;; The previous frame had a long line (with a wide glyph whose terminal
    ;; width may disagree with the grid); the new frame is much shorter.
    (let [old-g (grid/frame->grid ["⚠️ the remote branch has now diverged"] 40 1)
          new-g (grid/frame->grid ["hi"] 40 1)
          out (capture-diff new-g old-g)]
      ;; Writes the new content
      (is (str/includes? out "hi"))
      ;; Clears the blank tail with a single erase-to-end-of-line at the first
      ;; blank column ("hi" occupies cols 0-1, so erase starts at 1-based col 3)
      (is (str/includes? out "\033[1;3H\033[0m\033[K")
          "emits erase-to-EOL at the start of the blank tail"))))

(deftest emit-diff-no-erase-when-unchanged
  (testing "an unchanged row emits nothing (no erase spam)"
    (let [a (grid/frame->grid ["hello"] 20 1)
          b (grid/frame->grid ["hello"] 20 1)
          out (capture-diff a b)]
      (is (= "" out)))))

(deftest emit-diff-no-erase-on-full-width-background
  (testing "a row filled edge-to-edge with a background is not erased-to-EOL"
    ;; Full-width styled line has no default-blank tail, so it must not be
    ;; cleared with \033[K (which would drop the background).
    (let [styled (str "\033[44m" (apply str (repeat 20 " ")))
          old-g (grid/frame->grid ["prev"] 20 1)
          new-g (grid/frame->grid [styled] 20 1)
          out (capture-diff new-g old-g)]
      (is (not (str/includes? out "\033[K"))
          "no erase-to-EOL when the tail carries a background style"))))

(deftest line->row-tab-expansion
  (testing "tabs expand to spaces at 4-column stops"
    (let [row (grid/line->row "\ta" 10)]
      ;; Tab at col 0 should expand to 4 spaces
      (is (= " " (aget (aget row 0) 0)))
      (is (= " " (aget (aget row 1) 0)))
      (is (= " " (aget (aget row 2) 0)))
      (is (= " " (aget (aget row 3) 0)))
      ;; 'a' at col 4
      (is (= "a" (aget (aget row 4) 0)))))
  (testing "tab at non-zero column expands to next stop"
    (let [row (grid/line->row "ab\tc" 10)]
      ;; 'a' at 0, 'b' at 1, tab expands to col 4
      (is (= "a" (aget (aget row 0) 0)))
      (is (= "b" (aget (aget row 1) 0)))
      (is (= " " (aget (aget row 2) 0)))
      (is (= " " (aget (aget row 3) 0)))
      (is (= "c" (aget (aget row 4) 0)))))
  (testing "tab with ANSI preserves style"
    (let [row (grid/line->row "\033[31m\tx" 10)]
      ;; Tab spaces should carry the red style
      (is (= " " (aget (aget row 0) 0)))
      (is (= "\033[31m" (aget (aget row 0) 1)))
      (is (= " " (aget (aget row 3) 0)))
      (is (= "\033[31m" (aget (aget row 3) 1)))
      ;; 'x' at col 4 with red style
      (is (= "x" (aget (aget row 4) 0)))
      (is (= "\033[31m" (aget (aget row 4) 1))))))
