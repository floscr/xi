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

(deftest sandbox-mode-gate
  (let [on  {:sandbox {:enabled? true}}
        off {:sandbox {:enabled? false}}]
    (testing "sandbox denies writes/edits outside the working tree"
      (is (= :deny (action-type {:tool :write :path "/elsewhere/x.txt"
                                 :outside-cwd? true :state on})))
      (is (= :deny (action-type {:tool :edit :path "/elsewhere/x.txt"
                                 :outside-cwd? true :state on}))))
    (testing "the sandbox outside-write deny wins over the general outside ask"
      (is (= :ask (action-type {:tool :write :path "/elsewhere/x.txt"
                               :outside-cwd? true :state off}))
          "with sandbox off it falls through to the softer ask"))
    (testing "a write inside the tree is not denied by sandbox"
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :write :path "src/foo.cljs" :state on}))))
    (testing "sandbox denies reads of credential paths"
      (is (= :deny (action-type {:tool :read :credential-path? true :state on})))
      (is (= :deny (action-type {:tool :grep :credential-path? true :state on})))
      (is (= :deny (action-type {:tool :find :credential-path? true :state on})))
      (is (= :deny (action-type {:tool :ls   :credential-path? true :state on}))))
    (testing "non-credential reads pass, and reads pass when sandbox is off"
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :read :path "src/foo.cljs" :state on})))
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :read :credential-path? true :state off}))))
    (testing "sandbox denies backgrounded (`cmd &`) bash"
      (is (= :deny (action-type {:tool :bash :command "npm run dev &" :state on})))
      (is (= :deny (action-type {:tool :bash :command "sleep 5 &  " :state on}))))
    (testing "a foregrounded command passes, and `cmd &` passes when sandbox off"
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :bash :command "npm run dev" :state on})))
      (is (nil? (rules/first-match defaults/default-rules
                                   {:tool :bash :command "npm run dev &" :state off}))))))

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

(deftest defaults-tagged-scope
  (is (every? #(= :default (:scope %)) defaults/default-rules)))
