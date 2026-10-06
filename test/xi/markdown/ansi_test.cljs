(ns xi.markdown.ansi-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.markdown.ansi :as md-ansi]
            [xi.tui.ansi :as ansi]))

(defn- texts
  "Extract just the :text values from render output."
  [result]
  (mapv :text result))

(defn- visible-widths
  "Get visible widths of all lines from render output."
  [result]
  (mapv #(ansi/visible-width (:text %)) result))

;; ── Paragraph (already wrapped, but verify) ──────────────────────────────────

(deftest paragraph-wraps-long-text
  (let [result (md-ansi/render "hello world foo bar baz" 12)]
    (testing "all lines fit within width"
      (is (every? #(<= % 12) (visible-widths result))))))

;; ── Headings ─────────────────────────────────────────────────────────────────

(deftest heading-wraps-long-text
  (let [text "# This is a very long heading that should definitely wrap"
        result (md-ansi/render text 30)]
    (testing "heading produces multiple lines when long"
      (is (> (count result) 1)))
    (testing "all lines fit within width"
      (is (every? #(<= % 30) (visible-widths result))))))

(deftest heading-short-fits-one-line
  (let [result (md-ansi/render "# Short" 80)]
    (is (= 1 (count result)))))

;; ── Unordered Lists ──────────────────────────────────────────────────────────

(deftest ul-wraps-long-items
  (let [text "- This is a very long bullet point item that should wrap to multiple lines"
        result (md-ansi/render text 30)]
    (testing "produces multiple lines"
      (is (> (count result) 1)))
    (testing "all lines fit within width"
      (is (every? #(<= % 30) (visible-widths result))))))

(deftest ul-continuation-lines-indented
  (let [text "- Add docs/frontend.md documenting clj-ui-framework usage and update workflow"
        result (md-ansi/render text 40)
        lines (texts result)]
    (testing "first line has bullet prefix"
      (is (re-find #"•" (first lines))))
    (testing "continuation lines are indented (not bullet)"
      (doseq [line (rest lines)]
        (is (not (re-find #"•" line)))
        ;; Should start with spaces (continuation indent)
        (is (re-find #"^\s+" (ansi/strip-ansi line)))))))

(deftest ul-short-item-single-line
  (let [result (md-ansi/render "- short" 80)]
    (is (= 1 (count result)))))

;; ── Ordered Lists ────────────────────────────────────────────────────────────

(deftest ol-wraps-long-items
  (let [text "1. This is a very long ordered list item that should wrap to multiple lines when narrow"
        result (md-ansi/render text 30)]
    (testing "produces multiple lines"
      (is (> (count result) 1)))
    (testing "all lines fit within width"
      (is (every? #(<= % 30) (visible-widths result))))))

(deftest ol-continuation-lines-indented
  (let [text "1. First very long item that wraps\n2. Second item"
        result (md-ansi/render text 25)
        lines (texts result)]
    (testing "first item starts with number prefix"
      (is (re-find #"1\." (ansi/strip-ansi (first lines)))))))

;; ── Checkbox Lists ───────────────────────────────────────────────────────────

(deftest checkbox-wraps-long-items
  (let [text "- [x] This is a very long checkbox item that should definitely wrap to multiple lines"
        result (md-ansi/render text 30)]
    (testing "produces multiple lines"
      (is (> (count result) 1)))
    (testing "all lines fit within width"
      (is (every? #(<= % 30) (visible-widths result))))))

;; ── Code Blocks ──────────────────────────────────────────────────────────────

(deftest code-block-truncates-long-lines
  (let [long-line (apply str (repeat 100 "x"))
        text (str "```\n" long-line "\n```")
        result (md-ansi/render text 40)]
    (testing "code lines are truncated to width"
      (is (every? #(<= % 40) (visible-widths result))))))

;; ── Blockquotes ──────────────────────────────────────────────────────────────

(deftest blockquote-wraps-within-reduced-width
  (let [text "> This is a very long blockquote that should wrap accounting for the prefix"
        result (md-ansi/render text 30)]
    (testing "produces multiple lines"
      (is (> (count result) 1)))
    (testing "all lines fit within width"
      (is (every? #(<= % 30) (visible-widths result))))))

;; ── Tables ───────────────────────────────────────────────────────────────────

(deftest table-aligns-columns-with-spaces
  (let [text (str "| Name | Age |\n"
                  "| --- | --- |\n"
                  "| Alice | 30 |\n"
                  "| Bob | 5 |")
        result (md-ansi/render text 80)
        lines (mapv #(ansi/strip-ansi (:text %)) result)]
    (testing "header + separator + two body rows"
      (is (= 4 (count lines))))
    (testing "no box-drawing border characters"
      (doseq [l lines]
        (is (not (re-find #"[│─┼┌┐└┘├┤┬┴╪═]" l)))))
    (testing "columns are space-aligned to a common width"
      ;; "Name" / "Alice" / "Bob" → col width 5; cell padded with spaces
      (is (re-find #"^Name " (first lines)))
      (is (re-find #"^Bob   " (nth lines 3))))
    (testing "separator uses ascii dashes, not box drawing"
      (is (re-find #"-" (second lines))))))

(deftest table-right-alignment-pads-left
  (let [text (str "| n |\n| --: |\n| 1 |\n| 1000 |")
        result (md-ansi/render text 80)
        lines (mapv #(ansi/strip-ansi (:text %)) result)]
    (testing "right-aligned cell has leading spaces"
      (is (re-find #"^   1$" (nth lines 2))))))

(deftest table-respects-width
  (let [text (str "| aaaa | bbbb | cccc |\n| - | - | - |\n| 1111 | 2222 | 3333 |")
        result (md-ansi/render text 12)]
    (is (every? #(<= % 12) (visible-widths result)))))

;; ── Mixed content ────────────────────────────────────────────────────────────

(deftest all-block-types-respect-width
  (testing "no rendered line exceeds the given width"
    (let [markdown (str "# A heading that is quite long and verbose\n\n"
                        "A paragraph with enough words to wrap at narrow width.\n\n"
                        "- Bullet one with a long description that goes on\n"
                        "- Bullet two\n\n"
                        "1. Ordered item one with lots of text here\n"
                        "2. Ordered item two\n\n"
                        "> A blockquote with some lengthy text inside\n\n"
                        "```\n"
                        (apply str (repeat 80 "z"))
                        "\n```")
          width 35
          result (md-ansi/render markdown width)]
      (doseq [{:keys [text]} result]
        (is (<= (ansi/visible-width text) width)
            (str "Line exceeds width " width ": visible="
                 (ansi/visible-width text)
                 " text=" (pr-str (ansi/strip-ansi text))))))))

(deftest ul-nested-items-indent-under-parent
  (let [lines (map ansi/strip-ansi (texts (md-ansi/render "- parent\n  - child\n- next" 40)))]
    (is (= ["  • parent" "      • child" "  • next"] lines))))
