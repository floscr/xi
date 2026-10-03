(ns xi.web.views-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.views :as views]))

;; ── command-while-busy? ──────────────────────────────────────────────────────

(deftest command-while-busy
  (testing "non-interrupting read-only commands"
    (is (true? (views/command-while-busy? "/diff")))
    (is (true? (views/command-while-busy? "/help")))
    (is (true? (views/command-while-busy? "/debug"))))
  (testing "subcommands inherit the parent's flag"
    (is (true? (views/command-while-busy? "/diff staged")))
    (is (true? (views/command-while-busy? "  /diff session-commits  "))))
  (testing "session-mutating commands are not while-busy"
    (is (false? (boolean (views/command-while-busy? "/new"))))
    (is (false? (boolean (views/command-while-busy? "/clear"))))
    (is (false? (boolean (views/command-while-busy? "/truncate")))))
  (testing "plain prompts and unknown commands"
    (is (false? (boolean (views/command-while-busy? "hello world"))))
    (is (false? (boolean (views/command-while-busy? "/bogus"))))
    (is (false? (boolean (views/command-while-busy? nil))))))

;; ── format-elapsed (run timer / sub-agent duration) ─────────────────────────

(deftest format-elapsed-test
  (is (= "0s" (views/format-elapsed 0)))
  (is (= "59s" (views/format-elapsed 59)))
  (is (= "1m 00s" (views/format-elapsed 60)))
  (is (= "4m 05s" (views/format-elapsed 245)))
  (is (= "12m 30s" (views/format-elapsed 750))))

;; ── code-focus-segments (permission ask → muted / focused code) ───────────

(deftest code-focus-segments-splits-around-ranges
  (let [text "(def a 1)\n(spit p 1)\n(cp a b)"]
    (testing "text outside the ranges is muted, inside is not"
      (is (= [[true "(def a 1)\n"] [false "(spit p 1)"] [true "\n(cp a b)"]]
             (views/code-focus-segments text [[10 20]]))))
    (testing "several ranges, given in any order"
      (is (= [[true "(def a 1)\n"] [false "(spit p 1)"] [true "\n"] [false "(cp a b)"]]
             (views/code-focus-segments text [[21 29] [10 20]]))))
    (testing "ranges past the (truncated) text are clamped"
      (is (= [[true "(def a 1)\n"] [false "(spit p 1)\n(cp a b)"]]
             (views/code-focus-segments text [[10 999]])))
      (is (= [[true text]] (views/code-focus-segments text [[500 600]]))))
    (testing "a range covering everything mutes nothing"
      (is (= [[false text]] (views/code-focus-segments text [[0 (count text)]]))))))
