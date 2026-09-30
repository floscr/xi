# User extensions — sandboxed, loaded at runtime

Goal: users drop `.cljs` files into `~/.config/xi/extensions/` and a running,
already-built xi loads them. Built-in extensions stay compiled (full access);
user extensions are a separate, **capability-sandboxed** tier: SCI with no raw
host access, side effects only through rules-gated `xi.api.*`, dispatch and
state writes limited to their own slice. Surfaces: server, TUI client, `xi
prompt`, and web (browser SCI, sources sent by the server).

## Decisions (agreed)

- No `node:*` / npm / raw `js/` globals. `js` is an allowlist object (`Math`
  `Date` `JSON` `Promise` `setTimeout` `clearTimeout` `console`).
- Side effects only via `xi.api.fs` / `xi.api.sh` / `xi.api.http`, each call
  decided by the rules engine like a tool call (`:ask` → normal dialog; no room
  context → deny). Requests carry `:extension <id>`.
- Network: `fetch` gated as a new `:net` request with a `:host` match.
- `sh`: same rules as clj `(sh …)` (`{:tool :sh :cli …}`).
- Dispatch: own `:ext.<id>/*` events, `:ui/status`, and `:prompt/submit`
  (the last only from commands/keybindings — user-initiated, no agent loops
  from tool fns). Everything else is dropped + logged.
- State: handler/command results merge back only into `[:ext <id>]` and
  `[:rooms * :ext <id>]`; any other change is discarded.
- Effects: own `:fx` + filtered `:app/dispatch` only.
- No `:tool-gate`, no `:event-hooks` for user extensions (key allowlist).
- No load-time trust dialog — rules gate every side effect. A status line
  announces new/changed extensions (content hashes remembered).
- Web halves: hiccup sanitized (no `:innerHTML`, `script`/`iframe`/`object`,
  `javascript:` URLs).
- Accepted v1 gaps: an infinite loop in an extension blocks its thread (SCI
  isn't interruptible); user tools reach Claude models only (other providers
  still expose builtin tool defs only).

## Phase A — foundations

- [x] `rules/decide!` — async `(decide! req ctx) → Promise<{:allow? :message}>`
      from the existing pieces (hard-block, ordered rules, ask dialog incl.
      `:always`/`:repo` persistence). Rules tool-gate reimplemented on top,
      behaviour unchanged (existing tests stay green). Phase 2 reuses it.
- [x] Rules: `:extension` match field; `:net` tool kind + `:host` match
      (string/glob/regex/set). Defaults: ask on `:net` (`[a]lways` → session
      rule pinned to extension + host); allow `:read`/`:write` inside the
      extension's data dir (`~/.local/share/xi/extensions/<id>/`).
- [x] `xi.api.fs` — `read` `write` `list` `exists?` `data-dir`; promises;
      each op → `decide!` on `{:tool :read|:write :path … :extension id}`.
- [x] `xi.api.sh` — argv-style `(sh "cmd" "arg" …)` → `decide!` on
      `{:tool :sh :cli :command :extension}`; async spawn; server-control
      commands run detached (xi.server-control).
- [x] `xi.api.http` — `fetch` → `decide!` on `{:tool :net :host …}`.
- [x] Tests: decide! parity with the gate, new match fields, api gating
      (allow/deny/ask/headless).

Phase A notes:
- Deviation, agreed-in-spirit: extension `sh` does NOT get clj's auto-run
  list — new default `extension-sh` asks for every command (`[a]lways` pins
  the exact command). clj-sh's read-only allows (incl. `rm`, `git`, `find`)
  are only safe with clj's own confinement, which a real spawn lacks.
