(ns xi.git-lock-test
  (:require [cljs.test :refer [deftest is testing async]]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            [xi.ext.git-lock :as ext]
            [xi.git-lock :as lock]))

;; ── Classification ───────────────────────────────────────────────────────────

(deftest parse-argv-test
  (is (= {:dir nil :sub "add" :args ["--" "a"]} (lock/parse-argv ["add" "--" "a"])))
  (is (= {:dir "sub" :sub "commit" :args ["-m" "x"]}
         (lock/parse-argv ["-C" "sub" "-c" "k=v" "--no-pager" "commit" "-m" "x"])))
  (is (= "a/b" (:dir (lock/parse-argv ["-C" "a" "-C" "b" "status"]))))
  (is (nil? (:sub (lock/parse-argv ["--version"])))))

(deftest locking-test
  (testing "index/HEAD/worktree mutations lock"
    (doseq [argv [["add" "x"] ["commit" "-m" "x"] ["reset"] ["restore" "--staged" "x"]
                  ["stash"] ["stash" "pop"] ["checkout" "main"] ["rebase" "main"]
                  ["some-future-subcommand"]]]
      (is (lock/locking? (lock/parse-argv argv)) (pr-str argv))))
  (testing "read-only / ref-only ops don't"
    (doseq [argv [["status"] ["diff" "--cached"] ["log" "--oneline"] ["stash" "list"]
                  ["push"] ["fetch"] ["branch"] ["--version"] []]]
      (is (not (lock/locking? (lock/parse-argv argv))) (pr-str argv)))))

(deftest broad-add-test
  (doseq [argv [["add" "-A"] ["add" "."] ["add" "--all"] ["add" "-u"] ["add" "-vA"]
                ["add" "--" "."] ["commit" "-a" "-m" "x"] ["commit" "-am" "x"]
                ["commit" "--all"]]]
    (is (lock/broad-add? (lock/parse-argv argv)) (pr-str argv)))
  (doseq [argv [["add" "--" "src/a.clj"] ["add" "-p" "x"] ["commit" "-m" "x"]
                ["commit" "--amend" "--no-edit"] ["status"]]]
    (is (not (lock/broad-add? (lock/parse-argv argv))) (pr-str argv))))

(deftest tool-call-argvs-test
  (is (= [["add" "--" "a" "b"]]
         (lock/tool-call-argvs {:name "git_stage_hunks" :arguments {:files ["a" "b"]}})))
  (is (= [["add" "--" "a"] ["commit"]]
         (lock/tool-call-argvs {:name "mcp__xi-tools__git_commit"
                                :arguments {:message "m" :files ["a"]}})))
  (is (= [["commit"]] (lock/tool-call-argvs {:name "git_commit" :arguments {:message "m"}})))
  (is (= [["add" "x"] ["commit" "-m" "\"msg\""]]
         (lock/tool-call-argvs {:name "bash"
                                :arguments {:command "git add x && git commit -m \"msg\""}})))
  (is (nil? (lock/tool-call-argvs {:name "read" :arguments {:path "x"}}))))

(deftest stale-test
  (let [lock {:pid 1 :touched-at 0}
        alive (constantly true)]
    (is (lock/stale? lock {:now 1 :staged [] :alive? (constantly false)}) "dead pid")
    (is (not (lock/stale? lock {:now 1 :staged [] :alive? alive})) "fresh + clean")
    (is (lock/stale? lock {:now (inc lock/STALE_CLEAN_MS) :staged [] :alive? alive})
        "clean for too long")
    (is (not (lock/stale? lock {:now (* 10 lock/STALE_CLEAN_MS) :staged ["a"] :alive? alive}))
        "staged files keep it held indefinitely")))

(deftest foreign-sweep-test
  (is (= [{:label "B" :files ["/r/b"]}]
         (lock/foreign-sweep #{"/r/a" "/r/b"}
                             [{:label "B" :files ["/r/b" "/r/clean"]}
                              {:label "C" :files ["/r/c"]}]))))

;; ── Lease lifecycle on a real repo ───────────────────────────────────────────

