(ns xi.ext.permission-gate
  "Permission gate — blocks dangerous operations.
   Hard-blocks writes to sensitive paths, warns on dangerous bash commands."
  (:require [clojure.string :as str]))

(def ^:private BLOCKED_PATHS
  "Paths that should never be written to."
  ["/Mail/" "/.ssh/" "/.gnupg/" "/.password-store/"])

(def ^:private GUARDED_PATTERNS
  "Bash patterns that require extra caution."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"])

(def ^:private BLOCKED_WRITE_PATHS
  "File patterns that should not be written to."
  [".env" ".git/" "node_modules/"])

(defn- blocked-path?
  "Check if a path matches any blocked pattern."
  [path patterns]
  (some #(str/includes? (str path) %) patterns))

(defn- permission-gate-tool-call
  "Tool call hook: block dangerous operations."
  [tool-call _ctx]
  (let [{:keys [name arguments]} tool-call]
    (case name
      ("write" "edit")
      (let [path (:path arguments)]
        (cond
          (blocked-path? path BLOCKED_PATHS)
          (do (println (str "  [permission] BLOCKED: write to sensitive path: " path))
              nil)

          (blocked-path? path BLOCKED_WRITE_PATHS)
          (do (println (str "  [permission] BLOCKED: write to protected path: " path))
              nil)

          :else tool-call))

      "bash"
      (let [cmd (:command arguments)]
        (if (some #(str/includes? cmd %) GUARDED_PATTERNS)
          (do (println (str "  [permission] WARNING: potentially dangerous command: " cmd))
              ;; Allow but warn — in a TUI we'd prompt for confirmation
              tool-call)
          tool-call))

      ;; Allow everything else
      tool-call)))

(def extension
  {:name "permission-gate"
   :hooks {:tool-call permission-gate-tool-call}})
