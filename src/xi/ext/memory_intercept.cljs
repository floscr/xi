(ns xi.ext.memory-intercept
  "Blocks the inner agent from writing to Claude's auto-memory store
   (~/.claude/projects/<sanitized-cwd>/memory/, holding MEMORY.md and the
   per-topic memory files).

   The SDK already exposes an `:autoMemoryEnabled false` flag (set in
   xi.provider.claude), but the flag keeps getting renamed across SDK/CLI
   versions, so a rename silently re-enables memory. This gate is the
   belt-and-suspenders: it matches the memory directory by path, so writes
   never reach the filesystem regardless of what the flag is called. We don't
   use the auto-memory feature."
  (:require [clojure.string :as str]))

(def ^:private memory-path-re
  "Matches a path inside a `.claude/projects/<…>/memory/` auto-memory dir
   (also the bare `<…>/memory/MEMORY.md` index)."
  #"\.claude/projects/[^\n]*?/memory(?:/|\b)")

(defn memory-path?
  "True when `s` references the auto-memory directory."
  [s]
  (boolean (re-find memory-path-re (str s))))

(def ^:private STEER_NOTE
  (str "Skipped: xi does not use Claude's auto-memory feature. Writes to the "
       "auto-memory directory (~/.claude/projects/<cwd>/memory/) are blocked "
       "and were not applied. Don't save to memory — that store is disabled."))

(defn- tool-gate
  "Block write/edit/bash tool calls that touch the auto-memory directory."
  [tool-call _ctx]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))
        hits? (case lname
                ("write" "edit") (memory-path? (or (:path arguments)
                                                   (:file_path arguments)))
                "bash"           (memory-path? (:command arguments))
                false)]
    (if hits?
      {:intercepted true
       :result {:content [{:type "text" :text STEER_NOTE}]
                :is-error false}}
      tool-call)))

(def extension
  {:id        :memory-intercept
   :tool-gate tool-gate})
