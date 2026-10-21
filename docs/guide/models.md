# Models

Claude through the Claude Agent SDK is the default. Xi can also run local
models through Ollama, the models of OpenCode Zen and OpenCode Go, and GPT
models on a ChatGPT subscription. Pick one with `/model` or `--model`.

## Claude (default)

Xi signs in the way the Claude CLI does: your Claude Code login if you have
one, else `ANTHROPIC_API_KEY`. `/model` lists the available Claude models;
a bare id such as `claude-sonnet-4-6` goes to Claude.

## Ollama

Models served by a local [Ollama](https://ollama.com) are listed by `/model`
when Ollama is running. `OLLAMA_BASE_URL` changes the endpoint from
`http://localhost:11434`. Tool calls work with models that support them.

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
forgets them.

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

## Which model does what

| Use | Model |
| --- | --- |
| A chat | The one you picked, else your last `/model` pick (kept per [user](server.md#users), in the web client and the terminal alike), else the default |
| Naming a chat, `/summary`, `/truncate` | A cheaper Claude model, always |
| An [agent profile](agents.md) | Its `:model`, unless `--model` says otherwise |
