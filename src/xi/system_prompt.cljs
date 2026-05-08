(ns xi.system-prompt
  "System prompt construction. Loads AGENTS.md from project root + parents."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private BASE_PROMPT
  "You are Xi, a coding assistant. You help users with software engineering tasks.

You have access to tools for reading files, writing files, editing files, running commands, and listing directories. Use tools to gather information before answering.

Be concise and direct. When referencing files, use the read tool. When running commands, use bash.

Do not create files unless necessary. Prefer editing existing files over creating new ones.
Be careful not to introduce security vulnerabilities.
Don't add features, refactor code, or make improvements beyond what was asked.")

(defn- find-agents-md
  "Walk up from dir to root, collecting all AGENTS.md files found.
   Returns vec of paths, innermost (closest to cwd) last."
  [start-dir]
  (loop [dir (.resolve node-path start-dir)
         found []]
    (let [candidate (.join node-path dir "AGENTS.md")
          found (if (fs/existsSync candidate)
                  (conj found candidate)
                  found)
          parent (.dirname node-path dir)]
      (if (= parent dir)
        ;; At root — reverse so outermost is first, innermost last
        (vec (reverse found))
        (recur parent found)))))

(defn- load-agents-md
  "Load and concatenate all AGENTS.md files from cwd to root."
  []
  (let [files (find-agents-md (.cwd js/process))]
    (when (seq files)
      (str/join "\n\n---\n\n"
                (map (fn [f]
                       (str "# " (.relative node-path (.cwd js/process) f) "\n\n"
                            (fs/readFileSync f "utf8")))
                     files)))))

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
  [tool-defs]
  (let [parts [BASE_PROMPT
               (str "Current working directory: " (.cwd js/process))
               (tool-descriptions tool-defs)
               (load-agents-md)]]
    (str/join "\n\n" (filter some? parts))))
