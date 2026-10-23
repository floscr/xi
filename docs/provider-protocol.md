# The provider protocol

A model provider is a program. Xi starts it once per request (a chat turn,
a model list, a usage reading, a sign-in), writes the request as one line of
JSON on its stdin, and reads one JSON object per line from its stdout until
a `done` frame. The program can be written in anything: the bundled ones are
TypeScript run by Bun with `@xi-agent/provider-kit`, a personal one can be
a babashka script.

Xi keeps the tools, the rules, the dialogs, the system prompt, the history
and the transcripts. The program turns a prompt into text, thinking and tool
calls, and asks Xi to run each tool call.

This is protocol version 1. How Xi finds and loads providers is in
[providers.md](providers.md); how a user picks them is in the guide's
[configuration](guide/configuration.md#providers).

## The manifest

A provider is a directory with a manifest. In an npm package it is the `xi`
key of `package.json`; otherwise a `provider.edn` file with the same keys
(EDN keywords: `:side-turns?`, `:prompt-note`, …).

```json
{
  "name": "@xi-agent/provider-ollama",
  "xi": {
    "id": "ollama",
    "label": "Ollama",
    "command": ["bun", "src/main.ts"],
    "routes": { "default": true },
    "capabilities": ["models", "usage"]
  }
}
```

| Key | Meaning |
| --- | --- |
| `id` | The provider's id; a room's `:provider`. Required. |
| `command` | The program and its arguments, run with the provider directory as cwd. A leading `bun` is the Bun binary running Xi. Required. |
| `label` | The name shown to users. Default: the id. |
| `routes` | Which model ids it serves: `prefixes` (`["opencode/"]`), exact `ids` (`["sonnet"]`), or `default: true` for every id no other provider claims. The first provider (in config order) whose prefix or id matches wins. |
| `models` | A static model list for `/model`. Without it, and with the `models` capability, Xi sends a `models` request. |
| `capabilities` | Optional requests it answers: `models`, `usage`, `login`. |
| `login` | `{"name", "label"}`: `/login <name>` and `xi login <name>` run its `login` request. |
| `defaultModel` | The model a new chat starts on when nobody picked one; the first provider (in config order) that names one decides. |
| `sideTurns` | It runs titles, summaries, quick replies and compaction; the first such provider does. `{"fast": id, "strong": id}` names the models: `fast` for titles, summaries and quick replies, `strong` for compaction and a rollover's summary. `true` leaves the model to the provider (the request's `model` is empty). |
| `transcripts` | `host` (default): Xi records the chat's transcript and sends the history every turn. `self`: the program keeps the conversation and writes a Claude CLI transcript itself; Xi sends a `sessionId` to resume instead of the history. |
| `basePrompt` | `false` when the provider brings its own system prompt; Xi then leaves out `basePrompt`. Default `true`. |
| `promptNote` | Shown above the system prompt in `/prompt` and the web client's prompt tab, e.g. that an SDK prepends a preset. |

## Dependencies

Xi depends on no provider's libraries. A provider's npm `dependencies`
must resolve from its directory before Xi runs it: in a checkout `bun
install` puts them there, an `:npm` entry gets them from `bun add`, and a
bundled provider inside the npm package (a read-only directory in a global
install) is copied with its dependencies installed under
`~/.cache/xi/providers/bundled/` on the first start that loads it. A `:dir`
provider whose dependencies don't resolve is reported and skipped.

## Frames

Each line is one JSON object with a `type`. Xi's lines go to the program's
stdin, the program's to its stdout. stderr is passed to Xi's log.

### Every request

Xi's first line is the request. Every request has:

| Field | |
| --- | --- |
| `type` | `turn`, `models`, `usage`, `login` or `logout` |
| `protocol` | `1` |
| `providerId` | The manifest's id |
| `options` | The provider's entry in `config.edn` without `:id` / `:dir` / `:npm`, keys in camelCase (`:base-url` → `baseUrl`) |
| `dataDir` | A directory for the provider's own files (`~/.local/share/xi/providers/<id>/`); create it when needed |

The program ends every request with `{"type": "done", …}` and then exits. A
program that exits without `done` fails the request; the last line of its
stderr is shown.

### `turn`

```text
→ {type:"turn", model, prompt, images, system, basePrompt, history, tools,
   sessionId, cwd, effort, reasoningEffort, noTools, noThinking, roomId, env, …}
```

