# Phase 6 Plan — Extension Layer

Working plan for rebuild phase 6: new ext API + all 18 extensions ported,
working in all three connection modes (standalone / server / client).
Split across two sessions: **6a** (API + infra + 4 stateful extensions),
**6b** (remaining 14 ports + personal-agent policy + docs).

Reference: master's `../xi/src/xi/ext/` (18 extensions, ~3k lines).
Constraint reminders: `bb build` / `bb test` only; no interactive TUI runs
from the agent — user smoke-tests each milestone.

## Pre-flight (user, before 6a work builds on phase 5)

Interactive smoke test of phase 5 — extensions hook into this event stream:

- [ ] `xi` standalone: prompt → response, `/debug`, `/quit`
- [ ] `xi server` (TUI attached) + `xi join` from a second terminal: both
      mirror the same room; abort; room auto-destroy on leave-while-idle
- [ ] `xi server --headless` + `xi create`: provision new room, `/reload`
      and `/quit` act on the client only

## Already done (uncommitted — review + test + commit as 6a step 1)

- `src/xi/core/app.cljs`: `:transform-event` option (pre-dispatch event-hook
  chain, nil blocks + logs `:ext/blocked?`), `get-state` added to fx ctx.
- `src/xi/ext/core.cljs` (full rewrite, replaces master's 8 registry atoms):
  - Extension = data map: `:id :init :handlers :fx :event-hooks :tool-gate
    :tool-definitions :tool-registry :commands :system-prompt :keybindings
    :prompt-badge :on-shutdown`.
  - `compose` (pure merge), `merge-handlers` (chain after base),
    `transform-event` (hook chain; `:remote?` passes through untouched),
    `tool-gate` (async chain; nil blocks, `{:intercepted …}` short-circuits),
    `system-prompt`, `prompt-badges`, `on-shutdown!`.
  - `create-dialogs` — dialogs-as-data: `ask!` pushes into room
    `:ui :dialogs` + returns promise; `:ui/dialog-response` event →
    `:dialog/resolve` fx resolves it. Server mode with no clients in room
    resolves to a safe default immediately (no deadlock).

## Key design decisions

### Ext state scoping (fixes a mirror bug in the draft)

- **Room-scoped** ext state lives at `[:rooms rid :ext <id>]` — it rides in
  the `:room/joined` snapshot and mirrors to clients for free (badges render
  client-side from mirrored state). Used by: plan-mode `:enabled?`,
  done-notify `:enabled?`.
- **Process-local** ext state lives at top-level `[:ext <id>]` — never
  crosses the wire. Used by: dictation `:recording?` (client process).
- Draft change: `:init` becomes `{:room {…}}` and/or `{:process {…}}`;
  room template is merged in `state/make-room` (assembly passes it through)
  so standalone, `:room/setup`, and snapshots all agree.

### Hook mapping (master → new architecture)

| master hook | new mechanism |
|---|---|
| `:input` transform | `:event-hooks {:input/submit …}` (pre-dispatch; runs client-side in client mode, before forwarding — clipboard paths are client-local files) |
| `:tool-call` | `:tool-gate` (provider seam exists; needs threading through `agent/create-fx`) |
| `:tool-execution-end` / `:tool-result` | chained `:handlers {:agent/tool-result …}` — look up tool args from the room-history tool-call entry by `:id`; side effects become fx |
| `:agent-end` | chained `:handlers {:agent/turn-end …}` → `[:notify/…]` fx |
| `:session-start` / `:turn-end` (terminal title, skills) | chained handlers on `:session/created` `:session/resumed` `:agent/turn-end` |
| `:context` | **dropped** — master's only user (plan-mode) is a no-op |
| `ext/confirm!` | dialogs `ask!` exposed in gate ctx as `:confirm!` |
| `ext/insert-text!` `delete-chars!` `submit-text!` `show-completion!` | TUI-owned fx: `:editor/insert-text`, `:editor/delete-before-cursor`, `:editor/submit`, `:ui/menu-open` (menu already exists) |
| `ext/set-session-name!` | existing `:session/*` events |
| prompt badges / keybindings | composed `:prompt-badge` fns + `:keybindings` passed into `tui/create!` opts |

### Per-mode extension assembly (xi.cli)

| mode | extensions |
|---|---|
| **server-side** (server + standalone) | plan-mode, permission-gate, todo-intercept, parmezan, clj-surgeon, commit, gtd, kb, perplexity, web, skills, sub-project, pushover, done-notify |
| **client-side** (client + standalone) | terminal-title, clipboard-image, dictation, projects |

- Standalone composes both lists into one app.
- Server mode: server extensions run server-side so pushover/done-notify
  fire with no client attached.
- Client mode: client extensions' `:handlers` are installed **after**
  `ws-transport/make-handlers` wrapping (like `:room/joined`) so their
  events run locally instead of being forwarded; their `:event-hooks` run
  in the client app's `:transform-event`. Server ext state mirrors via the
  shared pure reducers (client has the same chained handlers; mirrored
  effects are stripped as usual, with the client-side fx whitelist extended
  for `:terminal/set-title` etc.).
- `pushover` needs visibility: add `:visible? true` (default for TUI
  clients) on `[:connection :clients cid]`; "no one watching" =
  no visible client in the room. (Web client refines this in phase 7.)

## Session 6a — ext API, assembly seams, 4 stateful extensions

### Step 1 — commit the ext API core (+ tests)

- Review/adjust the uncommitted draft per the state-scoping decision above.
- `test/xi/ext_core_test.cljs`: compose merging/chaining order;
  transform-event (transform, block, `:remote?` passthrough, hook-throw
  recovery); tool-gate chain (sync value, promise, nil block,
  `:intercepted` short-circuit, gate-throw recovery); system-prompt
  (str + fn forms); prompt-badges; dialog handlers (pure part:
  response removes dialog + emits `:dialog/resolve`).
- Commit: `feat(ext): extension composition API + pre-dispatch event hooks`

### Step 2 — assembly seams (the real integration work)

- **Commands injectable**: `xi.commands` `registry`/`by-name`/`cmd-help` are
  fixed defs — refactor to `make-handlers [extra-commands]` (or pass the
  merged command vector through state/closure) so ext commands appear in
  dispatch, `/help`, and TUI completion. Keep `parse-input` pure.
- **Tool plumbing**: `agent/create-fx` gains opts
  `{:tool-gate :extra-tool-definitions :extra-tool-registry :gate-ctx}`
  merged into the `:provider/start-turn` payload. `provider.claude`'s
  `build-mcp-server` already accepts these keys; ollama ignores them.
  Gate ctx: `{:dispatch! :get-state :room-id :cwd :confirm!}` (confirm! =
  dialogs `ask!` partial).
- **System prompt**: append `ext/system-prompt` for cwd in standalone
  assembly and in the `:room/setup` effect (server).
- **TUI seams** (`xi.client.tui` / `xi.tui.*`):
  - prompt badge: `tui/create!` takes `:prompt-badge (fn [state])`,
    rendered after `xi>`
  - extra keybindings: `[{:key :event :when}]` — dispatch with active
    `:room-id` assoc'd
  - editor fx registered by the TUI (editor is a ctx runtime resource):
    `:editor/insert-text`, `:editor/delete-before-cursor`, `:editor/submit`
  - dialog rendering: render room `:ui :dialogs` head as a confirm
    component (y/n → dispatch `:ui/dialog-response`). Scope to `:confirm`
    type only for phase 6.
- **Client transport**: client-local ext handlers installed unwrapped;
  extend `client-side-fx` whitelist as needed.
- **cli**: compose per-mode lists; merge composed `:handlers` (after base),
  `:fx`, `:commands`; pass `transform-event` to `create-app`; wire
  `on-shutdown!` into TUI exit; pass room ext-state template into room
  creation.
- Commit: `feat(ext): wire extensions through assembly, provider, commands, TUI`

### Step 3 — the 4 stateful extensions

1. **plan-mode** — room state `{:enabled? false}`; `/plan` toggles;
   tool-gate blocks `write`/`edit` outside `tasks/todo.md` + dangerous
   bash patterns. Drop the no-op `:context` hook. **Unit test the gate fn**
   (representative stateful extension).
2. **done-notify** — room state `{:enabled? false}`; Ctrl+Shift+N
   keybinding → `:ext.done-notify/toggle` event (forwarded in client mode,
   state mirrors back); `:agent/turn-end` handler → `[:notify/desktop …]`
   fx (notify-send); `:prompt-badge` 🔔 reads mirrored room state.
   Unit test toggle + turn-end handler.
3. **pushover** — server/standalone; enabled at assembly when env vars
   present (`PUSHOVER_USER_KEY`/`APP_TOKEN`/`URL`); `:agent/turn-end`
   handler → `[:notify/pushover …]` fx when (standalone: bell on;
   server: no visible client in room). Includes the `:visible?` client
   flag work.
4. **dictation** — client-only; process state `[:ext :dictation]`;
   sox/whisper child process lives in its fx closure; alt+r / ctrl+c
   keybindings; `● REC` badge; transcript via `:editor/insert-text` fx;
   `:on-shutdown` kills the recorder.
- Commit(s): `feat(ext): port stateful extensions (plan-mode, done-notify, pushover, dictation)`
  (split if any one balloons).

**6a milestone (user smoke test)**: `/plan` blocks a write in all 3 modes;
bell toggle shows badge on a joined client and notifies; pushover fires
from a headless server with no client; dictation records in standalone.

## Session 6b — remaining 14 ports

Start by reading this doc + `git log` since 6a.

### Batch 1 — tool/command extensions (mostly mechanical reshaping)

kb, web, perplexity, commit (uses `:confirm!` from gate ctx), clj-surgeon
(tools + `:agent/tool-result` handler for auto-lint), gtd (tools + commands
+ `:system-prompt` + `/gtd` picker via `:ui/menu-open` + `:editor/*` fx).
- Commit: `feat(ext): port tool extensions (kb, web, perplexity, commit, clj-surgeon, gtd)`

### Batch 2 — gates & result hooks

permission-gate (tool-gate + confirm dialog), todo-intercept (tool-gate →
`{:intercepted …}`), parmezan (`:agent/tool-result` handler → fix-file fx,
args from history tool-call entry), sub-project (master is a stub — port
as stub or fold into skills).
- Commit: `feat(ext): port gating extensions (permission-gate, todo-intercept, parmezan, sub-project)`

### Batch 3 — client-side extensions

terminal-title (`:session/*` + `:agent/turn-end` handlers →
`:terminal/set-title` fx, whitelisted client-side), clipboard-image
(event hook on `:input/submit`: clipboard paths → base64 `:images` +
text rewrite), projects (alt+p keybinding → project menu → editor
insert), **skills — finish the partial port**: `src/xi/ext/skills.cljs`
exists but its `extension` map is still master-shaped (`:name`/`:hooks`)
— convert to the new map (`:id`, `:commands`, session-start handler).
- Commit: `feat(ext): port client extensions (terminal-title, clipboard-image, projects, skills)`

### Batch 4 — personal-agent room policy (deferred from phase 5; only if cheap)

`xi server --personal-agent-only`: rooms created with
`[:agent :personal-agent?] true` (provider already restricts to
`PERSONAL_AGENT_TOOLS`; `system_prompt` has the personal variant),
sessions in `~/.pi/agent/personal-agent-sessions/`. The visible-clients
work from 6a is the prerequisite that made this cheap.
- Commit: `feat(server): personal-agent-only room policy`

### Wrap-up

- Update `docs/rebuild-plan.md`: phase table row 6 ✅ + implementation
  notes (ext map contract, state scoping, per-mode assembly).
- Update `docs/extensions.md` to describe the new API (it documents the
  old hook-state world).
- Delete this file or mark it done.

## Risks / open questions

1. **Dialog UX is net-new** in the rebuilt TUI (state schema reserved
   `:ui :dialogs` but nothing renders them). Scoped to `:confirm` only.
2. **Dictation** spawns sox/whisper — untestable by the agent; isolate in
   fx closure so the rest compiles/tests without the binaries.
3. **Tool-result handlers** depend on history tool-call entries carrying
   full `:arguments` — verify during parmezan port (entries are folded by
   `xi.agent`).
4. **Command registry refactor** touches `/help`, completion, and the
   client transport's `parse-input` interception — keep `parse-input`'s
   contract unchanged.
5. **perplexity is 482 lines** (largest ext) — but pure tool + command,
   should be a near-copy.
