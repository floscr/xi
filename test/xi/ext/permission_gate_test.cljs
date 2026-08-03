(ns xi.ext.permission-gate-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.permission-gate :as pg]))

(deftest server-control-kind-test
  (testing "host-server restart/stop tasks are detected"
    (is (= :restart (pg/server-control-kind "bb serve:restart")))
    (is (= :stop (pg/server-control-kind "bb serve:stop")))
    (is (= :restart (pg/server-control-kind "cd /home/floscr/Code/Projects/xi && timeout 60 bb serve:restart 2>&1 | tail -20")))
    (is (= :stop (pg/server-control-kind "bb serve:stop && echo done"))))

  (testing "personal-agent (:7475) control tasks are NOT treated as host-server control"
    (is (nil? (pg/server-control-kind "bb serve:personal:restart")))
    (is (nil? (pg/server-control-kind "bb serve:personal:stop"))))

  (testing "unrelated commands are not server-control"
    (is (nil? (pg/server-control-kind "bb check")))
    (is (nil? (pg/server-control-kind "bb serve")))
    (is (nil? (pg/server-control-kind "ls -la")))))
