# Providers

How model providers are wired. Every provider is an external program that
speaks the [provider protocol](provider-protocol.md); Xi compiles none in.
Picking providers and models as a user is in the guide:
[models](guide/models.md) and [configuration](guide/configuration.md#providers).

## Where they come from

`xi.providers.config/load-providers` reads `config.edn`'s `:providers`
(`xi.user-config/providers`) once at startup, in `xi.cli`:

| Entry | Resolves to |
| --- | --- |
| `:anthropic`, `{:id :anthropic …}` | a bundled provider: `providers/packages/<name>` next to the bundle, or under `$XI_PROVIDERS_DIR` |
| `{:dir "path" …}` | that directory; relative to the config file |
| `{:npm "name@1.2.3" …}` | the package, installed once with `bun add` into `~/.cache/xi/providers/<name>@<version>/` |

The entry's other keys are the provider's options. Without `:providers`
every bundled provider loads, in `resources/providers.edn`'s `:bundled`
order. A broken entry is logged as `[providers] …` and skipped; a turn
whose model no loaded provider routes gets "No provider runs …" from
`xi.agent`, a turn without a model "No model picked …". A new chat's model
is the first provider's `defaultModel` (`xi.providers.routing/default-model`,
`xi.cli/resolve-model-opts`); Xi names none itself.

Xi has no runtime dependencies of its own. A provider's npm dependencies
must resolve from its directory (`xi.providers.config/with-deps`): a
bundled one inside the npm package is copied, with the kit, to
`~/.cache/xi/providers/bundled/<name>-<hash>/` and `bun install`ed there on
the first start that loads it; in a checkout or on a deployed host `bun
install` in `providers/` covers them; a `:dir` provider without them is
skipped with a hint. Adding or removing a provider takes a restart; an edit to a
provider's program applies on its next request, since every request spawns
it afresh.

The bundled set is the [xi-providers](https://github.com/floscr/xi-providers)
repo at the commit `resources/providers.edn` pins. In a checkout,
`providers/` is either a link to your own clone (`bb providers:link`) or a
checkout of the pin (`bb providers:fetch`); `bb providers:pin` moves the pin
to the clone's pushed HEAD. `bb package` copies the bundled packages and the
kit into the npm package ([packaging.md](packaging.md)).

| Provider | Package | Model ids | Notes |
| --- | --- | --- | --- |
| Claude | `anthropic` | `claude-…`, `anthropic/…`, `sonnet`/`opus`/`haiku` | Claude Agent SDK; tools as an in-process MCP server proxying to Xi ([mcp-tool-bridge.md](mcp-tool-bridge.md)); runs side turns; writes its own transcripts |
| Ollama | `ollama` | every id no other provider claims | OpenAI-compatible Chat Completions against `OLLAMA_BASE_URL` / `:base-url`; starts a local `ollama serve` |
| OpenAI Codex | `codex` | `openai/…` | Responses API on the ChatGPT subscription, with the Codex CLI's login |
| OpenCode Zen · Go | `opencode` | `opencode/…` · `opencode-go/…` | three wire formats behind each gateway; `/login opencode` for Go |

Each package's README in xi-providers has its details (endpoints, auth,
prompt caching).

## The host side

| Namespace | Role |
| --- | --- |
| `xi.providers.manifest` | read and normalize a manifest (`package.json` `xi` key, else `provider.edn`); `spec`, the part clients get |
| `xi.providers.config` | resolve `:providers` entries to manifests, install npm ones, build the providers |
| `xi.providers.process` | `create`: a manifest → the provider map below; turn requests, frame handling, tool calls, `models` / `usage` / `login` requests |
| `xi.providers.runner` | spawn a program for one request, frame its stdio, pass stderr through |
| `xi.providers.locks` | the named mutexes of the protocol's `lock` frame, per Xi process |
| `xi.providers.routing` | (cljc) route a model id with the published specs; the side-turn provider |
| `xi.providers.fake` | the scripted e2e model ([testing.md](testing.md)) |

The provider map is what `xi.agent` and the side-turn effects consume:

```clojure
{:id :start-turn!   ; (fn [opts]) → {:promise :abort! :exited :steer!}
 :list-models!      ; (fn []) → Promise<[id …]>
 :side-turns? :spec :manifest
 :store             ; {:rewind! …} for `transcripts: self` (xi.session.claude-store)
 :login             ; {:name :label :login! :logout!}, with a manifest :login
 :usage-sources}    ; [{:id :fetch}], with the `usage` capability
```

`start-turn!` gets xi.agent's callbacks (`:on-text`, `:on-tool-start`, …)
and maps the program's frames onto them. A `tool-call` frame runs through the
turn's `:tool-policy` (the rules engine) and registry
(`xi.providers.process/run-call`), the same way for every provider. The
`done` frame becomes the result map (`:usage :cost :session-id
:result-text :is-error :aborted :resume-failed :fatal-error`); a missing
working directory resolves `{:cwd-missing true}` without starting the
program. `:exited` settles when the program is gone, which for Claude means
its CLI wrote everything; a rewind waits for it. `:steer!` writes a `user`
frame for a message sent during the turn (Alt+Enter) and answers whether
the program could still take it; `xi.agent` moves the prompt from the queue
into the history on `true` and leaves it queued otherwise.

## Routing

`xi.cli` publishes the loaded providers' specs as `:providers/changed`; the
state keeps them at `:providers` and `xi.server.ws` sends them every client
on admit. A room's `:agent :provider` is set from them at `:room/create`,
`/model`, a web resubmission with a model, and a resume; nil (no table yet)
leaves it to `xi.agent/resolve-provider`, which routes with the providers'
own specs, then falls back to the side-turn provider. `/prompt` and the
web's prompt tab show the spec's `:prompt-note`.

## Usage

`xi.server.usage` polls the live Claude Code login itself (the sidebar
ring reads it) and every provider's and extension's `:usage-sources`. A
provider's `usage` request returns readings in the protocol's camelCase;
`xi.providers.process/reading` turns them into `xi.usage`'s shape.

## Transcripts for sessionless providers

A provider with `transcripts: host` keeps no conversation. `xi.cli` wraps
each provider without a `:store` in `xi.session.recorder/with-transcripts`:
every main turn is recorded from the provider's callbacks (text, tool
calls, results, errors) into a Claude CLI-format transcript under
`~/.claude/projects/` (a sub-agent's `CLAUDE_CONFIG_DIR` wins, as for the
CLI), and its id is reported as the provider session id. Saving, the
sidebar, resume, interrupted-turn detection and `/tree` rewinds
(`xi.session.claude-store`) then work as for Claude. The prompt is written
when the turn starts and the rest when it ends; side turns are not
recorded. The model's context comes from the `history` Xi sends, which a
resume rebuilds from the transcript.

## Side turns

Titles, summaries, quick replies and compaction run on the first provider
with `sideTurns` (Claude), on the models its manifest names
(`xi.providers.routing/side-model`: `:fast` for titles, `/summary`,
`/truncate` and quick replies, `:strong` for compaction and a rollover's
summary); without such a provider they don't run. They pass `:no-tools? true` (the request's
`noTools`), or each carries every tool definition (~25k tokens), and run
against a throwaway `CLAUDE_CONFIG_DIR` (the request's `env`) so their
transcripts never reach the session list.
