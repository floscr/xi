(ns xi.rules.tasks-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules :as rules]
            [xi.rules.store :as store]
            [xi.rules.tasks :as tasks]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(deftest parse-runners
  (testing "bb: the first argument is the task, the rest its args"
    (is (= {:runner "bb" :task "test" :args ["foo" "--bar"]}
           (tasks/parse ["bb" "test" "foo" "--bar"])))
    (is (= "ts:test:jvm" (:task (tasks/parse ["bb" "ts:test:jvm"])))))
  (testing "npm / pnpm: `run <script>` and the lifecycle scripts"
    (is (= {:runner "npm" :task "build" :args ["--" "-w"]}
           (tasks/parse ["npm" "run" "build" "--" "-w"])))
    (is (= "test" (:task (tasks/parse ["npm" "test"]))))
    (is (= "lint" (:task (tasks/parse ["pnpm" "run-script" "lint"])))))
  (testing "yarn run / deno task"
    (is (= "dev" (:task (tasks/parse ["yarn" "run" "dev"]))))
    (is (= "dev" (:task (tasks/parse ["deno" "task" "dev"])))))
  (testing "a flag before the task, no task, or another subcommand never parses"
    (is (nil? (tasks/parse ["bb" "--config" "x.edn" "test"])))
    (is (nil? (tasks/parse ["bb" "-e" "(+ 1 2)"])))
    (is (nil? (tasks/parse ["bb"])))
    (is (nil? (tasks/parse ["npm" "run"])))
    (is (nil? (tasks/parse ["npm" "--prefix" "x" "run" "build"])))
    (is (nil? (tasks/parse ["npm" "install"])))
    (is (nil? (tasks/parse ["deno" "run" "x.ts"])))
    (is (nil? (tasks/parse ["make" "test"])))))

(deftest task-argv
  (is (= ["bb" "test" "a"] (tasks/task-argv "bb" " test " ["a"])))
  (is (= ["npm" "run" "build" "--" "-w"] (tasks/task-argv "npm" "build" ["--" "-w"])))
  (is (= ["deno" "task" "dev"] (tasks/task-argv "deno" "dev" nil)))
  (testing "no task lists the tasks"
    (is (= ["bb" "tasks"] (tasks/task-argv "bb" nil nil)))
    (is (= ["pnpm" "run"] (tasks/task-argv "pnpm" "" nil))))
  (is (nil? (tasks/task-argv "make" "test" nil)) "unknown runner"))

(deftest request-argv
  (is (= ["bb" "test" "a"] (tasks/request-argv {:tool :task :runner "bb" :arguments {:task " test " :args ["a"]}})))
  (is (= ["yarn" "run" "lint"] (tasks/request-argv {:tool :task :runner "npm" :arguments {:task "lint" :runner "yarn"}}))
      "the call's own runner wins")
  (is (nil? (tasks/request-argv {:tool :task :runner "bb" :arguments {}})) "a listing runs no task")
  (is (= ["bb" "test"] (tasks/request-argv {:tool :sh :argv ["bb" "test"]})))
  (is (= ["bb" "test" "x"] (tasks/request-argv {:tool :sh :command "bb test x"})))
  (is (nil? (tasks/request-argv {:tool :sh :command "bb test; rm -rf x"}))
      "shell syntax in a background command never parses")
  (is (nil? (tasks/request-argv {:tool :clj :command "bb test"}))))

