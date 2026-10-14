(ns xi.rules.tasks
  "Script runners for the `:task` rule key: a call's argv → the project task
   it runs (`bb test` → {:runner \"bb\" :task \"test\"}), confirmed against
   the runner's project file (bb.edn, package.json, deno.json), so a rule
   names tasks instead of a regex over the command line.

   `parse` and `project-tasks` are pure; `request-task` reads the project
   file and is called by xi.rules.store only when a `:task` rule is in play."
  (:require [clojure.string :as str]
            [cljs.tools.reader :as tr]
            [xi.paths :as paths]
            [xi.rules.readonly :as readonly]
            ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── Runners ──────────────────────────────────────────────────────────────────

(def runners
  "Program → how it names a task. `:run`: subcommands followed by the task
   name; `:scripts`: subcommands that are a task themselves (`npm test`);
   `:bare?`: the first argument is the task (`bb test`). `:file` is the
   project file defining the tasks, looked up from the cwd upwards. A flag
   before the task (`bb --config x.edn test`, `npm --prefix d run x`) can
   point the runner at another project file, so it never parses."
  {"bb"   {:bare? true :file "bb.edn"}
   "npm"  {:run #{"run" "run-script"} :scripts #{"test" "start" "stop" "restart"}
           :file "package.json"}
   "pnpm" {:run #{"run" "run-script"} :scripts #{"test" "start" "stop" "restart"}
           :file "package.json"}
   "yarn" {:run #{"run"} :file "package.json"}
   "deno" {:run #{"task"} :file "deno.json"}})

(defn- task-name? [s]
  (and (string? s) (not (str/blank? s)) (not (str/starts-with? s "-"))))

(defn parse
  "argv (program first) → {:runner :task :args} when it runs a task by name,
   else nil. Whether the project defines that task is `request-task`'s job."
  [argv]
  (let [[program & args] (map str argv)
        {:keys [bare? run scripts]} (get runners program)
        [sub & more] args
        task (fn [t args] (when (task-name? t)
                            {:runner program :task t :args (vec args)}))]
    (cond
      (contains? run sub)     (task (first more) (rest more))
      (contains? scripts sub) (task sub more)
      bare?                   (task sub more))))

(defn request-argv
  "The argv of a decision request that may run a task: the bb tool's
   task + args, or a `:sh` / `:bash` / `:terminal` command's literal argv
   (or plain command line, readonly/request-argv)."
  [{:keys [tool arguments] :as req}]
  (case tool
    :bb (when-let [t (not-empty (str/trim (str (:task arguments))))]
          (into ["bb" t] (map str (:args arguments))))
    (:sh :bash :terminal) (readonly/request-argv req)
    nil))

;; ── Project files ────────────────────────────────────────────────────────────

(defn- bb-edn-tasks [text]
  (binding [tr/*default-data-reader-fn* (fn [_tag v] v)]
    (->> (:tasks (tr/read-string text))
         keys
         (filter symbol?)
         (map str)
         set)))

(defn- json-tasks [k text]
  (let [m (js->clj (js/JSON.parse text))]
    (set (keys (get m k)))))

(defn project-tasks
  "The task names project file `text` of `runner` defines; nil when it
   doesn't parse."
  [runner text]
  (try
    (case (get-in runners [runner :file])
      "bb.edn"       (bb-edn-tasks text)
      "package.json" (json-tasks "scripts" text)
      "deno.json"    (json-tasks "tasks" text)
      nil)
    (catch :default _ nil)))

(defn- find-up [dir file-name]
  (loop [dir dir]
    (let [f (path/join dir file-name)]
      (if (fs/existsSync f)
        f
        (let [parent (path/dirname dir)]
          (when (not= parent dir) (recur parent)))))))

(defn- file? [p]
  (try (.isFile (fs/statSync p)) (catch :default _ false)))

(defn request-task
  "{:runner :task :args} of the task decision request `req` runs in its
   effective cwd, or nil: not a task call, the nearest project file doesn't
   define the task, or a file of that name sits in the cwd (bb runs a file
   before a task of the same name)."
  [req]
  (when-let [{:keys [runner task] :as t} (some-> (request-argv req) parse)]
    (let [cwd (paths/real-resolve (or (:effective-cwd req) (.cwd js/process)) ".")]
      (when-let [f (and (not (file? (path/resolve cwd task)))
                        (find-up cwd (get-in runners [runner :file])))]
        (when (contains? (project-tasks runner (str (fs/readFileSync f "utf8"))) task)
          t)))))
