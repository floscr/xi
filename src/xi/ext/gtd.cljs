(ns xi.ext.gtd
  "GTD task management extension — list, capture, change tasks, and interactive task picker."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]))

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

(defn- run-gtd-raw
  "Run a bb org gtd subcommand and return the raw stdout string.
   Returns promise of string."
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
       (-> (.text (.-stdout proc))
           (.then (fn [stdout] (resolve stdout))))))))

;; ── Task Picker ───────────────────────────────────────────────────────────────

(defn- parse-edn-tasks
  "Parse EDN task list output into a vector of task maps."
  [edn-str]
  (try
    (clj->js (js/JSON.parse "null")) ;; dummy — we parse EDN manually
    (let [;; Simple EDN vector-of-maps parse via regex extraction
          items (re-seq #"\{[^}]+\}" edn-str)]
      (mapv (fn [item-str]
              (let [kv-pairs (re-seq #":(\S+)\s+(?:\"([^\"]*)\"|(\S+))" item-str)]
                (into {}
                      (map (fn [[_ k v1 v2]]
                             [(keyword k) (or v1 v2)]))
                      kv-pairs)))
            items))
    (catch :default _ [])))

(defn- activate-task!
  "Set a task to ACTIVE and store the xi session ID as a property.
   Returns a promise."
  [task-id session-id cwd]
  (run-gtd (cond-> ["change" "--id" task-id "--todo" "ACTIVE"]
             session-id (conj "--property" (str "XI_SESSION=" session-id)))
           {:project-cwd cwd}))

(defn- show-task-menu!
  "Fetch non-done tasks and show a completion menu. On select, set ACTIVE + XI_SESSION."
  [{:keys [session cwd]}]
  (let [session-id (:id session)]
    (-> (run-gtd-raw ["agenda" "--output" "edn"] {:project-cwd cwd})
        (.then
         (fn [edn-str]
           (let [tasks (parse-edn-tasks edn-str)
                 ;; Filter out DONE/CANCELLED tasks
                 active-tasks (filterv #(not (#{"DONE" "CANCELLED"} (:todo-state %)))
                                       tasks)]
             (if (empty? active-tasks)
               (ext/insert-text! "No open tasks found.")
               (let [items (mapv (fn [t]
                                   {:label (str (when (:todo-state t)
                                                  (str (:todo-state t) " "))
                                                (:title t))
                                    :value t})
                                 active-tasks)]
                 (ext/show-completion!
                  {:items items
                   :prompt "task> "
                   :on-select
                   (fn [item]
                     (let [task (:value item)
                           task-id (:id task)
                           title (:title task)]
                       ;; Set task to ACTIVE, then fetch full org content and submit
                       (-> (activate-task! task-id session-id cwd)
                           (.then
                            (fn [_]
                              (run-gtd-raw
                               (cond-> ["view"]
                                 task-id (conj "--id" task-id)
                                 (and (not task-id) title) (conj "--query" title))
                               {:project-cwd cwd})))
                           (.then
                            (fn [org-content]
                              (ext/set-session-name! title)
                              (ext/submit-text!
                               (str "I'm starting work on the GTD task: " title "\n\n"
                                    "```org\n"
                                    (str/trim org-content) "\n"
                                    "```")))))))})))))))))

;; ── Prompt Builders ───────────────────────────────────────────────────────────

(defn- build-recommend-prompt [args]
  (let [extra (when (seq args) (str/trim args))]
    (str "Review my GTD task list and recommend which task I should work on next.\n\n"
         "Steps:\n"
         "1. Use gtd_list to fetch all current TODO tasks\n"
         "2. Review the tasks and their context\n"
         "3. Recommend ONE task to start, with a brief reason why\n"
         "4. Ask if I want to start it or pick a different one\n"
         (when extra
           (str "\nContext: " extra)))))

(defn- build-cleanup-prompt [args]
  (let [extra (when (seq args) (str/trim args))]
    (str "Review my GTD task list for cleanup.\n\n"
         "Steps:\n"
         "1. Use gtd_list to fetch all tasks (including DONE)\n"
         "2. Look for tasks that:\n"
         "   - Are already done but not marked DONE\n"
         "   - Are stale or no longer relevant\n"
         "   - Are duplicates or could be merged\n"
         "   - Need better titles or descriptions\n"
         "3. For each issue found, suggest a fix and ask before applying\n"
         "4. Use gtd_change to apply approved changes\n"
         (when extra
           (str "\nContext: " extra)))))

;; ── System Prompt ─────────────────────────────────────────────────────────────

(def ^:private GTD_PROMPT
  "# GTD Task Management

You have access to a GTD (Getting Things Done) task management system backed by org-mode files.

## Tools

- **gtd_list** — List tasks. Filter by `:todo` state (TODO, ACTIVE, DONE, WAITING, CANCELLED), `:file`, or use `:all true` for items without a todo state.
- **gtd_capture** — Create a new task. Requires `:title`. Optional: `:file` (default inbox.org), `:body`, `:todo` (default TODO).
- **gtd_change** — Modify a task by `:id` (UUID) or `:query` (title substring). Set `:todo` to change state, `:properties` to set org properties, or `:archive true` to remove it.

## Task States

- **TODO** — Not yet started
- **ACTIVE** — Currently being worked on (set automatically when user picks a task via `/gtd`)
- **DONE** — Completed
- **WAITING** — Blocked on something
- **CANCELLED** — No longer relevant

## Workflow

1. When the user asks about tasks, use `gtd_list` to see current items
2. To capture new work, use `gtd_capture` — prefer short, actionable titles
3. When work is done, use `gtd_change` with `:todo \"DONE\"` to mark it complete
4. Use `/gtd` to pick a task interactively, `/gtd recommend` for AI recommendation

## Project Auto-Detection

GTD tools automatically detect the current project from the session's working directory.
When a project has a linked GTD file (via dotfiles profiles), `gtd_list` and `gtd_capture`
default to that file. You don't need to pass `:file` manually — it just works.
Explicit `:file` always overrides the auto-detection.")

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:name "gtd"
   :system-prompt GTD_PROMPT
   :commands [{:name "gtd"
               :description "Pick a task to work on, get recommendations, or cleanup"
               :subcommands [{:name "recommend"
                              :description "Get an AI recommendation for what to work on"
                              :handler (fn [{:keys [args]}]
                                         {:type :prompt
                                          :text (build-recommend-prompt args)})}
                             {:name "cleanup"
                              :description "Review tasks for cleanup — stale, duplicate, or already done"
                              :handler (fn [{:keys [args]}]
                                         {:type :prompt
                                          :text (build-cleanup-prompt args)})}]
               :handler (fn [ctx]
                          (show-task-menu! ctx)
                          nil)}]
   :tools [{:name "gtd_list"
            :description "List tasks from the GTD backlog. Returns EDN with :id, :title, :todo-state, :tags, :file, :level for each task. By default only shows items with a todo state. Auto-detects project GTD file from session cwd."
            :input_schema {:type "object"
                           :properties {:todo {:type "string"
                                               :description "Filter by todo state (TODO, ACTIVE, DONE, WAITING, CANCELLED)"}
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
            :description "Modify a task — change its todo state, set properties, or archive it. Find tasks by ID (exact UUID match) or query (title substring)."
            :input_schema {:type "object"
                           :properties {:id {:type "string"
                                             :description "Find task by UUID (from :id field in gtd_list output)"}
                                        :query {:type "string"
                                                :description "Find task by title substring"}
                                        :todo {:type "string"
                                               :description "Set new todo state (TODO, ACTIVE, DONE, WAITING, CANCELLED)"}
                                        :properties {:type "object"
                                                     :description "Map of property key→value pairs to set on the heading (e.g. {\"XI_SESSION\": \"abc\"})"}
                                        :archive {:type "boolean"
                                                  :description "Remove the task from its file entirely"}}
                           :required []}
            :execute (fn [{:keys [id query todo archive properties]} {:keys [cwd]}]
                       (if (and (not id) (not query))
                         (js/Promise.resolve
                          {:content [{:type "text" :text "Error: must provide either --id or --query to find the task"}]
                           :is-error true})
                         (run-gtd (cond-> ["change"]
                                    id      (conj "--id" id)
                                    query   (conj "--query" query)
                                    todo    (conj "--todo" todo)
                                    archive (conj "--archive")
                                    properties (into (mapcat (fn [[k v]]
                                                              ["--property" (str k "=" v)])
                                                            properties)))
                                  {:project-cwd cwd})))}]})
