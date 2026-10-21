# Providers

How model providers are wired. Picking and authenticating a model as a user
is in the guide: [models](guide/models.md).

A provider is a map `{:id kw :start-turn! (fn [opts] {:promise :abort!
:exited}) :list-models! (fn [] Promise<[id…]>) :store {:rewind! …}}`.
`:exited` (optional, default `:promise`) settles once the turn's process is
gone and can no longer write its transcript. `:store` (optional) translates
conversation edits to the data the provider keeps:
`(:rewind! store) {:cwd :session-id} {:keep-prompts [text…]}` →
`Promise<{:status …}>` cuts the stored conversation to those prompts
(`xi.session.claude-store`, shared by Claude and the fake provider; design in
`tasks/session-store.md`). `:login` (optional) is a subscription sign-in,
`{:name :label :login! :logout!}`: `/login <name>` (`xi.fx` `:provider/login`)
and `xi login <name>` (`xi.cli`) call `(:login! login) {:on-prompt f}` →
`Promise<credentials>`, `f` receiving `{:url :user-code}` to show the user.
Which providers load is the
`providers` vector in `src/xi/config.cljc`; `xi.cli` derives the id →
provider lookup from it, wrapping every provider without a `:store` in
`xi.session.recorder` (below), so every provider's chats have a transcript. Routing: an explicit `:provider` on the room's
agent wins, else `xi.util/provider-for-model` picks by model-name prefix
(shared with the browser build). In-flight turn handles live in the
`agent/create-fx` closure; `:agent/abort` → `:provider/abort` → `abort!`.

| Provider | Namespace | Model ids | Transport |
| --- | --- | --- | --- |
| Claude | `xi.providers.anthropic` | bare Claude ids | Claude Agent SDK in the out-of-process runner, tools proxied back ([mcp-tool-bridge.md](mcp-tool-bridge.md)) |
| Ollama | `xi.providers.ollama` | Ollama ids | `xi.providers.openai-compat` against `OLLAMA_BASE_URL` |
| OpenCode Zen · Go | `xi.providers.zen` | `opencode/<id>` · `opencode-go/<id>` | dispatcher over three adapters (below), one base URL per gateway |
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

## OpenCode Zen and Go (`xi.providers.zen`)

One provider for OpenCode's two gateways: Zen (pay-as-you-go,
`opencode/<id>`) and the Go subscription (`opencode-go/<id>`). The prefix
picks the gateway (`xi.providers.zen.models/gateway`), and with it the base
URL, the key and the model list; the rest is shared. Each model is routed to
an adapter by a static wire-format table (`xi.providers.zen.models`,
transcribed from the docs' endpoint tables; unknown ids default to
chat-completions). Go serves MiniMax M3 and Qwen3.8 Max on the Anthropic
surface where Zen uses chat-completions; the table carries that override.

| Surface | Zen endpoint | Go endpoint | Models | Adapter |
| --- | --- | --- | --- | --- |
| Chat Completions | `POST /zen/v1/chat/completions` | `POST /inference/go/openai/v1/chat/completions` | DeepSeek, GLM, Kimi, MiniMax, Big Pickle, `*-free` | `openai-compat` |
| Anthropic Messages | `POST /zen/v1/messages` (`x-api-key`; bearer-only is rejected) | `POST /inference/go/anthropic/v1/messages` | Claude, Qwen | `xi.providers.zen.anthropic` |
| Responses | `POST /zen/v1/responses` | `POST /inference/go/openai/v1/responses` | GPT (incl. preview ids), Grok, Muse | `xi.providers.zen.responses` |
| Google | `POST /zen/v1/models/<id>` | `POST /inference/go/google/v1beta/models/<id>` | Gemini | not implemented; clear error |

Auth (`xi.providers.zen.auth/credential`, a promise resolved per turn).
Zen: `OPENCODE_API_KEY`, `OPENCODE_ZEN_API_KEY`, then the `opencode` entry of
`~/.local/share/opencode/auth.json`. Go: the Console sign-in first
(`xi.providers.zen.login`: the OpenCode CLI's device flow as client
`opencode-cli` against `https://opencode.ai/console/auth/device/*`; tokens at
`~/.config/xi/opencode-go.json`, refreshed a minute before expiry with one
refresh in flight at a time since the Console rotates refresh tokens, an
`invalid_grant` deleting them), then `OPENCODE_GO_API_KEY`, the `opencode-go`
entry, then the Zen key. A sign-in scoped to an organization adds
`x-org-id`. `/model` lists live ids from `https://opencode.ai/zen/v1/models`
and `https://opencode.ai/inference/go/v1/models`.

Every request carries `User-Agent: xi-agent` and the room id as
`x-opencode-session`: Go requires clients to identify themselves and uses
the session for routing and prompt caching (the usage server reads the Go
windows from `/zen/go/v1/usage` with the same key).

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

## Transcripts for sessionless providers

Ollama, Codex and OpenCode keep no conversation server-side. `xi.cli` wraps
each provider without a `:store` in `xi.session.recorder/with-transcripts`:
every main turn is recorded from the provider's callbacks (text, tool
calls, results, errors) into a Claude CLI-format transcript under
`~/.claude/projects/` (a sub-agent's `CLAUDE_CONFIG_DIR` wins, as for the
CLI), and its id is reported as the provider session id. Saving, the
sidebar, resume, interrupted-turn detection and `/tree` rewinds
(`xi.session.claude-store`) then work as for Claude. The prompt is written
when the turn starts and the rest when it ends; side turns are not
recorded. The model's context still comes from the replayed room history,
which a resume rebuilds from the transcript.

## Side turns

Text-only side turns (titles, summaries, quick replies, compaction) must pass
`:no-tools? true`, or each carries every tool definition (~25k tokens). They
run against a throwaway `CLAUDE_CONFIG_DIR` so their transcripts never reach
the session list.
