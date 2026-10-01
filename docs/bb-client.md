# Babashka client (`xi.client`)

`packages/bb-client/` is a tiny Babashka/JVM library for calling xi from other
services — one function, `xi.client/prompt!`, wrapping a one-shot
`xi prompt --json` run. It exists so bb services (health coach, finance
categorizer, …) stop hand-rolling process spawning and stop re-sending chat
history into every prompt: combined with [agent profiles](cli.md#agent-profiles)
and `--session`, each service holds a real stateful conversation.

## Setup

The lib lives in the xi repo at `packages/bb-client/` (its own `deps.edn`, so consumers
don't inherit xi's CLJS deps). Point a consumer's `bb.edn`/`deps.edn` at the
checkout:

```clojure
;; bb.edn
{:deps {xi/client {:local/root "/var/lib/xi/packages/bb-client"}}}      ; deployed (pi)
;; or   xi/client {:local/root "~/Code/Projects/xi/packages/bb-client"} ; dev
```

The compiled bundle is discovered via `$XI_BUNDLE`, then
`/var/lib/xi/target/main.js`, then `~/Code/Projects/xi/target/main.js`.

## Usage

```clojure
(require '[xi.client :as xi])

;; first turn — creates a session for the named agent
(xi/prompt! {:agent "coach" :message "I ran 5k today"})
;; => {:ok true :session-id "0198…" :text "Nice pace! …"}

;; later turns — pass the session id back; the provider transcript is
;; resumed, so no history needs to be compiled into the prompt
(xi/prompt! {:agent "coach" :session-id "0198…"
             :message "how does that compare to last week?"})
```

On failure: `{:ok false :error "…"}` (missing bundle, empty message, non-zero
exit, unparseable output).

### Options

| Key | Meaning |
| --- | --- |
| `:message` | The prompt text (required; sent on stdin). |
| `:agent` | Agent profile id → `--agent` (its `:tools` + system prompt from `~/.config/xi/config.edn`; sessions in `~/.config/xi/personal-agent/<id>/`). Without it the run is a full coding agent; pass `"root"` for the default restricted profile. |
| `:session-id` | Continue a saved conversation → `--session`. |
| `:model` | Override the agent's/default model → `--model`. |
| `:no-store?` | Ephemeral run, leaves no session behind → `--no-store`. |
| `:bundle` | Explicit path to `target/main.js` (default: auto-discovery). |

## Notes

- The subprocess runs in an **empty temp dir**; that's fine because named-agent
  runs execute in the agent's own directory server-side (see
  [prompt-mode.md](prompt-mode.md)) — resume does not depend on the caller's
  cwd.
- `ProcessBuilder` is used directly instead of `babashka.process` because its
  thread pools die under systemd.
- The call blocks for the full turn; run it on a background thread and poll
  from the UI (see the health coach for the pattern).
