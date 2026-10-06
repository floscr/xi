# Packaging (npm)

Xi is published as the npm package `xi-agent`; it installs the command `xi`. The
package is **assembled by `bb package`** into a staging directory rather than
packed from the working tree, so it holds exactly what runs and nothing from
the dev setup.

```bash
bb package             # release builds → dist/pkg → dist/xi-agent-<version>.tgz
bb package --no-pack   # only stage dist/pkg
```

`scripts/build-package.mjs` does the work. It runs `shadow-cljs release` for
`:main` and `:web` with `--config-merge` pointing the output into `dist/pkg`,
so a running dev watch and its `target/` + `resources/public/js` are never
touched, and it is safe to run next to `bb serve`.

## Layout

Paths between these are relative — the bundle finds `resources/` and
`packages/` next to `target/` — so the layout is part of the contract:

```
package.json                    generated (below)
bin/xi.js                       launcher
target/main.js                  release bundle of the :main build (~5.6 MB)
resources/public/               web client; js/ is the release :web build
resources/highlight/            syntax-highlighting grammars
resources/treesitter/           web-tree-sitter runtime + WASM grammars
packages/providers/anthropic/runner.mjs    the Claude SDK runner
LICENSE, THIRD_PARTY_NOTICES.md, README.md
```

Left out on purpose: `nix/` and the `claude` out-link (the Nix-pinned Claude
CLI), `docs/`, tests, the dev builds, `mkcert-rootCA.crt`.

## Runtime: Bun

Xi uses `Bun.serve`, `Bun.spawn` and friends, so it only runs on Bun. npm
installs a bin with the runtime named in its shebang, so `bin/xi.js` is plain
Node-compatible JS: under Bun it loads `target/main.js` in-process; under Node
it spawns `bun target/main.js …`, and prints how to install Bun when it is
missing (exit 127). `engines.bun` records the version it was tested with.

## The generated package.json

Derived from the repo's: `private`, `devDependencies` and `scripts` are
dropped, and the runner's `dependencies` (`@anthropic-ai/claude-agent-sdk`,
`zod`, read from `packages/providers/anthropic/package.json`) become the
package's own. The runner used to `npm install` into its own directory on
first use, which fails for a global install into a read-only directory; it now
skips that when the SDK already resolves from a parent `node_modules` (the
`external` state in `runner.mjs`). In a checkout nothing changes: the runner's
own `package.json` and bootstrap still apply.

The SDK brings its own native `claude` binary through the
`@anthropic-ai/claude-agent-sdk-<platform>` optional dependency, so a package
install needs no Nix and no separate Claude CLI.

## Name

`xi` is taken on npm (an unrelated CMS) as is `xi-cli`, so the package is
`xi-agent`.

## Publishing

```bash
npm login                      # once per machine (browser login)
bb package:publish             # bb package, then npm publish dist/<name>-<version>.tgz
bb package:publish --no-build  # publish the tarball already in dist/
```

npm refuses to publish without two-factor authentication on the account
(`403 Two-factor authentication … is required`): enable it on npmjs.com
(Account → Two-Factor Authentication), after which `npm publish` prompts for
the one-time code. The task publishes the packed tarball, not the working
tree, so what goes up is exactly what `bb package` staged. Bump `version` in the repo's
`package.json` before each release; npm refuses to republish a version.

## Testing a package

```bash
bb package:serve               # pack, install the tarball, run it on :7477 (Ctrl-C stops)
bb package:serve --port 7490   # another port
bb package:serve --no-build    # reuse the tarball already in dist/
```

`scripts/try-package.mjs` runs the package the way a user gets it: it packs
(`bb package`), installs the tarball into a fresh `dist/try`, and runs the
installed `xi server --headless` in the foreground — no tmux, and nothing
else (dev :7474, personal :7475, demo :7476) is touched. `HOME` and
`XDG_DATA_HOME` point at `dist/try/home`, so your `~/.config/xi` and sessions
are not used: pairing starts fresh and Claude auth is not shared (set
`ANTHROPIC_API_KEY` to send prompts). `dist/try/home` survives between runs;
delete it for a first-run experience.

The trial directory has its own `package.json` on purpose: `bun add` installs
into the nearest one above its cwd, and without it would install into the
repo root.

### On a clean machine (Docker)

`bb package:serve` still runs on your OS with your Bun. To see what a new user
sees (only Bun, nothing else installed), use a container:

```bash
bb package:docker              # build, recreate the container, wait, sign in to Claude
bb package:docker approve 1234 # pair the browser that shows code 1234
bb package:docker login        # sign in to Claude again
bb package:docker down         # remove the container (--fresh: and its home)
```

`scripts/try-package-docker.mjs` installs the packed tarball into an
`oven/bun` container the way `bun install -g xi-agent` does, and runs
`xi server --headless` there on host port 7478 (`--port N`; `--no-build`
reuses `dist/*.tgz`). The home directory is a named volume, so the Claude
login and the paired browsers survive re-running `bb package:docker`;
`--fresh` forgets them. With `ANTHROPIC_API_KEY` set in your shell the login
step is skipped and the key is passed in.

The container runs the server as the non-root `bun` user: Xi starts the Claude
CLI with `--dangerously-skip-permissions`, which the CLI refuses as root
("Claude Code process exited with code 1"). The image's `BUN_INSTALL_BIN` is
`/usr/local/bin`, which `bun` cannot write, so the script points it at
`~/.bun/bin`.
