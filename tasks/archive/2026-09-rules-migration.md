# Rules are the only policy hook — retire `:tool-gate`

Goal: every allow/deny/ask decision about a tool call comes from the rules
engine. `:tool-gate` (and `ext/allow`, the force-allow that skips later gates)
goes away. What gates do today that isn't policy moves to where it belongs:
the tool's own implementation, or core execution.

Prerequisite for runtime-loaded user extensions
(`~/.config/xi/extensions/`, separate plan): with no gate hook left, user
extensions simply can't take part in policy.

## Inventory — every `:tool-gate` today

| Gate | What it really is | Goes to |
|---|---|---|
| rules | the policy engine | called directly by core's tool pipeline |
| permission-gate | ask on `serve:restart/stop`, then run detached | ask → default rule; detached run → core (`xi.server-control`) |
| git-lock | wait for the cross-room staging lease; refuse broad adds | core execution path (see decision D2) |
| treesitter | replace `read` output with an outline | override `read` in `:tool-registry` (registry is `merge builtin extra`) |
| canvas-review | implements `canvas_review_*` in the gate (tool fns lack ctx) | `:tool-registry` once tool fns get full ctx |
| subagent | implements `*_subagent` in the gate; confirm on spawn | `:tool-registry`; spawn confirm → default rule |
| sandbox | run bash under bwrap/firejail | **removed** |
| clj `gate-bash` | block chained/piped bash | default rule (new `:chained` predicate) |
| clj `gate-bb` | bb.edn trust confirm + guarded patterns | default rules (new `:tool :bb`, `:bb-trusted`) |
| clj `gate-clj` | static validation + per-sh/path/rm-dir approval dialogs + grant injection | validation → clj tool; approvals → rules request expansion |

Also outside rules today: clj's worker runtime path gate (`on-gate-request`) and
its private dialogs (`approve-clis!`, `approve-outside-path`, `confirm-rm-dirs`,
guarded confirm). Not tool policy, left alone: worktree remove confirm, skills
form, design-mode / element-picker dialogs (user-initiated UI).

## Phase 0 — groundwork (each step ships green)

