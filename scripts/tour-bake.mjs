#!/usr/bin/env bun
/*
 * Bake a raw tour recording into the tape the site replays (xi.web.tour).
 *
 *   bun scripts/tour-bake.mjs <raw.json> <tour>
 *
 * <raw.json> is {recordedAt, frames: [[t, "in"|"out", transit] …]}, captured
 * in the browser against `bb tour <tour>` (see site/tours/README.md). Writes
 * site/public/tours/<tour>.json:
 *   - the tour HOME (/tmp/xi-tour-home) becomes /home/dev, so no local path
 *     ships; a frame still naming this repo or the real HOME aborts the bake
 *   - sends keep only their type (the client key never ships)
 *   - values the client makes up and the server echoes (:join-token) become
 *     @@join-token@@; the player fills in the replaying client's own
 *   - times start at 0
 *   - :gates, :steps and :variants (other runs of the same tape, picked with
 *     ?variant=) come from site/tours/<tour>/steps.json
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const REPO = path.resolve(import.meta.dir, "..");
// Same path as scripts/tour-seed.mjs and tour-home in bb.edn.
const TOUR_HOME = "/tmp/xi-tour-home";

const [rawPath, tour] = process.argv.slice(2);
if (!rawPath || !tour) {
  console.error("usage: bun scripts/tour-bake.mjs <raw.json> <tour>");
  process.exit(2);
}

const raw = JSON.parse(fs.readFileSync(rawPath, "utf8"));
const { gates, steps, variants = {} } = JSON.parse(
  fs.readFileSync(path.join(REPO, "site", "tours", tour, "steps.json"), "utf8"),
);

const typeOf = (transit) => (transit.match(/"~:type","~:([^"]+)"/) || [])[1] || "?";
const t0 = raw.frames.length ? raw.frames[0][0] : 0;

const echoes = raw.frames
  .filter(([, dir]) => dir === "out")
  .flatMap(([, , data]) => [...data.matchAll(/"~:(join-token)","([^"]+)"/g)])
  .map(([, key, value]) => [value, `@@${key}@@`]);
const fillEchoes = (data) => echoes.reduce((d, [value, slot]) => d.split(value).join(slot), data);

const frames = raw.frames.map(([t, dir, data]) =>
  dir === "out"
    ? [t - t0, "out", typeOf(data)]
    : [t - t0, "in", fillEchoes(data.split(TOUR_HOME).join("/home/dev"))]);

const leaks = [REPO, os.homedir()];
const leaked = frames.filter(([, , d]) => leaks.some((p) => d.includes(p)));
if (leaked.length) {
  console.error(`refusing to bake: ${leaked.length} frames still name ${leaks.join(" or ")}`);
  process.exit(1);
}

const out = path.join(REPO, "site", "public", "tours", `${tour}.json`);
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify({ version: 1, recordedAt: raw.recordedAt, gates, steps, variants, frames }));

const sends = frames.filter(([, d]) => d === "out").map(([, , ty]) => ty);
console.log(`Baked ${out}`);
console.log(`  ${frames.length} frames, ${(fs.statSync(out).size / 1024).toFixed(1)} KB`);
console.log(`  sends: ${sends.join(", ")}`);
console.log(`  gates: ${gates.join(", ")}`);
