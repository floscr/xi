# github_code_search

An agent tool that searches code across all of GitHub using **github.com's new
code search** — the "blackbird" engine behind `github.com/search?type=code` —
rather than the legacy `/search/code` REST API.

## Why the cookie?

GitHub exposes two different code searches:

| | Legacy API (`gh search code`, `/search/code`) | New code search (`github.com/search?type=code`) |
| --- | --- | --- |
| Auth | API token / `gh` login | logged-in **web session** |
| `path:*.nix`, `path:src/**/*.ts` globs | ❌ | ✅ |
| `language:`, `repo:`, `org:`, `user:` | partial | ✅ |
| regex, exact phrase | ❌ | ✅ |

The new engine supports the full query syntax (e.g. `recordly path:*.nix`) but
is **only reachable by a logged-in web session** — the API token returns
`logged_in: false` and zero results. So this tool borrows your GitHub
`user_session` cookie and sends it with the request.

## Setup

Provide the `user_session` cookie from a browser that is logged in to GitHub, in
one of two ways:

### 1. `/github-login` command (recommended)

```
/github-login <user_session-value>
```

This writes the cookie to `~/.config/xi/github/auth.json` (mode `0600`):

```json
{ "user_session": "…" }
```

### 2. Environment variable

```bash
export GITHUB_USER_SESSION="<user_session-value>"
```

The env var takes precedence over the file.

### Getting the cookie value

1. Open <https://github.com> logged in.
2. DevTools → **Application → Cookies → https://github.com**.
3. Copy the **Value** of the `user_session` cookie.

The same value is used for both `user_session` and
`__Host-user_session_same_site` request cookies (they match), plus
`logged_in=yes`.

> The cookie expires periodically. When it does, the tool returns a
> "refresh it via /github-login" error — just repeat the setup with a fresh
> value.

## Tool

`github_code_search`

| Arg | Type | Notes |
| --- | --- | --- |
| `query` | string (required) | github.com code-search syntax, e.g. `recordly path:*.nix` |
| `page` | number | 1-based result page (default 1) |

Returns matching **repo · file path · blob URL** and line-numbered code
snippets (HTML stripped, entities decoded).

## Example

```
query: recordly path:*.nix

GitHub code search: recordly path:*.nix
8 of 8 result(s) shown

[1] sreyassabbani/config — pkgs/recordly.nix  (Nix, 7 matches)
    https://github.com/sreyassabbani/config/blob/<sha>/pkgs/recordly.nix
    23:   pname = "recordly";
    …
```

## Implementation notes

- `core.cljs` is the whole extension: the `github_code_search` tool plus the
  `/github-login` command/effect.
- Endpoint: `GET https://github.com/search?q=<query>&type=code&p=<page>` with
  `Accept: application/json`; the response's `payload.results` is formatted for
  the agent.
- It's a **server-side** extension — restart the server (`bb serve:restart`) to
  pick up changes; a browser refresh is not enough.
