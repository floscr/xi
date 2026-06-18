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
