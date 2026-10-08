(ns xi.rules.readonly-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.rules.readonly :as ro]))

(defn- ok? [& argv] (nil? (ro/violation (vec argv))))
(defn- why [& argv] (ro/violation (vec argv)))

(deftest find-parser
  (testing "read-only expressions parse: tests, operators, value-taking primaries"
    (is (ok? "find" "." "-name" "*.clj" "-type" "f"))
    (is (ok? "find" "src" "-maxdepth" "2" "-mtime" "-1" "-size" "+1k"))
    (is (ok? "find" "-L" "." "!" "-path" "*/node_modules/*" "(" "-name" "a" "-o" "-name" "b" ")"))
    (is (ok? "find" "." "-newermt" "2024-01-01" "-print0"))
    (is (ok? "find" "." "-printf" "%p\\n" "-prune"))
    (is (ok? "find" ".")))
  (testing "running a program, deleting or writing a file is refused, with the reason"
    (is (str/includes? (why "find" "." "-exec" "rm" "{}" ";") "runs a program"))
    (is (str/includes? (why "find" "." "-name" "x" "-execdir" "sh" "-c" "id" ";") "runs a program"))
    (is (str/includes? (why "find" "." "-ok" "rm" "{}" ";") "runs a program"))
    (is (str/includes? (why "find" "." "-delete") "deletes"))
    (is (str/includes? (why "find" "." "-fprint" "/tmp/out") "writes a file"))
    (is (str/includes? (why "find" "." "-fprintf" "/tmp/out" "%p") "writes a file")))
  (testing "unknown options and a missing argument are refused (conservative)"
    (is (why "find" "." "-D" "exec"))
    (is (why "find" "." "-O3"))
    (is (why "find" "." "-name"))))

(deftest getopt-parsers
  (testing "fd"
    (is (ok? "fd" "--" "pattern" "src"))
    (is (ok? "fd" "-HI" "-e" "clj" "-t" "f" "--max-depth=3" "pat"))
    (is (ok? "fd" "-d3" "pat"))
    (is (str/includes? (why "fd" "-x" "rm" "pat") "runs a program"))
    (is (str/includes? (why "fd" "--exec" "rm" "pat") "runs a program"))
    (is (str/includes? (why "fd" "-Hx" "rm" "pat") "runs a program"))
    (is (str/includes? (why "fd" "--exec-batch=rm" "pat") "runs a program"))
    (is (why "fd" "-l" "pat") "fd -l runs ls")
    (is (ok? "fd" "--" "-x") "after -- everything is an operand"))
  (testing "rg"
    (is (ok? "rg" "-n" "--no-heading" "--max-count" "500" "-e" "foo" "src"))
    (is (ok? "rg" "-C3" "-tclj" "--type=cljs" "-g" "!*.min.js" "foo"))
    (is (ok? "rg" "-e" "-foo" "x") "-e consumes a value that looks like a flag")
    (is (str/includes? (why "rg" "--pre" "cat" "foo") "preprocessor"))
    (is (str/includes? (why "rg" "--pre=cat" "foo") "preprocessor"))
    (is (why "rg" "--hostname-bin" "x" "foo"))
    (is (why "rg" "-z" "foo"))
    (is (why "rg" "--unknown-flag" "foo")))
  (testing "sort"
    (is (ok? "sort" "-rn" "-k2,2" "-t," "f"))
    (is (ok? "sort" "-t" "," "-k" "2" "--check=quiet" "f"))
    (is (ok? "sort" "-u" "-"))
    (is (str/includes? (why "sort" "--compress-program" "gzip" "f") "runs a program"))
    (is (str/includes? (why "sort" "--compress-program=gzip" "f") "runs a program"))
    (is (str/includes? (why "sort" "-o" "/tmp/out" "f") "writes a file"))
    (is (str/includes? (why "sort" "-no" "/tmp/out" "f") "writes a file"))
    (is (why "sort" "--output=/tmp/out" "f"))
    (is (why "sort" "--comp" "gzip" "f") "an abbreviation of a refused option is not allowlisted"))
  (testing "ss"
    (is (ok? "ss" "-lntupH"))
    (is (ok? "ss" "-ltnp" "state" "established"))
    (is (ok? "ss" "--tcp" "-A" "tcp"))
    (is (str/includes? (why "ss" "-K" "dport" "=" ":443") "destroys sockets"))
    (is (str/includes? (why "ss" "--kill" "state" "established") "destroys sockets"))
    (is (str/includes? (why "ss" "-tK") "destroys sockets"))
    (is (str/includes? (why "ss" "-D" "/tmp/dump") "dump"))
    (is (str/includes? (why "ss" "--diag=/tmp/dump") "dump"))))

