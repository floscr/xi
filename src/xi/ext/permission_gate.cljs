(ns xi.ext.permission-gate
  "Permission gate — confirm before dangerous writes and bash commands.

   The old :tool-call hook becomes a tool-gate: it confirms via the gate
   ctx's :confirm! (a dialog), allowing the tool-call on yes and blocking
   it (nil → 'Blocked by Xi permission gate') on no. With no client
   attached, :confirm! resolves to its safe default (false), so guarded
   operations are blocked in headless server mode."
  (:require [clojure.string :as str]))

(def ^:private BLOCKED_PATHS
  "Paths that should never be written to without confirmation."
  ["/Mail/" "/.ssh/" "/.gnupg/" "/.password-store/"])

(def ^:private BLOCKED_COMMANDS
  "Bash patterns that are always blocked."
  ["ssh " "scp " "rsync " "sftp "])

(def ^:private GUARDED_PATTERNS
  "Bash patterns that require extra caution."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"
   "git push"])

(def ^:private BLOCKED_WRITE_PATHS
  "File patterns that should not be written to without confirmation."
  [".env" ".git/" "node_modules/"])

(defn- blocked-path?
  "True when path matches any of the patterns."
  [path patterns]
  (some #(str/includes? (str path) %) patterns))

(defn- ask-confirmation
  "Confirm a guarded operation; allow on yes, block (nil) on no. With no
   :confirm! available the call passes through unchanged."
  [tool-call confirm! message]
  (if confirm!
    (-> (confirm! message)
        (.then (fn [allowed?] (if allowed? tool-call nil))))
    tool-call))

(defn- tool-gate
  "Guard dangerous operations with user confirmation. Tool names may be
   PascalCase (from the SDK) or lowercase."
  [tool-call {:keys [confirm!]}]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (case lname
      ("write" "edit")
      (let [path (or (:path arguments) (:file_path arguments))]
        (cond
          (blocked-path? path BLOCKED_PATHS)
          (ask-confirmation tool-call confirm! (str "Write to sensitive path: " path))

          (blocked-path? path BLOCKED_WRITE_PATHS)
          (ask-confirmation tool-call confirm! (str "Write to protected path: " path))

          :else tool-call))

      "bash"
      (let [cmd (or (:command arguments) "")]
        (cond
          (some #(str/includes? cmd %) BLOCKED_COMMANDS)
          {:intercepted true
           :result {:content [{:type "text" :text "Blocked: remote shell commands (ssh, scp, rsync, sftp) are not allowed."}]
                    :is-error true}}

          (some #(str/includes? cmd %) GUARDED_PATTERNS)
          (ask-confirmation tool-call confirm! (str "Guarded command: " cmd))

          :else tool-call))

      ;; Everything else: allowed
      tool-call)))

(def extension
  {:id        :permission-gate
   :tool-gate tool-gate})
