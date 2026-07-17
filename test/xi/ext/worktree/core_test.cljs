(ns xi.ext.worktree.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.worktree.core :as wt]))

(deftest slugify-test
  (is (= "add-dark-mode" (wt/slugify "Add Dark Mode")))
  (is (= "foo-bar" (wt/slugify "  foo!!! @bar  ")))
  (is (= "a-b-c" (wt/slugify "a/b/c")))
  (is (= "" (wt/slugify "   ")))
  (is (= "" (wt/slugify nil))))

(deftest gen-branch-name-test
  (testing "slug from prompt (first words)"
    (is (= "add-a-dark-mode-toggle-here"
           (wt/gen-branch-name "Add a dark mode toggle here please now"))))
  (testing "timestamp fallback when no usable slug"
    (let [b (wt/gen-branch-name "   ")]
      (is (str/starts-with? b "wt-"))
      (is (> (count b) 3)))
    (is (str/starts-with? (wt/gen-branch-name nil) "wt-"))))

(deftest worktree-path-test
  (is (= "/home/dev/acme-web-add-dark-mode"
         (wt/worktree-path "/home/dev/acme-web" "add-dark-mode")))
  (testing "branch is slugified into the dir name"
    (is (= "/home/dev/acme-feat-x"
           (wt/worktree-path "/home/dev/acme" "feat/X")))))

(deftest extract-section-test
  (let [md (str "# Title\n\nintro\n\n"
                "## Worktrees\n\nDo the thing.\nMore.\n\n"
                "## Build\n\nrun bb build\n")]
    (testing "returns the matching section up to the next same-level heading"
      (let [s (wt/extract-section md "worktree")]
        (is (str/includes? s "## Worktrees"))
        (is (str/includes? s "Do the thing."))
        (is (not (str/includes? s "## Build")))))
    (testing "nil when no heading matches"
      (is (nil? (wt/extract-section md "nonexistent"))))))

(deftest cwd-linkage-test
  ;; /diff and /diff git read `(:cwd room)` (see xi.ext.diff), so the worktree
  ;; feature "respects the worktree" precisely because its handlers rewrite the
  ;; room's cwd. Lock that linkage: :worktree/created points the room at the
  ;; worktree, :worktree/switch moves it back to the main tree.
  (let [handlers (:handlers (wt/create nil))
        created  (:worktree/created handlers)
        switch   (:worktree/switch handlers)
        rid      "room-1"
        st0      (-> (state/initial-state)
                     (assoc-in [:rooms rid] (state/make-room rid {:cwd "/w/acme"})))]
    (testing ":worktree/created retargets the room cwd to the worktree path"
      (let [{st :state} (created st0 {:room-id rid :path "/w/acme-x"
                                      :branch "x" :base "master"
                                      :main-root "/w/acme"})]
        (is (= "/w/acme-x" (get-in st [:rooms rid :cwd])))
        (is (= {:path "/w/acme-x" :branch "x" :base "master" :main-root "/w/acme"}
               (get-in st [:rooms rid :ext :worktree])))))
    (testing ":worktree/switch moves the room cwd back and clears metadata"
      (let [{st :state} (created st0 {:room-id rid :path "/w/acme-x"
                                      :branch "x" :base "master"
                                      :main-root "/w/acme"})
            {st2 :state} (switch st {:room-id rid :cwd "/w/acme" :clear? true})]
        (is (= "/w/acme" (get-in st2 [:rooms rid :cwd])))
        (is (nil? (get-in st2 [:rooms rid :ext :worktree])))))
    (testing "handlers no-op on an unknown room"
      (is (nil? (created st0 {:room-id "nope" :path "/w/x" :branch "x"
                             :base "master" :main-root "/w/acme"})))
      (is (nil? (switch st0 {:room-id "nope" :cwd "/w/acme"}))))))

(deftest resumed-worktree-cwd-test
  ;; :session/resumed refines the cwd only for a now-removed sibling worktree
  ;; (the core handler already cds into a session's existing cwd). Lock the
  ;; pure short-circuits that must never touch git: unknown room, no session
  ;; cwd, and same-cwd resumes all stay put.
  (let [resumed (:session/resumed (:handlers (wt/create nil)))
        rid     "room-1"
        st      (-> (state/initial-state)
                    (assoc-in [:rooms rid] (state/make-room rid {:cwd "/w/acme"})))]
    (testing "no-op on an unknown room"
      (is (nil? (resumed st {:room-id "nope" :summary {:cwd "/w/acme-x"}}))))
    (testing "no-op when the session has no cwd"
      (is (nil? (resumed st {:room-id rid :summary {:cwd nil}}))))
    (testing "no-op when the session cwd already matches the room"
      (is (nil? (resumed st {:room-id rid :summary {:cwd "/w/acme"}}))))))

(deftest build-worktree-prompt-test
  (let [p (wt/build-worktree-prompt
           {:path "/w/acme-x" :branch "x" :base "master"
            :main-root "/w/acme" :guidance nil}
           "do the work")]
    (testing "carries worktree metadata and the isolated-checkout heads-up"
      (is (str/includes? p "/w/acme-x"))
      (is (str/includes? p "Branch: x (based on master)"))
      (is (str/includes? p "fresh build"))
      (is (str/includes? p "ports"))
      (is (str/ends-with? p "do the work")))
    (testing "AGENTS.md guidance is inlined when present"
      (let [p2 (wt/build-worktree-prompt
                {:path "/w/acme-x" :branch "x" :base "master"
                 :main-root "/w/acme" :guidance "## Worktrees\nuse port 7500"}
                "go")]
        (is (str/includes? p2 "use port 7500"))))))
