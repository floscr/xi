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

;; ── retint-difft ─────────────────────────────────────────────────────────────

(def ^:private difft-del-fg "\033[38;2;191;97;106m")
(def ^:private difft-add-fg "\033[38;2;163;190;140m")
(def ^:private difft-hdr-fg "\033[38;2;216;222;233m")
(def ^:private difft-dim-fg "\033[38;2;106;115;141m")

(deftest retint-difft-remaps-palette-to-theme
  (testing "bright red (removed) becomes theme danger red"
    (is (= (str difft-del-fg "gone" "\033[0m")
           (view/retint-difft "\033[91mgone\033[0m"))))
  (testing "bright green (added) becomes theme string green"
    (is (= (str difft-add-fg "new" "\033[0m")
           (view/retint-difft "\033[92mnew\033[0m"))))
  (testing "bright yellow (header) becomes bright fg"
    (is (= (str difft-hdr-fg "file.clj" "\033[0m")
           (view/retint-difft "\033[93mfile.clj\033[0m"))))
  (testing "dim becomes muted gray"
    (is (= (str difft-dim-fg "..." "\033[0m")
           (view/retint-difft "\033[2m...\033[0m"))))
  (testing "bold is preserved alongside a color"
    (is (= (str difft-add-fg "\033[1m" "12" "\033[0m")
           (view/retint-difft "\033[92;1m12\033[0m"))))
  (testing "unstyled text passes through untouched"
    (is (= "  plain  " (view/retint-difft "  plain  "))))
  (testing "palette codes with no text between them are dropped"
    (is (= (str difft-del-fg "x" "\033[0m")
           (view/retint-difft "\033[91;1m\033[2m\033[0m\033[91mx\033[0m")))))
