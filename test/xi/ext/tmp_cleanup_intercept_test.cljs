(ns xi.ext.tmp-cleanup-intercept-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.tmp-cleanup-intercept :as tci]))

(deftest removes-tmp?-test
  (testing "rm variants targeting /tmp are detected"
    (is (tci/removes-tmp? "rm -rf /tmp/foo"))
    (is (tci/removes-tmp? "rm -r /tmp/some-dir"))
    (is (tci/removes-tmp? "rm /tmp/bar.txt"))
    (is (tci/removes-tmp? "rm -f /tmp/*.log"))
    (is (tci/removes-tmp? "rm -rf \"/tmp/with space\""))
    (is (tci/removes-tmp? "rm -rf /tmp"))
    (is (tci/removes-tmp? "cd /home && rm -rf /tmp/foo")))

  (testing "non-/tmp rm commands are not intercepted"
    (is (not (tci/removes-tmp? "rm -rf /home/floscr/build")))
    (is (not (tci/removes-tmp? "rm ./local.txt")))
    (is (not (tci/removes-tmp? "ls /tmp")))
    (is (not (tci/removes-tmp? "echo done > /tmp/log.txt")))
    (is (not (tci/removes-tmp? "")))
    (is (not (tci/removes-tmp? nil))))

  (testing "rm and /tmp in different command segments are not intercepted"
    (is (not (tci/removes-tmp? "rm ./out.txt && cat /tmp/log")))
    (is (not (tci/removes-tmp? "cat /tmp/log; rm ./out.txt")))))
