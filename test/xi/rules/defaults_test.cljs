(ns xi.rules.defaults-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]))

(defn- action-type [req]
  (get-in (rules/first-match defaults/default-rules req) [:action :type]))

(defn- nudged? [req]
  (= :nudge (action-type req)))

(deftest tmp-cleanup-nudge
  (testing "rm targeting /tmp is nudged (wins over the guarded rm gate)"
    (is (nudged? {:tool :bash :command "rm -rf /tmp/foo"}))
    (is (nudged? {:tool :bash :command "rm /tmp/bar.txt"}))
    (is (nudged? {:tool :bash :command "cd /home && rm -rf /tmp/foo"}))
    (is (nudged? {:tool :bash :command "rm -rf \"/tmp/with space\""})))
  (testing "a non-/tmp destructive rm now hits the guarded gate"
    (is (= :ask (action-type {:tool :bash :command "rm -rf /home/x/build"}))))
  (testing "plain rm in a separate segment from /tmp passes through"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :bash :command "ls /tmp"})))
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :bash :command "rm ./out.txt && cat /tmp/log"}))
        "rm and /tmp in different segments do not match")))

(deftest auto-memory-nudge
  (testing "write/edit to the auto-memory dir is nudged (wins over write gates)"
    (is (nudged? {:tool :write
                  :path "/home/x/.claude/projects/-home-x/memory/MEMORY.md"}))
    (is (nudged? {:tool :edit
                  :path ".claude/projects/foo/memory"})))
  (testing "bash writing to the auto-memory dir is nudged"
    (is (nudged? {:tool :bash
                  :command "echo hi >> ~/.claude/projects/x/memory/MEMORY.md"})))
  (testing "unrelated paths pass through"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :write :path "src/foo.cljs"})))
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :read
                                  :path "/home/x/.claude/projects/x/memory/MEMORY.md"}))
        "reads are not gated")))

;; ── Ported permission-gate policy gates ──────────────────────────────────────

(deftest sensitive-write-gate
  (testing "writes into credential dirs ask"
    (is (= :ask (action-type {:tool :write :path "/home/x/.ssh/config"})))
    (is (= :ask (action-type {:tool :edit  :path "/home/x/.gnupg/gpg.conf"})))
    (is (= :ask (action-type {:tool :write :path "/home/x/.password-store/x.gpg"})))
    (is (= :ask (action-type {:tool :write :path "/home/x/Mail/inbox"}))))
  (testing "an ordinary source path is not sensitive"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :write :path "src/xi/rules.cljs"})))))

(deftest protected-write-gate
  (testing "writes into protected project files ask"
    (is (= :ask (action-type {:tool :write :path "/repo/.env"})))
    (is (= :ask (action-type {:tool :edit  :path "/repo/.git/config"})))
    (is (= :ask (action-type {:tool :write :path "/repo/node_modules/x/index.js"})))))

(deftest outside-write-gate
  (testing "a write flagged outside the cwd asks"
    (is (= :ask (action-type {:tool :write :path "/elsewhere/x.txt"
                              :outside-cwd? true}))))
  (testing "without the outside flag the outside gate does not fire"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :write :path "/elsewhere/x.txt"})))))

(deftest remote-shell-blocked
  (testing "remote shells are denied outright"
    (is (= :deny (action-type {:tool :bash :command "ssh host uptime"})))
    (is (= :deny (action-type {:tool :bash :command "rsync -a a b"})))
    (is (= :deny (action-type {:tool :bash :command "scp a host:b"})))
    (is (= :deny (action-type {:tool :bash :command "sftp host"})))))

(deftest guarded-command-gate
  (testing "destructive patterns ask"
    (is (= :ask (action-type {:tool :bash :command "sudo rm foo"})))
    (is (= :ask (action-type {:tool :bash :command "git push origin main"})))
    (is (= :ask (action-type {:tool :bash :command "kill -9 123"})))
    (is (= :ask (action-type {:tool :bash :command "bb -e '(babashka.fs/delete-tree \"x\")'"}))))
  (testing "an innocuous command passes through"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :bash :command "ls -la"})))))

(deftest mcp-default-gate
  (testing "every external MCP tool call asks by default"
    (is (= :ask (action-type {:tool :mcp :mcp-server "render" :mcp-tool "deploy"})))
    (is (= [:yes :no :always]
           (get-in (rules/first-match defaults/default-rules {:tool :mcp})
                   [:action :options]))))
  (testing "non-mcp tools are not caught by the mcp gate"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :read :path "src/foo.cljs"})))))

