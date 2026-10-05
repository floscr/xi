(ns xi.rules.defaults
  "Built-in default rules that ship with xi. These sit at the LOWEST precedence
   tier (below repo/global config and runtime rules), so a user rule always
   overrides a default. They express the behavioral nudges and policy gates that
   used to live in dedicated intercept/gate extensions."
  (:require [clojure.string :as str]))

(defn- alt-re
  "A regex matching any of `substrings` as a literal substring (re-find
   semantics), so a list of plain patterns becomes one alternation rule."
  [substrings]
  (re-pattern (str/join "|" (map #(str/replace (str %) #"[.*+?^${}()|\[\]\\/]" "\\$&")
                                 substrings))))

;; ── Sensitive / protected / outside write gates (was xi.ext.permission-gate) ──

(def ^:private sensitive-write-re
  "Credential/secret dirs a write should never touch without confirmation."
  #"/(Mail|\.ssh|\.gnupg|\.password-store)/")

(def ^:private protected-write-re
  "Project files a write should not clobber without confirmation."
  (alt-re [".env" ".git/" "node_modules/"]))

;; ── Guarded / blocked bash commands (was xi.ext.permission-gate) ──────────────

(def ^:private remote-shell-re
  "Remote-shell commands, always blocked."
  (alt-re ["ssh " "scp " "rsync " "sftp "]))

(def guarded-patterns
  "Destructive / high-blast-radius command substrings that require
   confirmation. Public: the clj gate applies the same list to (sh …) argv
   strings."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "fs/delete-dir" "fs/delete-tree"
   "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"
   "git push"
   "kill " "kill -" "pkill" "killall"])

(def ^:private guarded-command-re
  "`guarded-patterns` as one alternation regex."
  (alt-re guarded-patterns))

(def ^:private chained-bash-msg
  (str "bash: chained/piped shell commands are disabled — rewrite "
       "this with the clj tool (sandboxed Clojure REPL): (cat f) "
       "(glob …) (grep re path) (sh \"cmd\" \"arg\" …); compute "
       "in-script and return small values. Bash remains available "
       "for single simple commands."))

;; ── Server control (xi.server-control) ────────────────────────────────────────

(def ^:private server-control-re
  "`bb serve:restart` / `serve:stop` — they kill the server hosting this agent
   (the executors run them detached, see xi.server-control). Same substrings as
   xi.server-control/kind."
  #"serve:(?:restart|stop)")

;; ── Script / inline-code execution (script-exec) ─────────────────────────────
;;
;; Every pattern is anchored at the command's first token (an optional path
;; prefix is allowed), which is the binary for a clj `(sh …)` / bash command and
;; `bb` for the bb tool. They run over the space-joined command line, and the
;; clj gate matches each literal `(sh …)` / background command on its own.

