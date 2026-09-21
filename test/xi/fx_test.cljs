(ns xi.fx-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.fx :as fx]))

(defn- clj-call
  "A clj-tool history entry whose code + result text emulate a real eval."
  [code result-text]
  {:kind :tool-call :tool "clj"
   :arguments {:code code}
   :result [{:type "text" :text result-text}]})

(deftest session-commit-refs-test
  (testing "git_commit tool commits are collected"
    (is (= ["abc1234"]
           (fx/session-commit-refs
            {:history [{:kind :tool-call :tool "git_commit"
                        :arguments {:message "feat: thing"}
                        :result "[master abc1234] feat: thing\n 1 file changed"}]}))))

  (testing "shell `git commit` bash calls are collected"
    (is (= ["def5678"]
           (fx/session-commit-refs
            {:history [{:kind :tool-call :tool "bash"
                        :arguments {:command "git commit -m wip"}
                        :result "[master def5678] wip"}]}))))

  (testing "clj-sandbox (git \"commit\" …) calls are collected"
    (is (= ["aaa1111"]
           (fx/session-commit-refs
            {:history [(clj-call "(git \"commit\" \"-m\" \"from clj\")"
                                 "=> \"[master aaa1111] from clj\"")]}))))

  (testing "clj-sandbox escalated (sh \"git\" \"commit\" …) calls are collected"
    (is (= ["bbb2222"]
           (fx/session-commit-refs
            {:history [(clj-call "(sh \"git\" \"commit\" \"-m\" \"escalated\")"
                                 "=> \"[master bbb2222] escalated\"")]}))))

  (testing "clj calls that don't commit are ignored"
    (is (= []
           (fx/session-commit-refs
            {:history [(clj-call "(git \"status\" \"--short\")" "=> \" M src/foo.cljs\"")
                       (clj-call "(sh \"ls\")" "=> \"foo\\nbar\"")]}))))

  (testing "mixed history keeps commit order"
    (is (= ["abc1234" "aaa1111"]
           (fx/session-commit-refs
            {:history [{:kind :tool-call :tool "git_commit"
                        :arguments {:message "first"}
                        :result "[master abc1234] first"}
                       {:kind :user :text "next"}
                       (clj-call "(git \"commit\" \"-m\" \"second\")"
                                 "=> \"[master aaa1111] second\"")]})))))
