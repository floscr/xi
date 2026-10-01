# 1. Security defaults

← [Public release](../public-release.md)

## Context

- `src/xi/server/ws.cljs:660` — personal-agent mode admits every WS client
  ("Personal-agent mode has no pairing — admit every client").
- `src/xi/server/ws.cljs:775` — `/api/rooms` uses
  `authed? (or personal-agent? (auth/approved? key))`, so no auth in PA mode.
- No hostname is passed to `Bun.serve`, so it listens on all interfaces. On a
  laptop on public wifi, a PA-mode server is an open agent billed to the
  owner's subscription.
- Coding mode already pairs (client key ≥ 16 chars + approval code), see
  [docs/client-auth.md](../../docs/client-auth.md).

## Steps

- [ ] Route PA mode through the same pairing path as coding mode (WS and
      `/api/rooms`). Keep an explicit opt-out (e.g. `--no-auth` /
      `XI_NO_AUTH=1`) that logs a loud warning at startup.
- [ ] Add a bind-host option (`XI_HOST` / `--host`). Pick a default:
      - loopback (`127.0.0.1`): safest; remote users opt in, or
      - all interfaces (current behaviour), but only with pairing on.
      Document whichever wins in [docs/config.md](../../docs/config.md) and
      [docs/server.md](../../docs/server.md).
- [ ] Update the hetzner--xi / pi4 deployments (dotfiles
      `modules/services/xi-agent.nix`) to set the opt-out or pair their
      clients, so the change doesn't lock them out.
- [ ] Tests: PA mode denies an unknown key on WS and on `/api/rooms`; the
      opt-out admits it.
- [ ] Run gitleaks or trufflehog over the full history (`--all`). The audit
      only checked known key formats.

## Done when

No mode serves an unauthenticated agent unless explicitly told to, and the
secret scan is clean.
