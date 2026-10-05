#!/usr/bin/env bun
// Rebuilds resources/treesitter/ from resources/treesitter/manifest.json:
//
//   web-tree-sitter.{cjs,wasm}   the runtime, unpacked from its npm tarball
//   grammars/<lang>.wasm         each grammar, built from its pinned git rev
//
// Usage:  bun scripts/build-treesitter-wasm.mjs [lang …]    (default: runtime + all grammars)
//         bb treesitter:build [lang …]
//
// Needs `git`, `tar` and the tree-sitter CLI (≥ 0.26: `tree-sitter build --wasm`
// downloads its own wasi-sdk, so no emscripten/docker). Point $TREE_SITTER at a
// different invocation when the plain binary can't run, e.g. on NixOS the
// downloaded wasi-sdk needs an FHS shell:
//
//   TREE_SITTER="steam-run tree-sitter" bb treesitter:build
//
// Grammar checkouts go under target/treesitter-build/ (gitignored).

import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const outDir = join(root, "resources", "treesitter");
const grammarsOut = join(outDir, "grammars");
const work = join(root, "target", "treesitter-build");
const manifest = JSON.parse(readFileSync(join(outDir, "manifest.json"), "utf8"));
const treeSitter = (process.env.TREE_SITTER || "tree-sitter").split(/\s+/);

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: "inherit", ...opts });
  if (r.status !== 0) {
    console.error(`failed: ${[cmd, ...args].join(" ")}`);
    process.exit(r.status ?? 1);
  }
}

async function buildRuntime() {
  const { package: pkg, version } = manifest.runtime;
  const tgz = join(work, `${pkg}-${version}.tgz`);
  const unpacked = join(work, "runtime");
  mkdirSync(unpacked, { recursive: true });
  const res = await fetch(`https://registry.npmjs.org/${pkg}/-/${pkg}-${version}.tgz`);
  if (!res.ok) throw new Error(`fetching ${pkg}@${version}: HTTP ${res.status}`);
  await Bun.write(tgz, res);
  run("tar", ["-xzf", tgz, "-C", unpacked, "--strip-components=1",
    "package/web-tree-sitter.cjs", "package/web-tree-sitter.wasm", "package/LICENSE"]);
  cpSync(join(unpacked, "web-tree-sitter.cjs"), join(outDir, "web-tree-sitter.cjs"));
  cpSync(join(unpacked, "web-tree-sitter.wasm"), join(outDir, "web-tree-sitter.wasm"));
  cpSync(join(unpacked, "LICENSE"), join(outDir, "web-tree-sitter.LICENSE"));
  console.log(`runtime: ${pkg}@${version}`);
}

function checkout({ repo, rev }, dir) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  run("git", ["init", "-q"], { cwd: dir });
  run("git", ["fetch", "-q", "--depth", "1", repo, rev], { cwd: dir });
  run("git", ["checkout", "-q", "FETCH_HEAD"], { cwd: dir });
}

function buildGrammar(lang) {
  const g = manifest.grammars[lang];
  if (!g) throw new Error(`unknown grammar "${lang}" (see manifest.json)`);
  // typescript and tsx share one checkout
  const dir = join(work, g.repo.split("/").pop() + "-" + g.rev.slice(0, 12));
  if (!existsSync(join(dir, ".git"))) checkout(g, dir);
  const [cmd, ...pre] = treeSitter;
  run(cmd, [...pre, "build", "--wasm", "-o", join(grammarsOut, `${lang}.wasm`), g.subdir ? join(dir, g.subdir) : dir]);
  console.log(`grammar: ${lang} @ ${g.rev}`);
}

mkdirSync(work, { recursive: true });
mkdirSync(grammarsOut, { recursive: true });
const wanted = process.argv.slice(2);
if (wanted.length === 0) await buildRuntime();
for (const lang of wanted.length ? wanted : Object.keys(manifest.grammars)) buildGrammar(lang);
