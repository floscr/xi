(ns xi.ext.permission-gate
  "Permission gate — blocks dangerous operations.
   Asks for user confirmation before allowing writes to sensitive paths
   or dangerous bash commands."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]))

(def ^:private BLOCKED_PATHS
  "Paths that should never be written to."
  ["/Mail/" "/.ssh/" "/.gnupg/" "/.password-store/"])

(def ^:private GUARDED_PATTERNS
  "Bash patterns that require extra caution."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"
   "git push"])

(def ^:private BLOCKED_WRITE_PATHS
  "File patterns that should not be written to."
  [".env" ".git/" "node_modules/"])

(defn- blocked-path?
  "Check if a path matches any blocked pattern."
  [path patterns]
  (some #(str/includes? (str path) %) patterns))

(defn- ask-confirmation
  "Ask user to confirm a blocked operation. Returns Promise<tool-call|nil>."
  [tool-call message]
  (-> (ext/confirm! message)
      (.then (fn [allowed?]
               (if allowed? tool-call nil)))))

(defn- permission-gate-tool-call
  "Tool call hook: guard dangerous operations with user confirmation.
   Tool names may be PascalCase (from SDK) or lowercase."
  [tool-call _state]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (case lname
      ("write" "edit")
      (let [path (or (:path arguments) (:file_path arguments))]
        (cond
          (blocked-path? path BLOCKED_PATHS)
          (ask-confirmation tool-call (str "Write to sensitive path: " path))

          (blocked-path? path BLOCKED_WRITE_PATHS)
          (ask-confirmation tool-call (str "Write to protected path: " path))

          :else tool-call))

      "bash"
      (let [cmd (or (:command arguments) "")]
        (if (some #(str/includes? cmd %) GUARDED_PATTERNS)
          (ask-confirmation tool-call (str "Guarded command: " cmd))
          tool-call))

      ;; Allow everything else
      tool-call)))

(def extension
  {:name "permission-gate"
   :hooks {:tool-call permission-gate-tool-call}})
