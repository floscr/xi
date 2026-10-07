(ns xi.web.views-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.views :as views]))

;; ── diff-visible-rows (size limit of the diff view) ─────────────────────────

(defn- group
  "A file group: the :file row plus `n` body rows."
  [filename n]
  (into [{:row :file :filename filename}] (repeat n {:row :line})))

(deftest diff-visible-rows
  (let [f @#'views/diff-visible-rows]
    (testing "files that fit the budget render in full"
      (is (= [nil nil] (f [(group "a" 10) (group "b" 20)] #{} #{} 100))))
    (testing "files draw from one budget in order; later files start closed"
      (is (= [nil 20 0] (f [(group "a" 10) (group "b" 50) (group "c" 5)] #{} #{} 30))))
    (testing "expanded and collapsed files are unlimited and spend nothing"
      (is (= [nil nil nil]
             (f [(group "a" 500) (group "b" 500) (group "c" 10)] #{"b"} #{"a"} 10))))))

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

(deftest nav-badge-reads-a-positive-number-at-the-path
  (let [st {:user-ext/state {:chat {:unread 3 :zero 0 :text "x"}}}]
    (is (= "3" (views/nav-badge st {:badge-path [:user-ext/state :chat :unread]})))
    (is (nil? (views/nav-badge st {:badge-path [:user-ext/state :chat :zero]})))
    (is (nil? (views/nav-badge st {:badge-path [:user-ext/state :chat :text]})))
    (is (nil? (views/nav-badge st {:badge-path [:user-ext/state :chat :missing]})))
    (is (nil? (views/nav-badge st {:label "no badge"})))))

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
