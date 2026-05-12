(ns xi.ext.permission-gate-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.permission-gate :as gate]))

(def gate-fn (get-in gate/extension [:hooks :tool-call]))
(def state {:cwd "/home/user/project" :model "test"})

;; ── Write/Edit Blocking ────────────────────────────────────────────────────

(deftest blocks-write-to-ssh
  (testing "blocks write to .ssh directory"
    (is (nil? (gate-fn {:name "write" :arguments {:path "/home/user/.ssh/id_rsa"}} state)))))

(deftest blocks-write-to-gnupg
  (testing "blocks write to .gnupg directory"
    (is (nil? (gate-fn {:name "edit" :arguments {:path "/home/user/.gnupg/pubring.kbx"}} state)))))

(deftest blocks-write-to-password-store
  (testing "blocks write to password store"
    (is (nil? (gate-fn {:name "write" :arguments {:path "/home/user/.password-store/email.gpg"}} state)))))

(deftest blocks-write-to-mail
  (testing "blocks write to Mail directory"
    (is (nil? (gate-fn {:name "write" :arguments {:path "/home/user/Mail/inbox/msg"}} state)))))

(deftest blocks-write-to-dotenv
  (testing "blocks write to .env files"
    (is (nil? (gate-fn {:name "write" :arguments {:path ".env"}} state)))))

(deftest blocks-write-to-git-dir
  (testing "blocks write to .git/ directory"
    (is (nil? (gate-fn {:name "edit" :arguments {:path ".git/config"}} state)))))

(deftest blocks-write-to-node-modules
  (testing "blocks write to node_modules/"
    (is (nil? (gate-fn {:name "write" :arguments {:path "node_modules/foo/index.js"}} state)))))

;; ── Write/Edit Allowing ───────────────────────────────────────────────────

(deftest allows-write-to-normal-file
  (testing "allows write to normal source file"
    (let [call {:name "write" :arguments {:path "src/main.cljs"}}]
      (is (= call (gate-fn call state))))))

(deftest allows-edit-to-normal-file
  (testing "allows edit to normal file"
    (let [call {:name "edit" :arguments {:path "README.md"}}]
      (is (= call (gate-fn call state))))))

;; ── Bash Blocking ──────────────────────────────────────────────────────

(deftest blocks-dangerous-remove
  (testing "blocks dangerous remove commands"
    (is (nil? (gate-fn {:name "bash" :arguments {:command (str "r" "m -rf /")}} state)))))

(deftest blocks-privileged-exec
  (testing "blocks privileged execution"
    (is (nil? (gate-fn {:name "bash" :arguments {:command (str "su" "do apt install foo")}} state)))))

(deftest blocks-disk-dump
  (testing "blocks disk dump commands"
    (is (nil? (gate-fn {:name "bash" :arguments {:command (str "d" "d if=/dev/zero of=/dev/sda")}} state)))))

;; ── Bash Allowing ──────────────────────────────────────────────────────

(deftest allows-safe-bash
  (testing "allows safe bash commands"
    (let [call {:name "bash" :arguments {:command "ls -la"}}]
      (is (= call (gate-fn call state))))))

(deftest allows-git-status
  (testing "allows git status"
    (let [call {:name "bash" :arguments {:command "git status"}}]
      (is (= call (gate-fn call state))))))

;; ── Other Tools Pass Through ──────────────────────────────────────────────

(deftest allows-read-tool
  (testing "read tool always passes through"
    (let [call {:name "read" :arguments {:path ".ssh/id_rsa"}}]
      (is (= call (gate-fn call state))))))

(deftest allows-grep-tool
  (testing "grep tool always passes through"
    (let [call {:name "grep" :arguments {:pattern "password"}}]
      (is (= call (gate-fn call state))))))

;; ── Case Insensitivity ———————————————————————————————————————————

(deftest handles-uppercase-tool-names
  (testing "handles PascalCase tool names from SDK"
    (is (nil? (gate-fn {:name "Write" :arguments {:path ".env"}} state)))
    (is (nil? (gate-fn {:name "BASH" :arguments {:command (str "r" "m -rf /")}} state)))))