- Added default `extension-credentials`: deny extension read/write of
  `xi.paths/HIDDEN_PATHS` (client keys, ext/*.env secrets, .ssh, …).
- sh child env: scrubbed + DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR /
  DBUS_SESSION_BUS_ADDRESS (notification CLIs).
- 785 tests green; :main + :web build clean.

## SCI sandbox hardening (done, prerequisite for B)

Probing SCI for Phase B surfaced a LIVE escape in the existing clj tool:
`((Date/constructor "…"))` ran arbitrary host code (static-member access is an
unchecked property read in SCI cljs), and `aget`/`js-obj`/`eval` bypassed the
class gating. Fixed in 5821fb6: null-proto `:classes` values + a `:deny` set;
escape corpus in `test/xi/ext/clj_sandbox_test.cljs`. Phase B's user-extension
SCI context MUST reuse the same hardening (null-proto classes, :deny, js-block)
— extract into a shared `xi.sandbox.sci` so clj + user extensions stay in sync.
KB: "SCI cljs sandbox: configured classes leak js/Function".

## Phase B — loader (node surfaces)

- [x] `xi.ext.user`: scan dir, one SCI ctx per process — pure xi nses via
      `sci/copy-ns` (`xi.core.state`, `xi.core.events`, …) + `xi.api.*`, js
      allowlist, `:load-fn` for the user's own nses (relative to the dir),
      no js-libs. Conventions: a file's `extension` (server),
      `client-extension` (TUI), `web-extension` (browser; or `<name>/web.cljs`).
- [x] Validation → `/ext list` `rejected: <reason>`: key allowlist, `:id`
      not taken, tool names unique, eval errors.
- [x] Capability wrappers: try/catch + log on every fn; state-slice merge;
      effect filter; filtered `dispatch!` in fx / tool ctx; tool ctx without
      raw access.
- [x] Assembly in `xi.cli`: server / mirror / client extension lists, after
      the built-ins; manager registration; new/changed status line.
- [x] `/ext reload` — re-eval the dir; tools apply live, handlers/commands
      need a restart (same as built-ins).
- [x] Tests: validation, wrappers (state slice, dispatch filter, effect
      filter), loader on a tmp dir.

Phase B notes:
- One SCI ctx per extension FILE (not per process): isolates extensions
  from each other; siblings still load via :load-fn. sci/eval-string* resets
  *ns*, so the loader reads `<file-ns>/extension` qualified.
- Registered like MCP: `user-ext/install!` right after `mcp/install!` in
  standalone / prompt / server. NOT yet in the TUI client mirror/local lists
  (client mode shows server-side user ext commands only if the TUI loads the
  same dir) — follow-up. `client-extension` var not loaded yet.
- New-/changed-extension status line: deferred (no room at load time); the
  load is logged to the server log + `/ext reload` reports.
- xi.api.promise host fns are `then*`/`catch*` (a ns object with a `then`
  property is a thenable); SCI sees them as `then`/`catch`.
- docs/user-extensions.md example is loaded + run by a test.
- 803 tests green; :main + :web clean.

## Phase C — web

- [x] SCI in a lazily loaded shadow `:modules` entry of `:web` (`:user-ext`).
- [x] Server sends user web halves' source on request (roomless
      `:user-ext/web-sources`, reply to that client only); browser evals with
      curated `xi.web.views` + `ui.*` via copy-ns, filtered `dispatch!`
      (own events forwarded to the server), hiccup sanitizer; pages / routes /
      nav-items / taps added to the running client (router reads a routes atom).
- [x] Room-state sync: clients can't replay a user extension's server
      reducers, so the server guard re-emits a changed `[:rooms rid :ext id]`
      slice as `:user-ext/sync` (broadcast; applied by server + browser).
- [x] Tests: sanitizer, filtered dispatch, validation, sync.

## Phase D — docs + live check

- [x] `docs/user-extensions.md` (API, sandbox, surfaces, browser halves, rules
      examples); linked from extensions.md / AGENTS.md / demo.md.
- [x] Live check on the demo server: `scripts/demo-extensions/notes` (tool,
      command, fs, web page) is seeded; sidebar → Notes → Refresh shows the
      file's text via server fs read → sync. sh + fetch are covered by unit
      tests only (each asks, so they'd need a dialog in the demo).
- [x] `bb demo:stop` / `demo:restart` reap the leaked :7476 bun (it survived
      every `td/stop`, so the fresh server couldn't bind).

## Phase E — first ports out of the build

- [x] `kb`, `browser-open`, `done-notify` moved to user extensions in the
      dotfiles (`config/xi/extensions/`, symlinked to `~/.config/xi/extensions`
      by `modules/dev/ai.nix`; their CLIs pre-allowed in `config/xi/rules.edn`).
      Built-ins + their tests deleted.
- [x] TUI client mirror loads user extensions (`xi.ext.user/mirror-extensions`:
      handlers, commands, keybindings, badge) — without it a joined TUI had no
      Ctrl+Shift+N, no badge and no `/browser-open`.
- [x] Only files listed under `:extensions` in the global rules file load.

## Phase F — second round of ports

- [x] Sandbox additions: `xi.api.json` (`parse` / `stringify` / `pretty`),
      `xi.api.http` `url-encode` / `url-decode`, fetch `:timeout-ms` and the
      final `:url` in the response.
- [x] `pushover`, `freesearch` (`web_search`), `web` (`fetch`) and
      `github-code-search` moved to the dotfiles; built-ins deleted.
  - pushover keys: `config.edn` in its data dir (no env access). The web
    palette toggle forwards `:ext.pushover/toggle` via `:user-ext/forward`.
  - freesearch parses the DuckDuckGo-lite page itself; `scripts/websearch.clj`
    (bb + jsoup pod) is gone.
  - github-code-search keeps its cookie in its data dir (`auth.json`).
- [ ] Headless hosts (`modules/services/xi-agent.nix`: pi4, hetzner--xi) get
      `web_search` / `fetch` from the extensions dir + a rules file written by
      the module. Written, NOT deployed or tested there.
- [ ] `github` (PR pages) stays built-in: its events are roomless and answered
      to one client (`:server-fx`, `:roomless-events`), and its web half has
      client-local handlers, `:ws/send`, the diff renderer and room creation —
      none of which a user extension or browser half can have.

## Open

- Per-project extensions (`<repo>/.xi/extensions/`)? Not in v1 unless wanted.

## Rules migration, Phase 2

See `tasks/archive/2026-09-rules-migration.md`.

- [x] Step 1 (a7784e3): core calls `xi.ext.rules/tool-policy` directly;
      `:tool-gate` / `ext/tool-gate` / `ext/allow` deleted; provider option
      renamed `:tool-policy`. `:chained` + `bash-chained` (D3 = deny),
      `:bb-trusted` + `bb-trust` with an event-carrying "always" option
      (D1 = B, allowlisted events) and `:unanswered :deny`. clj's approvals run
      inside the clj tool (`approve`); model-supplied `_` grant keys dropped.
      Fixed zen ignoring `{:intercepted …}`. Live-checked on the demo server.
      Not yet live on :7474 (needs `bb serve:restart`).
- [ ] Step 2: clj approvals as rules sub-requests (request expansion;
      SAFE_AUTORUN / GIT_DENY / ss escalation / guarded patterns as default
      rules; approve-clis! / outside-path / rm-dir dialogs → rule asks; worker
      runtime path gate → `decide!`). Write the behaviour → rule mapping first.

Noticed, not fixed: `xi.tui.editor` / `xi.tui.pager` use `deftui-opt` (expands
to `xi.config/tui-opt`) without requiring `xi.config` → "undeclared var"
warnings on every :main build, and a load-order dependency.

## Review

(fill in when done)
