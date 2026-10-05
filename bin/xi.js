#!/usr/bin/env node
// The `xi` command. Xi runs on Bun (it uses Bun.serve, Bun.spawn, …), but npm
// installs the bin with whatever runtime the shebang names, so this stub is
// plain Node-compatible JS: under Bun it loads the bundle in-process, anywhere
// else it hands over to `bun` — or says what to install.

const { spawnSync } = require("node:child_process");
const path = require("node:path");

const main = path.join(__dirname, "..", "target", "main.js");

if (typeof Bun !== "undefined") {
  require(main);
} else {
  const r = spawnSync("bun", [main, ...process.argv.slice(2)], { stdio: "inherit" });
  if (r.error && r.error.code === "ENOENT") {
    process.stderr.write(
      "xi needs Bun (https://bun.sh) and could not find `bun` on your PATH.\n" +
      "Install it, e.g.  curl -fsSL https://bun.sh/install | bash\n"
    );
    process.exit(127);
  }
  if (r.error) throw r.error;
  if (r.signal) process.kill(process.pid, r.signal);
  process.exit(r.status ?? 1);
}
