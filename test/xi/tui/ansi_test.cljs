(ns xi.tui.ansi-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.highlight.theme :as hl-theme]
            [xi.tui.ansi :as ansi]))

(deftest wrap-text-propagates-sgr
  (testing "single-line text is unchanged"
    (is (= ["hello"] (ansi/wrap-text "hello" 80))))

  (testing "multi-line plain text has no spurious codes"
    (is (= ["line1" "line2" "line3"]
           (ansi/wrap-text "line1\nline2\nline3" 80))))

  (testing "multi-line text with opening ANSI code propagates to all lines"
    (let [dim "\033[90m"
          reset "\033[0m"
          text (str dim "line1\nline2\nline3" reset)
          result (ansi/wrap-text text 80)]
      ;; Every line should start with the dim code
      (is (= 3 (count result)))
      (doseq [line result]
        (is (clojure.string/starts-with? line dim)
            (str "Line should start with dim code: " (pr-str line))))))

  (testing "reset in middle stops propagation"
    (let [dim "\033[90m"
          reset "\033[0m"
          text (str dim "line1" reset "\nline2")
          result (ansi/wrap-text text 80)]
      (is (= 2 (count result)))
      ;; First line has dim
      (is (clojure.string/starts-with? (first result) dim))
      ;; Second line should NOT have dim (reset cleared it)
      (is (not (clojure.string/starts-with? (second result) dim)))))

  (testing "word-wrap also propagates SGR"
    (let [dim "\033[90m"
          reset "\033[0m"
          ;; A single long line that needs wrapping
          text (str dim "hello world foo bar" reset)
          result (ansi/wrap-text text 12)]
      ;; Should wrap into multiple lines, each with dim prefix
      (is (> (count result) 1))
      (doseq [line result]
        (is (clojure.string/starts-with? line dim)
            (str "Wrapped line should start with dim: " (pr-str line))))))

  (testing "long word without spaces is hard-broken"
    (let [text "abcdefghijklmnopqrstuvwxyz"
          result (ansi/wrap-text text 10)]
      (is (= ["abcdefghij" "klmnopqrst" "uvwxyz"] result))))

  (testing "long base64-like string is broken at width boundary"
    (let [text (apply str (repeat 50 "x"))
          result (ansi/wrap-text text 20)]
      (is (= 3 (count result)))
      (is (every? #(<= (count %) 20) result))
      (is (= text (apply str result))))))

(deftest wrap-text-hang-indent
  (testing "default mode drops the indent of a wrapped line"
    (is (= ["aaa bbb" "ccc"] (ansi/wrap-text "  aaa bbb ccc" 10))))

  (testing "code mode keeps the indent and hangs continuations deeper"
    (is (= ["  aaa bbb" "    ccc" "    ddd"]
           (ansi/wrap-text "  aaa bbb ccc ddd" 10 {:hang-indent 2}))))

  (testing "lines that fit and unindented lines are untouched"
    (is (= ["(foo" " bar)"]
           (ansi/wrap-text "(foo\n bar)" 10 {:hang-indent 2})))
    (is (= ["aaa bbb" "  ccc"]
           (ansi/wrap-text "aaa bbb ccc" 8 {:hang-indent 2}))))

  (testing "indent interleaved with SGR codes is measured by visible width"
    (let [red "\033[31m" reset "\033[0m"
          result (ansi/wrap-text (str " " red "aaa bbb ccc" reset) 10 {:hang-indent 2})]
      (is (= [" aaa bbb" "   ccc"] (mapv ansi/strip-ansi result)))))

  (testing "an indent eating over half the width falls back to plain wrap"
    (is (= ["aaa bbb"]
           (ansi/wrap-text "        aaa bbb" 10 {:hang-indent 2})))))

(deftest visible-width-wide-chars
  (testing "BMP wide emoji counts as 2 columns"
    (is (= 2 (ansi/visible-width "✅")))
    (is (= 2 (ansi/visible-width "❌"))))
  (testing "surrogate-pair emoji counts as 2 columns"
    (is (= 2 (ansi/visible-width "📎")))
    (is (= 2 (ansi/visible-width "😀"))))
  (testing "colored circle/square emoji (Geometric Shapes Extended) count as 2 columns"
    ;; 🟠🟡🟢 (U+1F7E0–2) and 🟥🟩 (U+1F7E5/9) render 2-wide; regression for
    ;; markers eating the next char (No timeout → No imeout).
    (is (= 2 (ansi/visible-width "🟠")))
    (is (= 2 (ansi/visible-width "🟡")))
    (is (= 2 (ansi/visible-width "🟢")))
    (is (= 2 (ansi/visible-width "🟥")))
    (is (= 5 (ansi/visible-width "🟠 No"))))
  (testing "CJK counts as 2 columns"
    (is (= 2 (ansi/visible-width "日")))
    (is (= 4 (ansi/visible-width "日本"))))
  (testing "VS16 upgrades narrow char to wide"
    ;; ⚠️ = U+26A0 (narrow) + U+FE0F (VS16)
    (is (= 2 (ansi/visible-width "⚠️")))
    (is (= 1 (ansi/visible-width "⚠"))))
  (testing "combining marks are zero-width"
    ;; e + U+0301 combining acute accent
    (is (= 1 (ansi/visible-width "e\u0301"))))
  (testing "zero-width joiner is zero-width"
    (is (= 2 (ansi/visible-width "a\u200dz")) "a + ZWJ + z counts ZWJ as 0"))
  (testing "mixed narrow and wide"
    (is (= 5 (ansi/visible-width "✅ ok")))
    (is (= 7 (ansi/visible-width "\033[31m✅ ok⚠️\033[0m")))))

(deftest truncate-to-width-wide-chars
  (testing "wide chars are not split mid-glyph"
    (let [result (ansi/truncate-to-width "✅✅✅" 5)]
      ;; target is 4 cols + ellipsis: two emoji fit, third does not
      (is (= 5 (ansi/visible-width result)))
      (is (clojure.string/includes? result "…"))
      (is (= 2 (count (re-seq #"✅" result))))))
  (testing "wide char straddling the boundary is replaced by ellipsis"
    (let [result (ansi/truncate-to-width "a✅✅✅" 6)]
      ;; target 5: a(1) + ✅(2) = 3, next ✅ ends at 5 = target — fits, then ellipsis
      (is (<= (ansi/visible-width result) 6))))
  (testing "short lines with wide chars pass through unchanged"
    (is (= "✅ ok" (ansi/truncate-to-width "✅ ok" 10)))))

(deftest wrap-text-wide-chars
  (testing "hard break never exceeds width with wide chars"
    (let [result (ansi/wrap-text (apply str (repeat 10 "✅")) 6)]
      (is (every? #(<= (ansi/visible-width %) 6) result))
      (is (= 10 (count (re-seq #"✅" (apply str result))))))))

(deftest visible-width-tabs
  (testing "tab at start expands to 4"
    (is (= 4 (ansi/visible-width "\t"))))
  (testing "tab after chars expands to next stop"
    (is (= 4 (ansi/visible-width "ab\t"))))
  (testing "tab at stop boundary expands to next stop"
    (is (= 8 (ansi/visible-width "abcd\t"))))
  (testing "tabs with ANSI codes"
    (is (= 4 (ansi/visible-width "\033[31m\t\033[0m")))))

;; ── apply-bg-to-line: readable default foreground on dark blocks ─────────────

(def ^:private ESC "\033[")
(def ^:private reset (str ESC "0m"))
(def ^:private code-bg (str ESC "48;2;38;44;55m"))

(deftest apply-bg-to-line-sets-default-fg
  (testing "opens the line with both bg and the light default fg so untokenized
            text stays readable on light terminals"
    (let [out (ansi/apply-bg-to-line "plain" 10 code-bg)]
      (is (str/starts-with? out (str code-bg (hl-theme/code-default-fg)))
          "line begins with bg + default fg")
      (is (str/ends-with? out reset)))))

(deftest apply-bg-to-line-reapplies-fg-after-reset
  (testing "after a syntax token's reset, both bg AND the default fg are
            re-applied so trailing plain text is not dropped to terminal default"
    (let [kw   (str ESC "38;2;129;161;193m")
          line (str kw "if" reset " plain")
          out  (ansi/apply-bg-to-line line 20 code-bg)]
      (is (str/includes? out (str reset code-bg (hl-theme/code-default-fg)))
          "bg + default fg re-applied immediately after the inner reset"))))

(deftest apply-bg-to-line-respects-explicit-fg
  (testing "an explicit fg-code overrides the default"
    (let [fg  (str ESC "38;2;1;2;3m")
          out (ansi/apply-bg-to-line "x" 5 code-bg fg)]
      (is (str/starts-with? out (str code-bg fg)))
      (is (not (str/includes? out (hl-theme/code-default-fg)))))))

;; ── strip-bg-sgr / hl-line ──────────────────────────────────────────────────

(deftest strip-bg-sgr-removes-truecolor-bg
  (testing "a lone truecolor bg sequence is removed"
    (is (= "" (ansi/strip-bg-sgr (str ESC "48;2;35;60;45m")))))
  (testing "a 256-color bg sequence is removed"
    (is (= "" (ansi/strip-bg-sgr (str ESC "48;5;236m"))))))

(deftest strip-bg-sgr-keeps-fg-and-styles
  (testing "a truecolor fg sequence is preserved verbatim"
    (let [fg (str ESC "38;2;129;161;193m")]
      (is (= fg (ansi/strip-bg-sgr fg)))))
  (testing "resets are preserved"
    (is (= reset (ansi/strip-bg-sgr reset))))
  (testing "bold style is preserved"
    (is (= (str ESC "1m") (ansi/strip-bg-sgr (str ESC "1m"))))))

(deftest strip-bg-sgr-splits-mixed-fg-and-bg
  (testing "a combined fg+bg sequence keeps only the fg params"
    (is (= (str ESC "38;2;1;2;3m")
           (ansi/strip-bg-sgr (str ESC "38;2;1;2;3;48;2;4;5;6m"))))))

(deftest strip-bg-sgr-does-not-misread-fg-channel-as-bg
  (testing "a fg channel value of 48 is not mistaken for a background code"
    (let [fg (str ESC "38;2;48;48;48m")]
      (is (= fg (ansi/strip-bg-sgr fg))))))

(deftest hl-line-overrides-existing-bg
  (testing "a diff-styled line's own bg is replaced by the highlight bg"
    (let [hl   (str ESC "48;2;59;66;82m")
          line (ansi/apply-bg-to-line "code" 10 code-bg)   ;; carries code-bg
          out  (ansi/hl-line line 10 hl)]
      (is (str/starts-with? out (str hl (hl-theme/code-default-fg)))
          "line opens with the highlight bg")
      (is (not (str/includes? out code-bg))
          "the original bg no longer appears anywhere")
      (is (str/ends-with? out reset)))))
