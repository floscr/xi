# OpenCode Zen provider

[OpenCode Zen](https://opencode.ai/docs/zen) is a curated, benchmarked
multi-model AI gateway from the OpenCode team. Xi talks to it as a **native HTTP
provider** (`xi.provider.zen`) — no `opencode` CLI subprocess and no extra npm
deps. It reuses Xi's existing OpenAI-compatible streaming core, plus a raw-HTTP
Anthropic Messages adapter for the Claude/Qwen models.

## Using it

Any model id prefixed with `opencode/` routes to the Zen gateway:

```bash
# One-shot (safe to run headless)
xi prompt --model opencode/big-pickle "Reply with exactly: OK"
xi prompt --model opencode/claude-haiku-4-5 "..."

# In the TUI
/model opencode/deepseek-v4-flash
```

The `/model` picker lists live Zen ids (fetched from
`https://opencode.ai/zen/v1/models`, prefixed with `opencode/`) alongside the
Claude and Ollama models.

The `opencode/` prefix is what forces the Zen gateway — a bare id like
`claude-opus-4-8` still routes to the native Claude provider. Internally the
prefix is stripped before the request (the API expects the bare id).

## Authentication

The key is resolved fresh on each turn, in this order:

1. `OPENCODE_API_KEY`
2. `OPENCODE_ZEN_API_KEY`
3. OpenCode's own credential store at
   `~/.local/share/opencode/auth.json`
   (`{"opencode":{"type":"api","key":"sk-…"}}`) — so if you've run
   `opencode auth login`, Xi picks up the same key automatically.

A key is **optional** for the free chat-completions models (Big Pickle, the
`*-free` models); paid models and the Anthropic/Responses surfaces require one.
Get a key at <https://opencode.ai/auth>.

## Supported API surfaces

Zen serves different model families through different wire formats. Xi routes
each model to the right adapter via a static table transcribed from the docs
(`xi.provider.zen.models`); unknown/new ids default to chat-completions.

| Surface | Endpoint | Models | Status |
| --- | --- | --- | --- |
| Chat Completions | `POST /zen/v1/chat/completions` | DeepSeek, GLM, Kimi, MiniMax, Big Pickle, all `*-free` | ✅ supported (via `xi.provider.openai-compat`) |
| Anthropic Messages | `POST /zen/v1/messages` (`x-api-key`) | Claude, Qwen | ✅ supported (via `xi.provider.zen.anthropic`) |
| OpenAI Responses | `POST /zen/v1/responses` | GPT (incl. GPT 6 Astra), Grok, Muse | ✅ supported (via `xi.provider.zen.responses`) |
| Google | `POST /zen/v1/models/<id>` | Gemini | ⏳ not yet implemented |

Selecting a Gemini model currently surfaces a clear "not supported yet" error
rather than failing silently.

## Architecture notes

- `xi.provider.openai-compat` — the shared OpenAI Chat Completions streaming +
  tool-use loop, extracted from `xi.provider.ollama`. Both Ollama and Zen's
  chat-completions surface are thin config wrappers over it (base URL, auth
  headers, optional pre-flight).
- `xi.provider.zen` — the dispatcher: picks the adapter by the model's wire
  format and injects the resolved auth header.
- `xi.provider.zen.anthropic` — raw-HTTP Anthropic Messages streaming adapter
  (tool_use blocks accumulated from `input_json_delta`, executed through Xi's
  registry + tool gate, fed back as `tool_result` blocks). Uses `x-api-key`
  auth (the Zen Anthropic surface rejects bearer-only).
- `xi.provider.zen.responses` — raw-HTTP OpenAI **Responses API** streaming
  adapter (GPT/Grok/Muse). Bearer auth. Runs stateless (`store:false`): the
  streamed output items (reasoning / message / function_call) are echoed back
  verbatim into each next request's `input`, so item ids and reasoning ↔
  function_call pairing stay valid across the tool loop; tool calls run through
  Xi's registry + gate and are fed back as `function_call_output` items. These
  are reasoning models, so it sends `reasoning.effort` (default `low` — the
  models reject `none`) with `include: ["reasoning.encrypted_content"]`, and
  streams reasoning summaries as thinking when a model emits them. Override the
  effort per turn via the `:reasoning-effort` opt.
- `xi.provider.zen.auth` / `xi.provider.zen.models` — key resolution and the
  id-normalization + wire-format routing table.

## Prompt caching (cost)

The Anthropic Messages adapter (`xi.provider.zen.anthropic`) uses Anthropic
**prompt caching**. Xi's tool-use loop re-sends the same large static prefix
(system prompt + tool definitions + prior turns) on every iteration; without
caching each round-trip is billed the full input price for all of it — a
multi-step turn like `/commit` (overview → diff → stage → commit) pays for the
whole prefix 4–5×.

The adapter places `cache_control: {type: "ephemeral"}` breakpoints on:

1. the system prompt (large, fixed for the whole turn),
2. the last tool definition (caches the whole static tool list), and
3. the tail of the conversation (so each iteration only pays full price for the
   newest turn; earlier turns are read from cache).

On Zen this cuts the cached span to ~10% of the input price (e.g. Claude Haiku:
`$1.00` input vs `$0.10` cached read). Measured on a real multi-tool turn:
`cache_read_input_tokens` of ~51K at 10% price vs. full price without caching —
roughly a 3× reduction, growing with the number/size of tool iterations.

Caching only kicks in above Anthropic's ~1024-token minimum prefix, so trivial
one-line turns won't show cache hits — that's expected. The native Claude
provider (`xi.provider.claude`) already caches automatically via the SDK's
`claude_code` preset; this brings the Zen Anthropic surface to parity.

## Privacy

The free models (Big Pickle, `*-free`) may retain prompts to improve the model
during their free period — don't send confidential data through them. See the
[Zen privacy docs](https://opencode.ai/docs/zen/#privacy).
