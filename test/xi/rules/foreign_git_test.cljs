(ns xi.rules.foreign-git-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules.foreign-git :as fg]))

(deftest hardenable
  (testing "read-only status and diff"
    (doseq [argv [["git" "status" "--short"]
                  ["git" "--no-pager" "diff" "--stat" "HEAD" "--" "a.txt"]
                  ["git" "diff" "--text"]
                  ["git" "diff" "--ignore-space-change"]
                  ["git" "status" "--ignored"]
                  ["git" "status" "--" "--ignore-submodules=none"]]]
      (is (fg/hardenable? argv) (pr-str argv))))
  (testing "other subcommands, refused flags and flags that undo the hardening"
    (doseq [argv [["git" "log" "-p"]
                  ["git" "show" "HEAD"]
                  ["git" "-c" "core.fsmonitor=x" "status"]
                  ["git" "-C" "/tmp" "status"]
                  ["git" "diff" "--ext-diff"]
                  ["git" "diff" "--textconv"]
                  ["git" "diff" "--submodule=diff"]
                  ["git" "diff" "--ignore-submodules=none"]
                  ["git" "status" "--ignore-submodules=none"]
                  ["git" "status" "--ignore-s=none"]
                  ["ls" "status"]]]
      (is (not (fg/hardenable? argv)) (pr-str argv)))))

(deftest harden
  (is (= ["git" "--no-pager" "--no-optional-locks" "--no-lazy-fetch"
          "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false"
          "status" "--ignore-submodules=all" "--short"]
         (fg/harden ["git" "status" "--short"])))
  (testing "diff flags go right after the subcommand, before revisions and paths"
    (is (= ["git" "--no-pager" "--no-optional-locks" "--no-lazy-fetch"
            "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false" "-P"
            "diff" "--no-ext-diff" "--no-textconv" "--ignore-submodules=all"
            "--stat" "HEAD" "--" "a.txt"]
           (fg/harden ["git" "-P" "diff" "--stat" "HEAD" "--" "a.txt"])))))

(deftest unsafe-keys
  (let [listing (str "global\tfilter.lfs.clean\n"
                     "global\tcore.worktree\n"
                     "local\tcore.bare\n"
                     "local\tcore.fsmonitor\n"
                     "local\tdiff.evil.textconv\n"
                     "local\tfilter.evil.clean\n"
                     "worktree\tcore.worktree\n"
                     "command\tfilter.x.clean\n")]
    (is (= ["filter.evil.clean" "core.worktree"] (fg/unsafe-keys listing))
        "own scopes only; fsmonitor and diff drivers are switched off by harden"))
  (is (= [] (fg/unsafe-keys ""))))
