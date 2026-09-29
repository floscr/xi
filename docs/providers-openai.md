# OpenAI Codex provider (ChatGPT subscription)

The **OpenAI Codex provider** (`xi.providers.openai.codex`) runs real GPT/Codex
models against your **ChatGPT subscription** — the same backend the
[`codex`](https://github.com/openai/codex) CLI uses
(`https://chatgpt.com/backend-api/codex/responses`) — rather than a
pay-per-token API key.

It speaks the OpenAI **Responses** SSE wire format, so it shares all of Xi's
Responses machinery (streaming, reasoning summaries, the tool-use loop) with the
Zen Responses surface via `xi.providers.openai.responses`; this provider only
supplies the Codex-specific endpoint, auth headers, and request body.

## Using it

Any model id prefixed with `openai/` routes to the Codex backend:

```bash
# One-shot (safe to run headless)
xi prompt --model openai/gpt-5.1-codex "Reply with exactly: OK"

# In the TUI
/model openai/gpt-5-codex
```

The `openai/` prefix is what forces the Codex provider. Internally the prefix is
stripped before the request (the API expects the bare id, e.g. `gpt-5.1-codex`).

> **Note:** Zen's anonymized preview ids (e.g. `gpt-6-astra`) are **Zen-only** —
> they don't exist on the OpenAI backend. Use `opencode/gpt-6-astra` for those
> (see [providers-zen.md](providers-zen.md)) and `openai/<real-model>` here.

## Authentication

Xi **reuses the Codex CLI's credentials** — analogous to how the Claude provider
reuses `~/.pi/agent/auth.json`. There is **no separate login flow in Xi**: just
sign in once with the Codex CLI.

```bash
codex login        # opens the browser OAuth flow, writes ~/.codex/auth.json
```

Xi then reads `~/.codex/auth.json` fresh on each turn:

- The `tokens.access_token` (a JWT) is used as the bearer token, and its
  `chatgpt_account_id` claim (or `tokens.account_id`) becomes the
  `chatgpt-account-id` header.
- When the access token is expired (checked via the JWT `exp` claim), Xi
  refreshes it against `https://auth.openai.com/oauth/token` using
  `tokens.refresh_token`, and **writes the fresh tokens back** to
  `~/.codex/auth.json` so the Codex CLI stays in sync.

Set `CODEX_HOME` to override the directory (defaults to `~/.codex`), matching the
Codex CLI's own env var.

If no credentials are found, a turn fails with a clear message pointing you to
`codex login`.

## Request shape

Each turn sends a stateless (`store:false`) streaming Responses request:

- The **system prompt** goes in the top-level `instructions` field (not as a
  `developer`-role input item like the Zen surface).
- `include: ["reasoning.encrypted_content"]`, `reasoning: {effort, summary:auto}`,
  `text: {verbosity:"medium"}`, `tool_choice:"auto"`, `parallel_tool_calls:true`.
- Xi's tools are exposed as Responses `function` defs; the model's streamed
  output items (reasoning / message / function_call) are echoed back verbatim
  into the next request's `input` so id/pairing stay valid across the tool loop.

Headers: `Authorization: Bearer <token>`, `chatgpt-account-id`,
`OpenAI-Beta: responses=experimental`, `originator: codex_cli_rs`.

## Limitations

- **Rate limits** are your ChatGPT plan's Codex limits; a `429` surfaces the
  plan/usage-limit message from the backend.
- Only the SSE transport is implemented (no WebSocket transport).
- Reasoning-effort clamping per specific model id is not applied; the default
  effort is `low`. Override with the turn's reasoning-effort option.