- [x] Remove sandbox: `xi.ext.sandbox`, `xi.sandbox.{bwrap,firejail}`,
      `make-policy`/`wrap-argv`/backend code, `::sandbox-mode` bundle + alias,
      `:wrap-argv` in `xi.tools.bash`, docs. Keep the path helpers
      (`real-resolve`, `path-within?`, `hidden-paths`, `within-tmp?`,
      `expand-home`, `scrub-env`) — move to `xi.paths`. Keep the generic
      `:credential` match field. (Checked: dotfiles rules.edn doesn't use the alias.)
- [x] Tool fns get the full ctx: `{:cwd :client-pid :room-id :dispatch!
      :get-state :confirm!}` (`run-gated-tool` → `tools/run-tool`). Fix the
      wrong `ext/core` docstring claim.
- [x] New `:tool-name` match field (string/glob on the raw tool name) — needed
      for `spawn_subagent` now and user-extension tools later.
- [x] canvas-review → `:tool-registry`.
- [x] subagent → `:tool-registry`; spawn confirm → default rule
      `{:tool-name "spawn_subagent"} → :ask` (bundle `::subagent-confirm`).
- [x] treesitter → registry `read` override wrapping the builtin read; drop
      the bash `cat` branch (bash is removed whenever clj is on). Fixes a live
      bug: a rules `:allow` on `read` currently force-allows past the outline gate.

## Phase 1 — core execution concerns

- [x] `xi.server-control` (core): bash exec, bb tool exec and clj `sh` detect
      `serve:restart|stop` and run them detached with the explicit result.
      Policy → default rule `::server-control` `{:tool #{:bash :bb :sh}
      :command #"serve:(restart|stop)"} → :ask`. Delete `xi.ext.permission-gate`
      (`GUARDED_PATTERNS` already mirrored by `guarded-command-re`).
      Note: clj today runs *only* the server-control command and drops the rest
      of the eval; in-exec detection fixes that.
      Done: + `:bb` rule kind (`:command` = `bb <task> <args>`), clj honours
      command-scoped `:ask` rules for scanned (sh …)/bg commands (was: ignored),
      `guarded-patterns` published from xi.rules.defaults.
- [x] git-lock → generic **holds** mechanism: core `xi.holds` (fixed
      registry, `wrap` around every registry exec-fn, turn-end
      `settle-room!` in xi.agent, /holds + /release), `xi.holds.lease`
      (cross-process lease files), `xi.git-lock/hold` (git-index hold; broad-add
      refusal is its `:refuse` hook). `xi.ext.git-lock` + `/git-unlock` deleted.
- [x] live check after `bb serve:restart`: clj `(sh "git" …)` add → lease
      held by this room, commit → released (worker ↔ main path). Caught +
      fixed `(.then :error)` (keyword isn't a JS fn → map passed through as a
      refusal). Not exercised live: two-room wait (unit-tested), /holds,
      /release, bb-tool serve:restart confirm dialog.

## Phase 2 — rules as the single policy step

- [ ] Core tool pipeline calls `rules/decide` directly →
      `{:allow tool-call} | {:result …}`; remove `:tool-gate` from
      `ext/compose`, `ext/tool-gate`, `ext/allow`, `tooling-opts :tool-gate`,
      provider `:tool-gate` opts (anthropic, ollama, openai, zen, subagent).
- [ ] bash: `:chained true` opt-in predicate (strip-quoted analysis from clj)
      → default deny rule (same message), replaces `gate-bash`.
- [ ] bb: `:tool :bb` kind + `:bb-trusted` predicate (reads the sha trust
      store) → default `{:tool :bb :bb-trusted true} → :allow`,
      `{:tool :bb} → :ask`; `guarded-command-re` applies to `:bb` too.
- [ ] clj request expansion: `decision-request` for a clj call returns the
      raw `:clj` request plus sub-requests — each scanned `:sh`/background
      command, each literal outside `:read`/`:write`, each recursive rm dir.
      Combine: any deny → deny (rule message); each ask → dialog (existing
      `:always`/`:repo` persistence); result carries **grants**
      (`:_allowed`, `:_allowed-commands`, `:_allowed-reads`, `:_allowed-writes`)
      the clj tool already consumes.
  - [ ] static validation (parse error, dynamic bg, `$()`) → clj tool exec
        (input validation, not policy); `sh bash -c` check dropped (hardened
        shell-interpreter rule covers it)
  - [ ] `SAFE_AUTORUN` / `GIT_DENY` / ss escalation / guarded sh patterns →
        default rules (reconcile with `sh-read-only`, `bash-guards`);
        helper hints stay in the tool output
  - [ ] `approve-clis!` / `approve-outside-path` / `confirm-rm-dirs` →
        rules `:ask` on the sub-requests
  - [ ] worker runtime path gate → `rules/decide` on a `:read`/`:write` request
- [ ] Docs: rules.md (new fields, bundles, server-control, no sandbox),
      extensions.md + writing-extensions.md (no `:tool-gate`, full tool ctx),
      clj-tool.md, git-lock.md, treesitter.md, `xi.config` comments on order.

## Verify (every step)

- `bb test` green; new tests: `:tool-name`, `:chained`, `:bb-trusted`,
  clj expansion combine (deny wins, grants), subagent/canvas via registry.
- `bb serve:restart`, then live: `read` outline, `(sh "git" "add" …)` across
  two rooms, `bb serve:restart` via the bb tool, subagent spawn confirm,
  canvas tools, a `/rules` session `:always` grant.

## Decisions

- **D1 bb trust `:always`:** recommend the standard session allow-rule;
  permanent trust stays `/clj trust-bb` (sha-pinned store read by
  `:bb-trusted`). The dialog loses its "trust forever" option.
- **D2 git-lock broad-add refusal:** recommend keeping it in core next to the
  lease (it reads live lease + room edit state; a rule predicate would make it
  user-overridable). Waiting is not a rule — rules are process-local, the lease
  is cross-process, and a rule can't wait.
- **D3 chained bash:** keep it a deny (error result, as today), or soften to
  a nudge (non-error steering result).

## Review

### Phase 0 (done, uncommitted, not yet live-tested)

- Tool ctx: `xi.agent` / `xi.subagent` pass the per-turn ctx as `:tool-ctx`;
  all four providers merge it under `{:cwd :client-pid}` for `run-tool`.
- `xi.tools.registry/with-extensions`: builtin registry + extension registry,
  used by every provider — openai/zen/ollama now honour builtin overrides
  (treesitter `read`), which they previously only got via the gate.
- `:tool-name` match field; `[a]lways` on an `:other` tool pins `:tool-name`
  (previously it would have produced `{:tool :other}` = allow every
  extension tool). Default ask text for named tools shows tool + arguments.
- `::subagent-confirm` bundle in the default tier.
- Sandbox removed; `xi.sandbox.core` → `xi.paths` (git mv), bash `:wrap-argv`
  / `:env` dropped. Default tier 19 → 17 rules.
- Docs: rules, extensions, writing-extensions, treesitter, clj-tool,
  concurrent-edits, AGENTS.md repo map.
- `bb test`: 767 tests, 0 failures. Watch: 0 warnings.

Found on the way (pre-existing, not fixed):
- openai/zen/ollama only advertise builtin tool defs — extension tools
  (clj, canvas, subagent, MCP, …) don't exist for those models.
- Sub-agent turns don't pass `:remove-tools`, so sub-agents see `bash`.
- zen's execute-tool-call ignores gate-rewritten `:arguments`.
