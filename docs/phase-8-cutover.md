# Phase 8 — Cutover

Goal: prove the `rebuild` branch reaches parity with the project's real
current state, close the gaps that block daily use, then merge into
`master`.

## Branch topology (the important part)

All three branches share one merge-base, `f4c6996`:

```
f4c6996  ── master  (== origin/master, frozen here)
   ├── rebuild       +22 commits  (the from-scratch rewrite, phases 1–7)
   └── ui-refactor   +33 commits  (continued feature work on the OLD core)
```

`master` is **stale** — no commit landed on it after the split. The
project's living branch is `ui-refactor` (it's the worktree checked out at
`../xi`). So the real parity target is **`ui-refactor`, not `master`**.
`rebuild` fast-forwards `master` cleanly, but that merge is meaningless
until `rebuild` also carries the user-visible behaviour that accumulated on
`ui-refactor`.

Cutover = make `rebuild` match `ui-refactor`'s behaviour, then point
`master` (and the daily worktree) at it.

## Parity audit — the 33 `ui-refactor` commits

Legend: ✅ covered by the rebuild already · ⚠️ present but unverified ·
❌ gap (missing behaviour) · N/A (architecture-specific, no port needed).

### Dialogs
| Commit | Behaviour | Status |
|--------|-----------|--------|
| `d9c23e4` | general dialog-queue (replaces single confirm slot) | ✅ room-scoped FIFO `:ui :dialogs`, `create-dialogs` resolvers |
| `c215784` | list-style confirm dialog in TUI | ✅ `tui/build-dialog` |
| `eb5f398` | dialogs scoped to rooms + render fix | ✅ `:rooms room-id :ui :dialogs` |

### Pushover
| Commit | Behaviour | Status |
|--------|-----------|--------|
| `4b6077e` | Pushover notifications (server mode) | ✅ `xi.ext.pushover` |
| `e85f2b6` | suppress when chat visible in browser | ✅ `:visible?` client check |
| `fa9221c` | suppress when user viewing chat | ✅ same path |
| `e0549f5` | don't notify on interrupted turns | ⚠️ verify (chained `:agent/turn-end` vs `:agent/abort`) |
| `1063ef8` | no dup notifications from multi-room registration | ⚠️ verify ext composed once across rooms |
| `204831a` | env vars reach server in tmux panes | ⚠️ verify server reads `PUSHOVER_*` env |
| `0160351` | runtime mode + pushover for standalone | ✅ server-vs-standalone deep-link branch |
| `854cac0` | omit deep link in standalone mode | ✅ deep link only in server mode |

### Web client — covered / verified
| Commit | Behaviour | Status |
|--------|-----------|--------|
| `68d1410` | collapse tool blocks, expand interesting ones | ✅ `expanded-tools` set |
| `2a0bc79` | auto-resume when navigating to chat URL | ✅ 7b deep-link resume (browser-verified) |
| `a9be418` | auto-resume sessions with no cached messages | ✅ router join on navigate |
| `5c5334b` | always join active rooms with stale cache | ✅ router/transport join |
| `fc9c2ce` | blue dot on sessions in an open room | ✅ `active-dot` in home view |
| `3664363` | split multi-class strings (Replicant diff crash) | N/A new views use `:class [..]` vectors |
| `2dc51d4` | batch history replay into one render | N/A render-from-state already single-pass |
| `9afbdcc` | working indicator in compose bar | ⚠️ verify placement |
| `f565514` | sending blocked when opening cached sessions | ⚠️ verify send works from cached view |
| `eb4eaf4` | debounce caching during streaming | ⚠️ cache persists on whitelist events; verify no thrash |
| `e424115` | mobile-first GPT-style chat UI | N/A rebuild has its own from-scratch UI |
| `ac7b883` | dark-mode borders + mic alignment | ⚠️ visual polish |
| `a8aeb88` | compose input alignment | ⚠️ visual polish |