(deftest git-parser
  (testing "ordinary repository subcommands, with value-less globals"
    (doseq [argv [["git" "status" "--short"]
                  ["git" "--no-pager" "log" "--oneline" "-15"]
                  ["git" "-P" "diff" "--stat" "HEAD~1"]
                  ["git" "show" "-s" "--format=%H"]
                  ["git" "log" "-S" "foo" "--no-notes"]
                  ["git" "add" "-A"]
                  ["git" "commit" "-sm" "msg" "-c" "HEAD"]
                  ["git" "commit" "-m" "use -x"]
                  ["git" "rebase" "-i" "--autosquash" "main"]
                  ["git" "merge" "--no-ff" "-X" "theirs" "-m" "m" "topic"]
                  ["git" "cherry-pick" "-s" "abc"]
                  ["git" "fetch" "--prune" "origin"]
                  ["git" "pull" "--rebase"]
                  ["git" "clone" "--depth" "1" "https://x/y.git" "/tmp/y"]
                  ["git" "stash" "push" "-m" "wip"]
                  ["git" "submodule" "update" "--init" "--recursive"]
                  ["git" "submodule"]
                  ["git" "bisect" "start"]
                  ["git" "config" "--get" "user.name"]
                  ["git" "config" "--global" "user.name"]
                  ["git" "config" "-l" "--show-origin"]
                  ["git" "config" "--get-regexp" "^alias"]
                  ["git" "config" "--file" "x" "--list"]
                  ["git" "config" "get" "user.name"]
                  ["git" "config" "list"]
                  ["git" "grep" "-n" "foo" "--" "src"]
                  ["git" "log" "--" "-x"]
                  ["git" "version"]
                  ["git" "--version"]
                  ["git" "worktree" "list"]
                  ["git" "branch" "-D" "topic"]
                  ["git" "reset" "--hard" "HEAD~1"]]]
      (is (nil? (ro/violation argv)) (pr-str argv))))
  (testing "refused: globals that set config, relocate git or run the pager"
    (is (str/includes? (why "git" "-c" "core.hooksPath=/tmp/h" "commit" "-m" "x") "sets configuration"))
    (is (str/includes? (why "git" "--config-env=core.pager=X" "log") "sets configuration"))
    (is (str/includes? (why "git" "-C" "/tmp/other" "status") "another directory"))
    (is (str/includes? (why "git" "--git-dir=/tmp/x/.git" "log") "relocates"))
    (is (str/includes? (why "git" "--exec-path=/tmp/bin" "status") "finds its commands"))
    (is (str/includes? (why "git" "-p" "log") "pager"))
    (is (why "git") "no subcommand"))
  (testing "refused: subcommands outside the allowlist, with the reason when known"
    (is (str/includes? (why "git" "push" "origin") "remote"))
    (is (str/includes? (why "git" "clean" "-fd") "untracked"))
    (doseq [sub ["difftool" "mergetool" "filter-branch" "hook" "maintenance" "credential"
                 "daemon" "archive" "help" "for-each-repo" "my-alias"]]
      (is (str/includes? (ro/violation ["git" sub]) "not an allowlisted subcommand") sub)))
  (testing "refused: per-subcommand flags that run a program, write elsewhere or escape"
    (is (str/includes? (why "git" "rebase" "-x" "make" "main") "runs a command"))
    (is (str/includes? (why "git" "rebase" "--exec" "make" "main") "runs a command"))
    (is (str/includes? (why "git" "rebase" "--exe" "make" "main") "runs a command")
        "git accepts unambiguous abbreviations, so a prefix of a refused flag is refused")
    (is (str/includes? (why "git" "rebase" "-ix" "make" "main") "runs a command"))
    (is (str/includes? (why "git" "merge" "-s" "evil" "topic") "strategy"))
    (is (str/includes? (why "git" "pull" "--strategy=evil") "strategy"))
    (is (str/includes? (why "git" "log" "--ext-diff") "external diff"))
    (is (str/includes? (why "git" "diff" "--output=/tmp/x") "writes a file"))
    (is (str/includes? (why "git" "diff" "--no-index" "/etc/a" "/etc/b") "outside"))
    (is (str/includes? (why "git" "format-patch" "-o" "/tmp/p" "HEAD~1") "writes files"))
    (is (str/includes? (why "git" "grep" "-O" "foo") "pager"))
    (is (str/includes? (why "git" "fetch" "--upload-pack=/tmp/evil" "origin") "remote side"))
    (is (str/includes? (why "git" "clone" "-u" "/tmp/evil" "x") "remote side"))
    (is (str/includes? (why "git" "clone" "--template=/tmp/t" "x") "template"))
    (is (str/includes? (why "git" "clone" "-c" "core.hooksPath=/tmp/h" "x") "sets configuration"))
    (is (str/includes? (why "git" "init" "--template" "/tmp/t") "template"))
    (is (str/includes? (why "git" "apply" "--unsafe-paths" "p.diff") "outside"))
    (is (str/includes? (why "git" "bisect" "run" "make") "runs a command"))
    (is (str/includes? (why "git" "submodule" "foreach" "ls") "per submodule"))
    (is (why "git" "submodule" "set-upstream" "x")))
  (testing "git config may only read"
    (is (str/includes? (why "git" "config" "user.name" "Mallory") "sets a value"))
    (is (str/includes? (why "git" "config" "--global" "core.hooksPath" "/tmp/h") "sets a value"))
    (is (str/includes? (why "git" "config" "--add" "alias.x" "!id") "writes"))
    (is (str/includes? (why "git" "config" "--unset" "user.name") "writes"))
    (is (str/includes? (why "git" "config" "-e") "editor"))
    (is (str/includes? (why "git" "config" "--edit") "writes"))
    (is (str/includes? (why "git" "config" "set" "user.name" "x") "writes"))
    (is (str/includes? (why "git" "config" "--remove-section" "alias") "writes"))))

