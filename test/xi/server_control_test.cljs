(ns xi.server-control-test
  ;; Only the pure classification is tested — run-detached! would really
  ;; spawn the command (and restart the server running the tests).
  (:require [cljs.test :refer [deftest is testing]]
            [xi.server-control :as sc]))

(deftest kind-classifies-server-control-commands
  (testing "the :1414 serve tasks"
    (is (= :restart (sc/kind "bb serve:restart")))
    (is (= :stop (sc/kind "cd /x && bb serve:stop"))))
  (testing "the personal server's tasks and everything else run inline"
    (is (nil? (sc/kind "bb serve:personal:restart")))
    (is (nil? (sc/kind "bb serve")))
    (is (nil? (sc/kind "bb test")))
    (is (nil? (sc/kind nil)))))

(deftest argv-kind-needs-bb-running-the-task
  (testing "bb with serve:restart / serve:stop as its task"
    (is (= :restart (sc/argv-kind ["bb" "serve:restart"])))
    (is (= :stop (sc/argv-kind ["/nix/store/x/bin/bb" "--debug" "serve:stop"]))))
  (testing "an argument that only mentions the task runs inline"
    (is (nil? (sc/argv-kind ["kb" "add" "--body" "then run bb serve:restart"])))
    (is (nil? (sc/argv-kind ["git" "commit" "-m" "serve:restart"])))
    (is (nil? (sc/argv-kind ["bb" "test" "serve:restart"])))
    (is (nil? (sc/argv-kind ["bb" "serve:personal:restart"])))
    (is (nil? (sc/argv-kind [])))))

(deftest result-text-tells-the-agent-not-to-retry
  (is (re-find #"Do not retry" (sc/result-text :restart)))
  (is (re-find #"stay down" (sc/result-text :stop))))
