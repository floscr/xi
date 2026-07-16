(ns xi.ext.todo-intercept
  "Intercepts writes to task files (e.g. tasks/todo.md) and converts the
   markdown TODO items into GTD captures instead.

   The old :tool-call hook becomes a tool-gate returning {:intercepted
   true :result …}: the write never reaches the filesystem; the content is
   parsed and captured into GTD, and a note nudges the agent toward the
   gtd_* tools."
  (:require [clojure.string :as str]))

(def ^:private org-cli-dir
  (or (aget js/process.env "ORG_CLI_DIR")
      (str (aget js/process.env "HOME") "/Code/Projects/org-mode-agenda-cli")))

(defn- task-file?
  "True when path looks like a task/todo file that should be redirected to GTD."
  [path]
  (let [p (str path)]
    (or (str/ends-with? p "tasks/todo.md")
        (str/ends-with? p "tasks/TODO.md"))))

(defn- run-gtd-capture
  "Run bb org gtd capture. Returns promise of stdout string."
  [title {:keys [body todo project-cwd]}]
  (js/Promise.
   (fn [resolve _reject]
     (let [env (js/Object.assign #js {} (unchecked-get js/process "env"))
           _   (when project-cwd
                 (unchecked-set env "GTD_PROJECT_CWD" project-cwd))
           args (cond-> ["bb" "org" "gtd" "capture" title]
                  body (conj "--body" body)
                  todo (conj "--todo" todo)
                  project-cwd (conj "--property" (str "PROJECT=" project-cwd)))
           proc (js/Bun.spawn
                 (clj->js args)
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd org-cli-dir
                      :env env})]
       (-> (.text (.-stdout proc))
           (.then (fn [stdout] (resolve stdout)))
           (.catch (fn [_] (resolve nil))))))))

(defn- parse-markdown-tasks
  "Extract checklist items from markdown.
   Returns [{:title str :done? bool}]."
  [content]
  (->> (str/split-lines content)
       (keep (fn [line]
               (let [trimmed (str/trim line)]
                 (or (when-let [[_ title] (re-find #"^-\s+\[x\]\s+(.+)" trimmed)]
                       {:title title :done? true})
                     (when-let [[_ title] (re-find #"^-\s+\[\s?\]\s+(.+)" trimmed)]
                       {:title title :done? false})))))
       vec))

(defn- extract-heading
  "Extract first markdown heading."
  [content]
  (->> (str/split-lines content)
       (some (fn [line]
               (second (re-find #"^#+\s+(.+)" (str/trim line)))))))

(def ^:private GTD_NOTE
  "Xi uses a GTD task management system. Use `gtd_capture`, `gtd_list`, and `gtd_change` tools instead of writing task files.")

(defn- tool-gate
  "Intercept writes to task files → GTD captures."
  [tool-call {:keys [cwd]}]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))
        path  (or (:path arguments) (:file_path arguments))]
    (if (and (#{"write" "edit"} lname) (task-file? path))
      (let [content (or (:content arguments) "")
            tasks   (parse-markdown-tasks content)
            heading (extract-heading content)]
        (if (empty? tasks)
          ;; No checklist items — capture the whole content as one task
          (-> (run-gtd-capture (or heading "Plan")
                               {:body content :project-cwd cwd})
              (.then (fn [_]
                       {:intercepted true
                        :result {:content [{:type "text"
                                            :text (str "✓ Captured plan to GTD.\n\n" GTD_NOTE)}]}})))
          ;; Capture each checklist item
          (-> (js/Promise.all
               (clj->js
                (mapv (fn [{:keys [title done?]}]
                        (run-gtd-capture title {:todo (if done? "DONE" "TODO")
                                                :project-cwd cwd}))
                      tasks)))
              (.then (fn [_]
                       {:intercepted true
                        :result {:content [{:type "text"
                                            :text (str "✓ Captured " (count tasks) " tasks to GTD"
                                                       (when heading (str " (" heading ")"))
                                                       ":\n"
                                                       (str/join "\n"
                                                                 (map (fn [{:keys [title done?]}]
                                                                        (str "  " (if done? "✓" "○") " " title))
                                                                      tasks))
                                                       "\n\n" GTD_NOTE)}]}})))))
      ;; Not a task file — pass through
      tool-call)))

(def extension
  {:id        :todo-intercept
   :tool-gate tool-gate})
