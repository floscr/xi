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
//     resources/extensions/   demo extensions (config.edn :demo-extensions)
//     resources/providers.edn which providers are bundled, in picker order
//     providers/packages/<n>  the bundled providers and the provider kit, from
//                             providers/ (bb providers:fetch or providers:link)
//     LICENSE, THIRD_PARTY_NOTICES.md, README.md, CHANGELOG.md (when present)
//
// Paths between these are relative (the bundle finds resources/ and
// providers/ next to target/), so the layout above is part of the contract.
//
// The generated package.json is the repo's with private/devDependencies
// dropped: xi itself has no runtime dependencies. A bundled provider's own
// (the Claude provider's Agent SDK) are installed on the first start that
// loads it, into a copy under ~/.cache/xi/providers/bundled/
// (xi.providers.config), since a global install's directory is read-only.

import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
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
copy("resources/extensions");
// public/ minus the dev web build (the release one is already in place) and
// the local-only mkcert root CA
copy("resources/public", undefined, (src) => {
  const name = src.slice(rel("resources/public").length);
  return !(name === "/js" || name.startsWith("/js/") || name === "/mkcert-rootCA.crt");
});
copy("resources/providers.edn");
for (const f of ["LICENSE", "THIRD_PARTY_NOTICES.md", "README.md", "CHANGELOG.md"]) {
  if (existsSync(rel(f))) copy(f);
}

// ── Bundled providers ────────────────────────────────────────────────────────
// Their source as it is (Bun runs the .ts), without what only development
// needs: node_modules, tests, the nix pin and its `claude` out-link (a package
// install runs the claude on PATH, else the SDK's own binary).
const pin = readFileSync(rel("resources/providers.edn"), "utf8");
const bundled = [...(/:bundled\s+\[([^\]]*)\]/.exec(pin)?.[1] ?? "").matchAll(/"([^"]+)"/g)].map((m) => m[1]);
if (!existsSync(rel("providers"))) {
  console.error("no providers/ checkout: run bb providers:fetch (or bb providers:link)");
  process.exit(1);
}
const providersSrc = realpathSync(rel("providers"));
const skip = new Set(["node_modules", "test", "nix", "claude"]);
for (const name of [...bundled, "provider-kit"]) {
  const pkgDir = join(providersSrc, "packages", name);
  if (!existsSync(join(pkgDir, "package.json"))) {
    console.error(`missing bundled provider ${name} in ${providersSrc}`);
    process.exit(1);
  }
  const to = into(`providers/packages/${name}`);
  cpSync(pkgDir, to, {
    recursive: true,
    dereference: true,
    filter: (src) => !skip.has(src.slice(pkgDir.length + 1).split("/")[0]),
  });
  // The kit is no package dependency here (relinkKit below): `workspace:*`
  // would stop the provider's own `bun install`.
  const pkgJson = JSON.parse(readFileSync(join(to, "package.json"), "utf8"));
  for (const dep of Object.keys(pkgJson.dependencies ?? {})) {
    if (dep.startsWith("@xi-agent/")) delete pkgJson.dependencies[dep];
  }
  writeFileSync(join(to, "package.json"), JSON.stringify(pkgJson, null, 2) + "\n");
}

// npm pack leaves out every node_modules directory, so the kit can't be
// resolved as a package there: its imports become relative paths.
const kitSrc = into("providers/packages/provider-kit/src");
function relinkKit(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) relinkKit(p);
    else if (e.name.endsWith(".ts")) {
      const src = readFileSync(p, "utf8");
      const out = src.replace(/(["'])@xi-agent\/provider-kit(?:\/([\w-]+))?\1/g, (_m, q, sub) => {
        let r = relative(dirname(p), join(kitSrc, (sub ?? "index") + ".ts"));
        if (!r.startsWith(".")) r = "./" + r;
        return q + r + q;
      });
      if (out !== src) writeFileSync(p, out);
    }
  }
}
for (const name of bundled) relinkKit(into(`providers/packages/${name}`));

// ── package.json ─────────────────────────────────────────────────────────────
const repo = JSON.parse(readFileSync(rel("package.json"), "utf8"));
const { private: _private, devDependencies: _dev, scripts: _scripts, ...pub } = repo;
writeFileSync(
  into("package.json"),
  JSON.stringify(pub, null, 2) + "\n",
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
