#!/usr/bin/env bun
// Assembles the npm package into dist/pkg and packs it to dist/<name>-<version>.tgz.
//
//   bun scripts/build-package.mjs [--no-pack]        (or: bb package)
//
// The package is built in a staging directory instead of from the working
// tree so that it contains exactly what Xi needs at runtime — release
// bundles, not the dev builds the shadow-cljs watch keeps in target/ and
// resources/public/js — and never picks up untracked files. The watch is left
// alone: both releases are written straight into dist/.
//
//   dist/pkg/
//     package.json            generated from the repo one (see below)
//     bin/xi.js               launcher (hands over to bun)
//     target/main.js          release bundle of the :main build
//     resources/public/       web client incl. the release :web build in js/
//     resources/highlight/    syntax grammars
//     resources/treesitter/   web-tree-sitter runtime + WASM grammars
//     packages/providers/anthropic/runner.mjs   the Claude SDK runner
//     LICENSE, THIRD_PARTY_NOTICES.md, README.md, CHANGELOG.md (when present)
//
// Paths between these are relative (the bundle finds resources/ and packages/
// next to target/), so the layout above is part of the contract.
//
// The generated package.json is the repo's with private/devDependencies
// dropped and the runner's dependencies (@anthropic-ai/claude-agent-sdk, zod)
// promoted to the package's own `dependencies`: the runner would otherwise
// `npm install` into the package directory on first use, which is read-only
// for a global install.

import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const dist = join(root, "dist");
const stage = join(dist, "pkg");
const rel = (p) => join(root, p);
const into = (p) => join(stage, p);

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: "inherit", cwd: root, ...opts });
  if (r.status !== 0) {
    console.error(`failed: ${[cmd, ...args].join(" ")}`);
    process.exit(r.status ?? 1);
  }
}

function copy(from, to, filter) {
  cpSync(rel(from), into(to ?? from), { recursive: true, filter });
}

rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });

// ── Release builds ───────────────────────────────────────────────────────────
run("npx", ["shadow-cljs", "release", "main", "--config-merge",
  `{:output-to "dist/pkg/target/main.js"}`]);
run("npx", ["shadow-cljs", "release", "web", "--config-merge",
  `{:output-dir "dist/pkg/resources/public/js"}`]);

// ── Runtime files ────────────────────────────────────────────────────────────
copy("bin/xi.js");
copy("resources/highlight");
copy("resources/treesitter");
// public/ minus the dev web build (the release one is already in place) and
// the local-only mkcert root CA
copy("resources/public", undefined, (src) => {
  const name = src.slice(rel("resources/public").length);
  return !(name === "/js" || name.startsWith("/js/") || name === "/mkcert-rootCA.crt");
});
copy("packages/providers/anthropic/runner.mjs");
for (const f of ["LICENSE", "THIRD_PARTY_NOTICES.md", "README.md", "CHANGELOG.md"]) {
  if (existsSync(rel(f))) copy(f);
}

// ── package.json ─────────────────────────────────────────────────────────────
const repo = JSON.parse(readFileSync(rel("package.json"), "utf8"));
const runner = JSON.parse(readFileSync(rel("packages/providers/anthropic/package.json"), "utf8"));
const { private: _private, devDependencies: _dev, scripts: _scripts, ...pub } = repo;
writeFileSync(
  into("package.json"),
  JSON.stringify({ ...pub, dependencies: runner.dependencies }, null, 2) + "\n",
);

// ── Pack ─────────────────────────────────────────────────────────────────────
function size(dir) {
  let n = 0;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    n += e.isDirectory() ? size(p) : statSync(p).size;
  }
  return n;
}
console.log(`\nstaged ${(size(stage) / 1e6).toFixed(1)} MB in dist/pkg`);

if (!process.argv.includes("--no-pack")) {
  run("npm", ["pack", "--pack-destination", dist], { cwd: stage });
}