### Web client — gaps to decide on
| Commit | Behaviour | Status |
|--------|-----------|--------|
| `339c2f6` | thinking blocks expanded by default | ✅ `:open true` (same `<details>` mechanism as tool blocks, browser-verified) |
| `73fdda3` | thinking blocks full height (not scrollable) | ✅ max-height/overflow removed from `.thinking-text` |
| `1666755` | virtualize chat timeline (long-session perf) | ✅ 60-entry window + "Show earlier" (+40); resets on navigate (browser-verified on a 742-entry session) |
| `7cd8138` | mobile compose fixes (keyboard padding, clearing) | ⚠️ verify on mobile viewport |
| `ba2b906` | safe-area insets for lightbox close | ✅ lightbox ported (timeline + compose thumbs, browser-verified) incl. safe-area CSS |
| `23d8394` | omit empty text block for image-only messages | ⚠️ verify with live image sends |
| `fe2ced4` | join-mode command palette + compose drafts | ✅ per-session drafts (`:web/drafts`, browser-verified); ⚠️ verify commands in join mode |

### TUI
| Commit | Behaviour | Status |
|--------|-----------|--------|
| `11f0a17` | Claude models in `/model` picker | ⚠️ verify picker lists Claude models |

## Confirmed gaps (carry-over backlog)

All four confirmed gaps were ported (2026-06-11, browser-verified against a
headless server):

1. **Thinking blocks expanded by default** (`339c2f6`, `73fdda3`) —
   `:open true` + full-height CSS. ✅
2. **Timeline virtualization** (`1666755`) — last 60 entries rendered,
   "Show earlier" expands by 40, `:web/timeline-window` resets on every
   `:route/navigate`. ✅
3. **Per-session compose drafts** — the compose textarea is now controlled
   via `:web/drafts {draft-key text}` keyed by session id (`:new` before
   the first join), so drafts survive navigation for free; cleared on
   send. ✅
4. **Image lightbox** (`ba2b906`) — click any timeline/compose image →
   `ui.lightbox` overlay (`:web/lightbox`); safe-area insets for the close
   button. Composer image attachments had already landed (`1ec0bdb`). ✅

**New observation during verification**: deep-link *reload* into a chat URL
sometimes joins a server room whose resumed history is empty (client state
shows `:history []` with no follow-up events; a later reload that creates a
fresh room works). Server-side room-resume race, pre-existing — the server
binary was unchanged during the test. Add to the verification checklist.

## Pre-merge verification checklist

- [x] `bb build` + `bb web:build` + `bb test` green (2026-06-11)
- [ ] Standalone TUI: prompt → stream → tools → abort; `/model` lists
      Claude models; `/commit`, `/truncate`, sessions work
- [ ] `xi server` + `xi join` (TUI): room create/join/list, mirror state
- [x] Two web clients in the same room mirror state (the one unticked 7b
      item) — browser-verified 2026-06-11: second tab on the same chat URL
      attaches to the existing room; `/help` output from one tab appears in
      the other without interaction. Also confirms commands work in join
      mode (`fe2ced4` ⚠️ item).
- [ ] Pushover: server-mode notify fires, deep link present; standalone
      omits link; suppressed when a visible web client is attached; no
      dup notifications; not fired on abort; env reaches a tmux server
- [ ] Permission gate / commit confirm raise dialogs in TUI **and** web
- [x] Decide each "confirmed gap" → all four ported (see above)
- [x] Deep-link reload race — fixed + browser-verified 2026-06-11: the
      client now always carries `:session-id` on `:room/join`, and the
      room manager forwards it to `:room/setup` when the cached room-id
      no longer exists. Verified by restarting the server (stale cached
      room mapping) and reloading the chat URL: session resumes with full
      history into a fresh room.

## Merge mechanics

`rebuild` is a clean fast-forward of `master`, but `ui-refactor` is **not**
an ancestor of `rebuild` — they share only the base. Plan:

1. Land the agreed must-have gaps on `rebuild`.
2. Run the full verification checklist above.
3. Merge `rebuild` → `master` (fast-forward).
4. Retire `ui-refactor`: cherry-pick any still-wanted backlog items onto
   the new `master`, then delete the branch. Do **not** merge `ui-refactor`
   into `master` — it would drag the old architecture back in.
5. Point the `../xi` worktree at the new `master`.
6. Update `docs/rebuild-plan.md` phase-8 row → done. ~~Rewrite stale
   docs~~ — done 2026-06-11: `AGENTS.md`, `web-client.md`, `web-offline.md`,
   `architecture.md` (was `headless-architecture.md`), `commands.md` (was
   `command-registry.md`), `mcp-tool-bridge.md`, `compaction.md`,
   `session-tree.md`, `syntax-highlighting.md` all rewritten/updated for
   the new core.
