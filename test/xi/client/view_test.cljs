(ns xi.client.view-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.client.view :as view]))

;; ── format-tool-args ─────────────────────────────────────────────────────────

(deftest format-tool-args-bash
  (is (= "ls -la" (view/format-tool-args "Bash" {"command" "ls -la"})))
  (is (= "ls -la" (view/format-tool-args "bash" {"command" "ls -la"}))))

(deftest format-tool-args-read-write-edit
  (is (= "src/main.cljs" (view/format-tool-args "Read" {"file_path" "src/main.cljs"})))
  (is (= "src/main.cljs" (view/format-tool-args "Write" {"file_path" "src/main.cljs"})))
  (is (= "src/main.cljs" (view/format-tool-args "Edit" {"file_path" "src/main.cljs"})))
  (is (= "foo.txt" (view/format-tool-args "read" {"path" "foo.txt"})))
  (is (= "bar.txt" (view/format-tool-args "write" {"path" "bar.txt"})))
  (is (= "baz.txt" (view/format-tool-args "edit" {"path" "baz.txt"}))))

(deftest format-tool-args-grep
  (is (= "TODO" (view/format-tool-args "Grep" {"pattern" "TODO"})))
  (is (= "TODO --glob *.cljs"
         (view/format-tool-args "grep" {"pattern" "TODO" "glob" "*.cljs"}))))

(deftest format-tool-args-git-tools
  (is (= "--staged" (view/format-tool-args "git_overview" {"staged" true})))
  (is (nil? (view/format-tool-args "git_overview" {})))
  (is (= "a.cljs b.cljs"
         (view/format-tool-args "git_file_diff" {"files" ["a.cljs" "b.cljs"]})))
  (is (= "fix: stuff"
         (view/format-tool-args "git_commit_with_user_approval" {"message" "fix: stuff"}))))

(deftest format-tool-args-unknown-fallback
  (is (= "a=hello b=world"
         (view/format-tool-args "mystery_tool" {"a" "hello" "b" "world"})))
  (testing "nil for empty arguments"
    (is (nil? (view/format-tool-args "mystery_tool" nil)))))

;; ── entry->block ─────────────────────────────────────────────────────────────

(deftest entry->block-returns-nodes-for-all-kinds
  (doseq [kind [:user :text :thinking :tool-call :status :error :aborted :unknown]]
    (let [entry (case kind
                  :user      {:kind :user :text "hi"}
                  :text      {:kind :text :text "yo"}
                  :thinking  {:kind :thinking :text "hmm"}
                  :tool-call {:kind :tool-call :id "t1" :tool "bash"
                              :arguments {} :status :done}
                  :status    {:kind :status :text "ok"}
                  :error     {:kind :error :error {:message "oops"}}
                  :aborted   {:kind :aborted}
                  :unknown   {:kind :unknown})
          block (view/entry->block entry)]
      (is (vector? (:nodes block)) (str kind " should return nodes"))
      (is (fn? (:update! block)) (str kind " should return update!")))))

(deftest launch-header-returns-nodes
  (let [nodes (view/launch-header {:model "test-model" :cwd "/tmp"})]
    (is (vector? nodes))
    (is (pos? (count nodes)))))
