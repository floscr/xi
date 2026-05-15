(ns xi.ext.plan-mode
  "Plan mode extension — /plan toggle for read-only code exploration.
   Injects plan-mode system prompt, guards bash to read-only commands."
  (:require [clojure.string :as str]))

(defonce ^:private state (atom {:enabled false}))

(def ^:private PLAN_PROMPT
  "[PLAN MODE ACTIVE]
You are in plan mode — a read-only exploration mode for safe code analysis.

Restrictions:
- You can use: read, bash (read-only), grep, find, ls
- You can ONLY write to: `tasks/todo.md` (the plan file)
- All other file modifications are blocked
- Bash is restricted to read-only commands

You MUST write your plan to `tasks/todo.md` using markdown checkboxes:

```markdown
# Plan: <title>

## Context
<brief description of what we're doing and why>

## Steps
- [ ] First step description
- [ ] Second step description
- [ ] Third step description

## Notes
<any relevant findings, decisions, or considerations>
```

The plan file is the source of truth. Keep it concise and actionable.
An existing plan file is at `tasks/todo.md`. Read it first, then update or replace it.")

(def ^:private DANGEROUS_PATTERNS
  #{"rm " "rm -" "mv " "cp " "chmod " "chown " "kill " "pkill "
    "sudo " "dd " "> " ">> " "tee " "truncate "})

(defn- read-only-bash?
  "Check if a bash command looks read-only."
  [command]
  (not (some #(str/includes? command %) DANGEROUS_PATTERNS)))

(defn- plan-mode-context
  "Context hook: inject plan mode prompt when enabled."
  [messages _state]
  messages)

(defn- plan-file?
  "Check if path is the plan file (allowed in plan mode)."
  [path]
  (= "tasks/todo.md" path))

(defn- plan-mode-tool-call
  "Tool call hook: block write/edit tools and dangerous bash when plan mode is on.
   Returns tool-call to allow, nil to block. No side effects."
  [tool-call _state]
  (if-not (:enabled @state)
    tool-call
    (let [{:keys [name arguments]} tool-call]
      (case name
        "write" (when (plan-file? (:path arguments)) tool-call)
        "edit"  (when (plan-file? (:path arguments)) tool-call)
        "bash"  (when (read-only-bash? (:command arguments)) tool-call)
        ;; Allow all other tools (read, grep, find, ls, etc.)
        tool-call))))

(defn- toggle-plan-mode
  "Toggle plan mode on/off. Returns a command result event."
  [_ctx]
  (swap! state update :enabled not)
  {:type :command-result
   :command "plan"
   :text (str "Plan mode: " (if (:enabled @state) "ON" "OFF"))})

(def extension
  {:name "plan-mode"
   :hooks {:context plan-mode-context
           :tool-call plan-mode-tool-call}
   :commands [{:name "plan"
               :description "Toggle plan mode (read-only exploration)"
               :handler toggle-plan-mode}]})