(defn- git! [dir & args]
  (let [r (cp/spawnSync "git" (clj->js args) #js {:cwd dir :encoding "utf8"})]
    (assert (= 0 (.-status r)) (str "git " args ": " (.-stderr r)))
    (.-stdout r)))

(defn- tmp-repo []
  (let [dir (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-git-lock-"))]
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@t")
    (git! dir "config" "user.name" "t")
    (fs/writeFileSync (node-path/join dir "a.txt") "a")
    (fs/writeFileSync (node-path/join dir "b.txt") "b")
    dir))

(def ^:private A {:pid js/process.pid :room "a" :label "Room A"})
(def ^:private B {:pid js/process.pid :room "b" :label "Room B"})

(deftest lease-lifecycle-test
  (let [dir (tmp-repo)]
    (testing "re-entrant for the holder, busy for others"
      (is (:fresh? (lock/try-acquire! dir A)))
      (is (= {:status :acquired :fresh? false}
             (select-keys (lock/try-acquire! dir A) [:status :fresh?])))
      (let [r (lock/try-acquire! dir B)]
        (is (= :busy (:status r)))
        (is (= "Room A" (get-in r [:holder :label])))))
    (testing "settle keeps the lease while files are staged"
      (git! dir "add" "a.txt")
      (is (not (lock/settle! dir A)))
      (is (= :busy (:status (lock/try-acquire! dir B)))))
    (testing "settle by a non-owner is a no-op"
      (git! dir "commit" "-qm" "a")
      (is (not (lock/settle! dir B))))
    (testing "commit → clean index → settle releases, B can take it"
      (is (lock/settle! dir A))
      (is (nil? (lock/holder dir)))
      (is (= :acquired (:status (lock/try-acquire! dir B)))))
    (testing "force-release drops any holder"
      (is (= "b" (:room (lock/force-release! dir))))
      (is (nil? (lock/holder dir))))
    (testing "outside a repo"
      (is (= :no-repo (:status (lock/try-acquire! (os/tmpdir) A)))))))

(deftest dead-holder-is-stealable-test
  (let [dir (tmp-repo)]
    (lock/try-acquire! dir {:pid 999999999 :room "ghost"})
    (git! dir "add" "a.txt")
    (is (= :acquired (:status (lock/try-acquire! dir A))))))

;; ── Extension gate (two rooms, one repo) ─────────────────────────────────────

(defn- gate [tool-call ctx]
  ((:tool-gate ext/extension) tool-call ctx))

(defn- gate-ctx [dir state room-id]
  {:room-id room-id :cwd dir :get-state (fn [] @state) :dispatch! (fn [_])})

(deftest gate-two-rooms-test
  (async done
    (let [dir   (tmp-repo)
          state (atom {:rooms {:ra {:id :ra :cwd dir :agent {:busy? true}}
                               :rb {:id :rb :cwd dir :agent {:busy? true}}}})
          stage {:name "git_stage_hunks" :arguments {:files ["a.txt"]}}
          commit {:name "git_commit" :arguments {:message "b"}}]
      (aset js/process.env "XI_GIT_LOCK_WAIT_SECS" "0")
      (-> (gate stage (gate-ctx dir state :ra))
          (.then (fn [r]
                   (is (= stage r) "A acquires and proceeds")
                   (git! dir "add" "a.txt")
                   (gate commit (gate-ctx dir state :rb))))
          (.then (fn [r]
                   (is (:intercepted r) "B is held off while A has staged files")
                   (is (re-find #"Room|ra|git index lock"
                                (get-in r [:result :content 0 :text])))
                   (git! dir "commit" "-qm" "a")
                   ((get-in ext/extension [:fx :git-lock/settle]) nil {:room-id :ra :cwd dir})
                   (gate commit (gate-ctx dir state :rb))))
          (.then (fn [r]
                   (is (= commit r) "B proceeds once A committed")
                   (is (= "rb" (:room (lock/holder dir))))))
          (.finally (fn []
                      (js-delete js/process.env "XI_GIT_LOCK_WAIT_SECS")
                      (done)))))))

(deftest broad-add-refused-when-sweeping-other-room-test
  (async done
    (let [dir   (tmp-repo)
          state (atom {:rooms {:ra {:id :ra :cwd dir :agent {:busy? true} :history []}
                               :rb {:id :rb :cwd dir :agent {:busy? true}
                                    :session {:name "Room B"}
                                    :history [{:kind :tool-call :tool "edit"
                                               :arguments {:path "b.txt"}}]}}})]
      (-> (gate {:name "bash" :arguments {:command "git add -A"}} (gate-ctx dir state :ra))
          (.then (fn [r]
                   (is (:intercepted r))
                   (is (re-find #"Room B.*b\.txt" (get-in r [:result :content 0 :text])))
                   (gate {:name "bash" :arguments {:command "git add a.txt"}}
                         (gate-ctx dir state :ra))))
          (.then (fn [r]
                   (is (not (:intercepted r)) "explicit paths are fine")))
          (.finally done)))))
