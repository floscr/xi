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

(def ^:private guarded-command-re
  "Destructive / high-blast-radius bash patterns that require confirmation.
   NOTE: the server-control tasks (serve:restart / serve:stop) are handled
   separately by the permission-gate extension (detached run) and are not
   matched here."
  (alt-re ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
           "fs/delete-dir" "fs/delete-tree"
           "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"
           "git push"
           "kill " "kill -" "pkill" "killall"]))

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

;; ── Sandbox mode (OS-level confinement; toggled by xi.ext.sandbox /sandbox) ───

(def ^:private sandbox-on
  "`:when` submap that matches while the room's sandbox flag is set. The sandbox
   extension owns the toggle/badge and the bash executor (bwrap/firejail
   wrapping); these deny-checks live here as data rules."
  {:sandbox {:enabled? true}})

(def ^:private sandbox-bg-bash-re
  "A backgrounded command (`cmd &`) — its child would escape (or be killed with)
   the sandbox wrapper, so it is refused while sandboxed. Matches a trailing `&`
   (optionally followed by whitespace)."
  #"&\s*$")

(def ^:private sandbox-write-msg
  (str "Sandbox is on: writes outside the working directory are blocked. Run "
       "/sandbox off to exit sandbox mode."))

(def ^:private sandbox-read-msg
  (str "Sandbox is on: reading credential paths (.ssh, .gnupg, .password-store, "
       "…) is blocked. Run /sandbox off to exit sandbox mode."))

(def ^:private sandbox-bg-msg
  "Sandbox is on: background processes (`cmd &`) are disabled. Run /sandbox off to exit sandbox mode.")

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
   ;; string instead — the clj sh deny-scan and the bash tool-gate both consult
   ;; the engine with :command, so this blocks the shell bypass regardless of the
   ;; wrapping CLI. `*.pub`/config/known_hosts stay allowed (excluded by regex).
   {:match  {:tool #{:sh :bash} :command hardened-ssh-key-command-re}
    :action {:type :deny :message "Blocked (hardened): reading SSH private keys under ~/.ssh via the shell is never allowed."}
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

(def default-rules
  "The built-in rule set, in precedence order, each tagged :scope :default.
   Behavioral nudges come first so a harmless /tmp `rm` or an auto-memory write
   is steered (not asked) even though it would also match a policy gate below."
  [;; Behavioral nudges (win over the policy gates below on overlap).
   {:match  {:tool :bash :command tmp-rm-re}
    :action {:type :nudge :message tmp-note}
    :scope  :default}
   {:match  {:tool #{:write :edit} :path memory-path-re}
    :action {:type :nudge :message memory-note}
    :scope  :default}
   {:match  {:tool :bash :command memory-path-re}
    :action {:type :nudge :message memory-note}
    :scope  :default}

   ;; Plan mode (read-only): allow the plan file, deny other writes/edits and any
   ;; mutating bash. Placed before the write/bash gates so plan-mode denies win
   ;; over the softer ask gates; reads/grep/find/ls and read-only bash fall
   ;; through. Only active while the room's plan-mode flag is set (:when).
   {:match  {:tool #{:write :edit} :path plan-file :when plan-mode-on}
    :action {:type :allow}
    :scope  :default}
   {:match  {:tool #{:write :edit} :when plan-mode-on}
    :action {:type :deny :message plan-write-msg}
    :scope  :default}
   {:match  {:tool :bash :command plan-mutating-bash-re :when plan-mode-on}
    :action {:type :deny :message plan-bash-msg}
    :scope  :default}

   ;; Sandbox mode (read-only confinement): deny writes outside the working
   ;; tree, reads of credential paths, and backgrounded (`cmd &`) commands. The
   ;; bash executor (bwrap/firejail wrapping) stays in xi.ext.sandbox. Placed
   ;; before the write/bash gates so sandbox denies win over the softer asks;
   ;; only active while the room's sandbox flag is set (:when).
   {:match  {:tool #{:write :edit} :outside :cwd :when sandbox-on}
    :action {:type :deny :message sandbox-write-msg}
    :scope  :default}
   {:match  {:tool #{:read :grep :find :ls} :credential :read :when sandbox-on}
    :action {:type :deny :message sandbox-read-msg}
    :scope  :default}
   {:match  {:tool :bash :command sandbox-bg-bash-re :when sandbox-on}
    :action {:type :deny :message sandbox-bg-msg}
    :scope  :default}

   ;; Write gates: sensitive → protected → outside the working tree.
   {:match  {:tool #{:write :edit} :path sensitive-write-re}
    :action {:type :ask
             :message "Write to a sensitive path (Mail / .ssh / .gnupg / .password-store)?"
             :options [:yes :no]}
    :scope  :default}
   {:match  {:tool #{:write :edit} :path protected-write-re}
    :action {:type :ask
             :message "Write to a protected path (.env / .git/ / node_modules/)?"
             :options [:yes :no]}
    :scope  :default}
   {:match  {:tool #{:write :edit} :outside :cwd}
    :action {:type :ask
             :message "Write outside the project repo?"
             :options [:yes :no :repo]}
    :scope  :default}

   ;; Bash gates: remote shells are blocked outright; destructive patterns ask.
   {:match  {:tool :bash :command remote-shell-re}
    :action {:type :deny
             :message (str "Blocked: remote shell commands (ssh, scp, rsync, "
                           "sftp) are not allowed.")}
    :scope  :default}
   {:match  {:tool :bash :command guarded-command-re}
    :action {:type :ask :message "Guarded command — proceed?" :options [:yes :no]}
    :scope  :default}

   ;; External MCP tools are third-party code — every call is confirmed (with an
   ;; informative server/tool/arguments block built by the rules ext). [a]lways
   ;; persists a session allow-rule narrowed to that mcp server + tool.
   {:match  {:tool :mcp}
    :action {:type :ask :options [:yes :no :always]}
    :scope  :default}

   ;; clj (sh …) shell-outs (:sh) — "disallow * then soften", scoped to :sh so
   ;; the real bash tool is untouched. Read-only/rm CLIs auto-run; every other
   ;; CLI hits the base ask (the clj gate turns that into its per-CLI approval
   ;; flow, and its allowlist/config/session softeners sit ABOVE this default
   ;; tier). sudo/remote-copy are denied earlier by the hardened tier.
   {:match  {:tool :sh :cli sh-autorun-clis}
    :action {:type :allow}
    :scope  :default}
   ;; Read-only `sed -n '<range>p' file…` inside a git repo. A :command-scoped
   ;; allow, so clj grants only that exact literal command — not `sed` at large.
   {:match  {:tool :sh :cli "sed" :command sed-print-re :repo #"."}
    :action {:type :allow}
    :scope  :default}
   {:match  {:tool :sh}
    :action {:type :ask :message "Run this CLI via clj (sh …)?" :options [:yes :no :always]}
    :scope  :default}])
