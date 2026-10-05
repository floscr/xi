(ns xi.rules.defaults-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
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
    (is (= :allow (action-type {:tool :read
                                :path "/home/x/.claude/projects/x/memory/MEMORY.md"}))
        "reads are not nudged (claude-sessions allows them)")))

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
  (testing "a call to an untrusted MCP server asks; [a]lways trusts the server"
    (let [req  {:tool :mcp :mcp-server "render" :mcp-tool "deploy" :mcp-trusted? false}
          rule (rules/first-match defaults/default-rules req)]
      (is (= :ask (get-in rule [:action :type])))
      (is (= :mcp/trust
             (some #(get-in % [:event :type]) (get-in rule [:action :options]))))))
  (testing "a trusted server's calls run without a prompt"
    (is (nil? (action-type {:tool :mcp :mcp-server "render" :mcp-tool "deploy"
                            :mcp-trusted? true}))))
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

(defn- script-exec-gated?
  "True when the default tier gates `cmd` with the script-exec rule — told apart
   from the generic `sh-confirm` by its `:unanswered :deny`."
  [tool cmd]
  (= :deny (get-in (rules/first-match defaults/default-rules
                                      {:tool tool :cli (first (str/split cmd #" ")) :command cmd})
                   [:action :unanswered])))

(deftest script-exec-gate
  (testing "inline code and script files ask, across the interpreters"
    (doseq [[tool cmd] [[:sh "bb -f /tmp/propfind.clj"]
                        [:sh "bb -e (+ 1 2)"]
                        [:sh "bb --eval (+ 1 2)"]
                        [:sh "bb /tmp/x.clj"]
                        [:sh "bb script.bb"]
                        [:sh "bb -m my.ns/main"]
                        [:bb "bb -e (slurp \"x\")"]
                        [:bash "bb -f x.clj"]
                        [:sh "node -e console.log(1)"]
                        [:sh "node script.js"]
                        [:sh "nodejs script.js"]
                        [:sh "/usr/bin/python3 x.py"]
                        [:sh "python3.12 -c print(1)"]
                        [:sh "ruby -e puts(1)"]
                        [:sh "perl -e print(1)"]
                        [:sh "java -jar x.jar"]
                        [:sh "bun -e console.log(1)"]
                        [:sh "bun run script.ts"]
                        [:sh "bun script.mjs"]
                        [:sh "bun x cowsay"]
                        [:sh "bunx cowsay"]
                        [:sh "deno eval 1+1"]
                        [:sh "deno run x.ts"]
                        [:sh "deno --allow-all run x.ts"]
                        [:sh "clojure -M:test"]
                        [:sh "clojure -X:build"]
                        [:sh "clj -e (+ 1 2)"]
                        [:sh "clj script.clj"]]]
      (let [rule (rules/first-match defaults/default-rules
                                    {:tool tool :cli (first (str/split cmd #" ")) :command cmd})]
        (is (= :ask (get-in rule [:action :type])) cmd)
        (is (rules/arg-scoped? rule) (str cmd " is command-scoped (clj confirms it per command)")))))
  (testing "no [a]lways, and refused when nobody can answer"
    (let [rule (rules/first-match defaults/default-rules {:tool :sh :cli "bb" :command "bb -f x.clj"})]
      (is (= [:yes :no] (get-in rule [:action :options])))
      (is (= :deny (get-in rule [:action :unanswered])))))
  (testing "task / subcommand modes and version probes don't match"
    (doseq [cmd ["bb test" "bb tasks" "bb run build" "bb lint src/xi"
                 "node --version" "python3 -V" "java -version"
                 "bun test" "bun install" "bun run build" "bun build src/x.ts"
                 "deno task dev" "deno test" "deno fmt"
                 "clojure -Spath" "clj -Sdescribe"]]
      (is (not (script-exec-gated? :sh cmd)) cmd)))
  (testing "only a command-start interpreter matches, not one mentioned in an arg"
    (is (not (script-exec-gated? :sh "grep node src")))
    (is (not (script-exec-gated? :sh "git log -e bb"))))
  (testing "a higher-precedence arg-scoped allow lifts it for that command only"
    (let [allow {:match {:tool :sh :cli "bb" :command #"^bb -f scripts/"} :action {:type :allow}}
          rs    (into [allow] defaults/default-rules)]
      (is (= :allow (get-in (rules/first-match rs {:tool :sh :cli "bb" :command "bb -f scripts/x.clj"})
                            [:action :type])))
      (is (= :ask (get-in (rules/first-match rs {:tool :sh :cli "bb" :command "bb -f /tmp/x.clj"})
                          [:action :type]))))))

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

(deftest localhost-curl
  (let [curl (fn [cmd] (action-type {:tool :sh :cli "curl" :command cmd}))]
    (testing "curl against loopback only is allowed"
      (doseq [cmd ["curl http://localhost:8199/"
                   "curl -sf http://localhost:8199/ -o /dev/null"
                   "curl -s http://127.0.0.1:7474/api/x?a=1"
                   "curl -sS -X POST http://localhost:3000/api"
                   "curl --max-time 5 http://[::1]:8080"
                   "curl http://localhost/a http://127.0.0.1/b"]]
        (is (= :allow (curl cmd)) cmd)))
    (testing "anything else falls to the base ask"
      (doseq [cmd ["curl https://example.com"
                   "curl http://localhost:8199/ http://example.com"
                   "curl http://localhost@evil.com/"
                   "curl http://localhost.evil.com/"
                   "curl -o out.txt http://localhost:8199/"
                   "curl -O http://localhost:8199/x"
                   "curl -d @secrets http://localhost:8199/"
                   "curl -T file http://localhost:8199/"
                   "curl --proxy http://evil:1 http://localhost/"
                   "curl -s"]]
        (is (= :ask (curl cmd)) cmd)))))

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

(deftest rm-of-tracked-content
  (let [matched (fn [req] (rules/first-match defaults/default-rules req))]
    (testing "rm whose operands are all git-tracked hits the arg-scoped allow (before the CLI-wide one)"
      (let [r (matched {:tool :sh :cli "rm" :command "rm -r src" :operands-tracked? true})]
        (is (= :allow (get-in r [:action :type])))
        (is (rules/arg-scoped? r))))
    (testing "a delayed check is forced by the matcher"
      (is (rules/arg-scoped? (matched {:tool :sh :cli "rm" :command "rm a"
                                       :operands-tracked? (delay true)}))))
    (testing "untracked / unchecked operands fall through to the CLI-wide rm allow"
      (doseq [req [{:tool :sh :cli "rm" :command "rm notes.txt" :operands-tracked? false}
                   {:tool :sh :cli "rm" :command "rm x"}]]
        (let [r (matched req)]
          (is (= :allow (get-in r [:action :type])))
          (is (not (rules/arg-scoped? r)) (pr-str req)))))
    (testing "only rm — a tracked operand doesn't lift other CLIs"
      (is (= :ask (action-type {:tool :sh :cli "shred" :command "shred a" :operands-tracked? true}))))))

(deftest chained-bash-is-denied
  (is (= :deny (action-type {:tool :bash :command "ls | head" :chained? true})))
  (is (nil? (action-type {:tool :bash :command "git status" :chained? false})))
  (testing "the /tmp cleanup nudge still wins over the chained deny"
    (is (nudged? {:tool :bash :command "rm -rf /tmp/foo; ls" :chained? true}))))

(deftest bb-trust-and-guards
  (testing "an untrusted bb.edn asks, and is refused when nobody can answer"
    (let [rule (rules/first-match defaults/default-rules
                                  {:tool :bb :command "bb test" :bb-trusted? false})]
      (is (= :ask (get-in rule [:action :type])))
      (is (= :deny (get-in rule [:action :unanswered])))
      (is (= :ext.clj/trust-bb
             (some #(get-in % [:event :type]) (get-in rule [:action :options]))))))
  (testing "a trusted bb.edn runs without a prompt"
    (is (nil? (action-type {:tool :bb :command "bb test" :bb-trusted? true}))))
  (testing "guarded patterns and server control still ask for a trusted bb.edn"
    (is (= :ask (action-type {:tool :bb :command "bb clean rm -rf target" :bb-trusted? true})))
    (is (= :ask (action-type {:tool :bb :command "bb serve:restart" :bb-trusted? true}))))
  (testing "the trust ask comes first, so a narrower ask can't wave an untrusted bb.edn through"
    (is (= :deny (get-in (rules/first-match defaults/default-rules
                                            {:tool :bb :command "bb serve:restart"
                                             :bb-trusted? false})
                         [:action :unanswered])))))

(deftest xi-sessions-readable
  (testing "the xi sessions dir and its files are allowed for read surfaces"
    (doseq [tool [:read :ls :grep :find]
            path ["/home/u/.config/xi/sessions"
                  "/home/u/.config/xi/sessions/"
                  "/home/u/.config/xi/sessions/abc.edn"]]
      (is (= :allow (action-type {:tool tool :path path})) (str tool " " path))))
  (testing "sibling credential files under ~/.config/xi are not covered"
    (is (not= :allow (action-type {:tool :read :path "/home/u/.config/xi/clients.edn"})))
    (is (not= :allow (action-type {:tool :read :path "/home/u/.config/xi/sessions-secret/k"}))))
  (testing "writes are not covered"
    (is (not= :allow (action-type {:tool :write :path "/home/u/.config/xi/sessions/x.edn"}))))
  (testing "user extensions stay denied (extension-credentials sits earlier)"
    (is (= :deny (action-type {:tool :ls :extension true :credential-path? true
                               :path "/home/u/.config/xi/sessions"})))))

(deftest claude-sessions-readable
  (testing "the Claude transcript dir and its files are allowed for read surfaces"
    (doseq [tool [:read :ls :grep :find]
            path ["/home/u/.claude/projects"
                  "/home/u/.claude/projects/-home-u-x/abc.jsonl"]]
      (is (= :allow (action-type {:tool tool :path path})) (str tool " " path))))
  (testing "other ~/.claude paths are not covered"
    (is (not= :allow (action-type {:tool :read :path "/home/u/.claude/settings.json"})))
    (is (not= :allow (action-type {:tool :read :path "/home/u/.claude/projects-old/x"}))))
  (testing "writes are not covered"
    (is (not= :allow (action-type {:tool :write :path "/home/u/.claude/projects/p/x.jsonl"})))))

(deftest defaults-tagged-scope
  (is (every? #(= :default (:scope %)) defaults/default-rules)))

(deftest bundle-aliases-expand
  (testing "the built-in tier is the expansion of the default aliases"
    (is (= defaults/default-rules (defaults/expand defaults/default-aliases)))
    (is (= 34 (count defaults/default-rules))))
  (testing "composites expand to their parts, in order"
    (is (= (defaults/expand [:xi.rules.defaults/sensitive-writes
                             :xi.rules.defaults/protected-writes
                             :xi.rules.defaults/outside-writes])
           (defaults/expand [:xi.rules.defaults/write-gates])))
    (is (= (defaults/expand [:xi.rules.defaults/repository-scripts
                             :xi.rules.defaults/localhost-curl
                             :xi.rules.defaults/sh-read-only
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
      (is (= :ask (get-in (rules/first-match rs {:tool :mcp :mcp-trusted? false}) [:action :type])))
      (is (nil? (rules/first-match rs {:tool :sh :cli "cat"})))))
  (testing "unknown aliases and non-rule entries throw"
    (is (thrown-with-msg? js/Error #"unknown default-rules alias"
          (defaults/expand [:xi.rules.defaults/nope])))
    (is (thrown-with-msg? js/Error #"invalid :defaults entry"
          (defaults/expand ["plan-mode"])))))
