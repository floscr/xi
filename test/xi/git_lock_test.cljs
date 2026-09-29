(ns xi.git-lock-test
  (:require [cljs.test :refer [deftest is testing async]]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            [xi.git-lock :as lock]
            [xi.holds :as holds]
            [xi.holds.lease :as lease]))

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

(deftest ops-keeps-only-locking-git-test
  (is (= ["add" "commit"] (map :sub (lock/ops "bash" {:command "git status && git add x && git commit -m m"}))))
  (is (= [] (lock/ops "bash" {:command "git log"})))
  (is (= [] (lock/ops "read" {:path "x"}))))

(deftest stale-clean-test
  (let [l {:pid 1 :touched-at 0}]
    (is (not (lock/stale-clean? l {:now 1 :staged []})) "fresh + clean")
    (is (lock/stale-clean? l {:now (inc lock/STALE_CLEAN_MS) :staged []})
        "clean for too long")
    (is (not (lock/stale-clean? l {:now (* 10 lock/STALE_CLEAN_MS) :staged ["a"]}))
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
  (let [dir (fs/realpathSync (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-git-lock-")))]
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@t")
    (git! dir "config" "user.name" "t")
    (fs/writeFileSync (node-path/join dir "a.txt") "a")
    (fs/writeFileSync (node-path/join dir "b.txt") "b")
    dir))

(def ^:private A {:pid js/process.pid :room "a" :label "Room A"})
(def ^:private B {:pid js/process.pid :room "b" :label "Room B"})

(defn- stale? [dir] #((:stale? lock/hold) dir %))

(deftest lease-lifecycle-test
  (let [dir  (tmp-repo)
        path (lock/lease-path dir)]
    (testing "the lease sits in the worktree's git dir"
      (is (= (node-path/join dir ".git" lock/LOCK_NAME) path)))
    (testing "re-entrant for the holder, busy for others"
      (is (:fresh? (lease/try-acquire! path A (stale? dir))))
      (is (= {:status :acquired :fresh? false}
             (select-keys (lease/try-acquire! path A (stale? dir)) [:status :fresh?])))
      (let [r (lease/try-acquire! path B (stale? dir))]
        (is (= :busy (:status r)))
        (is (= "Room A" (get-in r [:holder :label])))))
    (testing "settle keeps the hold while files are staged"
      (git! dir "add" "a.txt")
      (is (not (holds/settle! lock/hold dir A)))
      (is (= :busy (:status (lease/try-acquire! path B (stale? dir))))))
    (testing "settle by a non-owner is a no-op"
      (git! dir "commit" "-qm" "a")
      (is (not (holds/settle! lock/hold dir B))))
    (testing "commit → clean index → settle releases, B can take it"
      (is (holds/settle! lock/hold dir A))
      (is (nil? (lease/read-lease path)))
      (is (= :acquired (:status (lease/try-acquire! path B (stale? dir))))))
    (testing "force-release drops any holder"
      (is (= "b" (:room (lease/force-release! path))))
      (is (nil? (lease/read-lease path))))
    (testing "outside a repo there is nothing to hold"
      (is (nil? (lock/lease-path (os/tmpdir)))))))

(deftest dead-holder-is-stealable-test
  (let [dir  (tmp-repo)
        path (lock/lease-path dir)]
    (lease/try-acquire! path {:pid 999999999 :room "ghost"} (stale? dir))
    (git! dir "add" "a.txt")
    (is (= :acquired (:status (lease/try-acquire! path A (stale? dir)))))))

;; ── holds/wrap: two rooms, one repo ──────────────────────────────────────────

(defn- ctx [dir state room-id]
  {:room-id room-id :cwd dir :get-state (fn [] @state) :dispatch! (fn [_])})

(defn- ran [label] (fn [_args _ctx] {:content [{:type "text" :text label}]}))

(deftest wrap-two-rooms-test
  (async done
    (let [dir    (tmp-repo)
          state  (atom {:rooms {:ra {:id :ra :cwd dir :agent {:busy? true}}
                                :rb {:id :rb :cwd dir :agent {:busy? true}}}})
          stage  (holds/wrap "git_stage_hunks" (fn [_ _] (git! dir "add" "a.txt")
                                                 {:content [{:type "text" :text "staged"}]}))
          commit (holds/wrap "git_commit" (ran "committed"))]
      (aset js/process.env "XI_GIT_LOCK_WAIT_SECS" "0")
      (-> (stage {:files ["a.txt"]} (ctx dir state :ra))
          (.then (fn [r]
                   (is (= "staged" (get-in r [:content 0 :text])) "A acquires and runs")
                   (is (= "ra" (:room (lease/read-lease (lock/lease-path dir))))
                       "staged files keep A's hold after the call")
                   (commit {:message "b"} (ctx dir state :rb))))
          (.then (fn [r]
                   (is (:is-error r) "B is held off while A has staged files")
                   (is (re-find #"git index.*held|gave up" (get-in r [:content 0 :text])))
                   (git! dir "commit" "-qm" "a")
                   (holds/settle-room! :ra dir)
                   (commit {:message "b"} (ctx dir state :rb))))
          (.then (fn [r]
                   (is (= "committed" (get-in r [:content 0 :text])) "B runs once A committed")
                   (is (nil? (lease/read-lease (lock/lease-path dir)))
                       "clean index after B's call → B's hold settled too")))
          (.finally (fn []
                      (js-delete js/process.env "XI_GIT_LOCK_WAIT_SECS")
                      (done)))))))

(deftest wrap-refuses-broad-add-sweeping-other-room-test
  (async done
    (let [dir   (tmp-repo)
          state (atom {:rooms {:ra {:id :ra :cwd dir :agent {:busy? true} :history []}
                               :rb {:id :rb :cwd dir :agent {:busy? true}
                                    :session {:name "Room B"}
                                    :history [{:kind :tool-call :tool "edit"
                                               :arguments {:path "b.txt"}}]}}})
          bash  (holds/wrap "bash" (ran "ran"))]
      (-> (bash {:command "git add -A"} (ctx dir state :ra))
          (.then (fn [r]
                   (is (:is-error r))
                   (is (re-find #"Room B.*b\.txt" (get-in r [:content 0 :text])))
                   (bash {:command "git add a.txt"} (ctx dir state :ra))))
          (.then (fn [r]
                   (is (= "ran" (get-in r [:content 0 :text])) "explicit paths are fine")))
          (.finally done)))))

(deftest wrap-passes-unheld-calls-straight-through-test
  (let [called (atom nil)
        read   (holds/wrap "read" (fn [args _] (reset! called args) :direct))]
    (is (= :direct (read {:path "x"} {:cwd "/tmp"})) "no promise, no lease — just the exec-fn")
    (is (= {:path "x"} @called))))
