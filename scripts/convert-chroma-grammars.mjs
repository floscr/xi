#!/usr/bin/env bun
/**
 * Converts chroma XML lexer definitions to EDN grammar files.
 * 
 * Downloads all grammars from github.com/alecthomas/chroma,
 * extracts root-state rules (inlining includes), and generates:
 *   - Individual EDN files per grammar in resources/highlight/grammars/
 *   - A registry.edn mapping aliases → grammar filenames
 * 
 * Usage: bun scripts/convert-chroma-grammars.mjs
 */

import { writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const API_URL = "https://api.github.com/repos/alecthomas/chroma/contents/lexers/embedded";
const RAW_BASE = "https://raw.githubusercontent.com/alecthomas/chroma/master/lexers/embedded/";
const OUT_DIR = join(import.meta.dir, "../resources/highlight/grammars");

// ── Token type mapping ─────────────────────────────────────────────────────

const TOKEN_MAP = {
  // Comments
  "Comment": "comment", "CommentSingle": "comment", "CommentMultiline": "comment",
  "CommentSpecial": "comment", "CommentPreproc": "comment", "CommentPreprocFile": "comment",
  "CommentHashbang": "comment",
  // Strings
  "LiteralString": "string", "LiteralStringAffix": "string", "LiteralStringBacktick": "string",
  "LiteralStringChar": "string-char", "LiteralStringDelimiter": "string",
  "LiteralStringDoc": "string", "LiteralStringDouble": "string", "LiteralStringEscape": "string",
  "LiteralStringHeredoc": "string", "LiteralStringInterpol": "string",
  "LiteralStringOther": "string", "LiteralStringRegex": "string",
  "LiteralStringSingle": "string", "LiteralStringSymbol": "string-symbol",
  "LiteralStringBoolean": "string",
  // Numbers
  "LiteralNumber": "number", "LiteralNumberBin": "number", "LiteralNumberFloat": "number",
  "LiteralNumberHex": "number", "LiteralNumberInteger": "number",
  "LiteralNumberIntegerLong": "number", "LiteralNumberOct": "number",
  // Keywords
  "Keyword": "keyword", "KeywordConstant": "keyword", "KeywordDeclaration": "keyword-decl",
  "KeywordNamespace": "keyword", "KeywordPseudo": "keyword",
  "KeywordReserved": "keyword", "KeywordType": "keyword-type",
  // Names
  "NameBuiltin": "name-builtin", "NameBuiltinPseudo": "name-builtin",
  "NameClass": "name-class", "NameConstant": "name-var",
  "NameDecorator": "name-builtin", "NameEntity": "name-var",
  "NameException": "name-class", "NameFunction": "name-fn",
  "NameFunctionMagic": "name-fn", "NameLabel": "name-var",
  "NameNamespace": "name-var", "NameOther": "name-var",
  "NameProperty": "name-var", "NameTag": "keyword",
  "NameVariable": "name-var", "NameVariableClass": "name-var",
  "NameVariableGlobal": "name-var", "NameVariableInstance": "name-var",
  "NameVariableMagic": "name-var", "NameAttribute": "name-var",
  // Operators
  "Operator": "operator", "OperatorWord": "operator",
  // Punctuation
  "Punctuation": "punctuation",
  // Literals
  "Literal": "string", "LiteralDate": "string",
  // Generic
  "GenericDeleted": "operator", "GenericEmph": "text", "GenericError": "operator",
  "GenericHeading": "keyword", "GenericInserted": "string",
  "GenericOutput": "text", "GenericPrompt": "keyword",
  "GenericStrong": "keyword", "GenericSubheading": "keyword",
  "GenericTraceback": "operator", "GenericUnderline": "text",
  // Other
  "Text": "text", "TextWhitespace": "text", "TextSymbol": "text",
  "Other": "text", "Error": "text",
};

function mapTokenType(chromaType) {
  return TOKEN_MAP[chromaType] || "text";
}

// ── XML Parsing (minimal, no deps) ────────────────────────────────────────

function decodeEntities(s) {
  return s
    .replace(/&#34;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function parseXML(xml) {
  const config = {};
  const nameMatch = xml.match(/<name>([^<]+)<\/name>/);
  if (nameMatch) config.name = nameMatch[1];
  
  config.aliases = [];
  for (const m of xml.matchAll(/<alias>([^<]+)<\/alias>/g)) {
    config.aliases.push(m[1].toLowerCase());
  }
  config.filenames = [];
  for (const m of xml.matchAll(/<filename>([^<]+)<\/filename>/g)) {
    config.filenames.push(m[1]);
  }

  const states = {};
  const stateRegex = /<state name="([^"]+)">([\s\S]*?)<\/state>/g;
  for (const sm of xml.matchAll(stateRegex)) {
    const stateName = sm[1];
    const stateBody = sm[2];
    const rules = [];

    const ruleRegex = /<rule pattern="([^"]*)">([\s\S]*?)<\/rule>/g;
    for (const rm of stateBody.matchAll(ruleRegex)) {
      const pattern = decodeEntities(rm[1]);
      const ruleBody = rm[2];
      
      const tokenMatch = ruleBody.match(/<token type="([^"]+)"/);
      if (tokenMatch) {
        if (ruleBody.includes("<push") || ruleBody.includes("<pop")) continue;
        if (ruleBody.includes("<bygroups")) continue;
        if (ruleBody.includes("<using")) continue;
        
        rules.push({ pattern, token: mapTokenType(tokenMatch[1]) });
      }
    }

    const includeRegex = /<rule>\s*<include\s+state="([^"]+)"\s*\/>\s*<\/rule>/g;
    for (const im of stateBody.matchAll(includeRegex)) {
      rules.push({ include: im[1] });
    }

    states[stateName] = rules;
  }

  return { config, states };
}

