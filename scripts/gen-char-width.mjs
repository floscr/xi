#!/usr/bin/env node
// Generates src/xi/tui/char_width_data.cljs — the set of code points that
// occupy two terminal columns.
//
// A code point is double-width iff (UAX #11 + UTR #51):
//   * its East Asian Width is Wide (W) or Fullwidth (F), OR
//   * it has Emoji_Presentation=Yes (renders as a wide emoji by default).
//
// Rather than hand-curate ranges (which inevitably grow gaps as Unicode adds
// blocks — e.g. the colored circles 🟠🟡🟢 in U+1F7E0), we derive them from the
// authoritative Unicode data files and emit a sorted, merged range table for
// binary search.
//
// Re-run when bumping Unicode versions:  node scripts/gen-char-width.mjs
import { writeFileSync } from "node:fs";

const UNICODE_VERSION = "16.0.0";
const BASE = `https://www.unicode.org/Public/${UNICODE_VERSION}/ucd`;
const EAW_URL = `${BASE}/EastAsianWidth.txt`;
const EMOJI_URL = `${BASE}/emoji/emoji-data.txt`;

const OUT = new URL("../src/xi/tui/char_width_data.cljs", import.meta.url);

async function fetchText(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`GET ${url} → ${res.status}`);
  return res.text();
}

// Parse a UCD line's code point field: "1F7E0" or "1F7E0..1F7EB" → [lo, hi].
function parseRange(field) {
  const [a, b] = field.trim().split("..");
  const lo = parseInt(a, 16);
  const hi = b != null ? parseInt(b, 16) : lo;
  return [lo, hi];
}

function eastAsianWideRanges(text) {
  const out = [];
  for (const raw of text.split("\n")) {
    const line = raw.split("#")[0].trim();
    if (!line) continue;
    const [cp, width] = line.split(";").map((s) => s.trim());
    if (width === "W" || width === "F") out.push(parseRange(cp));
  }
  return out;
}

function emojiPresentationRanges(text) {
  const out = [];
  for (const raw of text.split("\n")) {
    const line = raw.split("#")[0].trim();
    if (!line) continue;
    const [cp, prop] = line.split(";").map((s) => s.trim());
    if (prop === "Emoji_Presentation") out.push(parseRange(cp));
  }
  return out;
}

// Sort by low bound, then merge overlapping/adjacent ranges.
function mergeRanges(ranges) {
  const sorted = [...ranges].sort((x, y) => x[0] - y[0]);
  const merged = [];
  for (const [lo, hi] of sorted) {
    const last = merged[merged.length - 1];
    if (last && lo <= last[1] + 1) {
      last[1] = Math.max(last[1], hi);
    } else {
      merged.push([lo, hi]);
    }
  }
  return merged;
}

function toCljs(ranges) {
  const flat = ranges.flat();
  const rows = [];
  for (let i = 0; i < flat.length; i += 16) {
    rows.push(
      "  " +
        flat
          .slice(i, i + 16)
          .map((n) => "0x" + n.toString(16).toUpperCase())
          .join(" "),
    );
  }
  return `(ns xi.tui.char-width-data
  "GENERATED — do not edit by hand. Run scripts/gen-char-width.mjs to regenerate.

   Sorted, non-overlapping ranges of code points that occupy two terminal
   columns: East Asian Width W/F union Emoji_Presentation=Yes, from Unicode
   ${UNICODE_VERSION}. Stored as a flat js array [lo0 hi0 lo1 hi1 ...] (${ranges.length} ranges)
   for binary search — see xi.tui.ansi/wide?.")

(def wide-ranges
  #js [
${rows.join("\n")}])
`;
}

const [eawText, emojiText] = await Promise.all([
  fetchText(EAW_URL),
  fetchText(EMOJI_URL),
]);

const ranges = mergeRanges([
  ...eastAsianWideRanges(eawText),
  ...emojiPresentationRanges(emojiText),
]);

writeFileSync(OUT, toCljs(ranges));
console.log(`Wrote ${ranges.length} ranges to ${OUT.pathname} (Unicode ${UNICODE_VERSION})`);
