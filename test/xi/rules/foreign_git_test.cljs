(ns xi.rules.foreign-git-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules.foreign-git :as fg]))

(deftest hardenable
  (testing "read-only calls of the hardened subcommands"
    (doseq [argv [["git" "status" "--short"]
                  ["git" "--no-pager" "diff" "--stat" "HEAD" "--" "a.txt"]
                  ["git" "diff" "--text"]
                  ["git" "diff" "--ignore-space-change"]
                  ["git" "status" "--ignored"]
                  ["git" "status" "--" "--ignore-submodules=none"]
                  ["git" "log" "-p" "-5"]
                  ["git" "show" "HEAD"]
                  ["git" "show" "--text" "HEAD:a.txt"]
                  ["git" "ls-tree" "--name-only" "HEAD" "src/a.cljc"]
                  ["git" "ls-files" "-m"]
                  ["git" "cat-file" "-p" "HEAD:a.txt"]
                  ["git" "rev-parse" "--show-toplevel"]
                  ["git" "rev-list" "--count" "HEAD"]
                  ["git" "merge-base" "HEAD" "origin/master"]
                  ["git" "show-ref" "--heads"]
                  ["git" "for-each-ref" "--format=%(refname)"]]]
      (is (fg/hardenable? argv) (pr-str argv))))
  (testing "other subcommands, refused flags and flags that undo the hardening"
    (doseq [argv [["git" "blame" "a.txt"]
                  ["git" "grep" "x"]
                  ["git" "-c" "core.fsmonitor=x" "status"]
                  ["git" "-C" "/tmp" "status"]
                  ["git" "diff" "--ext-diff"]
                  ["git" "diff" "--textconv"]
                  ["git" "diff" "--submodule=diff"]
                  ["git" "diff" "--ignore-submodules=none"]
                  ["git" "status" "--ignore-submodules=none"]
                  ["git" "status" "--ignore-s=none"]
                  ["git" "log" "-p" "--textconv"]
                  ["git" "show" "--submodule=log"]
                  ["git" "log" "--output=x"]
                  ["git" "ls-files" "--recurse-submodules"]
                  ["git" "ls-files" "--rec"]
                  ["git" "cat-file" "--textconv" "HEAD:a.txt"]
                  ["git" "cat-file" "--te" "HEAD:a.txt"]
                  ["git" "cat-file" "--filters" "HEAD:a.txt"]
                  ["ls" "status"]]]
      (is (not (fg/hardenable? argv)) (pr-str argv)))))

(def ^:private globals
  ["git" "--no-pager" "--no-optional-locks" "--no-lazy-fetch"
   "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false"
   "-c" "gpg.program=/dev/null" "-c" "gpg.ssh.program=/dev/null"
   "-c" "gpg.x509.program=/dev/null"])

(deftest harden
  (is (= (into globals ["status" "--ignore-submodules=all" "--short"])
         (fg/harden ["git" "status" "--short"])))
  (testing "diff flags go right after the subcommand, before revisions and paths"
    (is (= (into globals ["-P" "diff" "--no-ext-diff" "--no-textconv" "--ignore-submodules=all"
                          "--stat" "HEAD" "--" "a.txt"])
           (fg/harden ["git" "-P" "diff" "--stat" "HEAD" "--" "a.txt"])))
    (is (= (into globals ["log" "--no-ext-diff" "--no-textconv" "--ignore-submodules=all" "-p"])
           (fg/harden ["git" "log" "-p"]))))
  (testing "plumbing gets the globals only"
    (is (= (into globals ["ls-tree" "--name-only" "HEAD"])
           (fg/harden ["git" "ls-tree" "--name-only" "HEAD"])))))

(deftest unsafe-keys
  (let [listing (str "global\tfilter.lfs.clean\n"
                     "global\tcore.worktree\n"
                     "local\tcore.bare\n"
                     "local\tcore.fsmonitor\n"
                     "local\tdiff.evil.textconv\n"
                     "local\tfilter.evil.clean\n"
                     "local\tmerge.evil.driver\n"
                     "local\tlog.showSignature\n"
                     "local\tgpg.program\n"
                     "worktree\tcore.worktree\n"
                     "command\tfilter.x.clean\n")]
    (is (= ["filter.evil.clean" "core.worktree"] (fg/unsafe-keys ["git" "status"] listing))
        "own scopes only; fsmonitor, diff drivers and gpg are switched off by harden")
    (testing "log and show also run merge drivers (--remerge-diff)"
      (is (= ["filter.evil.clean" "merge.evil.driver" "core.worktree"]
             (fg/unsafe-keys ["git" "--no-pager" "log" "-p"] listing)))))
  (is (= [] (fg/unsafe-keys ["git" "status"] ""))))