(deftest clis-and-read-only?
  (testing "the programs the :read-only key vouches for"
    (is (every? ro/clis ["find" "fd" "rg" "sort" "ss" "git" "cat" "ls" "wc"]))
    (is (not (contains? ro/clis "rm")) "rm is a write, allowed CLI-wide by its own rule")
    (is (not (contains? ro/clis "npm"))))
  (testing "violation judges only parsed programs; read-only? also requires the set"
    (is (nil? (ro/violation ["cat" "/etc/passwd"])))
    (is (nil? (ro/violation ["npm" "publish"])))
    (is (ro/read-only? ["cat" "x"]))
    (is (ro/read-only? ["find" "." "-name" "x"]))
    (is (not (ro/read-only? ["find" "." "-exec" "id" ";"])))
    (is (not (ro/read-only? ["npm" "--version"])))))

(deftest request-read-only?
  (testing "a literal :argv"
    (is (true? (ro/request-read-only? {:tool :sh :cli "git" :argv ["git" "status"]})))
    (is (false? (ro/request-read-only? {:tool :sh :cli "git" :argv ["git" "push"]})))
    (is (true? (ro/request-read-only? {:tool :sh :cli "cat" :argv ["cat" "x"]}))))
  (testing "a background command, split only without shell syntax"
    (is (true? (ro/request-read-only? {:tool :sh :cli "tail" :command "tail -f /tmp/x.log"})))
    (is (false? (ro/request-read-only? {:tool :sh :cli "git" :command "git -c a=b commit"})))
    (is (nil? (ro/request-read-only? {:tool :sh :cli "find" :command "find . -exec rm {} \\;"}))
        "shell syntax: nothing to judge")
    (is (nil? (ro/request-read-only? {:tool :sh :cli "find" :command "find . -name ?exec"}))
        "a glob could expand into a flag"))
  (testing "nil: unknown program, other tools, no argv"
    (is (nil? (ro/request-read-only? {:tool :sh :cli "npm" :argv ["npm" "test"]})))
    (is (nil? (ro/request-read-only? {:tool :bash :command "git status"})))
    (is (nil? (ro/request-read-only? {:tool :sh :cli "git"})))))
