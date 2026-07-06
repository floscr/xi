(ns xi.ext.worktree.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
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
