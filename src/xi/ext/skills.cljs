(ns xi.ext.skills
  "Skills extension — detects project type and injects tool knowledge into context.
   Skills are conditionally loaded based on marker files in the project.
   Also provides on-demand skills loaded from ~/.config/xi/skills/*/SKILL.md."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
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

;; ── On-Demand Skills ──────────────────────────────────────────────────────────
;;
;; Skills loaded from ~/.config/xi/skills/*/SKILL.md
;; Format: markdown with YAML-ish frontmatter (name, description)

(def ^:private SKILLS_DIR
  (.join node-path (os/homedir) ".config" "xi" "skills"))

(defn- parse-frontmatter
  "Parse simple YAML frontmatter from markdown content.
   Returns {:frontmatter {...} :body ...}."
  [content]
  (if (str/starts-with? content "---")
    (let [end-idx (str/index-of content "---" 3)]
      (if end-idx
        (let [fm-str (subs content 3 end-idx)
              body (str/trim (subs content (+ end-idx 3)))
              pairs (->> (str/split-lines fm-str)
                         (map str/trim)
                         (remove empty?)
                         (map #(let [colon-idx (str/index-of % ":")
                                     k (when colon-idx (str/trim (subs % 0 colon-idx)))
                                     v (when colon-idx (str/trim (subs % (inc colon-idx))))]
                                 (when (and k v) [k v])))
                         (remove nil?)
                         (into {}))]
          {:frontmatter pairs :body body})
        {:frontmatter {} :body content}))
    {:frontmatter {} :body content}))

(defn scan-skills
  "Scan SKILLS_DIR for available skill directories.
   Returns vec of {:name :description :path}."
  []
  (if (fs/existsSync SKILLS_DIR)
    (let [entries (fs/readdirSync SKILLS_DIR #js {:withFileTypes true})]
      (->> entries
           (filter #(.isDirectory %))
           (keep (fn [dirent]
                   (let [dir-name (.-name dirent)
                         skill-path (.join node-path SKILLS_DIR dir-name "SKILL.md")]
                     (when (fs/existsSync skill-path)
                       (let [content (.toString (fs/readFileSync skill-path "utf-8"))
                             {:keys [frontmatter]} (parse-frontmatter content)]
                         {:name (or (get frontmatter "name") dir-name)
                          :description (or (get frontmatter "description") "")
                          :path skill-path})))))
           (sort-by :name)
           vec))
    []))

(defn- load-skill
  "Load a skill by name. Returns the full content (body after frontmatter) or nil."
  [skill-name]
  (let [skills (scan-skills)]
    (when-let [skill (some #(when (= (:name %) skill-name) %) skills)]
      (let [content (.toString (fs/readFileSync (:path skill) "utf-8"))
            {:keys [body]} (parse-frontmatter content)]
        body))))

;; ── Skill Commands ────────────────────────────────────────────────────────────

(defn- cmd-skill-list
  "List all available on-demand skills."
  [_opts]
  (let [skills (scan-skills)]
    (if (seq skills)
      (let [max-name (apply max (map #(count (:name %)) skills))
            lines (map (fn [{:keys [name description]}]
                         (let [padded (str name (apply str (repeat (- (+ max-name 2) (count name)) " ")))]
                           (str "  " padded description)))
                       skills)]
        {:type :prompt
         :text (str "Available skills:\n" (str/join "\n" lines))})
      {:type :prompt
       :text (str "No skills found in " SKILLS_DIR)})))

(defn- cmd-skill-load
  "Load a skill and auto-post its content."
  [opts]
  (let [skill-name (:args opts)]
    (if (str/blank? skill-name)
      {:type :prompt
       :text "Usage: /skill load <name>\nUse /skill list to see available skills."}
      (if-let [content (load-skill (str/trim skill-name))]
        {:type :prompt :text content}
        {:type :prompt
         :text (str "Skill '" (str/trim skill-name) "' not found. Use /skill list to see available skills.")}))))

(defn- cmd-skill
  "Skill command dispatcher — defaults to list."
  [opts]
  (cmd-skill-list opts))

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
   :hooks {:session-start session-start-hook}
   :commands [{:name "skill"
               :description "List or load on-demand skills"
               :handler cmd-skill
               :subcommands [{:name "list"
                              :description "List available skills"
                              :handler cmd-skill-list}
                             {:name "load"
                              :description "Load a skill and auto-post it"
                              :handler cmd-skill-load}]}]})