| Field | |
| --- | --- |
| `model` | The model id as the user picked it, routing prefix included |
| `prompt` | The user's message |
| `images` | `[{mediaType, data}]`, base64; images and PDFs attached to the prompt |
| `system` | The chat's system prompt: project instructions, extension prompts |
| `basePrompt` | Xi's base prompt with the tool list and cwd, for a provider without a prompt of its own: send `basePrompt` + `system` |
| `history` | The conversation before `prompt` (`transcripts: host`): `{role:"user", text}`, `{role:"assistant", text, toolCalls:[{id, name, arguments}]}`, `{role:"tool", results:[{id, content, isError}]}` |
| `sessionId` | The session to resume (`transcripts: self`), as reported earlier |
| `tools` | The turn's tools: `[{name, description, input_schema}]`, JSON Schema |
| `cwd` | The chat's working directory |
| `effort`, `reasoningEffort` | The chat's effort settings, when set |
| `noTools` | A side turn: answer in text only, advertise no tools, keep no session |
| `noThinking` | Turn extended thinking off |
| `roomId` | The chat's id, stable across turns |
| `env` | Variables for what the program starts (a side turn's `CLAUDE_CONFIG_DIR`) |

The program streams what the model does:

| Frame | |
| --- | --- |
| `{type:"text", text}` | Assistant text, a delta |
| `{type:"thinking", text}` | Thinking, a delta |
| `{type:"tool-start", id, name, arguments?}` | Show a tool call in the chat |
| `{type:"tool-args", id, name, arguments}` | The call's arguments so far; send it again as they grow, and once complete |
| `{type:"tool-call", id, name, arguments}` | Run a tool on Xi; `id` is any id unique within the request |
| `{type:"tool-done", id, content, isError}` | Show the call's result in the chat; `id` as in `tool-start` |
| `{type:"session", sessionId}` | The provider's session id, as soon as it is known |
| `{type:"error", error:{type, message}}` | Show an error in the chat. `{type:"rate_limit", info}` with Claude's rate-limit info renders as a usage-limit card |

A `tool-call` is answered with

```text
← {type:"tool-result", id, content:[{type:"text", text}, …], isError}
```

after Xi ran the tool through its rules and dialogs (a denied call comes
back as an error result). The call and the chat's tool block are separate:
send `tool-start` and `tool-done` around it (the kit's `runTool` does), or
let an SDK that streams them (Claude) send them.

A message the user sends while the turn runs (Alt+Enter) arrives as

```text
← {type:"user", text, images?}
```

Give it to the model at the next opportunity: after the tool results of
the current round, before the next request (the kit's loops do; the Claude
provider pushes it into the CLI's input stream). Xi shows it in the chat
once sent and does not repeat it in the next turn's `history`, so a program
that ignores the frame loses the message; one arriving after `done` is
Xi's to resend.

The turn ends with

```text
→ {type:"done", stopReason, resultText, usage, cost, sessionId, model, isError, aborted, failure}
```

all optional. `usage` uses Anthropic's field names (`input_tokens`,
`output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`),
`cost` is USD, `resultText` the final answer (side turns read it).
`failure: "resume-failed"` makes Xi rerun the turn without `sessionId`;
`failure: "fatal"` marks an error no retry fixes, already reported.

When the user stops the turn Xi sends `{type:"abort"}`, and kills the
program half a second later. A turn whose working directory does not exist
never reaches the program.

### `models`

`→ {type:"done", ids:["model-id", …]}`. Ids carry the provider's routing
prefix, so that picking one routes back to it.

### `usage`

`→ {type:"done", readings:[…]}`: cards for the web client's usage page, the
shape of [`:usage-sources`](guide/extensions-reference.md#the-map) readings
with camelCase keys (`resetsAt`, `periodMs`, `usedPct`, `fetchedAt`).

### `login` and `logout`

A sign-in shows the user a page and a code:
`{type:"login-prompt", url, userCode}`, then `{type:"done", email?}` once
approved. A failed sign-in sends `error`, then `done`. `logout` forgets the
stored sign-in and answers `done`.

### Services

Any request may use these:

| Frame | |
| --- | --- |
| `{type:"lock", name}` → `{type:"locked", name}` | A named mutex shared by every request Xi runs at once. Wait for `locked`, send `{type:"unlock", name}` when done; it is released anyway when the program exits. Use it around a refresh-token rotation, or anything else two concurrent turns must not both do |
| `{type:"log", level, message}` | A line in Xi's log |

Xi sends a turn `tool-result`, `user` (a message sent during the turn) and
`abort`; a program may ignore any frame it does not know.

## A provider in TypeScript

```ts
import { serve } from "@xi-agent/provider-kit";
import { chatCompletionsTurn } from "@xi-agent/provider-kit/openai-compat";

serve({
  turn: (req, io) =>
    chatCompletionsTurn(req, io, {
      baseUrl: (req.options.baseUrl as string) ?? "http://localhost:11434",
      label: "Ollama",
    }),
});
```

The kit's `serve` reads the request, calls the handler with an `io` that
writes frames (`text`, `toolStart`, `runTool`, `lock`, …), sends `done` with
the handler's result and exits. Its loops cover OpenAI Chat Completions,
OpenAI Responses and Anthropic Messages. The bundled providers in
[xi-providers](https://github.com/floscr/xi-providers) are the examples.

## A provider in another language

Read the request line, write frames, end with `done`. The end-to-end
scenarios run one written by hand: `test/providers/scripted/` (EDN
manifest, a lock, a tool call, options, usage).
