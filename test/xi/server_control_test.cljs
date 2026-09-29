(ns xi.server-control-test
  ;; Only the pure classification is tested — run-detached! would really
  ;; spawn the command (and restart the server running the tests).
  (:require [cljs.test :refer [deftest is testing]]
            [xi.server-control :as sc]))

(deftest kind-classifies-server-control-commands
  (testing "the :7474 serve tasks"
    (is (= :restart (sc/kind "bb serve:restart")))
    (is (= :stop (sc/kind "cd /x && bb serve:stop"))))
  (testing "the personal server's tasks and everything else run inline"
    (is (nil? (sc/kind "bb serve:personal:restart")))
    (is (nil? (sc/kind "bb serve")))
    (is (nil? (sc/kind "bb test")))
    (is (nil? (sc/kind nil)))))

(deftest result-text-tells-the-agent-not-to-retry
  (is (re-find #"Do not retry" (sc/result-text :restart)))
  (is (re-find #"stay down" (sc/result-text :stop))))
