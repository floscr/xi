# Rules Engine

A declarative, config-driven policy layer that subsumes every **policy gate**
(allow / deny / nudge / confirm) in the codebase, plus tree-sitter node
targeting. Everything is covered **except server-restart/stop**, which stays as
the existing immutable special-case handler (`pg/ask-server-control`).

## Goal

- One data-driven rules store replaces the scattered hardcoded policy gates.
- "Always allow" (and allow-repo) answers *write a rule* instead of ad-hoc
  allowlist sets — configured rules then override those runtime grants.
- Rules can match on tool, file path/glob/regex, bash command, cwd/repo/dir,
  room state (mode flags), MCP tool name, **and tree-sitter AST nodes**.
- Guard dialogs get a "recommend a rule" option → sub-agent → editable
  rule-recommendation UI block (scope-switchable) → user saves.
- Agents can NEVER write the rules files (hard-coded, non-overridable deny).

## Rule schema (final)

```clojure
{:match  {:tool     #{:write :edit}        ; keyword or set; read/grep/find/ls/bash/mcp too
          :path     #"\.sh$"               ; regex OR glob string on the target path
          :command  #"\brm\b.*/tmp"        ; regex on the bash command
          :repo     "config/dotfiles"      ; git-root path suffix / remote basename
          :dir      "~/code/projects"       ; absolute path prefix
          :mcp-server "context7"           ; MCP server id (tool = mcp__<server>__<tool>)
          :mcp-tool   "*"                   ; MCP tool name/glob
          :when     {:mode :plan}          ; predicate on room ext state (mode toggles)
          :node     {:type "function_definition"  ; tree-sitter AST predicate (opt-in)
                     :name #"^gate-"
                     :contains #"println"}}
 :action {:type    :nudge                  ; :allow | :deny | :nudge | :ask
          :message "write babashka scripts only"
          :options [:yes :no :always]}     ; for :ask; :always/:allow-repo persist a rule
 :scope  :repo}                            ; provenance, set by loader/writer
```

- `:on-block`/`:do` accepted as aliases for `:match`/`:action` (matches the
  notation from the feature request).
- `:action :type`:
  - `:allow` → short-circuit allow (skips remaining gates via new signal).
  - `:deny`  → `{:intercepted true :result {is-error true :text message}}`.
  - `:nudge` → `{:intercepted true :result {is-error false :text message}}`.
  - `:ask`   → raise `confirm!` with `:options`; `:always`/`:allow-repo`
               dispatch a rule-add at the chosen scope.
- All `:match` keys are ANDed. Absent key = no constraint.

### Effective working directory (`:dir` opt)

`sh` / `process/start` / `process/poll-until` accept a bb-style leading opts
map with `:dir` — the directory to run in, relative to the room cwd, absolute,
or `~`-prefixed (e.g. `(sh {:dir "sub"} "bb" "build")`). This changes what
the matcher must treat as the command's location:

- Normalization resolves the **effective cwd** = the command's `:dir` when
  present, else the room cwd. `:repo` / `:dir` / `:node` matching resolves
  against the effective cwd, NOT just the room cwd — so a rule scoped to a repo
  still matches a command that `:dir`s into that repo, and a rule scoped
  elsewhere doesn't leak across a `:dir` hop.
