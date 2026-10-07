# Demo setup (isolated self-test server)

A throwaway xi server the **agent can drive in Chrome to self-test the web UI
without ever touching the real active session**. It runs on its own port
(**7476**) with `HOME` redirected at a gitignored `.demo-home/` tree seeded with
fake sessions, so it never sees or mutates `~/.config/xi` or `~/.claude`.

Everything xi reads derives from `$HOME` — session metadata
(`~/.config/xi/sessions`), transcripts (`~/.claude/projects`), and client auth
(`~/.config/xi/clients.edn`). Launching the server with `HOME=.demo-home` moves
all of it into the sandbox in one shot, with **no code changes**.

## Quick start

```bash
bb demo            # seed .demo-home + start the demo server (tmux session xi-demo, :7476)
bb demo:key        # print the demo client key + the localStorage snippet
```

Then in the browser, on the `http://localhost:7476` origin, install the
pre-approved demo client key (so there is **no pairing dance**) and reload:

```js
localStorage.setItem('xi-client-key',
  'demo00000000000000000000000000000000000000000000000000000000demo')
```

The home view shows two demo projects (`acme-web`, `acme-api`) and four seeded
sessions; opening one renders its full transcript (text + Read/Edit/Bash tool
blocks) straight from disk.

## Tasks

| Task            | What it does                                                        |
|-----------------|--------------------------------------------------------------------|
| `bb demo`       | (Re)seed `.demo-home` and start the demo server on :7476           |
| `bb demo:seed`  | Rebuild `.demo-home` only (no server)                              |
| `bb demo:restart` | Re-seed and restart the server (pick up server-side code changes) |
| `bb demo:stop`  | Stop the `xi-demo` tmux session (and reap a leaked :7476 server)   |
| `bb demo:logs`  | Tail the demo server window                                        |
| `bb demo:key`   | Print the client key + `localStorage.setItem(...)` snippet         |

`bb check` also reports :7476 and the `xi-demo` session alongside the real ones.

The demo reuses the **already-compiled `target/main.js`** from the main watch
(`bb dev` / `bb serve`); it does not run its own shadow-cljs watch. If you change
server-side namespaces, run `bb demo:restart`.

## What is isolated vs. shared

`.demo-home/` is rebuilt from scratch on every seed by `scripts/demo-seed.mjs`.

**Isolated (fresh seed data — the real ones are never read or written):**

- `.config/xi/sessions/<enc>/*.json` — Xi session metadata
- `.claude/projects/<enc>/*.jsonl` — matching Claude transcripts
- `.config/xi/clients.edn` — pre-approved demo client key
- `.config/xi/favorites.json` — a couple of bookmarked sessions
- `.config/xi/config.edn` — enables the bundled
  [demo extensions](guide/extensions.md#demo-extensions) (`:demo-extensions
  ["notes.cljs" "hn.cljs" "ping.cljs"]`, loaded straight from
  `resources/extensions/`). `notes` has a `notes_add` tool, a `/notes`
  command and a browser half at `/notes` (sidebar → Extensions → Notes; open
  a chat first, since Refresh reads through the active room). It also
  declares two users, `root` ("Demo") and `alice`, so the sidebar's user
  switcher appears and multi-user features can be tried from one browser;
  `.config/xi/rules.edn` is an empty, valid rules file.
- To test one of your own user extensions here, point the seed at it:
  `XI_DEMO_EXTENSIONS_DIR=~/.config/xi/extensions XI_DEMO_EXTENSIONS=messenger.cljs bb demo`
  copies the directory into the demo extensions dir and adds the named files
  to the enabled list (`bb demo:restart` with the same variables re-seeds).
- `.local/share/xi/extensions/` — extension data dirs (`XDG_DATA_HOME` is
  redirected too, so the notes file never lands in the real one)

**Shared from the real `$HOME` so live Claude turns still work** (option 1 —
live turns enabled):

- `.claude/.credentials.json`, `.claude/settings.json`, `.claude/CLAUDE.md` →
  **symlinks** (Claude CLI OAuth + config; token refresh stays shared)
- `.claude.json` → **copy** (config; demo writes stay contained, real config is
  never mutated)

### Seeded sessions vs. live turns

- **Browsing / rendering seeded sessions is fully reliable** — the web client
  reads transcripts straight off disk, so the seeded chats always render.
- **Live turns** (auth is wired via the shared credentials above) are best
  exercised by starting a **New chat** in a demo project. Resuming a
  *hand-seeded* transcript through the real Claude CLI (`-r <id>`) is
  best-effort — the seed JSONL is crafted for display, not guaranteed to be a
  byte-perfect CLI session for resume.

## Agent usage

To self-test the xi web UI, use the demo server — **never** the real server on
:7474 unless the user explicitly says so:

1. `bb demo` (starts :7476, seeds data)
2. Open `http://localhost:7476`, install the demo key in `localStorage` (see
   `bb demo:key`), reload.
3. Drive the UI. Nothing here can affect the user's real sessions.

## Customizing the seed

Edit the `seeds` vector in `scripts/demo-seed.mjs` (project cwds, session names,
models, ages, and transcript lines built from `userMsg` / `asstMsg` /
`toolUse` / `toolResult`), then `bb demo:seed` (or `bb demo:restart`).

## Static `?demo=<view>` render (screenshots)

Separate from the demo server: loading the web client with a `?demo=<view>`
query param (on any server) renders a static, fully-populated view once — no
transport, no live data — from the fabricated data in `src/xi/web/demo.cljs`
(`demo-sessions` for the list, `demo-history` for the chat timeline). Views:
`sessions` (session list), `chat` (a coding conversation, rendered with the
configured appearance), `chat-viewer` (the same chat forced into viewer mode:
grouped, collapsed tool + thinking rows), `chat-super` (viewer mode with
`:super-collapsed?` — each collapsed group folded into one summary row) and
`chat-open` (every block expanded, ungrouped); any other value falls back to the session list. Use it for README/marketing screenshots that
must never leak real sessions.

To capture them via the Chrome DevTools MCP: emulate `390x844x3,mobile,touch`
(iPhone size, 3× DPR), navigate to `http://localhost:7474/?demo=<view>`, and
take a full-page screenshot. The color scheme stays on `auto`, so emulating
light/dark switches the theme.
