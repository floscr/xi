(ns xi.ext.plan-mode
  "Plan mode extension — /plan toggle for read-only code exploration.
   Injects plan-mode system prompt, guards bash to read-only commands.")

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
  (not (some #(clojure.string/includes? command %) DANGEROUS_PATTERNS)))

(defn- plan-mode-context
  "Context hook: inject plan mode prompt when enabled."
  [messages _ctx]
  messages)

(defn- plan-mode-tool-call
  "Tool call hook: block write/edit tools and dangerous bash when plan mode is on."
  [tool-call _ctx]
  (when (:enabled @state)
    (let [{:keys [name arguments]} tool-call]
      (case name
        "write"
        (if (= "tasks/todo.md" (:path arguments))
          tool-call ;; allow writing to plan file
          (do (println (str "  [plan-mode] Blocked write to " (:path arguments)))
              nil))

        "edit"
        (if (= "tasks/todo.md" (:path arguments))
          tool-call
          (do (println (str "  [plan-mode] Blocked edit to " (:path arguments)))
              nil))

        "bash"
        (if (read-only-bash? (:command arguments))
          tool-call
          (do (println (str "  [plan-mode] Blocked non-read-only bash: " (:command arguments)))
              nil))

        ;; Allow all other tools
        tool-call))))

(defn- toggle-plan-mode
  "Toggle plan mode on/off."
  [_ctx]
  (swap! state update :enabled not)
  (println (str "\nPlan mode: " (if (:enabled @state) "ON" "OFF"))))

(def extension
  {:name "plan-mode"
   :hooks {:context plan-mode-context
           :tool-call plan-mode-tool-call}
   :commands [{:name "plan"
               :description "Toggle plan mode (read-only exploration)"
               :handler toggle-plan-mode}]})
