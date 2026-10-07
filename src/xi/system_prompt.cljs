(ns xi.system-prompt
  "System prompt construction. Loads AGENTS.md from project root + parents.
   Supports per-project agents prompts from config.edn
   `:projects :settings` (xi.projects/agents-prompt)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [xi.ext.clojure-skills :as skills]
            [xi.projects :as projects]
            [xi.tools.util :as tools-util]
            [xi.user-config :as user-config]))

(def ^:private PROMPT_FILES_EDN
  (str (aget js/process.env "HOME") "/.config/xi/prompt-files.edn"))

(defn- expand-home [p]
  (if (str/starts-with? p "~")
    (str (aget js/process.env "HOME") (subs p 1))
    p))

(defn load-prompt-files
  "User-configured extra system-prompt files. ~/.config/xi/prompt-files.edn
   is an EDN vector of markdown file paths (leading ~ expanded); each existing
   non-empty file becomes a {:source :text} part appended to every session's
   system prompt on this machine. Missing config or files are silently
   skipped. Returns a vector of parts (possibly empty)."
  []
  (try
    (if (fs/existsSync PROMPT_FILES_EDN)
      (let [paths (reader/read-string {:default (fn [_tag v] v)}
                                      (str (fs/readFileSync PROMPT_FILES_EDN "utf8")))]
        (into []
              (keep (fn [p]
                      (when (string? p)
                        (let [f (expand-home p)]
                          (when (fs/existsSync f)
                            (let [content (str (fs/readFileSync f "utf8"))]
                              (when (seq content)
  (let [home (aget js/process.env "HOME")
        disp (if (str/starts-with? f home)
               (str "~" (subs f (count home)))
               f)]
    {:source disp
     :text   (str "# " disp "\n\n" content)}))))))))
              (when (sequential? paths) paths)))
      [])
    (catch :default _ [])))