(def script-exec-res
  "Command lines that execute code the gate can't see: inline code (`-e`,
   `--eval`, `-c`, `-p`) or a script file, for the usual scripting runtimes. The
   first group (plain interpreters) treats any invocation but a bare
   version/help flag as code execution; the rest (bb, bun, deno, clojure) have
   task / subcommand modes that stay unmatched, e.g. `bb test`, `bun run build`,
   `deno task dev`, `bun test`. Public: tests and docs share the list."
  [;; Plain interpreters — any run is code execution, `python -m x` and
   ;; `java -jar x` included.
   #"^\s*(?:\S*/)?(?:node(?:js)?|python|pypy|ruby|perl|php|lua|luajit|Rscript|elixir|julia|sbcl|guile|racket|nbb|tsx|ts-node|java|jshell|groovy)(?:\d+(?:\.\d+)*)?(?=\s|$)(?!\s+(?:-v|-V|--version|-version|--help|-h)\s*$)"
   ;; babashka — eval/file/stdin-expression/main/exec flags, or a script operand.
   ;; `bb <task>` stays free: its code is the trusted bb.edn.
   #"^\s*(?:\S*/)?bb(?=\s)(?:.*\s(?:-e|--eval|-f|--file|-i|-I|-o|-O|-m|--main|-x|--exec|--init|--repl|--nrepl-server|--socket-repl)(?=[\s=]|$)|.*\s\S*\.(?:clj[cs]?|bb)(?=\s|$)|.*\s(?:\.{1,2}/|~/|/)\S*)"
   ;; bun — eval/print flags, x / repl / exec, or a script operand. Package
   ;; management, `bun test`, `bun build` and `bun run <package script>` stay free.
   #"^\s*(?:\S*/)?bun(?=\s)(?!\s+(?:test|install|i|add|remove|rm|update|pm|build|outdated|audit|info|why|-v|--version|--help|-h)(?=\s|$))(?:\s+(?:x|repl|exec)(?=\s|$)|.*\s(?:-e|--eval|-p|--print)(?=[\s=]|$)|.*\s(?:\S*\.[cm]?[jt]sx?|(?:\.{1,2}/|~/|/)\S*)(?=\s|$))"
   ;; bunx runs a package binary fetched on the fly.
   #"^\s*(?:\S*/)?bunx(?=\s|$)"
   ;; deno — eval / run / repl / serve / x; `deno task|test|fmt|lint|check` stay free.
   #"^\s*(?:\S*/)?deno(?:\s+-\S+)*\s+(?:eval|run|repl|serve|x)(?=\s|$)"
   ;; Clojure CLI — -M / -X / -T (run a main / fn / tool), -e, -i, -m, -r, or a script.
   #"^\s*(?:\S*/)?(?:clojure|clj)(?=\s)(?:.*\s(?:-[MXT]|-e|--eval|-i|--init|-m|--main|-r|--repl)(?=[\s:=]|$)|.*\s\S+\.clj[cs]?(?=\s|$))"])

(def ^:private script-exec-action
  {:type        :ask
   :message     "Runs a script or inline code through an interpreter — proceed?"
   ;; No [a]lways: a session grant would be a CLI-wide allow, which lifts the
   ;; gate for every later script of that interpreter, not just this one.
   :options     [:yes :no]
   ;; Headless (`xi prompt`, sub-agents): refuse rather than run unseen code.
   :unanswered  :deny})

;; ── /tmp cleanup nudge (was xi.ext.tmp-cleanup-intercept) ────────────────────

(def ^:private tmp-rm-re
  "An `rm` invocation targeting a /tmp path within the same command segment
   (no &&, ||, ;, |, or newline between `rm` and /tmp). Handles flags/quotes."
  #"\brm\b[^\n&|;]*?/tmp(?:/|\b)")

(def ^:private tmp-note
  (str "Skipped: `/tmp` is a temporary filesystem on this machine (cleared on "
       "restart), so there's no need to clean up files under `/tmp`. The `rm` "
       "was not run. Leave temp files in place — they're removed automatically."))

;; ── Auto-memory write nudge (was xi.ext.memory-intercept) ────────────────────

(def ^:private memory-path-re
  "A path inside a `.claude/projects/<…>/memory/` auto-memory dir (also the
   bare `<…>/memory/MEMORY.md` index)."
  #"\.claude/projects/[^\n]*?/memory(?:/|\b)")

(def ^:private memory-note
  (str "Skipped: xi does not use Claude's auto-memory feature. Writes to the "
       "auto-memory directory (~/.claude/projects/<cwd>/memory/) are blocked "
       "and were not applied. Don't save to memory — that store is disabled."))

;; ── Xi session metadata (readable despite the hidden ~/.config/xi dir) ───────

(def ^:private xi-sessions-re
  "The xi session-metadata dir `~/.config/xi/sessions` and anything under it.
   Matched against the resolved absolute path (see `:path` in docs/rules.md)."
  #"/\.config/xi/sessions(?:/|$)")

(def ^:private claude-sessions-re
  "The Claude CLI transcript dir `~/.claude/projects` and anything under it.
   Matched against the resolved absolute path (see `:path` in docs/rules.md)."
  #"/\.claude/projects(?:/|$)")

;; ── Plan mode (read-only exploration; toggled by xi.ext.plan-mode /plan) ──────

(def ^:private plan-mode-on
  "`:when` submap that matches while the room's plan-mode flag is set. The
   plan-mode extension owns the toggle/badge and stores this flag; the read-only
   policy lives here as data rules."
  {:plan-mode {:enabled? true}})

(def ^:private plan-file
  "The one path plan mode still lets the agent write — its scratch/plan file."
  "tasks/todo.md")

(def ^:private plan-mutating-bash-re
  "Bash patterns treated as mutations while plan mode is on (everything else,
   incl. read-only bash, passes through). Mirrors the old plan-mode gate."
  (alt-re ["rm " "mv " "cp " "chmod " "chown " "kill " "pkill "
           "sudo " "dd " "> " ">> " "tee " "truncate "]))

(def ^:private plan-write-msg
  (str "Plan mode is on (read-only exploration). Only " plan-file " may be "
       "written — this write was blocked. Run /plan to exit plan mode."))

(def ^:private plan-bash-msg
  (str "Plan mode is on (read-only exploration). This bash command looks like a "
       "mutation and was blocked. Run /plan to exit plan mode."))

;; ── Hardened tier (prepended above all config/runtime rules; flag-removable) ───

(def ^:private hardened-remote-clis
  "Remote-copy shells refused via clj `(sh …)`. `ssh` is intentionally excluded —
   it falls through to the per-CLI ask flow, matching the legacy clj gate."
  #{"scp" "rsync" "sftp"})

(def ^:private hardened-remote-bash-re
  #"\b(?:scp|rsync|sftp)\b")

(def ^:private hardened-shell-clis
  "Interactive/login shell interpreters that clj `(sh …)` must never invoke.
   Running a shell (`bash -lc \"grep … | grep … | head\"`, `sh -c …`, `zsh`, …)
   smuggles a whole pipeline/shell string past clj's argv-only model, defeating
   its per-command policy scan (the engine only ever sees `:cli` \"bash\" and one
   opaque `:command` blob it can't reason about). Blocked outright — commands go
   argv-style through `(sh \"cmd\" \"arg\" …)` and pipelines compose in Clojure."
  #{"bash" "sh" "zsh" "fish" "dash" "ksh" "csh" "tcsh" "ash" "mksh"})

(def ^:private hardened-sudo-re
  #"\bsudo\b")

(def ^:private hardened-ssh-key-re
  "Private SSH key files under ~/.ssh: any file there that is not a `.pub`
   public key or the non-secret config/known_hosts/authorized_keys/environment.
   Read-blocked outright (hardened, non-overridable) — no allow button. Matches
   the final path segment at any depth under a `.ssh/` dir so id_rsa,
   id_ed25519, and custom-named keys are all caught while `*.pub` stays readable."
  #"(?:^|/)\.ssh/(?:[^/]*/)*(?!(?:known_hosts|authorized_keys|config|environment)[^/]*$)(?![^/]*\.pub$)[^/]+$")

(def ^:private hardened-ssh-key-command-re
  "Same private-key detection as `hardened-ssh-key-re`, but tuned to find the key
   path *inside a shell command string* (segments bounded by whitespace/quotes,
   not anchored at end-of-string). Catches `cat ~/.ssh/id_rsa`,
   `head -c9 ~/.ssh/id_ed25519`, `base64 ~/.ssh/key`, `cp ~/.ssh/id_rsa /tmp/x`,
   etc. regardless of the wrapping CLI — closes the shell bypass that structured
   :read guards can't see (a path handed to `sh cat` is an opaque arg). `*.pub`
   and config/known_hosts/authorized_keys/environment stay allowed."
  #"\.ssh/(?:[^\s'\"/]+/)*(?!(?:known_hosts|authorized_keys|config|environment)(?:[\s'\"]|$))(?![^\s'\"/]*\.pub(?:[\s'\"]|$))[^\s'\"/]+")

(def hardened-rules
  "Non-overridable policy rules, tagged :scope :hardened. The store prepends
   these ABOVE every config/runtime/default rule, so they always win — a user
   allow-rule can never soften them. They are removable ONLY via a launch-time
   CLI flag (never by the agent, which cannot relaunch the process). This is the
   engine-resident home for what used to be clj's private hard-blocks.

   NOTE: the rules-file-write protection stays imperative in `store/hard-block`
   (root-of-trust — not even flag-removable), and runs before this tier."
  [;; sudo: never allowed from the agent, for both the bash tool and clj sh-outs
   ;; (both carry :command).
   {:match  {:tool #{:sh :bash} :command hardened-sudo-re}
    :action {:type :deny :message "Blocked (hardened): sudo is never allowed from the agent."}
    :scope  :hardened}
   ;; Remote-copy shells: clj sh-outs match by :cli (binary), the bash tool by
   ;; :command (bash reqs carry no :cli). ssh is excluded on purpose.
   {:match  {:tool :sh :cli hardened-remote-clis}
    :action {:type :deny :message "Blocked (hardened): remote shell commands (scp, rsync, sftp) are not allowed."}
    :scope  :hardened}
   {:match  {:tool :bash :command hardened-remote-bash-re}
    :action {:type :deny :message "Blocked (hardened): remote shell commands (scp, rsync, sftp) are not allowed."}
    :scope  :hardened}
   ;; Shell interpreters via clj `(sh …)`: `(sh "bash" "-lc" "… | … | head")`
   ;; runs a login/interactive shell to smuggle a pipeline past clj's argv-only
   ;; model — the engine only sees :cli "bash" and one opaque :command blob, so
   ;; the per-command scan can't reason about what actually runs. Denied so
   ;; commands go argv-style through (sh "cmd" "arg" …) and pipelines compose in
   ;; Clojure (or the builtin helpers: (grep …), (curl …), (jq …), …). Matches
   ;; by :cli (binary), so the real bash tool (:tool :bash) is untouched.
   {:match  {:tool :sh :cli hardened-shell-clis}
    :action {:type :deny
             :message (str "Blocked (hardened): running a shell interpreter "
                           "(bash, sh, zsh, …) via clj (sh …) is not allowed — "
                           "it smuggles a pipeline/shell string past the "
                           "argv-only model. Run commands argv-style, one per "
                           "(sh \"cmd\" \"arg\" …) call, and compose pipelines in "
                           "Clojure or via the builtin helpers ((grep …), "
                           "(curl …), (jq …), …).")}
    :scope  :hardened}
   ;; SSH private keys under ~/.ssh: never readable by any read surface (the
   ;; built-in read/grep/find/ls tools and clj's cat/grep, which all consult
   ;; the engine as :read/:grep/…). Hardened → not clickable-through; the softer
   ;; sensitive-path ask below can only add friction, never grant access here.
   ;; `*.pub` and config/known_hosts stay readable (excluded by the regex).
   {:match  {:tool #{:read :grep :find :ls} :path hardened-ssh-key-re}
    :action {:type :deny :message "Blocked (hardened): reading SSH private keys under ~/.ssh is never allowed."}
    :scope  :hardened}
   ;; SSH private keys via the shell: a `(sh "cat" "~/.ssh/id_rsa")` / bash
   ;; `cat ~/.ssh/id_rsa` names the key as an opaque command arg, so the
   ;; structured :read guard above never sees it and read-only CLIs (cat, head,
   ;; base64, …) auto-run without a prompt. Match the key path inside the command
   ;; string instead — the clj sh deny-scan and the bash tool policy both consult
   ;; the engine with :command, so this blocks the shell bypass regardless of the
   ;; wrapping CLI. `*.pub`/config/known_hosts stay allowed (excluded by regex).
   {:match  {:tool #{:sh :bash} :command hardened-ssh-key-command-re}
    :action {:type :deny :message "Blocked (hardened): reading SSH private keys under ~/.ssh via the shell is never allowed."}
    :scope  :hardened}
   ;; xi rules files anywhere else (e.g. a dotfiles source that gets copied to
   ;; ~/.config/xi/rules.edn): any change is confirmed, every time. The
   ;; canonical locations are already hard-blocked (store/hard-block); this
   ;; covers the rest. A `rules.edn` only counts when it carries `:version`, so
   ;; unrelated tools' rules.edn files stay untouched. Hardened + no [a]lways,
   ;; so neither a session grant nor a config allow can skip the prompt — the
   ;; [r] repo grant covers the repo's other files (it lands in the session
   ;; tier, below this rule), the rules file itself keeps asking.
   {:match  {:tool #{:write :edit :bash :clj} :xi-rules-file true}
    :action {:type    :ask
             :message "Change an xi rules file (a rules.edn with :version — permission policy)?"
             :options [:yes :no :repo]}
    :scope  :hardened}])

;; ── clj (sh …) softeners: "disallow * then soften", scoped to :sh ────────────

(def sh-autorun-clis
  "Read-only CLIs (plus `rm`) that clj `(sh …)` may run without approval — the
   engine home for clj's SAFE_AUTORUN set (which stays the source of truth in
   xi.ext.clj and must be kept in sync). `git`/`ss` are included for their
   read-only uses; their escalations (git push/clean, ss -K/-D) are detected by
   precise argv parsing in the clj gate and routed to approval there — not
   expressible as a safe command regex."
  #{"ls" "cat" "head" "tail" "grep" "rg" "find" "fd" "pwd" "echo" "mktemp"
    "stat" "du" "readlink" "realpath" "which" "basename" "dirname" "date"
    "wc" "sort" "uniq" "cut" "tr" "git" "rm" "ss" "netstat" "lsof"})

(def sed-print-re
  "The read-only `sed -n '<addr>p' file…` idiom (a line-range / pattern print,
   e.g. `sed -n 3060,3420p src/foo.cljs`), matched against clj's space-joined
   literal (sh …) args. The script may only be `p` commands over line-number,
   `$`, or `/re/` addresses (`;`-separated), and every trailing arg must be a
   non-flag operand — so `-i`/`--in-place`, and sed's writing (`w`) or
   executing (`e`, `s///e`) commands never match and stay gated."
  #"^sed -n (?:(?:\d+|\$|/[^/\s]*/)(?:,(?:\d+|\$|/[^/\s]*/))?p;?)+(?: [^\s-]\S*)+$")

(def localhost-curl-re
  "A `curl` whose every URL is loopback (localhost / 127.0.0.1 / [::1], any
   port), matched against clj's space-joined literal (sh …) args — for poking
   dev servers. Every token must be a loopback URL or an allowlisted flag:
   silent/fail/verbose-style switches, `-m N`, `-X <METHOD>`, `-o /dev/null`.
   File-writing/reading (`-o file`, `-O`, `-T`, `-K`, `-d @file`), `-H`, proxies
   and any non-loopback URL never match and stay gated. The URL must end its
   token right after the host/port/path, so `http://localhost@evil.com` and
   `http://localhost.evil.com` don't pass."
  (let [url  "https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::\\d+)?(?:[/?#]\\S*)?(?= |$)"
        flag (str "-[sSfLvikIg]+(?= |$)"
                  "|--(?:silent|show-error|fail|location|verbose|include|head|insecure)(?= |$)"
                  "|(?:-m|--max-time) \\d+(?= |$)"
                  "|(?:-X|--request) (?:GET|POST|PUT|PATCH|DELETE|HEAD)(?= |$)"
                  "|(?:-o|--output) /dev/null(?= |$)")]
    (re-pattern (str "^curl(?: (?:" flag "))*(?: (?:" url ")(?: (?:" flag "|" url "))*)$"))))

(def sh-repo-file-clis
  "File-management CLIs clj `(sh …)` may run without approval when every
   operand stays inside the git repo (or tmp) — see the `:within :repo`
   default rule. Deliberately absent: `ln` (a relative link target resolves
   against the link's dir, not cwd, so the operand check can't vouch for it)
   and `chmod` (a permission change isn't a content change — `+x` turns a
   written file into something runnable, `u+s`/`o+r` escalate or expose —
   so it stays confirmed even inside the repo)."
  #{"mv" "cp" "mkdir" "touch" "rmdir"})

(def bundles
  "Named default-rule bundles, keyed by alias (`:xi.rules.defaults/<name>`).
   A bundle is a vector of rule maps and/or other aliases (composites), expanded
   in order by `expand`. Rules are first-match-wins, so the ORDER bundles are
   listed in (see `default-aliases`) is their precedence. A rules file's
   `:defaults` vector composes these to replace the built-in default tier."
  {;; Behavioral nudges (listed first so they win over the policy gates on
   ;; overlap — a harmless /tmp `rm` is steered, not asked).
   ::tmp-cleanup
   [{:match  {:tool :bash :command tmp-rm-re}
     :action {:type :nudge :message tmp-note}}]

   ::no-auto-memory
   [{:match  {:tool #{:write :edit} :path memory-path-re}
     :action {:type :nudge :message memory-note}}
    {:match  {:tool :bash :command memory-path-re}
     :action {:type :nudge :message memory-note}}]

   ;; A user extension never touches credential paths (.ssh, .gnupg,
   ;; .config/xi — client keys + ext/*.env secrets — .netrc, …; see
   ;; xi.paths/HIDDEN_PATHS): reading one and sending it out through an
   ;; approved host would leak it.
   ::extension-credentials
   [{:match  {:tool #{:read :ls :write :edit} :extension true :credential :read}
     :action {:type :deny
              :message "Blocked: extensions may not read or write credential paths."}}]

   ;; A user extension (xi.api.fs) reads/writes its own data dir freely —
   ;; before plan-mode and the write gates: the dir is outside every repo, and
   ;; plan mode restricts the agent's work, not an extension's own state.
   ::extension-data
   [{:match  {:tool #{:read :write :edit :ls} :extension true :extension-data :own}
     :action {:type :allow}}]

   ;; Xi's own session metadata (~/.config/xi/sessions) is readable. It sits
   ;; under the hidden ~/.config/xi credential dir (client keys, ext secrets),
   ;; so clj's `(ls …)`/`(cat …)` would otherwise be hard-blocked there — an
   ;; engine :allow on the read is what lifts that block, for this subtree
   ;; only. Listed after extension-credentials, so user extensions stay denied.
   ::xi-sessions
   [{:match  {:tool #{:read :ls :grep :find} :path xi-sessions-re}
     :action {:type :allow}}]

   ;; Claude CLI transcripts (~/.claude/projects) — where xi keeps session
   ;; transcripts. Outside every repo, so reads would otherwise ask each time.
   ;; Read surfaces only; writes there stay gated (no-auto-memory, outside-writes).
   ::claude-sessions
   [{:match  {:tool #{:read :ls :grep :find} :path claude-sessions-re}
     :action {:type :allow}}]

   ;; Plan mode (read-only): allow the plan file, deny other writes/edits and any
   ;; mutating bash. Must precede the write/bash gates so plan-mode denies win
   ;; over the softer ask gates; reads/grep/find/ls and read-only bash fall
   ;; through. Only active while the room's plan-mode flag is set (:when).
   ::plan-mode
   [{:match  {:tool #{:write :edit} :path plan-file :when plan-mode-on}
     :action {:type :allow}}
    {:match  {:tool #{:write :edit} :when plan-mode-on}
     :action {:type :deny :message plan-write-msg}}
    {:match  {:tool :bash :command plan-mutating-bash-re :when plan-mode-on}
     :action {:type :deny :message plan-bash-msg}}]

   ;; Write gates: sensitive → protected → outside the working tree.
   ::sensitive-writes
   [{:match  {:tool #{:write :edit} :path sensitive-write-re}
     :action {:type :ask
              :message "Write to a sensitive path (Mail / .ssh / .gnupg / .password-store)?"
              :options [:yes :no]}}]

   ::protected-writes
   [{:match  {:tool #{:write :edit} :path protected-write-re}
     :action {:type :ask
              :message "Write to a protected path (.env / .git/ / node_modules/)?"
              :options [:yes :no]}}]

   ::outside-writes
   [{:match  {:tool #{:write :edit} :outside :cwd}
     :action {:type :ask
              :message "Write outside the project repo?"
              :options [:yes :no :repo]}}]

   ::write-gates
   [::sensitive-writes ::protected-writes ::outside-writes]

   ;; Chained/piped bash is refused: those one-liners are what the clj tool
   ;; replaces. bash is hidden from the model while the clj extension is on, so
   ;; this only bites where bash is still offered (drop the alias, or add an
   ;; allow rule, to run without clj).
   ::bash-chained
   [{:match  {:tool :bash :chained true}
     :action {:type :deny :message chained-bash-msg}}]

   ;; bb tasks run bb.edn's code: an untrusted bb.edn (sha not in the trust
   ;; store, xi.bb-trust) asks first — and is refused when nobody can answer.
   ;; [a]lways trusts that bb.edn (content-addressed, so an edit re-asks)
   ;; instead of saving a session rule. Listed before the guards so an
   ;; untrusted bb.edn is never waved through by a narrower ask.
   ::bb-trust
   [{:match  {:tool :bb :bb-trusted false}
     :action {:type :ask
              :options [:yes :no
                        {:value :trust-bb :key "a" :label "Always (trust bb.edn)"
                         :resolved-label "bb.edn trusted"
                         :event {:type :ext.clj/trust-bb}}]
              :unanswered :deny}}]

   ;; Bash gates: remote shells are blocked outright; destructive patterns ask
   ;; (for bb task command lines too).
   ::bash-guards
   [{:match  {:tool :bash :command remote-shell-re}
     :action {:type :deny
              :message (str "Blocked: remote shell commands (ssh, scp, rsync, "
                            "sftp) are not allowed.")}}
    {:match  {:tool #{:bash :bb} :command guarded-command-re}
     :action {:type :ask :message "Guarded command — proceed?" :options [:yes :no]}}]

   ;; Restarting/stopping the server drops every connected session — always
   ;; ask (bash, the bb tool, and clj (sh …) alike). The executors run an
   ;; approved one detached so the agent's own turn gets a result.
   ::server-control
   [{:match  {:tool #{:bash :bb :sh} :command server-control-re}
     :action {:type :ask :options [:yes :no]}}]

   ;; External MCP servers are third-party code: a call to a server that isn't
   ;; trusted is confirmed (with an informative server/tool/arguments block
   ;; built by the rules ext). [a]lways trusts the server (xi.mcp.trust), so
   ;; its calls — the agent's and user extensions' — run without asking until
   ;; its code or mcp.edn entry changes.
   ::mcp-confirm
   [{:match  {:tool :mcp :mcp-trusted false}
     :action {:type :ask
              :options [:yes :no
                        {:value :trust-mcp :key "a" :label "Always (trust this server)"
                         :resolved-label "MCP server trusted"
                         :event {:type :mcp/trust}}]}}]

   ;; A sub-agent runs a whole unattended agent turn — every spawn is confirmed
   ;; (the dialog shows the task). [a]lways persists a session allow-rule
   ;; pinned to spawn_subagent.
   ::subagent-confirm
   [{:match  {:tool-name "spawn_subagent"}
     :action {:type :ask :options [:yes :no :always]}}]

   ;; Shell-outs from user extensions (xi.api.sh) ask for every command —
   ;; placed before clj-sh on purpose: its read-only auto-run list is only safe
   ;; with clj's own confinement (confined `rm` helper, git push/clean
   ;; escalation, worker path limits), which a real extension spawn doesn't
   ;; have. [a]lways pins the exact command to that extension.
   ::extension-sh
   [{:match  {:tool :sh :extension true}
     :action {:type :ask :options [:yes :no :always]}}]

   ;; Network requests from user extensions (xi.api.http) — every host asks;
   ;; [a]lways persists a session allow-rule pinned to extension + host.
   ::net-confirm
   [{:match  {:tool :net}
     :action {:type :ask :options [:yes :no :always]}}]

   ;; Headless-Chrome visits from user extensions (xi.api.chrome), already
   ;; limited to the hosts the extension declares — each host still asks;
   ;; [a]lways persists a session allow-rule pinned to extension + host.
   ::browser-confirm
   [{:match  {:tool :browser}
     :action {:type :ask :options [:yes :no :always]}}]

   ;; Interpreters running inline code or a script file (`bb -f x.clj`,
   ;; `node -e …`, `python x.py`, `bun run x.ts`, …) — the interpreter reads the
   ;; script itself, so neither the read/write gates nor the path guards ever see
   ;; what it does (e.g. `slurp` of a config file). Command-scoped, so clj asks
   ;; per exact command even when the CLI is allowlisted / `bb` is trusted.
   ;; Placed after extension-sh (an extension's own shell-outs keep that rule's
   ;; [a]lways) and before clj-sh. A higher-precedence `:allow` — config rule,
   ;; `/clj allow <cli>` — lifts it, so prefer arg-scoped allows.
   ::script-exec
   (mapv (fn [re] {:match  {:tool #{:bash :sh :bb} :command re}
                   :action script-exec-action})
         script-exec-res)

   ;; clj (sh …) shell-outs (:sh) — "disallow * then soften", scoped to :sh so
   ;; the real bash tool is untouched. Read-only/rm CLIs auto-run; every other
   ;; CLI hits the base ask (the clj gate turns that into its per-CLI approval
   ;; flow, and its allowlist/config/session softeners sit ABOVE this default
   ;; tier). sudo/remote-copy are denied earlier by the hardened tier.
   ::sh-read-only
   [{:match  {:tool :sh :cli sh-autorun-clis}
     :action {:type :allow}}]

   ;; Repo-local shell-outs, each arg-scoped (clj grants only the exact literal
   ;; command, never the binary at large): read-only `sed -n '<range>p' file…`
   ;; inside a git repo, file management (mv/cp/mkdir/…) whose every operand
   ;; resolves inside the repo (never .git/) or tmp, and `rm` of git-tracked
   ;; content (a file in the index, or a directory holding only indexed files
   ;; — recoverable from git). The rm rule must sit BEFORE sh-read-only's
   ;; CLI-wide `rm` allow: (sh "rm" …) auto-runs either way, but the clj
   ;; builtin (rm dir) lifts its recursive-delete confirm only on an
   ;; arg-scoped allow, which first-match-wins would otherwise never reach.
   ::repository-scripts
   [{:match  {:tool :sh :cli "sed" :command sed-print-re :repo #"."}
     :action {:type :allow}}
    {:match  {:tool :sh :cli sh-repo-file-clis :within :repo}
     :action {:type :allow}}
    {:match  {:tool :sh :cli "rm" :tracked :git}
     :action {:type :allow}}]

   ;; `curl` against loopback only (dev servers) — arg-scoped like the repo
   ;; scripts above; a non-loopback URL or a file-touching flag falls through
   ;; to sh-confirm.
   ::localhost-curl
   [{:match  {:tool :sh :cli "curl" :command localhost-curl-re}
     :action {:type :allow}}]

   ::sh-confirm
   [{:match  {:tool :sh}
     :action {:type :ask :message "Run this CLI via clj (sh …)?" :options [:yes :no :always]}}]

   ::clj-sh
   [::repository-scripts ::localhost-curl ::sh-read-only ::sh-confirm]})

(defn expand
  "Expand a `:defaults` vector — bundle aliases (recursively, so composites
   work) and inline rule maps, in order — into a flat rule vector tagged
   :scope :default. Throws ex-info on an unknown alias or a non-rule entry."
  [entries]
  (letfn [(step [e]
            (cond
              (map? e)     [(assoc e :scope :default)]
              (keyword? e) (if-let [b (get bundles e)]
                             (mapcat step b)
                             (throw (ex-info (str "unknown default-rules alias " e)
                                             {:alias e})))
              :else        (throw (ex-info (str "invalid :defaults entry " (pr-str e)
                                                " (expected an alias keyword or a rule map)")
                                           {:entry e}))))]
    (vec (mapcat step entries))))

(def default-aliases
  "The built-in default tier, as bundle aliases in precedence order. A rules
   file's `:defaults` replaces this vector."
  [::tmp-cleanup
   ::no-auto-memory
   ::extension-credentials
   ::extension-data
   ::xi-sessions
   ::claude-sessions
   ::plan-mode
   ::write-gates
   ::bash-chained
   ::bb-trust
   ::bash-guards
   ::server-control
   ::mcp-confirm
   ::subagent-confirm
   ::extension-sh
   ::net-confirm
   ::browser-confirm
   ::script-exec
   ::clj-sh])

(def default-rules
  "The built-in rule set (`default-aliases` expanded), in precedence order, each
   tagged :scope :default."
  (expand default-aliases))