- The `:dir` path is **gated as a read** (cd'ing in is at least a read). clj's
  scanner already records it into `:reads` as `{:head 'sh :path <dir>}`, so a
  read rule like "allow reads from `~/code/projects`" must also cover the
  `:dir` of an sh/process command — the rules store handles both via the same
  read-family matching.

## Scopes & precedence (first match wins, top → bottom)

1. **hard-coded immutable** — never write rules files, blocked secret paths.
   Not user-editable, cannot be overridden.
2. **repo config** — `<repo>/.xi/rules.edn`
3. **global config** — `~/.config/xi/rules.edn`
4. **server-session** — process-local `[:ext :rules]`, cleared on restart
5. **session runtime** — room-scoped `[:rooms rid :ext :rules]`, mirrors to
   clients, persists with the session

Config (2–3) above runtime (4–5) so a configured rule overrides a careless
"always allow". Repo above global (more specific wins).

## Gate protocol change

Add one short-circuit token to `xi.ext.core/tool-gate`: a gate may return
`{::core/allow tool-call}` → reducer stops, unwraps, returns the tool-call,
skipping remaining gates. Keeps every existing gate untouched. deny/nudge reuse
the existing `:intercepted` path.

## Phases

### Phase 1 — engine core ✅
- [x] `xi.rules` — schema, normalize tool-call → decision request
      `{:tool :path :command :cwd :repo :mcp-server :mcp-tool}`, matcher
      (path/command/repo/dir/tool/when/node + `:on-block`/`:do` aliases).
- [x] `xi.rules.store` — load + cache global (`~/.config/xi/rules.edn`) and repo
      (`<repo>/.xi/rules.edn`) EDN; merge with runtime scopes from state;
      imperative hard-block; decision-request normalization (effective-cwd,
      repo, mcp parse); ordered ruleset in precedence order.
- [x] `::core/allow` signal in `xi.ext.core/tool-gate` (+ `ext/allow` helper).
- [x] `xi.ext.rules` extension (FIRST in `config/server`): tool-gate
      (hard-block → first-match → apply :allow/:deny/:nudge/:ask) +
      `/rules list|reload` command + `:ext.rules/add` handler. Hard-gate
      denying writes to the rules files lives in the store (immutable scope 1).
- [x] `docs/rules.md` — schema + scopes + examples (used by recommend sub-agent).
- [x] Tests: matcher + precedence (`xi.rules-test`), hard-gate + normalization
      (`xi.rules.store-test`), action application + allow-short-circuit +
      hard-block-before-rules + runtime add (`xi.ext.rules-test`).

  Note: `/rules add|rm` were dropped from Phase 1 — runtime rules are added via
  `:ext.rules/add` (from `:always` answers and, later, the recommend flow);
  file rules are edited directly. A `/rules add|rm` CLI can come later if wanted.

### Phase 2 — unify "always allow" + port nudges ✅
- [x] Port `tmp-cleanup-intercept` → default nudge rule; delete extension.
- [x] Port `memory-intercept` → default nudge rule; delete extension.
      Both now live in `xi.rules.defaults/default-rules` (scope :default, lowest
      precedence, appended by `store/ordered-rules`); extensions + their tests
      deleted and unregistered from `config/server`. Tests in
      `xi.rules.defaults-test`.
- [x] Rules-gate `:ask` `:always` dispatches `:ext.rules/add` (session scope);
      `allow-rule-from-req` now builds a narrow allow-rule (MCP → server+tool,
      else tool + path/command). This is the "always allow writes a rule"
      mechanism.
- [~] Retire mcp / permission-gate ad-hoc allowlist sets (`:mcp/allow-tool`,
      `:allowed-write-repos`, …) → **deferred into Phase 4**, where those gates
      themselves become rules. Until then they keep owning their own confirm
      dialogs; the rules gate (registered first) already lets a persisted
      `:allow` rule short-circuit them, so no double-prompt.

### Phase 3 — recommend-a-rule flow
- [x] `:recommend-rule` confirm-option (key `?`) on guard dialogs
      (`xi.dialog/confirm-option`). Appended to every `:ask` rule's options via
      `recommend-options` in `xi.ext.rules`.
- [x] On choose: deny current call safely (`recommend-blocked-msg`), capture the
      decision-request `{tool path command cwd repo mcp-*}`, spawn a background
      sub-agent (`spawn-recommend!`, id tagged `rules-rec-`) with the rule schema
      + block context (`recommend-task`) asking for an EDN rule recommendation.
- [x] `:rule-recommendation` UI reuses the `:form` dialog (now prefill-capable:
      `dialog/form-fields` :value + TUI/web renderers): editable rule EDN +
      scope field (session/repo/global/server) + Save. On the recommend sub's
      `:subagent/turn-end` (rules ext is first, so its effect runs after the
      subagent handler sets `:result`), `recommend-done-fx` parses the reply
      (`extract-rule`) and opens the dialog; `save-recommended!` writes the rule
      directly — runtime scopes via `:ext.rules/add`, config scopes via
      `store/append-rule-file!` — bypassing the tool-gate so the hard-gate holds.
- [x] Regex-aware rule parsing: `cljs.reader` is EDN-only and throws on `#"…"`
      (regex isn't EDN), which broke regex `:path`/`:command` rules from files
      AND the recommend flow. Added `store/read-rule-edn` (full
      `cljs.tools.reader`), now the single parser for both file loading and the
      ext. Rules ext converted to a `create` factory capturing `ask!`.
- [ ] Optional: auto-retry the tool after saving an allow-rule. (deferred to v2)

### Phase 4 — port permission-gate + mcp + plan-mode + sandbox
- [x] `permission-gate`: BLOCKED_PATHS / BLOCKED_WRITE_PATHS / GUARDED_PATTERNS
      / BLOCKED_COMMANDS / outside-repo → default rules (deny/ask). Keep
      `ask-server-control` as the immutable special case (NOT a rule).

      Implemented as `xi.rules.defaults/default-rules` (scope :default): sensitive
      write (Mail/.ssh/.gnupg/.password-store → :ask), protected write
      (.env/.git//node_modules/ → :ask), outside-write (:outside :cwd → :ask with
      [:yes :no :repo]), remote-shell (ssh/scp/rsync/sftp → :deny), guarded bash
      (rm -rf, sudo, git push, kill, … → :ask). Behavioral nudges are ordered
      FIRST so a /tmp `rm` or auto-memory write is nudged, not asked, on overlap.
      New pieces: `:outside :cwd` matcher + `needs-outside?` in `xi.rules`;
      `store/outside-cwd?` (symlink-canonicalized, reuses sandbox) +
      `store/enrich-request` (populates `:outside-cwd?` only when an :outside rule
      is in play); the `:ask` `:repo` answer now persists a repo-scoped allow-rule
      (`allow-repo-rule-from-req`, groups write+edit), distinct from `:always`
      (path/command-exact). `permission-gate/tool-gate` now only guards
      server-control; `outside-project?`, `approve-write-path/read-path`,
      `GUARDED_PATTERNS`, and the allow-repo handlers stay (clj reuses them until
      Phase 5). NOTE interim: clj's builtin read/write still consult
      permission-gate's `:allowed-*-repos` sets, separate from the rules store —
      unified in Phase 5.
- [x] `mcp` `:tool-gate` → rule (`:match {:tool :mcp}` → `:ask` with `:always`);
      `/mcp` command + installer stay.

      Added default rule `{:match {:tool :mcp} :action {:type :ask :options [:yes
      :no :always]}}` (last in default-rules). To keep the information-rich mcp
      prompt (server/tool/every argument), `store/decision-request` now carries
      the tool-call `:arguments`, and `apply-action`'s `:ask` builds an
      informative default message via `ask-message` when the rule supplies no
      `:message` — mcp → server/tool/args block, else bash/clj command, else
      target path (so bash/write asks got richer too). `:always` reuses
      `allow-rule-from-req`, which already narrows mcp to server+tool. Removed
      `mcp-tool-gate` + its allowlist machinery (`tool-allowed?`,
      `allow-tool-handler`, `:mcp/allow-tool`, `:allowed-tools`, `gate-message`,
      `format-arguments`) from `xi.ext.mcp`; the `create` factory no longer
      carries `:tool-gate`/`:handlers`. `parse-qualified-name` stays (general
      utility, still tested).
- [x] `plan-mode` → default rule-set gated by `:when {:plan-mode {:enabled?
      true}}`; keep `/plan` toggle command.

      Added three `:scope :default` rules to `xi.rules.defaults` (before the
      write/bash gates so plan-mode's deny wins over the softer asks): allow
      `{:tool #{:write :edit} :path "tasks/todo.md"}`, deny other `#{:write
      :edit}`, deny `:bash` matching the mutating-bash regex — all gated by
      `:when plan-mode-on`. Enhanced `xi.rules/match-when` to match a map value
      recursively (nested submap, ignoring extra keys) so a rule can target one
      ext's flag. Deviation from the plan's `:when {:mode :plan}` wording: plan
      mode is a room-ext flag (`[:ext :plan-mode :enabled?]`), and modes can be
      independent toggles, so `:when {:plan-mode {:enabled? true}}` reflects real
      state rather than inventing a mutually-exclusive `:mode` field. `plan-mode`
      ext slimmed to just the `/plan` toggle, room flag, and 📋 badge (tool-gate
      + dangerous-pattern helpers removed).
- [x] `sandbox`: deny-checks (outside-cwd write, credential read, `cmd &`) →
      rules; KEEP the bwrap/firejail executor (rules can't run bash under a
      wrapper). Keep `/sandbox` toggle.

      Added three `:scope :default` rules to `xi.rules.defaults`, gated by
      `:when {:sandbox {:enabled? true}}` and placed before the write/bash gates
      so sandbox's hard denies win over the softer asks: deny `{:tool #{:write
      :edit} :outside :cwd}` (reuses the existing `:outside :cwd` matcher), deny
      `{:tool #{:read :grep :find :ls} :credential :read}`, deny `{:tool :bash
      :command #"&\s*$"}` (backgrounded `cmd &`). Added a new opt-in
      `:credential :read` matcher (`match-credential` + `needs-credential?` in
      `xi.rules`; `store/credential-path?` resolves the target symlink-
      canonicalized and checks `sandbox/hidden-paths`; `enrich-request`
      populates `:credential-path?` only when a `:credential` rule is in play,
      mirroring `:outside`). `xi.ext.sandbox` slimmed to just the bwrap/firejail
      bash executor (`run-bash!` + a bash-only `tool-gate`), the `/sandbox`
      toggle/command, room flag, and 🔒 badge — the deny helpers (`blocked`,
      `gate-write`, `gate-read`, `background-command?`, `target-path`) removed.
      Rules ext is registered first, so a sandbox deny fires before the executor
      ever runs; write/edit/read run in-process and pass the executor untouched.
      Deviation (same rationale as plan-mode): `:when {:sandbox {:enabled?
      true}}` reflects the real room-ext flag rather than a `:mode` field.

### Phase 5 — clj internal policy consults the rules store

Guiding principle (agreed): **"disallow \* then soften"** — a base rule denies,
and more-specific allow/ask rules stack above it (first-match-wins by precedence
tier). Applied **scoped to a new `:sh` tool-kind** first, so clj's allowlist
falls out of the engine with zero blast radius on the other tools. Widening the
base from `{:tool :sh}` to `{}` (all tools) is a documented future step, gated on
deciding the headless ask-without-`confirm!` semantics + a full default allow-set.

KEEP as execution (not policy): arg injection (`:_allowed*`), SCI worker
coordination, dynamic runtime gate-requests (SAB), the git→helper bounce, ss/
helper-equiv rewrites, and result annotation (helper hints, tmp-rm note).

Parked holes (do later, per agreement): the engine's `:ask` action can't
aggregate/inject — clj keeps its own approve-loop + injection, consulting the
engine per scanned item; "allow-but-annotate" has no engine action (hints stay
clj-side); structural guards (`bg-dynamic?`, parse-error) stay clj pre-checks.

**5a — schema: `:sh` tool-kind + `:cli` matcher. ✅ DONE**
- [x] Add a `:cli` matcher to `xi.rules/matches?` (`match-cli`: string/set →
      exact binary match, regex → re-find, against `(:cli req)`; nil → match).
- [x] clj builds synthetic reqs with `:tool :sh` + `:cli <first-token>` +
      `:command <reconstructed>` + `:effective-cwd`; `match-tool` already handles
      `:sh` via set membership, so no `store/tool-kind` change needed.
      (Matcher landed; clj-side req construction lands in 5d.)
- [x] Tests: `match-cli` string/set/regex/nil; a `{:tool :sh :cli …}` rule only
      matches sh reqs, never the real `:bash` tool. (`match-cli-sh`)

**5b — hardened tier (prepended, flag-removable). ✅ DONE**
- [x] Add a hardened rules source that sits **above** repo/global config
      (highest data-rule precedence), always wins, removable only via a
      launch-time CLI flag (`--no-hardened-rules`). The agent can never drop it
      (can't relaunch the process). `store/set-hardened-disabled!` +
      `store/hardened-rules`; wired in `xi.cli/main`.
- [x] Move `sudo` (hard-deny) and remote `scp`/`rsync`/`sftp` (hard-deny) into
      the hardened tier for `#{:sh :bash}`. `ssh` stays OUT (falls to ask).
      (`defaults/hardened-rules`.)
- [x] KEEP the immutable `store/hard-block` (rules-file writes) as imperative
      code — it is the root-of-trust and must NOT be even flag-removable.
- [x] Tests: hardened deny wins over a user allow-rule; the flag drops the tier;
      rules-file block still fires regardless of the flag.
      (`hardened-tier-prepended-and-flag-removable`, `hardened-sudo-and-remote-denies`)

**5c — allowlist as `:sh` softeners ("disallow \* then soften").**
- [x] Default rules (lowest tier): base `{:tool :sh} → :ask` + `:allow`
      softeners for the read-only SAFE_AUTORUN CLIs. `rm` → allow (parity with
      today); bash's `rm -rf` stays guarded via the existing `:bash` rule.
- [~] `global-allow-clis` (`~/.config/xi/ext/clj.edn` `:allow-clis`) → global
      config allow-rules. DEFERRED: kept as a `base` source so existing user
      `clj.edn :allow-clis` config keeps working without migration. bb-trust
      stays a conditional `base` conj (`bb`) when the bb.edn sha is trusted.
- [x] Session `:allowed-clis` → session rules. `approve-clis!` `:always`,
      `/clj allow`/`/clj revoke`, and `status-text` now go through session
      allow-rules `{:tool :sh :cli x → :allow}` (via `:ext.rules/add` /
      `[:rooms rid :ext :rules :rules]`); the private `:allowed-clis` set,
      `allow-cli` handler and `:init` are gone. Kills the split-brain with
      `/rules`. (`command-allow-writes-session-rule`,
      `command-revoke-removes-session-rule`.)
- [x] git allowed from clj but `push`/`clean` escalate: git is a SAFE_AUTORUN
      CLI (allowed) and the `push`/`clean` escalation + helper bounce stay
      clj-side (execution). SAFE_AUTORUN CLIs are excluded from the engine-allow
      set so the engine can't short-circuit that escalation.

**5d — clj consults the engine per scanned item.**
- [x] `gate-clj` consults the engine per scanned command: `REMOTE_CLIS` and the
      sudo/remote private branches are replaced by `rules/first-match` over
      `store/ordered-rules` (incl. hardened tier) — `:deny` blocks with the
      rule message (checked first, so hardened deny always wins), `:allow`
      pre-approves the binary and feeds the EXISTING injection. SAFE_AUTORUN
      CLIs excluded from engine-allow (their autorun/escalation path stays).
      (`gate-engine-allow-rule-skips-approval`, `gate-engine-deny-rule-blocks`.)
- [x] Path policy: `pg/GUARDED_PATTERNS` stays (tool-impl guard), but outside
      read/write policy now consults the engine (see next bullet).
- [x] Outside read/write: `pg/outside-project?` → `rules-store/outside-cwd?` +
      engine match (`approve-outside-path`): a user `:allow` rule skips the
      dialog and injects; `:deny` blocks; `:ask`/none runs clj's approve+inject
      dialog. The `[r]` answer persists a repo-scoped session allow-rule
      (`outside-repo-rule`) instead of `pg`'s `:allowed-write-repos`/
      `:allowed-read-repos`; those private allowlists + the dead `pg` fns are
      removed. (`gate-engine-allow-rule-skips-outside-read-dialog`,
      `gate-engine-deny-rule-blocks-outside-write`,
      `gate-outside-write-repo-answer-persists-session-rule`.)

**5e — per-call effective cwd (`:dir`).**
- [x] Each scanned `(sh …)` / `(process/start …)` / `(process/poll-until …)`
      carries its effective cwd (the call's `:dir` opt resolved against the room
      cwd, else the room cwd). `gate-clj` consults the engine per command at that
      cwd (`cmd-decision` + memoized `ruleset-for`), so `:dir`/`:repo` rules
      match at the directory the command runs in. The `:dir`-opt read is already
      routed through the read scan. (`gate-dir-scoped-deny-matches-per-command-dir`,
      `gate-dir-scoped-deny-ignores-other-dir`.)

### Phase 6 — tree-sitter node targeting
- [x] `:node` matcher: opt-in (only rules with `:node` trigger a parse, via
      `rules/needs-nodes?`), only for languages with a grammar + treesitter
      available. `store/enrich-request` populates `:nodes` via
      `xi.rules.nodes/nodes-for` (sync `parse-file-sync`).
- [x] For `edit`: locate the edit's `oldText` line range in the target file,
      collect the enclosing named node(s), match `:type`/`:name`/`:contains`.
- [x] For `write`: parse new content (temp file — CLI is path-based), match the
      top-level defs against `:type`/`:name`/`:contains`.
- [x] Falls back to nil `:nodes` when grammar/treesitter/language absent (a
      `:node` rule then never matches). (`xi.rules.nodes-test`,
      `enrich-request-populates-nodes-only-when-needed`.)

## Stays as-is (NOT rules — tool-implementation gates)

`clj` (gate machinery), `treesitter` (outline transform), `canvas-review`,
`subagent` — these implement tools via the gate hook; only `clj`'s internal
*policy* moves to the store (Phase 5).

## Explicitly excluded

- **server-restart / server-stop** — stays as `pg/ask-server-control`
  (detached run + canned message). Never expressed as a rule.

## Review

_(to be filled in after implementation)_