(def ^:private BASE_PROMPT
  "You are Xi, a coding assistant. You help users with software engineering tasks.

You have access to tools for reading files, writing files, editing files, running commands, and listing directories. Use tools to gather information before answering.

Be concise and direct. When referencing files, use the read tool. When running commands, use bash.

Do not create files unless necessary. Prefer editing existing files over creating new ones.
Be careful not to introduce security vulnerabilities.
Don't add features, refactor code, or make improvements beyond what was asked.

Python is not available in this environment. Do not write or run Python scripts.

When committing changes:
- ALWAYS use the git_commit tool — never run git commit directly
- Before committing, present a short summary of the changes (what and why) so the user can review before approving")

(def ^:private INSTRUCTION_FILES
  "Per-directory instruction file names, in priority order. CLAUDE.md is only a
   fallback for repos without an AGENTS.md — a directory holding both (CLAUDE.md
   is then usually a pointer to AGENTS.md) contributes AGENTS.md alone.

   Xi is the sole loader of project instructions: the Anthropic provider tells
   the Claude CLI not to load them itself (settingSources, see
   xi.providers.anthropic/base-query-opts), otherwise every session would carry
   the same file twice."
  ["AGENTS.md" "CLAUDE.md"])

(defn- agents-ignored?
  "True when config.edn `:projects :settings` sets `:agents-ignore true` for
   exactly this cwd."
  [cwd]
  (try
    (projects/agents-ignore?! (user-config/projects-spec) cwd)
    (catch :default _e
      false)))

(defn- inside?
  "True when path `p` is `base` or lies below it."
  [base p]
  (let [p (.resolve node-path p)]
    (or (= p base)
        (str/starts-with? p (str base (.-sep node-path))))))


(defn- find-agents-md*
  [start-dir]
  (loop [dir (.resolve node-path start-dir)
         found []]
    (let [candidate (some (fn [n]
                            (let [p (.join node-path dir n)]
                              (when (fs/existsSync p) p)))
                          INSTRUCTION_FILES)
          found (if candidate
                  (conj found candidate)
                  found)
          parent (.dirname node-path dir)]
      (if (= parent dir)
        ;; At root — reverse so outermost is first, innermost last
        (vec (reverse found))
        (recur parent found)))))

(defn find-agents-md
  "Walk up from dir to root, collecting each directory's instruction file
   (AGENTS.md, else CLAUDE.md — see INSTRUCTION_FILES).
   Returns vec of paths, innermost (closest to cwd) last. With `:agents-ignore`
   set for the project, the files inside the repo (git root, or dir when not in
   a repo) are left out; those in directories above it still load."
  [start-dir]
  (let [dir (.resolve node-path start-dir)
        files (find-agents-md* dir)]
    (if (agents-ignored? dir)
      (let [base (or (tools-util/git-root dir) dir)]
        (vec (remove #(inside? base %) files)))
      files)))



(defn- project-agents-prompt
  "The agents prompt config.edn `:projects :settings` defines for this cwd.
   Returns {:prompt <str> :replace <bool>} or nil."
  [cwd]
  (try
    (projects/agents-prompt! (user-config/projects-spec) cwd)
    (catch :default _e
      nil)))

(defn- find-all-agents-md
  "List every AGENTS.md in the repo (tracked or untracked, but respecting
   .gitignore) as repo-root-relative paths with a leading slash, e.g.
   \"/AGENTS.md\", \"/sub/AGENTS.md\". Returns a sorted vec; nil outside a
   git repo."
  [cwd]
  (try
    (when-let [root (tools-util/git-root cwd)]
      (let [proc (js/Bun.spawnSync
                  #js ["git" "-C" root "ls-files"
                       "--cached" "--others" "--exclude-standard"
                       "*AGENTS.md"]
                  #js {:stdout "pipe" :stderr "pipe"
                       :timeout 5000})]
        (when (zero? (.-exitCode proc))
          (let [stdout (str (.toString (.-stdout proc)))
                lines (remove str/blank? (str/split stdout #"\n"))]
            (->> lines
                 (filter #(= "AGENTS.md" (.basename node-path %)))
                 (map #(str "/" %))
                 distinct
                 sort
                 vec)))))
    (catch :default _e
      nil)))

(defn- agents-md-prompt
  "Build a prompt section listing every AGENTS.md in the repo, instructing
   the agent to read each one when editing related files. When a profile
   replaces the cwd AGENTS.md (:replace), that file is omitted so the list
   matches the prompt actually inserted. nil when none, or when the project
   sets `:agents-ignore`."
  ([cwd] (agents-md-prompt cwd (project-agents-prompt cwd)))
  ([cwd profile-prompt]
   (when-not (agents-ignored? cwd)
     (let [replaced (when (:replace profile-prompt)
                      (when-let [root (tools-util/git-root cwd)]
                        (str "/" (.relative node-path root
                                            (.join node-path (.resolve node-path cwd)
                                                   "AGENTS.md")))))
           files (cond->> (find-all-agents-md cwd)
                   replaced (remove #(= % replaced))
                   true     seq)]
       (when files
         (str "These AGENTS.md files have been found, read them automatically "
              "when editing files related to them:\n"
              (str/join "\n" (map #(str "[" % "]") files))))))))

(defn load-agents-md
  "Load and concatenate all AGENTS.md files from cwd to root.
   If a profile defines :agents-prompt with :agents-replace true,
   the project root AGENTS.md is replaced with the profile prompt.
   Also appends any active skill prompts for the project."
  [cwd]
  (let [files (find-agents-md cwd)
        profile-prompt (project-agents-prompt cwd)
        ;; When the project says replace, swap out the root (cwd) AGENTS.md
        ;; The root AGENTS.md is the last entry (innermost = closest to cwd)
        effective-files (if (and profile-prompt (:replace profile-prompt) (seq files))
                          ;; Drop the innermost (root project) instruction file
                          (let [root-dir (.resolve node-path cwd)]
                            (vec (remove #(= (.dirname node-path %) root-dir) files)))
                          files)
        agents-content (when (seq effective-files)
                         (str/join "\n\n---\n\n"
                                   (map (fn [f]
                                          (str "# " (.relative node-path cwd f) "\n\n"
                                               (fs/readFileSync f "utf8")))
                                        effective-files)))
        profile-content (when profile-prompt
                          (:prompt profile-prompt))
        ;; Combine: parent AGENTS.md files + profile prompt + skills
        combined-agents (cond
                          (and agents-content profile-content)
                          (str agents-content "\n\n---\n\n" profile-content)

                          profile-content profile-content
                          agents-content  agents-content
                          :else           nil)
        skill-content (skills/load-skill-prompts cwd)
        prompt-files-content (some->> (load-prompt-files) seq
                                      (map :text)
                                      (str/join "\n\n---\n\n"))
        base (cond
               (and combined-agents skill-content)
               (str combined-agents skill-content)

               combined-agents combined-agents
               skill-content   skill-content
               :else           nil)]
    (cond
      (and base prompt-files-content) (str base "\n\n---\n\n" prompt-files-content)
      prompt-files-content            prompt-files-content
      :else                           base)))

(defn load-agents-parts
  "Load AGENTS.md and related prompts as source-attributed parts (skill prompts
   come from the skills extension, not from here).
   Returns a vector of {:source :text :repo?} maps (may be empty). :repo? marks
   parts whose source file lives inside the current repo (git root, or cwd when
   not a git repo)."
  [cwd]
  (let [files (find-agents-md cwd)
        base (or (tools-util/git-root cwd) (.resolve node-path cwd))
        repo-file? (fn [f]
                     (let [rf (.resolve node-path f)]
                       (or (= rf base)
                           (str/starts-with? rf (str base (.-sep node-path))))))
        profile-prompt (project-agents-prompt cwd)
        effective-files (if (and profile-prompt (:replace profile-prompt) (seq files))
                          (let [root-dir (.resolve node-path cwd)]
                            (vec (remove #(= (.dirname node-path %) root-dir) files)))
                          files)
        parts (into []
                    (keep (fn [f]
                            (let [content (str (fs/readFileSync f "utf8"))]
                              (when (seq content)
                                {:source (.relative node-path cwd f)
                                 :repo?  (repo-file? f)
                                 :text   (str "# " (.relative node-path cwd f) "\n\n" content)}))))
                    effective-files)
        parts (if-let [pc (:prompt profile-prompt)]
                (conj parts {:source "project" :text pc})
                parts)
        parts (if-let [sub (agents-md-prompt cwd profile-prompt)]
                (conj parts {:source "agents-md" :text sub})
                parts)
        ;; Skill prompts are NOT added here: the skills extension contributes
        ;; them via its :system-prompt, which every caller appends
        ;; (ext/system-prompt-parts). Adding them here too sent them twice.
        parts (into parts (load-prompt-files))]
    parts))

(defn combine
  "Join system-prompt parts (nils/empties dropped) with the standard
   separator. Used to append extension system prompts to AGENTS.md."
  [& parts]
  (let [parts (filter seq parts)]
    (when (seq parts)
      (str/join "\n\n" parts))))

(defn parts->system
  "Concatenate a vector of {:source :text} parts into a single system prompt string.
   Returns nil when parts is empty."
  [parts]
  (when (seq parts)
    (str/join "\n\n" (map :text parts))))

(defn- tool-descriptions
  "Format tool definitions into a system prompt section."
  [tool-defs]
  (str "# Available Tools\n\n"
       (str/join "\n"
                 (map (fn [t]
                        (str "- **" (:name t) "**: " (:description t)))
                      tool-defs))))

(defn build
  "Build the full system prompt string."
  [tool-defs cwd]
  (let [cwd (or cwd (.cwd js/process))
        parts [BASE_PROMPT
               (str "Current working directory: " cwd)
               (tool-descriptions tool-defs)
               (load-agents-md cwd)
               (agents-md-prompt cwd)]]
    (str/join "\n\n" (filter some? parts))))