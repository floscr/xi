# Providers

How model providers are wired. Picking and authenticating a model as a user
is in the guide: [models](guide/models.md).

A provider is a map `{:id kw :start-turn! (fn [opts] {:promise :abort!})
:list-models! (fn [] Promise<[id…]>)}`. Which providers load is the
`providers` vector in `src/xi/config.cljc`; `xi.cli` derives the id →
provider lookup from it. Routing: an explicit `:provider` on the room's
agent wins, else `xi.util/provider-for-model` picks by model-name prefix
(shared with the browser build). In-flight turn handles live in the
`agent/create-fx` closure; `:agent/abort` → `:provider/abort` → `abort!`.

| Provider | Namespace | Model ids | Transport |
| --- | --- | --- | --- |
| Claude | `xi.providers.anthropic` | bare Claude ids | Claude Agent SDK in the out-of-process runner, tools proxied back ([mcp-tool-bridge.md](mcp-tool-bridge.md)) |
| Ollama | `xi.providers.ollama` | Ollama ids | `xi.providers.openai-compat` against `OLLAMA_BASE_URL` |
| OpenCode Zen | `xi.providers.zen` | `opencode/<id>` | dispatcher over three adapters (below) |
| OpenAI Codex | `xi.providers.openai.codex` | `openai/<id>` | Responses SSE against the ChatGPT Codex backend |

The runner host side (`xi.providers.runner`) is provider-agnostic; the
Claude CLI is pinned via nix in `packages/providers/anthropic/nix/` and
built by the runner (`bb claude:update` bumps it).

## Shared cores

- `xi.providers.openai-compat` — OpenAI Chat Completions streaming + tool-use
  loop, extracted from the Ollama provider; Ollama and Zen's chat-completions
  surface are config wrappers (base URL, auth headers, pre-flight).
- `xi.providers.openai.responses` — OpenAI **Responses** SSE streaming +
  tool loop, shared by Zen's Responses surface and the Codex provider. Runs
  stateless (`store:false`): streamed output items (reasoning / message /
  function_call) are echoed verbatim into the next request's `input` so ids
  and reasoning ↔ function_call pairing stay valid; tool calls run through
  the registry + policy and return as `function_call_output`. Sends
  `reasoning.effort` (default `low`; the models reject `none`) with
  `include: ["reasoning.encrypted_content"]`, streams reasoning summaries as
  thinking. `:reasoning-effort` on the turn overrides.

## OpenCode Zen (`xi.providers.zen`)

Routes each model to an adapter by a static wire-format table
(`xi.providers.zen.models`, transcribed from the Zen docs; unknown ids
default to chat-completions):

| Surface | Endpoint | Models | Adapter |
| --- | --- | --- | --- |
| Chat Completions | `POST /zen/v1/chat/completions` | DeepSeek, GLM, Kimi, MiniMax, Big Pickle, `*-free` | `openai-compat` |
| Anthropic Messages | `POST /zen/v1/messages` (`x-api-key`; bearer-only is rejected) | Claude, Qwen | `xi.providers.zen.anthropic` |
| Responses | `POST /zen/v1/responses` | GPT (incl. preview ids), Grok, Muse | `xi.providers.zen.responses` |
| Google | `POST /zen/v1/models/<id>` | Gemini | not implemented; clear error |

Auth (`xi.providers.zen.auth`), resolved per turn: `OPENCODE_API_KEY`,
`OPENCODE_ZEN_API_KEY`, then `~/.local/share/opencode/auth.json`. `/model`
lists live ids from `https://opencode.ai/zen/v1/models`.

**Prompt caching.** The Anthropic adapter sets
`cache_control: {type: "ephemeral"}` on the system prompt, the last tool
definition (caching the whole static tool list) and the tail of the
conversation, so each tool-loop iteration pays full price only for the newest
turn. Measured on a real multi-tool turn: ~51K `cache_read_input_tokens` at
10% price, roughly a 3× reduction, growing with the number of iterations.
Cache hits need Anthropic's ~1024-token minimum prefix, so one-line turns show
none. The native Claude provider already caches via the SDK's `claude_code`
preset.

## OpenAI Codex (`xi.providers.openai.codex`)

Reuses the Codex CLI's credentials: reads `~/.codex/auth.json` (`CODEX_HOME`)
on each turn, uses `tokens.access_token` as bearer and its
`chatgpt_account_id` claim (or `tokens.account_id`) as the
`chatgpt-account-id` header; when the JWT `exp` has passed it refreshes
against `https://auth.openai.com/oauth/token` with `tokens.refresh_token` and
writes the tokens back so the CLI stays in sync. Request: the system prompt in
the top-level `instructions` field, `include: ["reasoning.encrypted_content"]`,
`reasoning: {effort, summary:auto}`, `text: {verbosity:"medium"}`,
`tool_choice:"auto"`, `parallel_tool_calls:true`; headers `OpenAI-Beta:
responses=experimental`, `originator: codex_cli_rs`. SSE only, no WebSocket
transport; no per-model effort clamping. The `openai/` prefix is stripped
before the request.

## Side turns

Text-only side turns (titles, summaries, quick replies, compaction) must pass
`:no-tools? true`, or each carries every tool definition (~25k tokens). They
run against a throwaway `CLAUDE_CONFIG_DIR` so their transcripts never reach
the session list.
