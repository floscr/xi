(ns xi.ext.memory-intercept-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.memory-intercept :as mi]))

(deftest memory-path?-test
  (testing "auto-memory paths are detected"
    (is (mi/memory-path? "/home/floscr/.claude/projects/-home-floscr-Code-Projects-xi/memory/MEMORY.md"))
    (is (mi/memory-path? "/home/floscr/.claude/projects/-home-floscr--config-dotfiles/memory/feedback_job.md"))
    (is (mi/memory-path? ".claude/projects/foo/memory"))
    (is (mi/memory-path? "echo hi >> ~/.claude/projects/x/memory/MEMORY.md")))

  (testing "non-memory paths are not intercepted"
    (is (not (mi/memory-path? "/home/floscr/.claude/projects/x/session.jsonl")))
    (is (not (mi/memory-path? "/home/floscr/Code/Projects/xi/src/memory.cljs")))
    (is (not (mi/memory-path? "src/xi/core/log.cljs")))
    (is (not (mi/memory-path? "")))
    (is (not (mi/memory-path? nil)))))

(deftest tool-gate-test
  (let [gate (:tool-gate mi/extension)
        mem-path "/home/floscr/.claude/projects/x/memory/MEMORY.md"]
    (testing "write/edit to memory is intercepted"
      (is (:intercepted (gate {:name "write" :arguments {:path mem-path}} {})))
      (is (:intercepted (gate {:name "Edit" :arguments {:file_path mem-path}} {})))
      (is (:intercepted (gate {:name "bash" :arguments {:command (str "echo x > " mem-path)}} {}))))

    (testing "unrelated tool calls pass through unchanged"
      (let [tc {:name "write" :arguments {:path "src/foo.cljs"}}]
        (is (= tc (gate tc {}))))
      (let [tc {:name "read" :arguments {:path mem-path}}]
        (is (= tc (gate tc {})))))))
