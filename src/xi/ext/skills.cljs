(ns xi.ext.skills
  "Skills extension — detects project type and injects tool knowledge into context.
   Skills are conditionally loaded based on marker files in the project."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

;; ── Skill Definitions ─────────────────────────────────────────────────────────
;;
;; Each skill has:
;;   :name     — identifier
;;   :markers  — set of filenames that trigger this skill
;;   :content  — the skill prompt text (inline)
;;   :load-when — (optional) custom predicate (fn [cwd] -> bool)
;;               defaults to checking :markers

(defn- file-exists-in-cwd?
  "Check if any of the given filenames exist in cwd."
  [cwd filenames]
  (some #(fs/existsSync (.join node-path cwd %)) filenames))

(def ^:private CLOJURE_SKILL
  "# Clojure Structural Operations (clj-surgeon)

You have `clj-surgeon` available — a babashka CLI for structural Clojure refactoring via AST (rewrite-clj). ~5ms startup, returns EDN.

## Proactive Rules

- **Before reading any .clj/.cljs/.cljc file over 200 lines**, run `clj_outline` first (~50 tokens vs thousands)
- **When you see or add a `(declare ...)`**, run `clj_fix_declares` to eliminate it
- **Before manually moving forms between files**, use `clj_extract`

## Available Tools

- `clj_outline` — form boundaries, types, arglists. Use BEFORE reading large files.
- `clj_tree` — map entire project namespace tree
- `clj_deps` — transitive dependency tree for a form
- `clj_extract` — extract forms to a new namespace (dry-run by default)
- `clj_fix_declares` — eliminate forward declares by reordering (dry-run by default)
- `clj_mv` — reorder a form within a file
- `clj_rename_ns` — structural namespace rename across all files
- `clj_topo` — topological sort showing optimal form ordering

## Workflow: Split Large File

1. `clj_outline` on the file to see structure
2. `clj_deps` on target form to check dependencies
3. `clj_extract` with execute=false (dry-run) to see the plan
4. `clj_extract` with execute=true to write changes
5. Run tests — compiler catches bare refs instantly

## Notes

- All read ops are pure. Only execute=true variants write.
- After extraction, always run tests — the compiler catches bare references.")

(def ^:private skill-registry
  [{:name "clojure"
    :markers #{"bb.edn" "deps.edn" "project.clj" "squint.edn"}
    :content CLOJURE_SKILL}])

(defn- should-load?
  "Check if a skill should be loaded for the given cwd."
  [skill cwd]
  (if-let [custom-pred (:load-when skill)]
    (custom-pred cwd)
    (file-exists-in-cwd? cwd (:markers skill))))

(defn active-skills
  "Return vec of skill names active for the given cwd."
  [cwd]
  (->> skill-registry
       (filter #(should-load? % cwd))
       (mapv :name)))

(defn load-skill-prompts
  "Load and concatenate all active skill prompts for cwd.
   Returns nil if no skills match."
  [cwd]
  (let [active (->> skill-registry
                    (filter #(should-load? % cwd))
                    (map :content))]
    (when (seq active)
      (str "\n\n# Active Skills\n\n"
           (str/join "\n\n---\n\n" active)))))

;; ── Extension Hooks ───────────────────────────────────────────────────────────

(defn- session-start-hook
  "On session start, log which skills are active."
  [state]
  (let [cwd (:cwd state)
        skills (when cwd (active-skills cwd))]
    (when (seq skills)
      nil)))

(def extension
  {:name "skills"
   :hooks {:session-start session-start-hook}})
