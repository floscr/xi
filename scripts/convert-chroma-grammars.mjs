#!/usr/bin/env bun
/**
 * Converts chroma XML lexer definitions to xi highlight grammar EDN.
 * 
 * Downloads all grammars from github.com/alecthomas/chroma,
 * extracts root-state rules (inlining includes), and generates
 * a CLJS file with the grammar data.
 * 
 * Usage: bun scripts/convert-chroma-grammars.mjs
 */

const API_URL = "https://api.github.com/repos/alecthomas/chroma/contents/lexers/embedded";
const RAW_BASE = "https://raw.githubusercontent.com/alecthomas/chroma/master/lexers/embedded/";

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
  // Extract config
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

  // Extract states
  const states = {};
  const stateRegex = /<state name="([^"]+)">([\s\S]*?)<\/state>/g;
  for (const sm of xml.matchAll(stateRegex)) {
    const stateName = sm[1];
    const stateBody = sm[2];
    const rules = [];

    // Match rules with patterns
    const ruleRegex = /<rule pattern="([^"]*)">([\s\S]*?)<\/rule>/g;
    for (const rm of stateBody.matchAll(ruleRegex)) {
      const pattern = decodeEntities(rm[1]);
      const ruleBody = rm[2];
      
      // Simple token rule
      const tokenMatch = ruleBody.match(/<token type="([^"]+)"/);
      if (tokenMatch) {
        // Skip rules with push/pop (state transitions)
        if (ruleBody.includes("<push") || ruleBody.includes("<pop")) continue;
        // Skip bygroups rules (complex multi-token matching)
        if (ruleBody.includes("<bygroups")) continue;
        // Skip using rules (lexer delegation)
        if (ruleBody.includes("<using")) continue;
        
        rules.push({ pattern, token: mapTokenType(tokenMatch[1]) });
      }
    }

    // Match include directives
    const includeRegex = /<rule>\s*<include\s+state="([^"]+)"\s*\/>\s*<\/rule>/g;
    for (const im of stateBody.matchAll(includeRegex)) {
      rules.push({ include: im[1] });
    }

    states[stateName] = rules;
  }

  return { config, states };
}

function resolveIncludes(states, stateName, visited = new Set()) {
  if (visited.has(stateName)) return []; // Avoid cycles
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
  // Convert Python-style named groups (?P<name>...) to JS (?<name>...)
  let fixed = pattern.replace(/\(\?P</g, "(?<");
  // Convert Python-style named backrefs (?P=name) to JS \k<name>
  fixed = fixed.replace(/\(\?P=(\w+)\)/g, "\\k<$1>");
  return fixed;
}

// ── CLJS Generation ───────────────────────────────────────────────────────

function escapeClj(s) {
  return s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function grammarToClj(name, rules) {
  if (rules.length === 0) return null;
  
  const lines = rules.map(r => {
    const escaped = escapeClj(r.pattern);
    return `   {:pattern "${escaped}" :token :${r.token}}`;
  });
  
  return `(def ${name}\n  [${lines.join("\n")  }])`;
}

function sanitizeName(name) {
  let s = name
    .toLowerCase()
    .replace(/\+/g, "plus")  // C++ -> cplusplus
    .replace(/#/g, "sharp")  // C# -> csharp
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .replace(/^(\d)/, "lang-$1"); // Can't start with digit in CLJS
  return s;
}

// ── Main ──────────────────────────────────────────────────────────────────

async function main() {
  console.error("Fetching grammar list...");
  
  // Get file list
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
        
        // Resolve root state with includes
        const rootRules = resolveIncludes(states, "root");
        
        // Filter to JS-compatible regexes
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
  
  // Sort by name
  grammars.sort((a, b) => a.name.localeCompare(b.name));
  
  // Generate CLJS
  const parts = [];
  parts.push(`(ns xi.highlight.grammars
  "Syntax highlighting grammars — ordered regex rules per language.
   Auto-generated from chroma lexer definitions (MIT licensed).
   See: https://github.com/alecthomas/chroma
   
   Each grammar is a vector of {:pattern regex-str :token token-type}.
   Generated by scripts/convert-chroma-grammars.mjs")`);
  parts.push("");
  
  // Grammar definitions — deduplicate def names (first wins)
  const defNames = new Set();
  for (const g of grammars) {
    if (defNames.has(g.name)) {
      console.error(`  Skipping duplicate def: ${g.name} (${g.displayName})`);
      g.skippedDef = true;
      continue;
    }
    defNames.add(g.name);
    const clj = grammarToClj(g.name, g.rules);
    if (clj) {
      parts.push(`;; ── ${g.displayName} ${"─".repeat(Math.max(1, 65 - g.displayName.length))}`)
      parts.push("");
      parts.push(clj);
      parts.push("");
    }
  }
  
  // Registry
  parts.push(";; ── Registry ─────────────────────────────────────────────────────────────────");
  parts.push("");
  parts.push("(def registry");
  const entries = [];
  const seen = new Set();
  for (const g of grammars) {
    if (g.rules.length === 0 || g.skippedDef) continue;
    // Add the canonical name
    if (!seen.has(g.name)) {
      entries.push(`   "${g.name}" ${g.name}`);
      seen.add(g.name);
    }
    // Add aliases
    for (const alias of g.aliases) {
      if (!seen.has(alias)) {
        entries.push(`   "${alias}" ${g.name}`);
        seen.add(alias);
      }
    }
    // Add filename-based aliases (e.g. "*.py" -> strip to "py")
    for (const fn of g.filenames) {
      const ext = fn.replace(/^\*\./, "").toLowerCase();
      if (!seen.has(ext) && /^[a-z][a-z0-9]*$/.test(ext)) {
        entries.push(`   "${ext}" ${g.name}`);
        seen.add(ext);
      }
    }
  }
  parts.push(`  {${entries.join("\n")  }})`);
  parts.push("");
  
  parts.push(`(defn get-grammar
  "Look up a grammar by language name (case-insensitive). Returns nil if unknown."
  [lang]
  (when lang
    (get registry (-> lang .toLowerCase .trim))))`);
  
  console.log(parts.join("\n"));
  
  // Stats
  console.error(`\nGenerated ${grammars.length} grammars with ${entries.length} registry entries`);
}

main().catch(e => { console.error(e); process.exit(1); });
