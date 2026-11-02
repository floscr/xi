(ns xi.rules.message-args-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules.message-args :as message-args :refer [MASK]]))

(deftest masks-git-messages
  (testing "-m, --message, bundled and attached forms"
    (is (= ["git" "commit" "-q" "-m" MASK "-m" MASK]
           (message-args/mask ["git" "commit" "-q" "-m" "fix: sudo" "-m" "body"])))
    (is (= ["git" "commit" "-am" MASK] (message-args/mask ["git" "commit" "-am" "sudo"])))
    (is (= ["git" "commit" (str "-m" MASK)] (message-args/mask ["git" "commit" "-msudo"])))
    (is (= ["git" "commit" "--message" MASK] (message-args/mask ["git" "commit" "--message" "sudo"])))
    (is (= ["git" "commit" (str "--message=" MASK)] (message-args/mask ["git" "commit" "--message=sudo"]))))
  (testing "past global options, by path"
    (is (= ["/usr/bin/git" "-C" "/repo" "-c" "user.name=x" "tag" "-a" "v1" "-m" MASK]
           (message-args/mask ["/usr/bin/git" "-C" "/repo" "-c" "user.name=x" "tag" "-a" "v1" "-m" "sudo"]))))
  (testing "operands after -- stay"
    (is (= ["git" "commit" "-m" MASK "--" "-m" "x"]
           (message-args/mask ["git" "commit" "-m" "a" "--" "-m" "x"])))))

(deftest leaves-everything-else
  (testing "another subcommand or program"
    (is (= ["git" "cherry-pick" "-m" "1" "abc"] (message-args/mask ["git" "cherry-pick" "-m" "1" "abc"])))
    (is (= ["sudo" "git" "commit" "-m" "x"] (message-args/mask ["sudo" "git" "commit" "-m" "x"])))
    (is (= ["env" "sudo" "whoami"] (message-args/mask ["env" "sudo" "whoami"]))))
  (testing "git -c values are config, not messages"
    (is (= ["git" "-c" "alias.x=!sudo id" "x"] (message-args/mask ["git" "-c" "alias.x=!sudo id" "x"]))))
  (testing "a value-taking flag before m is not a bundle"
    (is (= ["git" "commit" "-Cm" "HEAD"] (message-args/mask ["git" "commit" "-Cm" "HEAD"])))))

(deftest masks-gh-and-jj
  (is (= ["gh" "pr" "create" "--title" MASK "-b" MASK]
         (message-args/mask ["gh" "pr" "create" "--title" "t" "-b" "run sudo"])))
  (is (= ["jj" "describe" "-m" MASK] (message-args/mask ["jj" "describe" "-m" "sudo"]))))
