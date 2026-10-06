// Tries the npm package the way a user gets it: packs it, installs the tarball
// into a clean directory and runs the installed `xi` as a headless server in
// the foreground (Ctrl-C stops it). No tmux, and it never touches the dev
// servers: its own port and an isolated HOME.
//
//   bun scripts/try-package.mjs [--port N] [--no-build]     (or: bb package:serve)
//
//   --port N     port to listen on (default 7477; dev 7474 · personal 7475 · demo 7476)
//   --no-build   reuse the tarball already in dist/ instead of rebuilding it
//
// State lives under dist/try (gitignored): the install in dist/try/node_modules
// is recreated on every run, the HOME in dist/try/home is kept, so pairing and
// settings survive between runs. `rm -rf dist/try/home` starts a first run over.

import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");
const trial = join(dist, "try");
const home = join(trial, "home");

const args = process.argv.slice(2);
const portAt = args.indexOf("--port");
const port = portAt >= 0 ? args[portAt + 1] : "7477";
if (!/^\d+$/.test(port ?? "")) {
  console.error("--port needs a number");
  process.exit(2);
}

const run = (cmd, argv, opts = {}) => {
  const r = spawnSync(cmd, argv, { stdio: "inherit", ...opts });
  if (r.error || r.status !== 0) {
    console.error(`\n${cmd} ${argv.join(" ")} failed`);
    process.exit(r.status ?? 1);
  }
};

if (!args.includes("--no-build")) run("bun", [join(root, "scripts/build-package.mjs")], { cwd: root });

// The tarball name comes from the staged package.json, so a stale tarball of
// another version in dist/ is never picked up.
const pkgFile = join(dist, "pkg", "package.json");
if (!existsSync(pkgFile)) {
  console.error("dist/pkg is missing — run without --no-build first");
  process.exit(1);
}
const pkg = JSON.parse(readFileSync(pkgFile, "utf8"));
const tarball = join(dist, `${pkg.name}-${pkg.version}.tgz`);
if (!existsSync(tarball)) {
  console.error(`${tarball} is missing — run without --no-build first`);
  process.exit(1);
}

rmSync(join(trial, "node_modules"), { recursive: true, force: true });
rmSync(join(trial, "bun.lock"), { force: true });
mkdirSync(home, { recursive: true });
// `bun add` installs into the nearest package.json above its cwd. Without one
// here it would walk up and install into the repo root, so give the trial
// directory its own.
writeFileSync(join(trial, "package.json"), '{"name": "xi-package-try", "private": true}\n');
run("bun", ["add", tarball], { cwd: trial });
const bin = join(trial, "node_modules", ".bin", "xi");
if (!existsSync(bin)) {
  console.error(`install did not produce ${bin}`);
  process.exit(1);
}

console.log(`\nxi ${pkg.version} installed from ${tarball}`);
console.log(`web:  http://localhost:${port}`);
console.log(`home: ${home}  (isolated — your ~/.config/xi is not used)`);
console.log("Claude auth is not shared; set ANTHROPIC_API_KEY to send prompts.\n");

const server = spawn(
  bin,
  ["server", "--headless", "--port", port],
  {
    cwd: trial,
    stdio: "inherit",
    // XDG_DATA_HOME too: user extensions keep their data there, and an
    // inherited absolute value would point at the real one.
    env: { ...process.env, HOME: home, XDG_DATA_HOME: join(home, ".local", "share") },
  },
);
// The terminal delivers Ctrl-C to the whole foreground group; waiting for the
// server to exit keeps this script from returning before the port is released.
process.on("SIGINT", () => {});
process.on("SIGTERM", () => server.kill("SIGTERM"));
server.on("exit", (code, signal) => process.exit(signal ? 0 : (code ?? 0)));
