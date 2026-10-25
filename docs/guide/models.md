# Models

Xi runs Claude through the Claude Agent SDK, local models through Ollama,
the models of OpenCode Zen and OpenCode Go, and GPT models on a ChatGPT
subscription. Pick one with `/model` or `--model`. Each of these is a
[provider](providers.md); `:providers` in `config.edn` chooses which run,
and you can add your own.

## Claude

Xi signs in the way the Claude CLI does: your Claude Code login if you have
one, else `ANTHROPIC_API_KEY`. `/model` lists the available Claude models;
a bare id such as `claude-sonnet-4-6` goes to Claude. When Claude runs, a
new chat starts on Claude Opus. The first start that loads it installs
the Claude Agent SDK, once.

## Ollama

Models served by a local [Ollama](https://ollama.com) are listed by `/model`
when Ollama is running. A model id no other provider claims goes to Ollama.
The endpoint is `http://localhost:11434`; the `:base-url` option or
`OLLAMA_BASE_URL` changes it:

```clojure
:providers [:anthropic {:id :ollama :base-url "http://gpu:11434"} :openai :zen]
```

Tool calls work with models that support them.

## OpenCode Zen

[OpenCode Zen](https://opencode.ai/docs/zen) is a gateway to many models.
Any id prefixed with `opencode/` goes there:

```text
/model opencode/big-pickle
/model opencode/claude-haiku-4-5
```

```sh
xi prompt --model opencode/deepseek-v4-flash "Reply with exactly: OK"
```

`/model` lists the live ids. The key is read from `OPENCODE_API_KEY`,
`OPENCODE_ZEN_API_KEY`, or the OpenCode CLI's own login
(`~/.local/share/opencode/auth.json`), in that order. The free models work
without a key. Gemini models are not supported yet and say so.

The free models may keep prompts during their free period; do not send
confidential material through them.

## OpenCode Go

[OpenCode Go](https://opencode.ai/docs/go) is OpenCode's subscription with
monthly usage for open coding models. Any id prefixed with `opencode-go/`
goes there:

```text
/model opencode-go/deepseek-v4.1-flash
/model opencode-go/kimi-k3
```

```sh
xi prompt --model opencode-go/deepseek-v4.1-flash "Reply with exactly: OK"
```

Sign in with your OpenCode account, the way the OpenCode CLI does:

```text
/login opencode
```

```sh
xi login opencode                             # the same, from the shell
```

Xi shows a link and a code. Open the link, enter the code, and the chat
reports the sign-in. The tokens are kept in `~/.config/xi/opencode-go.json`
and refreshed as needed. `/logout opencode` (or `xi logout opencode`)
signs out. You can keep several OpenCode accounts: see [Accounts](#accounts).

Without a sign-in, a key is read from `OPENCODE_GO_API_KEY`, the
`opencode-go` entry of `~/.local/share/opencode/auth.json`, or else the Zen
key above. `/model` lists the live ids after the Zen ones. Each request
carries the chat's id as the session header Go asks coding agents for. The
web client's [usage page](web-client.md#usage) shows the plan's windows.

## OpenAI Codex (ChatGPT subscription)

Any id prefixed with `openai/` runs on the Codex backend of your ChatGPT
subscription, the same one the `codex` CLI uses:

```sh
codex login                                   # once, with the Codex CLI
xi prompt --model openai/gpt-5.1-codex "Reply with exactly: OK"
```

```text
/model openai/gpt-5-codex
```

Xi reads the Codex CLI's credentials (`~/.codex/auth.json`, or `CODEX_HOME`)
on every message and refreshes an expired token for both. Rate limits are
your plan's. Zen's preview ids (`gpt-6-astra`) exist only on Zen; use
`opencode/` for those.

## Accounts

Claude and OpenCode Go keep several sign-ins, one of them live: the one
chats run on. `/account` lists them, `xi account` does the same from the
shell, and the web client's [Accounts page](web-client.md#accounts) has
buttons for it.

```text
/account                            # every provider's accounts
/account claude                     # Claude's; * marks the live one
/account claude work                # make "work" live: an id, email or alias
/account claude switch              # the next one
/account claude login --alias work  # sign in to another account
/account claude code <CODE>         # finish a Claude sign-in
/account claude alias 2 spare       # name an account; no name clears it
/account claude forget spare        # forget a stored account
```

A sign-in shows a link to open on any device. Claude's page then shows a
code: send it back with `/account claude code <CODE>` (`xi account claude
login` asks for it). OpenCode Go shows its code in Xi: enter it on the
page. `/account <provider> cancel` stops a sign-in. `/login <provider>` is
`/account <provider> login`, `/logout <provider>` its `forget`.

Every account request first stores the live sign-in, so a login made
elsewhere (`claude /login`) joins the list. Forgetting the live OpenCode Go
account signs out; the live Claude account can't be forgotten, switch away
first.

Switching Claude rewrites the Claude CLI's login, so every chat on the
machine moves to the new account with its next message. The accounts are
kept in `~/.local/share/xi/providers/<provider>/accounts.json`; a
`claude-swap` store is imported once. The [usage page](web-client.md#usage)
shows a card for each stored account.

## Which model does what

| Use | Model |
| --- | --- |
| A chat | The one you picked, else your last `/model` pick (kept per [user](server.md#users), in the web client and the terminal alike), else the first provider's default (Claude Opus); with none, Xi asks you to pick one |
| Naming a chat, `/summary`, `/truncate`, quick replies | The side-turn provider's fast model (Claude Haiku) |
| `/compact`, a `/rollover` summary | The side-turn provider's strong model (Claude Sonnet) |
| An [agent profile](agents.md) | Its `:model`, unless `--model` says otherwise |

The side-turn provider is the first that runs side turns; of the bundled
ones only Claude does. Without it, Xi generates no titles, summaries or
quick replies.
