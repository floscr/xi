(ns xi.ext.review
  "Code review extension — ports the awesome-skills/code-review-skill core
   methodology into a `/review` command.

   `/review` gathers a git diff overview for the room's cwd and submits a
   prompt that embeds the review methodology (four-phase process, severity
   labels, feedback principles). Reading git is I/O, so the command stays
   pure and defers to a :review/start effect.

   Targets:
     /review          → working-tree changes vs HEAD
     /review staged   → staged changes vs HEAD
     /review <ref>    → PR-style diff of <ref>...HEAD (what the branch added)

   The prompt is augmented with project-specific review guidance: built-in
   language checklists (auto-detected from marker files, like the skills
   extension) plus an optional per-profile override via
   `bb profile:review-prompt <cwd>` (mirrors the `:agents-prompt` start
   prompt). A profile with `:review-replace true` replaces the built-in
   guidance instead of appending to it."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private BB_EDN
  (str (aget js/process.env "HOME") "/.config/dotfiles/modules/scripts/bb.edn"))

;; ── Git plumbing (impure edge) ────────────────────────────────────────────────

(defn- git-sync
  "Run a git command synchronously, return stdout string."
  [cwd & args]
  (let [proc (js/Bun.spawnSync
              (clj->js (cons "git" args))
              #js {:stdout "pipe" :stderr "pipe"
                   :cwd (or cwd (.cwd js/process))})]
    (str (.toString (.-stdout proc)))))

(defn- resolve-target
  "Map command args to a git diff spec and a human description.
   Returns {:diff-args [...] :desc str :include-untracked? bool}. Untracked
   files only make sense for the working-tree target."
  [args]
  (let [arg (str/trim (or args ""))]
    (cond
      (str/blank? arg)
      {:diff-args ["diff" "HEAD" "--stat"]
       :desc      "uncommitted changes (working tree + staged) vs HEAD"
       :include-untracked? true}

      (= arg "staged")
      {:diff-args ["diff" "--cached" "--stat"]
       :desc      "staged changes vs HEAD"
       :include-untracked? false}

      :else
      {:diff-args ["diff" (str arg "...HEAD") "--stat"]
       :desc      (str "PR-style diff of " arg "...HEAD (what this branch added)")
       :include-untracked? false})))

(defn- review-overview-sync
  "Diff --stat for the resolved target, plus a note about untracked files
   when reviewing the working tree."
  [cwd diff-args include-untracked?]
  (let [stat      (str/trim (apply git-sync cwd diff-args))
        untracked (when include-untracked?
                    (str/trim (git-sync cwd "ls-files" "--others" "--exclude-standard")))]
    (str (if (seq stat) stat "(no changes)")
         (when (seq untracked)
           (str "\n\nUntracked files (not yet in git — read them directly):\n" untracked)))))

;; ── Review methodology (ported from code-review-skill/SKILL.md) ───────────────

