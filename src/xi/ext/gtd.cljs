(ns xi.ext.gtd
  "GTD task management extension — list, capture, and change tasks via org-mode-agenda-cli."
  (:require [clojure.string :as str]))

(def ^:private org-cli-dir
  "Directory containing the org-mode-agenda-cli project."
  (or (aget js/process.env "ORG_CLI_DIR")
      (str (aget js/process.env "HOME") "/Code/Projects/org-mode-agenda-cli")))

(defn- run-gtd
  "Run a bb org gtd subcommand. Returns promise of {:content [...] :is-error bool}.
   When project-cwd is provided, it's passed as GTD_PROJECT_CWD env var
   so the profile matcher can auto-detect the GTD file for the project."
  [args & [{:keys [project-cwd]}]]
  (js/Promise.
   (fn [resolve _reject]
     (let [env (js/Object.assign #js {} (unchecked-get js/process "env"))
           _   (when project-cwd
                 (unchecked-set env "GTD_PROJECT_CWD" project-cwd))
           proc (js/Bun.spawn
                 (clj->js (concat ["bb" "org" "gtd"] args))
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd org-cli-dir
                      :env env})]
       (-> (js/Promise.all #js [(.text (.-stdout proc))
                                (.text (.-stderr proc))
                                (.-exited proc)])
           (.then (fn [results]
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code   (aget results 2)]
                      (resolve
                       (if (zero? code)
                         {:content [{:type "text" :text (if (seq stdout) stdout "(no output)")}]}
                         {:content [{:type "text" :text (str "gtd error: " (if (seq stderr) stderr stdout))}]
                          :is-error true}))))))))))

(defn- build-gtd-prompt [args]
  (let [extra (when (seq args) (str/trim args))]
    (str "Review my GTD task list and recommend which task I should work on next.\n\n"
         "Steps:\n"
         "1. Use gtd_list to fetch all current TODO tasks\n"
         "2. Review the tasks and their context\n"
         "3. Recommend ONE task to start, with a brief reason why\n"
         "4. Ask if I want to start it or pick a different one\n"
         (when extra
           (str "\nContext: " extra)))))

(def ^:private GTD_PROMPT
  "# GTD Task Management

You have access to a GTD (Getting Things Done) task management system backed by org-mode files.

## Tools

- **gtd_list** — List tasks. Filter by `:todo` state (TODO, DONE, WAITING, CANCELLED), `:file`, or use `:all true` for items without a todo state.
- **gtd_capture** — Create a new task. Requires `:title`. Optional: `:file` (default inbox.org), `:body`, `:todo` (default TODO).
- **gtd_change** — Modify a task by `:id` (UUID) or `:query` (title substring). Set `:todo` to change state, or `:archive true` to remove it.

## Workflow

1. When the user asks about tasks, use `gtd_list` to see current items
2. To capture new work, use `gtd_capture` — prefer short, actionable titles
3. When work is done, use `gtd_change` with `:todo \"DONE\"` to mark it complete
4. Use the `/gtd` command to get a recommendation on what to work on next

## Project Auto-Detection

GTD tools automatically detect the current project from the session's working directory.
When a project has a linked GTD file (via dotfiles profiles), `gtd_list` and `gtd_capture`
default to that file. You don't need to pass `:file` manually — it just works.
Explicit `:file` always overrides the auto-detection.")

(def extension
  {:name "gtd"
   :system-prompt GTD_PROMPT
   :commands [{:name "gtd"
               :description "Review tasks and get a recommendation for what to work on"
               :handler (fn [{:keys [args cwd]}]
                          {:type :prompt
                           :text (build-gtd-prompt args)})}]
   :tools [{:name "gtd_list"
            :description "List tasks from the GTD backlog. Returns EDN with :id, :title, :todo-state, :tags, :file, :level for each task. By default only shows items with a todo state. Auto-detects project GTD file from session cwd."
            :input_schema {:type "object"
                           :properties {:todo {:type "string"
                                               :description "Filter by todo state (TODO, DONE, WAITING, CANCELLED)"}
                                        :file {:type "string"
                                               :description "Only show tasks from this file (relative path, e.g. work/hyma.org)"}
                                        :all {:type "boolean"
                                              :description "Include items without todo state"}}
                           :required []}
            :execute (fn [{:keys [todo file all]} {:keys [cwd]}]
                       (run-gtd (cond-> ["agenda" "--output" "edn"]
                                  todo (conj "--todo" todo)
                                  file (conj "--file" file)
                                  all  (conj "--all"))
                                {:project-cwd cwd}))}

           {:name "gtd_capture"
            :description "Capture a new task to the GTD backlog. Creates a TODO item with auto-generated ID and timestamp. Auto-detects project GTD file from session cwd."
            :input_schema {:type "object"
                           :properties {:title {:type "string"
                                                :description "Task title"}
                                        :file {:type "string"
                                                :description "Target file relative to GTD dir (default: auto-detected from project, or inbox.org)"}
                                        :body {:type "string"
                                               :description "Body text for the task"}
                                        :todo {:type "string"
                                               :description "Todo state (default: TODO)"}}
                           :required ["title"]}
            :execute (fn [{:keys [title file body todo]} {:keys [cwd]}]
                       (run-gtd (cond-> ["capture" title]
                                  file (conj "--file" file)
                                  body (conj "--body" body)
                                  todo (conj "--todo" todo))
                                {:project-cwd cwd}))}

           {:name "gtd_change"
            :description "Modify a task — change its todo state or archive it. Find tasks by ID (exact UUID match) or query (title substring)."
            :input_schema {:type "object"
                           :properties {:id {:type "string"
                                             :description "Find task by UUID (from :id field in gtd_list output)"}
                                        :query {:type "string"
                                                :description "Find task by title substring"}
                                        :todo {:type "string"
                                               :description "Set new todo state (TODO, DONE, WAITING, CANCELLED)"}
                                        :archive {:type "boolean"
                                                  :description "Remove the task from its file entirely"}}
                           :required []}
            :execute (fn [{:keys [id query todo archive]} {:keys [cwd]}]
                       (if (and (not id) (not query))
                         (js/Promise.resolve
                          {:content [{:type "text" :text "Error: must provide either --id or --query to find the task"}]
                           :is-error true})
                         (run-gtd (cond-> ["change"]
                                    id      (conj "--id" id)
                                    query   (conj "--query" query)
                                    todo    (conj "--todo" todo)
                                    archive (conj "--archive"))
                                  {:project-cwd cwd})))}]})
