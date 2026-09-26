(ns xi.rules-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules :as rules]))

(deftest canonical-normalizes-aliases
  (is (= {:match {:tool :write} :action {:type :nudge}}
         (rules/canonical {:on-block {:tool :write} :do {:type :nudge}})))
  (testing ":match/:action win over aliases"
    (is (= {:match {:tool :edit} :action {:type :deny}}
           (rules/canonical {:match {:tool :edit} :action {:type :deny}
                             :on-block {:tool :write} :do {:type :nudge}})))))

(deftest glob->re-basics
  (is (= "[^/]*\\.sh" (rules/glob->re "*.sh")))
  (is (= ".*\\.sh" (rules/glob->re "**.sh")))
  (is (= "src/[^/]*/x" (rules/glob->re "src/*/x"))))

(deftest match-tool
  (let [rule {:match {:tool #{:write :edit}}}]
    (is (rules/matches? rule {:tool :write}))
    (is (rules/matches? rule {:tool :edit}))
    (is (not (rules/matches? rule {:tool :bash}))))
  (testing "single keyword"
    (is (rules/matches? {:match {:tool :bash}} {:tool :bash}))))

(deftest match-path-glob-and-regex
  (is (rules/matches? {:match {:tool :write :path "*.sh"}}
                      {:tool :write :path "deploy.sh"}))
  (is (not (rules/matches? {:match {:tool :write :path "*.sh"}}
                           {:tool :write :path "dir/deploy.sh"}))
      "single-star does not cross a slash")
  (is (rules/matches? {:match {:tool :write :path "**.sh"}}
                      {:tool :write :path "dir/deploy.sh"}))
  (is (rules/matches? {:match {:tool :write :path #"\.sh$"}}
                      {:tool :write :path "any/deep/deploy.sh"})))

(deftest match-command-regex-and-substring
  (is (rules/matches? {:match {:tool :bash :command #"\brm\b.*/tmp"}}
                      {:tool :bash :command "rm -rf /tmp/x"}))
  (is (rules/matches? {:match {:tool :bash :command "git push"}}
                      {:tool :bash :command "git push origin main"}))
  (is (not (rules/matches? {:match {:tool :bash :command "git push"}}
                           {:tool :bash :command "git status"}))))

(deftest match-repo-and-dir
  (is (rules/matches? {:match {:repo "config/dotfiles"}}
                      {:repo "/home/x/config/dotfiles"}))
  (is (rules/matches? {:match {:dir "/home/x/code"}}
                      {:effective-cwd "/home/x/code/proj"}))
  (is (not (rules/matches? {:match {:dir "/home/x/code"}}
                           {:effective-cwd "/home/x/other"}))))

(deftest match-mcp
  (is (rules/matches? {:match {:mcp-server "context7" :mcp-tool "*"}}
                      {:tool :mcp :mcp-server "context7" :mcp-tool "search"}))
  (is (not (rules/matches? {:match {:mcp-server "context7"}}
                           {:tool :mcp :mcp-server "other"}))))

(deftest match-when-state
  (is (rules/matches? {:match {:tool :write :when {:mode :plan}}}
                      {:tool :write :state {:mode :plan}}))
  (is (not (rules/matches? {:match {:tool :write :when {:mode :plan}}}
                           {:tool :write :state {:mode :normal}})))
  (testing "a map value matches recursively (nested submap), ignoring extra keys"
    (is (rules/matches? {:match {:tool :write :when {:plan-mode {:enabled? true}}}}
                        {:tool :write :state {:plan-mode {:enabled? true :x 1}}}))
    (is (not (rules/matches? {:match {:tool :write :when {:plan-mode {:enabled? true}}}}
                             {:tool :write :state {:plan-mode {:enabled? false}}})))
    (is (not (rules/matches? {:match {:tool :write :when {:plan-mode {:enabled? true}}}}
                             {:tool :write})))))

(deftest match-node-optin
  (let [rule {:match {:tool :edit :node {:type "function_definition"
                                         :name #"^gate-"}}}]
    (is (rules/matches? rule
                        {:tool :edit
                         :nodes [{:type "function_definition" :name "gate-clj"}]}))
    (is (not (rules/matches? rule {:tool :edit :nodes nil}))
        "no nodes → a :node rule never matches")
    (is (not (rules/matches? rule
                             {:tool :edit
                              :nodes [{:type "function_definition" :name "other"}]})))))

(deftest match-outside-optin
  (let [rule {:match {:tool #{:write :edit} :outside :cwd}}]
    (is (rules/matches? rule {:tool :write :path "/x" :outside-cwd? true}))
    (is (not (rules/matches? rule {:tool :write :path "/x" :outside-cwd? false}))
        "not flagged outside → no match")
    (is (not (rules/matches? rule {:tool :write :path "/x"}))
        "absent flag → a :outside rule never matches")))

(deftest match-credential-optin
  (let [rule {:match {:tool #{:read :grep :find :ls} :credential :read}}]
    (is (rules/matches? rule {:tool :read :path "/x" :credential-path? true}))
    (is (not (rules/matches? rule {:tool :read :path "/x" :credential-path? false}))
        "not a credential path → no match")
    (is (not (rules/matches? rule {:tool :read :path "/x"}))
        "absent flag → a :credential rule never matches")))

(deftest match-cli-sh
  (testing "string → exact binary match"
    (let [rule {:match {:tool :sh :cli "git"}}]
      (is (rules/matches? rule {:tool :sh :cli "git" :command "git status"}))
      (is (not (rules/matches? rule {:tool :sh :cli "gitk"}))
          "exact, not prefix")))
  (testing "set → membership"
    (let [rule {:match {:tool :sh :cli #{"scp" "rsync" "sftp"}}}]
      (is (rules/matches? rule {:tool :sh :cli "rsync"}))
      (is (not (rules/matches? rule {:tool :sh :cli "ssh"})))))
  (testing "regex → re-find"
    (let [rule {:match {:tool :sh :cli #"^git"}}]
      (is (rules/matches? rule {:tool :sh :cli "gitk"}))
      (is (not (rules/matches? rule {:tool :sh :cli "hg"})))))
  (testing "nil spec unconstrained; absent :cli never matches a :cli rule"
    (is (rules/matches? {:match {:tool :sh}} {:tool :sh :cli "anything"}))
    (is (not (rules/matches? {:match {:tool :sh :cli "git"}} {:tool :sh}))
        "no :cli on req → a :cli rule can't match"))
  (testing "a :sh :cli rule never matches the real :bash tool"
    (is (not (rules/matches? {:match {:tool :sh :cli "git"}}
                             {:tool :bash :command "git push"}))
        "bash reqs carry no :cli and :tool differs")))

(deftest first-match-precedence
  (let [rules-list [{:match {:tool :write :path "*.sh"} :action {:type :deny} :scope :repo}
                    {:match {:tool :write} :action {:type :ask} :scope :global}]]
    (testing "earlier rule wins"
      (is (= :deny (:type (:action (rules/first-match rules-list
                                                      {:tool :write :path "x.sh"})))))
      (is (= :ask (:type (:action (rules/first-match rules-list
                                                     {:tool :write :path "x.txt"}))))))
    (is (nil? (rules/first-match rules-list {:tool :bash :command "ls"})))))

(deftest needs-nodes
  (is (rules/needs-nodes? [{:match {:tool :edit :node {:type "x"}}}]))
  (is (not (rules/needs-nodes? [{:match {:tool :edit :path "*.clj"}}]))))

(deftest needs-outside
  (is (rules/needs-outside? [{:match {:tool #{:write :edit} :outside :cwd}}]))
  (is (not (rules/needs-outside? [{:match {:tool :edit :path "*.clj"}}]))))

(deftest needs-credential
  (is (rules/needs-credential? [{:match {:tool #{:read :grep} :credential :read}}]))
  (is (not (rules/needs-credential? [{:match {:tool :read :path "*.clj"}}]))))
