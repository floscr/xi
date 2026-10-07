(ns xi.projects-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.projects :as projects]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

;; A fake tree: dir path → child dir names; repos are the dirs in `repos`.
(defn- fake-ops [tree repos]
  (let [dirs (into #{} (mapcat (fn [[p kids]] (cons p (map #(str p "/" %) kids)))) tree)]
    {:child-dirs (fn [p] (vec (get tree p [])))
     :repo?      (fn [p] (contains? repos p))
     :dir?       (fn [p] (contains? dirs p))
     :expand     identity
     :home       "/home/u"}))

(def tree
  {"/c"          ["a" "b" "plain" "work"]
   "/c/work"     ["x" "y"]
   "/c/work/x"   ["deep"]
   "/c/plain"    ["inner"]
   "/c/b"        ["nested"]
   "/repo"       []
   "/r1"         []
   "/r2"         []
   "/r3"         []
   "/scratch"    []})

(def repos #{"/c/a" "/c/b" "/c/work/x" "/c/work/y" "/c/work/x/deep" "/c/b/nested"
             "/r1" "/r2" "/r3"})

(deftest scan-browse-finds-repos-to-depth
  (let [ops (fake-ops tree repos)
        scan (fn [m] (vec (projects/scan-browse ops (merge {:dir "/c" :depth 1 :git? true} m))))]
    (testing "depth 1 lists only repos that are direct children"
      (is (= ["/c/a" "/c/b"] (scan {}))))
    (testing "depth 2 descends into non-repo dirs, never into repos"
      (is (= ["/c/a" "/c/b" "/c/work/x" "/c/work/y"] (scan {:depth 2}))))
    (testing "a repo's own children are never scanned (b/nested, x/deep)"
      (is (= ["/c/a" "/c/b" "/c/work/x" "/c/work/y"] (scan {:depth 4}))))
    (testing ":git? false lists every directory down to depth"
      (is (= ["/c/a" "/c/b" "/c/plain" "/c/work"] (scan {:git? false})))
      (is (= ["/c/a" "/c/b" "/c/b/nested" "/c/plain" "/c/plain/inner"
              "/c/work" "/c/work/x" "/c/work/y"]
             (scan {:git? false :depth 2}))))))

(deftest list-projects-orders-and-dedupes
  (let [ops   (fake-ops tree repos)
        spec  {:browse [{:dir "/c" :depth 1 :git? true}]
               :repos ["/repo" "/gone"]
               :remember-limit 2}
        list* (fn [state & [spec']] (projects/list-projects ops (or spec' spec) state))]
    (testing "configured repos, then scanned; missing dirs dropped"
      (is (= ["/repo" "/c/a" "/c/b"] (list* {:visits {}}))))
    (testing "visited dirs sort first, newest first; the rest keep their order"
      (is (= ["/c/b" "/repo" "/c/a"]
             (list* {:visits {"/c/b" 20 "/repo" 10}}))))
    (testing "visited git repos outside every source are remembered, capped"
      (is (= ["/r1" "/r2" "/repo" "/c/a" "/c/b"]
             (list* {:visits {"/r1" 30 "/r2" 20 "/r3" 10}}))
          "newest two remembered survive the cap of 2")
      (is (= ["/repo" "/c/a" "/c/b"]
             (list* {:visits {"/r1" 30}} (assoc spec :remember-limit 0)))
          "limit 0 turns remembering off"))
    (testing "a visited directory that isn't a repo is never remembered"
      (is (= ["/repo" "/c/a" "/c/b"] (list* {:visits {"/scratch" 99}})))
      (is (= ["/repo" "/c/a" "/c/b"] (list* {:visits {"/removed" 99}}))
          "nor one that is gone"))
    (testing "but it still orders a configured non-repo project"
      (is (= ["/scratch" "/repo" "/c/a" "/c/b"]
             (list* {:visits {"/scratch" 50}} (assoc spec :repos ["/repo" "/scratch"])))))
    (testing "a visit to a configured project does not count against the cap"
      (is (= ["/c/a" "/r1" "/r2" "/repo" "/c/b"]
             (list* {:visits {"/c/a" 40 "/r1" 30 "/r2" 20}}))))))

(deftest repo-root-is-the-enclosing-repo
  (let [ops  (assoc (fake-ops {} #{"/home/u/code/app" "/home/u/code/app/vendor/lib" "/home/u"})
                    :home "/home/u")
        root #(projects/repo-root ops %)]
    (is (= "/home/u/code/app" (root "/home/u/code/app")) "the repo itself")
    (is (= "/home/u/code/app" (root "/home/u/code/app/src/deep")) "a sub-directory")
    (is (= "/home/u/code/app/vendor/lib" (root "/home/u/code/app/vendor/lib/x"))
        "the nearest repo wins")
    (is (nil? (root "/home/u/notes"))
        "a .git in home doesn't make everything below it one project")
    (is (nil? (root "/tmp/scratch/deep")))
    (is (nil? (root "/")))))

(deftest state-transitions
  (testing "visit stamps the dir"
    (is (= {"/a" 7} (:visits (projects/visit-state {:visits {}} "/a" 7)))))
  (testing "visits are capped at the newest `visit-cap`"
    (let [many  (into {} (map (fn [i] [(str "/d" i) (inc i)]))
                      (range (+ projects/visit-cap 20)))
          state (projects/visit-state {:visits many} "/fresh" 99999)]
      (is (= projects/visit-cap (count (:visits state))))
      (is (contains? (:visits state) "/fresh"))
      (is (not (contains? (:visits state) "/d0"))))))

(deftest parse-spec-validates-and-normalizes
  (let [err (fn [data] (:error (projects/parse-spec data)))]
    (testing "absent → defaults"
      (is (= projects/default-spec (projects/parse-spec nil)))
      (is (= projects/default-spec (projects/parse-spec {}))))
    (testing "strings and maps normalize to {:dir :depth :git?}"
      (is (= {:browse [{:dir "~/a" :depth 1 :git? true}
                       {:dir "~/b" :depth 3 :git? false}]
              :repos ["~/r"]
              :remember-limit 7
              :settings {}}
             (projects/parse-spec {:browse ["~/a" {:dir "~/b" :depth 3 :git? false}]
                                   :repos ["~/r"]
                                   :remember-limit 7}))))
    (testing ":settings are kept as given"
      (let [settings {"~/p" {:agents-prompt "docs/a.md" :agents-replace true
                             :snippets [{:label "x" :text "y"}]}
                      "~/q" {}}]
        (is (= settings (:settings (projects/parse-spec {:settings settings}))))))
    (testing "invalid shapes are errors"
      (is (re-find #":projects must be a map" (err [])))
      (is (re-find #"unknown :projects key\(s\) :dirs" (err {:dirs []})))
      (is (re-find #":browse must be a vector" (err {:browse "~/a"})))
      (is (re-find #":browse entries" (err {:browse [{:depth 2}]})))
      (is (re-find #":browse entries" (err {:browse [{:dir "~/a" :depth 0}]})))
      (is (re-find #":browse entries" (err {:browse [{:dir "~/a" :depth 99}]})))
      (is (re-find #":browse entries" (err {:browse [{:dir "~/a" :extra 1}]})))
      (is (re-find #":repos must be a vector" (err {:repos [1]})))
      (is (re-find #":remember-limit" (err {:remember-limit -1})))
      (is (re-find #":settings must map" (err {:settings []})))
      (is (re-find #":settings must map" (err {:settings {"" {}}})))
      (is (re-find #":settings must map" (err {:settings {"~/p" {:prompt "x"}}})))
      (is (re-find #":settings must map" (err {:settings {"~/p" {:agents-prompt 1}}})))
      (is (re-find #":settings must map" (err {:settings {"~/p" {:agents-replace "yes"}}})))
      (is (re-find #":settings must map" (err {:settings {"~/p" {:snippets ["x"]}}}))))))

;; ── Per-project settings ───────────────────────────────────────────────────────

(def settings-ops
  "Fake fs: `~` → /home/u, a trailing slash is dropped; files = a path → text map."
  (let [files {"/home/u/proj/docs/agents.md" "FILE RELATIVE"
               "/etc/abs-prompt.md"           "FILE ABSOLUTE"}]
    {:expand    (fn [p] (-> p (str/replace #"^~" "/home/u") (str/replace #"(?<=.)/+$" "")))
     :read-file (fn [p] (get files p))}))

(def settings-spec
  (projects/parse-spec
   {:settings {"~/proj"  {:agents-prompt "docs/agents.md" :agents-replace true
                          :snippets [{:label "Check" :text "run checks"}]}
               "~/abs/"  {:agents-prompt "/etc/abs-prompt.md"}
               "~/inl"   {:agents-prompt "Be terse."}
               "~/blank" {:agents-prompt "  "}
               "~/snip"  {:snippets [{:label "a" :text "b"}]}
               "~/ign"   {:agents-ignore true :agents-prompt "Freelancer."}}}))

(deftest settings-are-looked-up-by-exact-dir
  (let [for* #(projects/settings-for settings-ops settings-spec %)]
    (is (some? (for* "/home/u/proj")))
    (is (some? (for* "~/proj")) "cwd may use ~ too")
    (is (some? (for* "/home/u/abs")) "a trailing slash in the key is ignored")
    (is (nil? (for* "/home/u/proj/sub")) "sub-directories don't inherit")
    (is (nil? (for* "/home/u/other")))))

(deftest agents-prompt-resolves-file-or-literal
  (let [prompt #(projects/agents-prompt settings-ops settings-spec %)]
    (testing "a file relative to the project dir, with :replace"
      (is (= {:prompt "FILE RELATIVE" :replace true} (prompt "/home/u/proj"))))
    (testing "an absolute file"
      (is (= {:prompt "FILE ABSOLUTE" :replace false} (prompt "/home/u/abs"))))
    (testing "anything that isn't a file is the prompt itself"
      (is (= {:prompt "Be terse." :replace false} (prompt "/home/u/inl"))))
    (testing "no prompt, a blank one, or no settings → nil"
      (is (nil? (prompt "/home/u/snip")))
      (is (nil? (prompt "/home/u/blank")))
      (is (nil? (prompt "/home/u/other"))))))

(deftest agents-ignore-is-per-exact-dir
  (let [ignore? #(projects/agents-ignore? settings-ops settings-spec %)]
    (is (true? (ignore? "/home/u/ign")))
    (is (false? (ignore? "/home/u/proj")) "unset defaults to false")
    (is (false? (ignore? "/home/u/ign/sub")) "sub-directories don't inherit")
    (is (false? (ignore? "/home/u/other"))))
  (testing "ignoring files doesn't touch the prompt"
    (is (= {:prompt "Freelancer." :replace false}
           (projects/agents-prompt settings-ops settings-spec "/home/u/ign"))))
  (testing "must be a boolean"
    (is (:error (projects/parse-spec {:settings {"~/p" {:agents-ignore "yes"}}})))))

(deftest snippets-come-from-settings
  (is (= [{:label "Check" :text "run checks"}]
         (projects/snippets settings-ops settings-spec "/home/u/proj")))
  (is (= [] (projects/snippets settings-ops settings-spec "/home/u/inl")))
  (is (= [] (projects/snippets settings-ops settings-spec "/home/u/other"))))

(deftest real-read-file-treats-inline-text-as-literal
  (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-settings-"))
        file (node-path/join dir "prompt.md")
        long (apply str (repeat 400 "x"))
        spec (projects/parse-spec {:settings {dir {:agents-prompt "prompt.md"}
                                              (str dir "/sub") {:agents-prompt long}}})]
    (try
      (fs/writeFileSync file "FROM DISK")
      (fs/mkdirSync (node-path/join dir "sub"))
      (is (= {:prompt "FROM DISK" :replace false} (projects/agents-prompt! spec dir)))
      (is (= {:prompt long :replace false} (projects/agents-prompt! spec (str dir "/sub")))
          "an over-long inline string is not mistaken for a path")
      (finally (fs/rmSync dir #js {:recursive true :force true})))))

;; ── Real fs: state file + scanning round trip ───────────────────────────────

(deftest sessions-in-repos-are-remembered
  (let [root   (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-projects-"))
        mk     (fn [& parts] (let [p (apply node-path/join root parts)]
                               (fs/mkdirSync p #js {:recursive true})
                               p))
        code   (mk "code")
        a      (mk "code" "a")
        _git-a (mk "code" "a" ".git")
        a-sub  (mk "code" "a" "sub")
        _plain (mk "code" "plain")
        orepo  (mk "other" "repo")
        _git-o (mk "other" "repo" ".git")
        o-sub  (mk "other" "repo" "deep" "sub")
        scratch (mk "scratch")
        spec   (projects/parse-spec {:browse [code]})]
    (projects/set-state-file! (node-path/join root "state" "projects.edn"))
    (try
      (testing "a fresh state lists only the scanned repos"
        (is (= [a] (projects/list-projects! spec))))
      (testing "a session deep inside an unrelated repo remembers the repo's root"
        (is (= orepo (projects/visit! o-sub)))
        (is (= [orepo a] (projects/list-projects! spec))))
      (testing "a session inside a configured repo just reorders it"
        (is (= a (projects/visit! a-sub)))
        (is (= [a orepo] (projects/list-projects! spec))))
      (testing "a directory outside any repo is not remembered into the list"
        (is (= scratch (projects/visit! scratch)))
        (is (= [a orepo] (projects/list-projects! spec))))
      (testing "home and missing directories are never tracked"
        (is (nil? (projects/visit! (os/homedir))))
        (is (nil? (projects/visit! (node-path/join root "missing")))))
      (testing "the state file is plain EDN; a :pinned key from older xi is ignored"
        (is (str/includes? (fs/readFileSync (projects/state-file) "utf8") ":visits"))
        (fs/writeFileSync (projects/state-file) "{:pinned [\"/x\"] :visits {}}")
        (is (= {:visits {}} (projects/read-state))))
      (finally
        (projects/set-state-file! nil)
        (fs/rmSync root #js {:recursive true :force true})))))
