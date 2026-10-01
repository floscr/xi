# Provider fallback extension (`xi.ext.provider-fallback`)

Switch a room to a fallback provider/model when the current one runs out of
usage (mostly Anthropic subscription limits), re-run the failed prompt there,
and switch back once the limit resets.

## Findings

- **Signal (Anthropic):** the CLI emits `rate_limit_event` with
  `status "rejected"` + `resetsAt` (epoch s) + `rateLimitType`
  (`five_hour`/`seven_day`/…). `xi.providers.anthropic/process-sdk-message`
  already forwards it as `:agent/error {:type "rate_limit" :info {…}}` (no
  `:message`, so it renders as a raw `pr-str` today). The CLI then writes a
  synthetic assistant text "You've hit your limit · resets 8:10pm (…)" and the
  turn ends with "Claude Code returned an error result: …" → a second
  `:agent/error`. So the turn ends promptly; detection has to dedupe.
- **Context across providers:** sessionless providers (openai/zen/ollama)
  rebuild the conversation from the room's in-memory `:history` every turn
  (`:history` = prior-history in `build-turn-effect`), so Claude → fallback
  keeps the context with no extra work.
- **Back to Claude:** the room still holds the old Claude
  `provider-session-id`, whose transcript lacks the fallback turns. Same fix as
  `/fork` and `/tree`: clear the id + `:inject-history? true` → the next Claude
  turn starts fresh with the whole history injected as context.
- **Re-running the prompt:** `:agent/retry-fresh` already finds the last
  `:user` entry and re-runs it with prior-history, but it always clears
  `provider-session-id`. With the id cleared, `:session/sync` stops
  persisting the room's metadata. → needs a `:keep-session?` flag (small core
  change, `xi.agent`).
- `/model` already switches provider+model via plain state
  (`[:rooms rid :agent :model/:provider]`), so the extension can do the same.

## Design

Server extension, `src/xi/ext/provider_fallback.cljs`, registered in
`xi.config/server`. Config: `xi.config/provider-fallback`, e.g.
`{:anthropic "openai/<model>"}` (provider → fallback model), documented in
docs/config.md.

Room state `[:rooms rid :ext :provider-fallback]`:
`{:enabled? true :limited nil|{:primary {:model :provider} :resets-at ms :switched-at-idx n}}`

1. **Detect.** Chain on `:agent/error`: `:type "rate_limit"` (Anthropic), or
   an error message matching 429 / usage limit / quota for other providers.
   Record `:pending-limit {:resets-at …}` once per turn.
2. **Switch + replay.** Chain on `:agent/turn-end` (runs after the base
   handler, before its queued-prompt `:app/dispatch` is processed, so queued
   prompts also go to the fallback):
   - set `:agent :model/:provider` to the fallback, store `:primary`
   - status line: "Claude usage limit hit (resets 20:10) → switched to X"
   - dispatch `:agent/retry-fresh {:keep-session? true}`: re-runs the last
     user prompt on the fallback with prior-history (drops the synthetic
     "hit your limit" text + error entries).
   - Mid-turn limit (tool calls already ran after the prompt): send
     "Continue — the previous model hit its usage limit" with the full
     history instead of replaying, so tools don't run twice.
   - Effect: schedule a restore timer at `resets-at`.
3. **Restore.** The timer dispatches `:ext.provider-fallback/restore`: switch
   back to `:primary`, clear `provider-session-id` + `:inject-history? true`
   (only if fallback turns happened), status line. If the room is busy, set a
   flag and restore at the next turn-end.
4. **UI.** `:prompt-badge` (`⇄ <fallback>` while on the fallback).
   `/fallback` shows status. `/fallback back` restores now.
   `/fallback off|on` turns auto-switching off/on for the room.
5. **Core touch-ups.**
   - `xi.agent/retry-fresh`: `:keep-session?` flag.
   - `xi.providers.anthropic`: give the `rate_limit` error a readable
     `:message` (reset time).

## Known limitations (v1)

- Fallback turns aren't in any Claude transcript, so resuming the session
  from disk drops them (the in-memory room keeps them). Same gap `/model`
  already has when switching providers.
- Side turns (titles, quick replies, summaries) and sub-agents keep using
  Claude and will just fail while limited.
- Restore timers don't survive a server restart (but neither does the room's
  in-memory ext state).
- Not in v1: a proactive warning on `allowed_warning` (utilization) events.

## Rejected alternative

A wrapper "meta-provider" that retries inside `start-turn!`. That would need
no core change and the retry would happen inside the turn. But providers
aren't extensions, the room's `:model` would not reflect who answered, and
switching back would be invisible.

## Steps

- [ ] `retry-fresh` `:keep-session?` + test
- [ ] readable `rate_limit` message in anthropic provider
- [ ] `xi.ext.provider-fallback` (detect, switch, replay, restore, badge, /fallback)
- [ ] register in `xi.config/server`, add the `provider-fallback` config option
- [ ] tests `test/xi/ext/provider_fallback_test.cljs` (pure handlers)
- [ ] docs: config.md, extensions.md built-in table
- [ ] `bb test`, compile via watch; manual check by faking a `rate_limit` error
