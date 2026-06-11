(ns xi.tui.ansi-test
  (:require [cljs.test :refer [deftest is testing]]
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

(deftest visible-width-wide-chars
  (testing "BMP wide emoji counts as 2 columns"
    (is (= 2 (ansi/visible-width "✅")))
    (is (= 2 (ansi/visible-width "❌"))))
  (testing "surrogate-pair emoji counts as 2 columns"
    (is (= 2 (ansi/visible-width "📎")))
    (is (= 2 (ansi/visible-width "😀"))))
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