function resolveIncludes(states, stateName, visited = new Set()) {
  if (visited.has(stateName)) return [];
  visited.add(stateName);
  
  const rules = states[stateName] || [];
  const resolved = [];
  
  for (const rule of rules) {
    if (rule.include) {
      resolved.push(...resolveIncludes(states, rule.include, visited));
    } else {
      resolved.push(rule);
    }
  }
  
  return resolved;
}

// ── Regex compatibility ───────────────────────────────────────────────────

function isJSCompatible(pattern) {
  try {
    new RegExp(pattern, "y");
    return true;
  } catch {
    return false;
  }
}

function fixupRegex(pattern) {
  let fixed = pattern.replace(/\(\?P</g, "(?<");
  fixed = fixed.replace(/\(\?P=(\w+)\)/g, "\\k<$1>");
  return fixed;
}

// ── EDN Generation ────────────────────────────────────────────────────────

function escapeEdn(s) {
  return s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function grammarToEdn(rules) {
  if (rules.length === 0) return null;
  const lines = rules.map(r => {
    return `   {:pattern "${escapeEdn(r.pattern)}" :token :${r.token}}`;
  });
  return `[${lines.join("\n")}]`;
}

function sanitizeName(name) {
  let s = name
    .toLowerCase()
    .replace(/\+/g, "plus")
    .replace(/#/g, "sharp")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .replace(/^(\d)/, "lang-$1");
  return s;
}

function toFilename(name) {
  // EDN filenames use underscores (matching ClojureScript conventions)
  return name.replace(/-/g, "_");
}

// ── Main ──────────────────────────────────────────────────────────────────

async function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  
  console.error("Fetching grammar list...");
  
  const listResp = await fetch(API_URL, {
    headers: { "Accept": "application/json" }
  });
  const files = await listResp.json();
  const xmlFiles = files.filter(f => f.name.endsWith(".xml")).map(f => f.name);
  
  console.error(`Found ${xmlFiles.length} grammars`);
  
  const grammars = [];
  const errors = [];
  
  // Process in batches of 10
  for (let i = 0; i < xmlFiles.length; i += 10) {
    const batch = xmlFiles.slice(i, i + 10);
    const results = await Promise.all(batch.map(async (filename) => {
      try {
        const resp = await fetch(RAW_BASE + filename);
        const xml = await resp.text();
        const { config, states } = parseXML(xml);
        
        const rootRules = resolveIncludes(states, "root");
        
        const compatible = [];
        let skipped = 0;
        for (const rule of rootRules) {
          const fixed = fixupRegex(rule.pattern);
          if (isJSCompatible(fixed)) {
            compatible.push({ ...rule, pattern: fixed });
          } else {
            skipped++;
          }
        }
        
        if (compatible.length === 0) {
          return { filename, error: "no compatible rules" };
        }
        
        const name = sanitizeName(config.name || filename.replace(".xml", ""));
        
        return {
          filename,
          name,
          displayName: config.name,
          aliases: config.aliases,
          filenames: config.filenames,
          rules: compatible,
          skipped,
          total: rootRules.length,
        };
      } catch (e) {
        return { filename, error: e.message };
      }
    }));
    
    for (const r of results) {
      if (r.error) {
        errors.push(r);
      } else {
        grammars.push(r);
      }
    }
    
    console.error(`  Processed ${Math.min(i + 10, xmlFiles.length)}/${xmlFiles.length}...`);
  }
  
  console.error(`\nConverted: ${grammars.length}, Errors: ${errors.length}`);
  if (errors.length > 0) {
    console.error("Errors:", errors.map(e => `${e.filename}: ${e.error}`).join(", "));
  }
  
  grammars.sort((a, b) => a.name.localeCompare(b.name));
  
  // Write individual EDN grammar files — deduplicate def names (first wins)
  const defNames = new Set();
  const validGrammars = [];
  
  for (const g of grammars) {
    if (defNames.has(g.name)) {
      console.error(`  Skipping duplicate: ${g.name} (${g.displayName})`);
      continue;
    }
    defNames.add(g.name);
    
    const edn = grammarToEdn(g.rules);
    if (edn) {
      const fname = toFilename(g.name);
      writeFileSync(join(OUT_DIR, `${fname}.edn`), edn + "\n");
      validGrammars.push({ ...g, fname });
    }
  }
  
  // Build registry — alias → filename
  const entries = [];
  const seen = new Set();
  
  for (const g of validGrammars) {
    // Canonical name
    if (!seen.has(g.name)) {
      entries.push(`"${g.name}" "${g.fname}"`);
      seen.add(g.name);
    }
    // Aliases
    for (const alias of g.aliases) {
      if (!seen.has(alias)) {
        entries.push(`"${alias}" "${g.fname}"`);
        seen.add(alias);
      }
    }
    // Filename extensions
    for (const fn of g.filenames) {
      const ext = fn.replace(/^\*\./, "").toLowerCase();
      if (!seen.has(ext) && /^[a-z][a-z0-9]*$/.test(ext)) {
        entries.push(`"${ext}" "${g.fname}"`);
        seen.add(ext);
      }
    }
  }
  
  // Add manual aliases
  if (!seen.has("cljs")) entries.push(`"cljs" "clojure"`);
  if (!seen.has("cljc")) entries.push(`"cljc" "clojure"`);
  
  const registryEdn = `{${entries.join("\n ")}}`;
  writeFileSync(join(OUT_DIR, "registry.edn"), registryEdn + "\n");
  
  console.error(`\nWrote ${validGrammars.length} grammar EDN files + registry (${entries.length} entries)`);
}

main().catch(e => { console.error(e); process.exit(1); });