(def ^:private REVIEW_METHODOLOGY
  "# Code Review Methodology

Transform code review from gatekeeping into knowledge sharing: catch bugs and
edge cases, ensure maintainability, and share knowledge — without nitpicking
formatting (that's the linter's job) or rewriting to personal preference.

## Effective Feedback

- Specific and actionable, educational not judgmental, focused on the code not
  the person, and balanced (call out good work too).
- Prefer questions over commands and suggestions over mandates:
  - Instead of \"This will fail on an empty list\" → \"What happens if `items`
    is empty?\"
  - Instead of \"Extract this into a function\" → \"This logic appears in 3
    places — would it make sense to extract it?\"

## Four-Phase Process

**Phase 1 — Context.** Understand the scope and intent of the change. If the
diff is large (>~400 lines) or spans unrelated concerns, say so.

**Phase 2 — High-level.** Architecture & design fit (SOLID, coupling/cohesion,
anti-patterns), performance (algorithmic complexity, N+1 queries, memory), file
organization, and test strategy (are edge cases covered?).

**Phase 3 — Line-by-line.** For each changed file:
- Logic & correctness — edge cases, off-by-one, null checks, race conditions.
- Security — input validation, injection (SQLi/command), XSS, IDOR, sensitive
  data exposure.
- Performance — N+1 queries, needless loops, leaks.
- Maintainability — clear names, single responsibility, dead code.
- Reuse — before endorsing new code, check for existing utilities/helpers it
  duplicates.

**Phase 4 — Summary & decision.** Summarize the key concerns, highlight what
you liked, and give a clear decision: Approve / Comment / Request Changes.

## Severity Labels

Tag every finding so the author can triage:

- 🔴 `[blocking]`  — must fix before merge
- 🟡 `[important]` — should fix; discuss if you disagree
- 🟢 `[nit]`       — minor style/preference, not blocking
- 💡 `[suggestion]`— an alternative worth considering
- 📚 `[learning]`  — educational note, no action needed
- 🎉 `[praise]`    — good work, call it out

Reference each finding with `path:line` so it's easy to navigate. When the
changed code is in a language with well-known pitfalls (React hooks, Python
mutable defaults, Go goroutine leaks, Rust unsafe/cancellation, SQL injection,
async cancellation safety, …), apply that ecosystem's specific checks.")

;; ── Project-type review guidance (auto-loaded by marker files) ────────────────
;; Checklist content lives in resources/review/*.md and is read from disk at
;; call time, so editing a checklist takes effect without recompiling/restarting.

(defn- find-xi-root
  "Walk up from the main script's directory to xi's project root (package.json)."
  []
  (let [script-path (aget js/process.argv 1)
        start-dir   (when script-path (.dirname node-path (.resolve node-path script-path)))]
    (when start-dir
      (loop [dir start-dir]
        (let [pkg (.join node-path dir "package.json")]
          (cond
            (fs/existsSync pkg) dir
            (= dir (.dirname node-path dir)) nil
            :else (recur (.dirname node-path dir))))))))

(def ^:private review-dir
  "Absolute path to resources/review, or nil if the project root can't be found."
  (when-let [root (find-xi-root)]
    (.join node-path root "resources" "review")))

(defn- load-review-md
  "Read resources/review/<name>.md from disk at call time. Returns the content
   string, or nil when unavailable."
  [name]
  (when review-dir
    (let [f (.join node-path review-dir (str name ".md"))]
      (when (fs/existsSync f)
        (.toString (fs/readFileSync f "utf-8"))))))

(def ^:private review-prompt-registry
  "Built-in, project-type review checklists. Each entry's checklist file is
   loaded (from resources/review/<file>.md) when any of its marker files exists
   in the room's cwd (same detection as the skills extension)."
  [{:name "clojure"
    :markers #{"bb.edn" "deps.edn" "project.clj" "shadow-cljs.edn" "squint.edn"}
    :file "clojure"}
   {:name "typescript"
    :markers #{"tsconfig.json" "package.json"}
    :file "typescript"}
   {:name "swift"
    :markers #{"Package.swift" "Podfile" "Project.swift" "Package.resolved"}
    :file "swift"}])

(defn- file-exists-in-cwd?
  "True if any of the given filenames exists directly in cwd."
  [cwd filenames]
  (boolean (some #(fs/existsSync (.join node-path cwd %)) filenames)))

(defn- builtin-review-guidance
  "Content of every registry entry whose markers match cwd, read from disk at
   call time, or nil when none match."
  [cwd]
  (->> review-prompt-registry
       (filter #(file-exists-in-cwd? cwd (:markers %)))
       (map #(load-review-md (:file %)))
       (remove nil?)
       seq))

(defn- fetch-profile-review-prompt
  "Call `bb profile:review-prompt <cwd>` to check whether a profile defines a
   custom review prompt for this cwd. Returns {:prompt <str> :replace <bool>}
   or nil. Mirrors system-prompt/fetch-profile-agents-prompt."
  [cwd]
  (try
    (let [proc (js/Bun.spawnSync
                #js ["bb" "--config" BB_EDN "profile:review-prompt" cwd]
                #js {:stdout "pipe" :stderr "pipe" :timeout 10000})]
      (when (zero? (.-exitCode proc))
        (let [parsed (js->clj (js/JSON.parse (str (.toString (.-stdout proc))))
                              :keywordize-keys true)]
          (when (:prompt parsed) parsed))))
    (catch :default _e nil)))

(defn- project-review-guidance
  "Combine built-in language checklists with any per-profile override.
   A profile with :review-replace true replaces the built-in guidance;
   otherwise the profile prompt is appended after it. Returns a single string
   or nil."
  [cwd]
  (let [profile (fetch-profile-review-prompt cwd)
        builtin (when-not (:replace profile) (builtin-review-guidance cwd))
        parts   (cond-> (vec builtin)
                  (:prompt profile) (conj (:prompt profile)))]
    (when (seq parts)
      (str/join "\n\n---\n\n" parts))))

;; ── Prompt builder ──────────────────────────────────────────────────────────

(defn- build-review-prompt [desc overview guidance]
  (str REVIEW_METHODOLOGY
       (when guidance
         (str "\n\n## Project-Specific Review Guidance\n\n" guidance))
       "\n\n---\n\n"
       "Conduct a code review of the " desc ".\n\n"
       "Files changed:\n```\n" overview "\n```\n\n"
       "Read the actual diffs for each changed file (use `git diff …`, or the "
       "`git_file_diff` tool if available), then deliver a structured review "
       "following the four-phase process and severity labels above. Be concise "
       "and prioritize blocking/important findings."))

;; ── Command + effect ──────────────────────────────────────────────────────────

(defn- review-command
  "/review [staged|<ref>] — pure: defer the git read to an effect."
  [_st {:keys [room-id args]}]
  {:effects [[:review/start {:room-id room-id :args args}]]})

(defn- review-start-fx
  "Gather a diff overview for the room's cwd and submit the review prompt."
  [{:keys [dispatch! get-state]} {:keys [room-id args]}]
  (let [room       (get-in (get-state) [:rooms room-id])
        cwd        (:cwd room)
        {:keys [diff-args desc include-untracked?]} (resolve-target args)
        overview   (review-overview-sync cwd diff-args include-untracked?)
        guidance   (project-review-guidance cwd)]
    (dispatch! {:type :prompt/submit :room-id room-id
                :text (build-review-prompt desc overview guidance)})))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:id       :review
   :commands [{:name "review"
               :description "Review git changes against the code-review methodology"
               :handler review-command
               :subcommands [{:name "staged" :description "Review staged changes vs HEAD"}]}]
   :fx       {:review/start review-start-fx}})