(deftest server-control-gate
  (testing "restarting/stopping the server asks — via bash, the bb tool, or clj sh"
    (is (= :ask (action-type {:tool :bash :command "bb serve:restart"})))
    (is (= :ask (action-type {:tool :bb   :command "bb serve:stop"})))
    (is (= :ask (action-type {:tool :sh   :cli "bb" :command "bb serve:restart"})))
    (is (= [:yes :no]
           (get-in (rules/first-match defaults/default-rules
                                      {:tool :bb :command "bb serve:restart"})
                   [:action :options]))
        "no [a]lways — every restart is confirmed"))
  (testing "the rule is command-scoped, so clj confirms that exact command"
    (is (rules/arg-scoped? (rules/first-match defaults/default-rules
                                              {:tool :sh :cli "bb" :command "bb serve:restart"}))))
  (testing "other bb tasks and the personal server's tasks don't match"
    (is (nil? (rules/first-match defaults/default-rules {:tool :bb :command "bb test"})))
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :bb :command "bb serve:personal:restart"})))))

(deftest guarded-patterns-published
  ;; clj applies the same list to (sh …) argv strings.
  (is (some #{"fs/delete-dir"} defaults/guarded-patterns))
  (is (some #{"git push"} defaults/guarded-patterns)))

(deftest subagent-spawn-gate
  (testing "spawning a sub-agent asks, with [a]lways"
    (is (= :ask (action-type {:tool :other :tool-name "spawn_subagent"})))
    (is (= [:yes :no :always]
           (get-in (rules/first-match defaults/default-rules
                                      {:tool :other :tool-name "spawn_subagent"})
                   [:action :options]))))
  (testing "polling/stopping sub-agents runs without a prompt"
    (doseq [t ["list_subagents" "subagent_result" "stop_subagent"]]
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :other :tool-name t}))
          t))))

(deftest plan-mode-gate
  (let [on  {:plan-mode {:enabled? true}}
        off {:plan-mode {:enabled? false}}]
    (testing "plan mode allows the plan file but denies other writes/edits"
      (is (= :allow (action-type {:tool :write :path "tasks/todo.md" :state on})))
      (is (= :allow (action-type {:tool :edit  :path "tasks/todo.md" :state on})))
      (is (= :deny  (action-type {:tool :write :path "src/foo.cljs" :state on})))
      (is (= :deny  (action-type {:tool :edit  :path "src/foo.cljs" :state on}))))
    (testing "plan mode denies mutating bash, lets read-only bash through"
      (is (= :deny (action-type {:tool :bash :command "rm -rf build" :state on})))
      (is (= :deny (action-type {:tool :bash :command "echo hi > out.txt" :state on})))
      (is (= :deny (action-type {:tool :bash :command "sudo reboot" :state on})))
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :bash :command "ls -la" :state on})))
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :bash :command "git status" :state on}))))
    (testing "reads always pass in plan mode"
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :read :path "src/foo.cljs" :state on}))))
    (testing "with plan mode off the plan rules do not fire"
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :write :path "src/foo.cljs" :state off})))
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :edit :path "src/foo.cljs"}))))))

(deftest sh-softener-gate
  (testing "read-only / rm autorun CLIs are allowed without asking"
    (is (= :allow (action-type {:tool :sh :cli "ls"  :command "ls -la"})))
    (is (= :allow (action-type {:tool :sh :cli "grep" :command "grep x y"})))
    (is (= :allow (action-type {:tool :sh :cli "rm"  :command "rm -rf build"}))
        "rm auto-runs from clj (bash's rm -rf stays guarded via the :bash rule)")
    (is (= :allow (action-type {:tool :sh :cli "git" :command "git status"}))))
  (testing "an unknown CLI hits the base ask (disallow * then soften)"
    (is (= :ask (action-type {:tool :sh :cli "terraform" :command "terraform apply"})))
    (is (= :ask (action-type {:tool :sh :cli "npm" :command "npm run dev"}))))
  (testing "the :sh rules never touch the real bash tool"
    (is (nil? (rules/first-match defaults/default-rules
                                 {:tool :bash :command "terraform apply"}))
        "a plain bash command matches no :sh rule")))