(deftest project-tasks
  (is (= #{"test" "ts:lint"}
         (tasks/project-tasks "bb" "{:paths [\"src\"] :tasks {:requires ([babashka.fs :as fs])
                                     test (shell \"bb\" #(inc %))
                                     ts:lint {:task (run 'x)}}}")))
  (is (= #{"build" "test"}
         (tasks/project-tasks "npm" "{\"scripts\": {\"build\": \"x\", \"test\": \"y\"}}")))
  (is (= #{"dev"} (tasks/project-tasks "deno" "{\"tasks\": {\"dev\": \"x\"}}")))
  (is (nil? (tasks/project-tasks "bb" "{:tasks"))))

(defn- decide [rule-match req]
  (some-> (rules/first-match [{:match rule-match :action {:type :allow}}] req)
          (get-in [:action :type])))

(deftest task-matching
  (let [req (fn [runner task] {:tool :sh :runner-task {:runner runner :task task}})]
    (testing "patterns: exact, glob, regex, a list"
      (is (= :allow (decide {:task {"bb" "test"}} (req "bb" "test"))))
      (is (= :allow (decide {:task {"bb" ["ts:*"]}} (req "bb" "ts:test:jvm"))))
      (is (= :allow (decide {:task {"bb" #"^ts:"}} (req "bb" "ts:lint"))))
      (is (= :allow (decide {:task {"bb" ["lint" "test"]}} (req "bb" "test"))))
      (is (nil? (decide {:task {"bb" ["lint" "test"]}} (req "bb" "deploy")))))
    (testing "`!` excludes; only negations means everything else"
      (is (= :allow (decide {:task {"bb" ["*" "!deploy"]}} (req "bb" "test"))))
      (is (nil? (decide {:task {"bb" ["*" "!deploy"]}} (req "bb" "deploy"))))
      (is (nil? (decide {:task {"bb" ["*" "!deploy:*"]}} (req "bb" "deploy:prod"))))
      (is (= :allow (decide {:task {"bb" ["!deploy"]}} (req "bb" "test")))))
    (testing "the runner must be listed"
      (is (nil? (decide {:task {"bb" "test"}} (req "npm" "test"))))
      (is (= :allow (decide {:task {"bb" "test" "npm" "test"}} (req "npm" "test")))))
    (testing "no resolved task, or a malformed spec, never matches"
      (is (nil? (decide {:task {"bb" "*"}} {:tool :sh})))
      (is (nil? (decide {:task "test"} (req "bb" "test")))))
    (is (rules/arg-scoped? {:match {:tool :sh :cli "bb" :task {"bb" "test"}}})
        "a :task allow covers only the exact command, never all of bb")))

(defn- tmp-project []
  (let [dir (fs/realpathSync (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-tasks-")))]
    (fs/writeFileSync (node-path/join dir "bb.edn") "{:tasks {test (shell \"x\") lint (shell \"y\")}}")
    (fs/mkdirSync (node-path/join dir "sub"))
    dir))

(deftest request-task-reads-the-project-file
  (let [dir (tmp-project)
        sub (node-path/join dir "sub")]
    (is (= {:runner "bb" :task "test" :args ["a"]}
           (tasks/request-task {:tool :sh :argv ["bb" "test" "a"] :effective-cwd dir})))
    (is (= "lint" (:task (tasks/request-task {:tool :task :runner "bb" :arguments {:task "lint"} :effective-cwd sub})))
        "bb.edn is found from a subdirectory")
    (is (nil? (tasks/request-task {:tool :sh :argv ["bb" "deploy"] :effective-cwd dir}))
        "a task bb.edn doesn't define is no task")
    (is (nil? (tasks/request-task {:tool :sh :argv ["npm" "test"] :effective-cwd dir}))
        "no package.json")
    (fs/writeFileSync (node-path/join dir "lint") "(println :pwned)")
    (is (nil? (tasks/request-task {:tool :sh :argv ["bb" "lint"] :effective-cwd dir}))
        "bb runs a file of the task's name instead of the task")))

(deftest store-enriches-only-with-a-task-rule
  (let [dir     (tmp-project)
        rule    {:match {:tool :sh :cli "bb" :task {"bb" ["*" "!lint"]}} :action {:type :allow}}
        req     {:tool :sh :cli "bb" :argv ["bb" "test"] :effective-cwd dir}
        decide* (fn [req] (get-in (rules/first-match [rule] (store/enrich-request req [rule]))
                                  [:action :type]))]
    (is (nil? (:runner-task (store/enrich-request req []))))
    (is (= :allow (decide* req)))
    (is (nil? (decide* (assoc req :argv ["bb" "lint"]))))
    (is (nil? (decide* (assoc req :argv ["bb" "nope"]))))))

(deftest detect-runner-and-project-file
  (let [dir (tmp-project)
        js  (fs/realpathSync (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-tasks-js-")))]
    (is (= "bb" (tasks/detect-runner (node-path/join dir "sub"))))
    (is (= (node-path/join dir "bb.edn") (tasks/project-file (node-path/join dir "sub") "bb")))
    (fs/writeFileSync (node-path/join js "package.json") "{\"scripts\": {\"build\": \"x\"}}")
    (is (= "npm" (tasks/detect-runner js)))
    (fs/writeFileSync (node-path/join js "pnpm-lock.yaml") "")
    (is (= "pnpm" (tasks/detect-runner js)) "a lockfile picks the package manager")
    (is (= (node-path/join js "package.json") (tasks/project-file js "yarn")))))

(deftest clj-sh-task-matches-run-task-rules
  (let [dir     (tmp-project)
        rule    {:match {:tool :task :task-file-trusted false :task {"bb" "*"}} :action {:type :allow}}
        req     {:tool :sh :cli "bb" :command "bb test" :argv ["bb" "test"] :effective-cwd dir}
        enrich  #(store/enrich-request % [rule])
        decide* #(get-in (rules/first-match [rule] (enrich %)) [:action :type])]
    (is (true? (:task-call? (enrich req))))
    (is (= (node-path/join dir "bb.edn") (:task-file (enrich req))))
    (is (false? (:task-file-trusted? (enrich req))) "trust is resolved for the sh call too")
    (is (= :allow (decide* req)) "(sh \"bb\" \"test\") is matched like run_task")
    (is (nil? (decide* (assoc req :argv ["bb" "nope"] :command "bb nope")))
        "a task bb.edn doesn't define stays a plain :sh call")
    (is (nil? (decide* (assoc req :argv ["bb" "-e" "(+ 1 2)"] :command "bb -e (+ 1 2)"))))
    (is (nil? (decide* (dissoc req :argv))) "dynamic args never match")
    (is (nil? (decide* (assoc req :extension "notes")))
        "an extension's (sh \"bb\" …) keeps its own asks")))
