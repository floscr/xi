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

(deftest match-tool-name
  (let [req {:tool :other :tool-name "spawn_subagent"}]
    (testing "exact string"
      (is (rules/matches? {:match {:tool-name "spawn_subagent"}} req))
      (is (not (rules/matches? {:match {:tool-name "list_subagents"}} req))))
    (testing "glob, regex, set"
      (is (rules/matches? {:match {:tool-name "*_subagent"}} req))
      (is (rules/matches? {:match {:tool-name #"^spawn_"}} req))
      (is (rules/matches? {:match {:tool-name #{"a" "spawn_subagent"}}} req))
      (is (not (rules/matches? {:match {:tool-name #{"a" "b"}}} req))))
    (testing "ANDed with :tool; absent spec is unconstrained"
      (is (rules/matches? {:match {:tool :other :tool-name "spawn_subagent"}} req))
      (is (not (rules/matches? {:match {:tool :bash :tool-name "spawn_subagent"}} req)))
      (is (rules/matches? {:match {:tool :other}} req)))
    (testing "a request without a tool name never matches a :tool-name rule"
      (is (not (rules/matches? {:match {:tool-name "spawn_subagent"}} {:tool :sh}))))))

(deftest match-extension-and-host
  (let [req {:tool :net :extension "pushover" :host "api.pushover.net"}]
    (testing ":extension true matches any extension request, never a tool call"
      (is (rules/matches? {:match {:extension true}} req))
      (is (not (rules/matches? {:match {:extension true}} {:tool :net :host "x"}))))
    (testing ":extension by name / glob / set"
      (is (rules/matches? {:match {:extension "pushover"}} req))
      (is (rules/matches? {:match {:extension #{"a" "pushover"}}} req))
      (is (not (rules/matches? {:match {:extension "notes"}} req))))
    (testing ":host glob / set / regex"
      (is (rules/matches? {:match {:host "*.pushover.net"}} req))
      (is (rules/matches? {:match {:host #{"api.pushover.net"}}} req))
      (is (rules/matches? {:match {:host #"pushover\.net$"}} req))
      (is (not (rules/matches? {:match {:host "example.com"}} req))))
    (testing ":extension-data :own reads the store-computed flag"
      (is (rules/matches? {:match {:extension-data :own}} (assoc req :own-data? true)))
      (is (not (rules/matches? {:match {:extension-data :own}} req))))))

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

(deftest match-xi-rules-file-optin
  (let [rule {:match {:tool #{:write :edit} :xi-rules-file true}}]
    (is (rules/matches? rule {:tool :edit :path "/x/rules.edn" :xi-rules-file? true}))
    (is (not (rules/matches? rule {:tool :edit :path "/x/rules.edn" :xi-rules-file? false})))
    (is (not (rules/matches? rule {:tool :edit :path "/x/rules.edn"}))
        "absent flag → an :xi-rules-file rule never matches")))

(deftest needs-xi-rules-file
  (is (rules/needs-xi-rules-file? [{:match {:tool :edit :xi-rules-file true}}]))
  (is (not (rules/needs-xi-rules-file? [{:match {:tool :edit :path "*.edn"}}]))))

(deftest match-xi-config-file-optin
  (let [rule {:match {:tool #{:write :edit} :xi-config-file true}}]
    (is (rules/matches? rule {:tool :edit :path "/x/config.edn" :xi-config-file? true}))
    (is (not (rules/matches? rule {:tool :edit :path "/x/config.edn" :xi-config-file? false})))
    (is (not (rules/matches? rule {:tool :edit :path "/x/config.edn"}))
        "absent flag → an :xi-config-file rule never matches")))

(deftest needs-xi-config-file
  (is (rules/needs-xi-config-file? [{:match {:tool :edit :xi-config-file true}}]))
  (is (not (rules/needs-xi-config-file? [{:match {:tool :edit :xi-rules-file true}}]))))

(deftest match-tracked-optin
  (let [rule {:match {:tool :sh :cli "rm" :tracked :git}}]
    (is (rules/matches? rule {:tool :sh :cli "rm" :command "rm src/a.clj"
                              :operands-tracked? true}))
    (is (not (rules/matches? rule {:tool :sh :cli "rm" :command "rm notes.txt"
                                   :operands-tracked? false}))
        "untracked operand → no match")
    (is (not (rules/matches? rule {:tool :sh :cli "rm" :command "rm x"}))
        "absent flag (dynamic args / no :tracked check) → a :tracked rule never matches")
    (is (not (rules/matches? {:match {:tool :sh :tracked :repo}}
                             {:tool :sh :cli "rm" :command "rm x" :operands-tracked? true}))
        "only :git is a known value")))

(deftest needs-tracked
  (is (rules/needs-tracked? [{:match {:tool :sh :cli "rm" :tracked :git}}]))
  (is (not (rules/needs-tracked? [{:match {:tool :sh :cli "rm" :within :repo}}]))))

(deftest tracked-rules-are-arg-scoped
  (is (rules/arg-scoped? {:match {:tool :sh :cli "rm" :tracked :git}}))
  (is (rules/arg-scoped? {:match {:tool :sh :cli "mv" :within :repo}}))
  (is (not (rules/arg-scoped? {:match {:tool :sh :cli "rm"}}))))

(deftest match-installed-optin
  (let [missing {:match {:tool :sh :installed false}}
        present {:match {:tool :sh :installed true}}]
    (is (rules/matches? missing {:tool :sh :cli "python3" :installed? false}))
    (is (not (rules/matches? missing {:tool :sh :cli "ls" :installed? true})))
    (is (rules/matches? present {:tool :sh :cli "ls" :installed? true}))
    (is (not (rules/matches? missing {:tool :sh :cli "python3"}))
        "absent flag (no :installed rule in play, or not a program token) never matches")
    (is (rules/matches? {:match {:tool :sh}} {:tool :sh :cli "x" :installed? false})
        "nil spec is unconstrained")))

(deftest needs-installed
  (is (rules/needs-installed? [{:match {:tool :sh :installed false}}]))
  (is (rules/needs-installed? [{:match {:tool :sh :installed true}}]))
  (is (not (rules/needs-installed? [{:match {:tool :sh :cli "git"}}]))))

(deftest first-match-collects-hints
  (let [hint-http {:match  {:tool :sh :command #"http\.server"}
                   :action {:type :hint :message "Use bb http-server instead."}}
        hint-py   {:match  {:tool :sh :cli "python3"}
                   :action {:type :hint :message "No python3 on this box ({cli})."}}
        deny      {:match  {:tool :sh :installed false}
                   :action {:type :deny :message "`{cli}` is not installed."}}
        ask       {:match  {:tool :sh}
                   :action {:type :ask}}
        req       {:tool :sh :cli "python3" :command "python3 -m http.server" :installed? false}]
    (testing "hints above the deciding rule stack in order; the decision is the first non-hint match"
      (let [r (rules/first-match [hint-http hint-py deny ask] req)]
        (is (= :deny (get-in r [:action :type])))
        (is (= ["Use bb http-server instead." "No python3 on this box (python3)."] (:hints r))
            "placeholders are rendered in hints")
        (is (= "`python3` is not installed." (get-in r [:action :message]))
            "and in the deciding message")
        (is (= (str "`python3` is not installed.\n\n"
                    "Use bb http-server instead.\n\n"
                    "No python3 on this box (python3).")
               (rules/decision-message r nil)))))
    (testing "a hint never decides: alone it yields no match"
      (is (nil? (rules/first-match [hint-http] req))))
    (testing "hints below the deciding rule are not collected"
      (is (nil? (:hints (rules/first-match [deny hint-http] req)))))
    (testing "non-matching hints are skipped"
      (is (= ["No python3 on this box (python3)."]
             (:hints (rules/first-match [hint-http hint-py deny]
                                        (assoc req :command "python3 x.py"))))))
    (testing "a rule without a message keeps none; decision-message falls back and appends"
      (let [r (rules/first-match [hint-py ask] (dissoc req :installed?))]
        (is (= :ask (get-in r [:action :type])))
        (is (not (contains? (:action r) :message)))
        (is (= "Run?\n\nNo python3 on this box (python3)." (rules/decision-message r "Run?")))
        (is (= "No python3 on this box (python3)." (rules/decision-message r nil)))))
    (testing "with-hints"
      (is (= "m" (rules/with-hints "m" nil)))
      (is (= "m" (rules/with-hints "m" ["" nil])))
      (is (nil? (rules/with-hints nil [])))
      (is (= "a\n\nb" (rules/with-hints "a" ["b"]))))
    (testing "render-message leaves unfillable placeholders alone"
      (is (= "{cli} ran {command}" (rules/render-message "{cli} ran {command}" {:tool :write})))
      (is (= "git ran git push" (rules/render-message "{cli} ran {command}"
                                                      {:cli "git" :command "git push"})))
      (is (nil? (rules/render-message nil {:cli "git"}))))))