(deftest sed-print-in-repo
  (let [sed (fn [cmd & [repo]] (action-type {:tool :sh :cli "sed" :command cmd
                                             :repo (or repo "/home/u/code/proj")}))]
    (testing "read-only `sed -n <addr>p file…` inside a repo is allowed"
      (is (= :allow (sed "sed -n 3060,3420p src/xi/web/views.cljs")))
      (is (= :allow (sed "sed -n 1p a b")))
      (is (= :allow (sed "sed -n $p f")))
      (is (= :allow (sed "sed -n 10,$p f")))
      (is (= :allow (sed "sed -n /start/,/end/p f")))
      (is (= :allow (sed "sed -n 1,5p;20,30p f"))))
    (testing "writing / executing / flag-carrying forms fall to the base ask"
      (is (= :ask (sed "sed -i s/a/b/ f")))
      (is (= :ask (sed "sed -n 1p -i f")))
      (is (= :ask (sed "sed -n 1p --in-place f")))
      (is (= :ask (sed "sed -n 1p;w out f")))
      (is (= :ask (sed "sed -n 1e f")))
      (is (= :ask (sed "sed -n s/a/b/ep f")))
      (is (= :ask (sed "sed s/a/b/ f")))
      (is (= :ask (sed "sed -n 1,5p")) "no file operand (stdin / dynamic arg)"))
    (testing "outside a git repo it asks"
      (is (= :ask (action-type {:tool :sh :cli "sed" :command "sed -n 1p f"}))))))

(deftest file-clis-within-repo
  (testing "mv/cp/mkdir/… whose operands stay in the repo are allowed"
    (doseq [cli ["mv" "cp" "mkdir" "touch" "rmdir"]]
      (is (= :allow (action-type {:tool :sh :cli cli :command (str cli " a b")
                                  :operands-within-repo? true}))
          cli)))
  (testing "operands outside the repo (or unchecked) fall to the base ask"
    (is (= :ask (action-type {:tool :sh :cli "mv" :command "mv a /etc/x"
                              :operands-within-repo? false})))
    (is (= :ask (action-type {:tool :sh :cli "mv" :command "mv a b"}))
        "no argv (dynamic args) → operands unchecked")
    (is (= :ask (action-type {:tool :sh :cli "ln" :command "ln -s a b"
                              :operands-within-repo? true}))
        "ln is not covered — its link target resolves against the link's dir")
    (is (= :ask (action-type {:tool :sh :cli "chmod" :command "chmod +x a"
                              :operands-within-repo? true}))
        "chmod is not covered — a permission change (+x, u+s) isn't a content change")))

(deftest defaults-tagged-scope
  (is (every? #(= :default (:scope %)) defaults/default-rules)))

(deftest bundle-aliases-expand
  (testing "the built-in tier is the expansion of the default aliases"
    (is (= defaults/default-rules (defaults/expand defaults/default-aliases)))
    (is (= 22 (count defaults/default-rules))))
  (testing "composites expand to their parts, in order"
    (is (= (defaults/expand [:xi.rules.defaults/sensitive-writes
                             :xi.rules.defaults/protected-writes
                             :xi.rules.defaults/outside-writes])
           (defaults/expand [:xi.rules.defaults/write-gates])))
    (is (= (defaults/expand [:xi.rules.defaults/sh-read-only
                             :xi.rules.defaults/repository-scripts
                             :xi.rules.defaults/sh-confirm])
           (defaults/expand [:xi.rules.defaults/clj-sh]))))
  (testing "every alias a bundle references resolves"
    (doseq [[alias entries] defaults/bundles
            e entries
            :when (keyword? e)]
      (is (contains? defaults/bundles e) (str alias " → " e))))
  (testing "inline rule maps pass through, tagged :default"
    (is (= [{:match {:tool :read} :action {:type :allow} :scope :default}]
           (defaults/expand [{:match {:tool :read} :action {:type :allow}}]))))
  (testing "a subset keeps only the chosen bundles"
    (let [rs (defaults/expand [:xi.rules.defaults/mcp-confirm])]
      (is (= :ask (get-in (rules/first-match rs {:tool :mcp}) [:action :type])))
      (is (nil? (rules/first-match rs {:tool :sh :cli "cat"})))))
  (testing "unknown aliases and non-rule entries throw"
    (is (thrown-with-msg? js/Error #"unknown default-rules alias"
          (defaults/expand [:xi.rules.defaults/nope])))
    (is (thrown-with-msg? js/Error #"invalid :defaults entry"
          (defaults/expand ["plan-mode"])))))
