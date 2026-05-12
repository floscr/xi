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
            (str "Wrapped line should start with dim: " (pr-str line)))))))
