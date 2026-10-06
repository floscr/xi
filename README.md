# Xi

Xi is an extendable coding harness for the terminal, with a web UI that joins
the same sessions. It runs Claude through the Claude Agent SDK, keeps every
tool call on your machine, and lets you shape it: permission rules in EDN, a
sandboxed Clojure REPL as the agent's shell, MCP servers, and extensions you
write yourself.

Documentation and the user guide: <https://xi.florianschroedl.com>

## Install

Xi runs on [Bun](https://bun.sh) 1.3 or newer.

```sh
bun install -g xi-agent
```

Then, in a project:

```sh
xi            # the terminal client
xi server     # the same, plus the web client on http://localhost:7474
```

Xi uses your Claude Code login when there is one; otherwise set
`ANTHROPIC_API_KEY`. The [Getting started](https://xi.florianschroedl.com/docs/getting-started/)
page walks through the first run, the browser, and pairing another device.

## From source

```sh
git clone https://github.com/floscr/xi
cd xi
npm install
bb build        # the terminal client and server
bb web:build    # the web client
bin/xi
```

The `bb` tasks need [Babashka](https://babashka.org); `bb tasks` lists them.
Contributor documentation lives in [AGENTS.md](AGENTS.md) and [docs/](docs/).

## License

[MIT](LICENSE). Third-party notices are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
