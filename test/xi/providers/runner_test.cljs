(ns xi.providers.runner-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.runner :as runner]))

(deftest split-frames
  (testing "complete lines are split off, the unterminated tail is kept"
    (is (= {:lines ["{\"a\":1}" "{\"b\":2}"] :rest "{\"c\""}
           (runner/split-frames "" "{\"a\":1}\n{\"b\":2}\n{\"c\""))))
  (testing "a frame split across chunks is reassembled"
    (let [{:keys [lines rest]} (runner/split-frames "" "{\"a\":")]
      (is (= [] lines))
      (is (= {:lines ["{\"a\":1}"] :rest ""}
             (runner/split-frames rest "1}\n")))))
  (testing "blank lines are dropped"
    (is (= {:lines ["x"] :rest ""}
           (runner/split-frames "" "\n\nx\n  \n")))))

(deftest resolve-command
  (testing "bun is the Bun running Xi, so it needs nothing on PATH"
    (is (= [(.-execPath js/process) "src/main.ts"] (runner/resolve-command ["bun" "src/main.ts"]))))
  (testing "anything else runs as named"
    (is (= ["bb" "main.clj"] (runner/resolve-command ["bb" "main.clj"])))))
