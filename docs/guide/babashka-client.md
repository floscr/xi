# Babashka client

A small library for calling Xi from Babashka or JVM programs: one function
that runs a [one-shot prompt](prompt.md) and returns the answer with the
session id, so a service can hold a conversation across calls.

## Setup

The library lives in the Xi repository at `packages/bb-client/`, with its own
`deps.edn`. Point your project at the checkout:

```clojure
;; bb.edn
{:deps {xi/client {:local/root "/path/to/xi/packages/bb-client"}}}
```

It runs the Xi bundle it finds at `$XI_BUNDLE`, else at
`/var/lib/xi/target/main.js`, else at `~/Code/Projects/xi/target/main.js`.
Pass `:bundle` to name one.

## Usage

```clojure
(require '[xi.client :as xi])

;; first message — creates a chat for the named agent profile
(xi/prompt! {:agent "coach" :message "I ran 5k today"})
;; => {:ok true :session-id "0198…" :text "Nice pace! …"}

;; later — pass the id back; the agent remembers the conversation
(xi/prompt! {:agent "coach" :session-id "0198…"
             :message "how does that compare to last week?"})
```

On failure: `{:ok false :error "…"}`.

| Key | Does |
| --- | --- |
| `:message` | The prompt (required) |
| `:agent` | An [agent profile](agents.md). Without it the run is a full coding agent. |
| `:session-id` | Continue that conversation |
| `:model` | Another model |
| `:no-store?` | Leave no session behind |
| `:bundle` | Path to `target/main.js` |

The call blocks for the whole turn; run it on a background thread. Profile
runs execute in the profile's own directory on the Xi side, so the caller's
working directory does not matter.
