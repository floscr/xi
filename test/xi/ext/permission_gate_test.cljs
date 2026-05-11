(ns xi.ext.permission-gate-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.permission-gate :as gate]))

(def gate-fn (get-in gate/extension [:hooks :tool-call]))

;; ── Write/Edit Blocking ────────────────────────────────────────────────────

(deftest blocks-write-to-ssh
  (testing "blocks write to .ssh directory"
    (is (nil? (gate-fn {:name "write" :arguments {:path "/home/user/.ssh/id_rsa"}} {})))))

(deftest blocks-write-to-gnupg
  (testing "blocks write to .gnupg directory"
    (is (nil? (gate-fn {:name "edit" :arguments {:path "/home/user/.gnupg/pubring.kbx"}} {})))))

(deftest blocks-write-to-password-store
  (testing "blocks write to password store"
    (is (nil? (gate-fn {:name "write" :arguments {:path "/home/user/.password-store/email.gpg"}} {})))))

(deftest blocks-write-to-mail
  (testing "blocks write to Mail directory"
    (is (nil? (gate-fn {:name "write" :arguments {:path "/home/user/Mail/inbox/msg"}} {})))))

(deftest blocks-write-to-dotenv
  (testing "blocks write to .env files"
    (is (nil? (gate-fn {:name "write" :arguments {:path ".env"}} {})))))

(deftest blocks-write-to-git-dir
  (testing "blocks write to .git/ directory"
    (is (nil? (gate-fn {:name "edit" :arguments {:path ".git/config"}} {})))))

(deftest blocks-write-to-node-modules
  (testing "blocks write to node_modules/"
    (is (nil? (gate-fn {:name "write" :arguments {:path "node_modules/foo/index.js"}} {})))))

;; ── Write/Edit Allowing ───────────────────────────────────────────────────

(deftest allows-write-to-normal-file
  (testing "allows write to normal source file"
    (let [call {:name "write" :arguments {:path "src/main.cljs"}}]
      (is (= call (gate-fn call {}))))))

(deftest allows-edit-to-normal-file
  (testing "allows edit to normal file"
    (let [call {:name "edit" :arguments {:path "README.md"}}]
      (is (= call (gate-fn call {}))))))

;; ── Bash Blocking ──────────────────────────────────────────────────────

(deftest blocks-dangerous-remove
  (testing "blocks dangerous remove commands"
    (is (nil? (gate-fn {:name "bash" :arguments {:command (str "r" "m -rf /")}} {})))))

(deftest blocks-privileged-exec
  (testing "blocks privileged execution"
    (is (nil? (gate-fn {:name "bash" :arguments {:command (str "su" "do apt install foo")}} {})))))

(deftest blocks-disk-dump
  (testing "blocks disk dump commands"
    (is (nil? (gate-fn {:name "bash" :arguments {:command (str "d" "d if=/dev/zero of=/dev/sda")}} {})))))

;; ── Bash Allowing ──────────────────────────────────────────────────────

(deftest allows-safe-bash
  (testing "allows safe bash commands"
    (let [call {:name "bash" :arguments {:command "ls -la"}}]
      (is (= call (gate-fn call {}))))))

(deftest allows-git-status
  (testing "allows git status"
    (let [call {:name "bash" :arguments {:command "git status"}}]
      (is (= call (gate-fn call {}))))))

;; ── Other Tools Pass Through ──────────────────────────────────────────────

(deftest allows-read-tool
  (testing "read tool always passes through"
    (let [call {:name "read" :arguments {:path ".ssh/id_rsa"}}]
      (is (= call (gate-fn call {}))))))

(deftest allows-grep-tool
  (testing "grep tool always passes through"
    (let [call {:name "grep" :arguments {:pattern "password"}}]
      (is (= call (gate-fn call {}))))))

;; ── Case Insensitivity ———————————————————————————————————————————

(deftest handles-uppercase-tool-names
  (testing "handles PascalCase tool names from SDK"
    (is (nil? (gate-fn {:name "Write" :arguments {:path ".env"}} {})))
    (is (nil? (gate-fn {:name "BASH" :arguments {:command (str "r" "m -rf /")}} {})))))
