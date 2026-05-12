(ns xi.ext.permission-gate-test
  (:require [cljs.test :refer [deftest is testing async]]
            [xi.ext.permission-gate :as gate]))

(def gate-fn (get-in gate/extension [:hooks :tool-call]))
(def state {:cwd "/home/user/project" :model "test"})

;; Helper: blocked calls now return a Promise (resolving to nil when no
;; confirm handler is set).  Use `async` + `.then` to assert.

(defn- assert-blocked [call done]
  (let [result (gate-fn call state)]
    (if (instance? js/Promise result)
      (.then result (fn [v] (is (nil? v)) (done)))
      (do (is (nil? result)) (done)))))

;; ── Write/Edit Blocking ────────────────────────────────────────────────────

(deftest blocks-write-to-ssh
  (testing "blocks write to .ssh directory"
    (async done
      (assert-blocked {:name "write" :arguments {:path "/home/user/.ssh/id_rsa"}} done))))

(deftest blocks-write-to-gnupg
  (testing "blocks write to .gnupg directory"
    (async done
      (assert-blocked {:name "edit" :arguments {:path "/home/user/.gnupg/pubring.kbx"}} done))))

(deftest blocks-write-to-password-store
  (testing "blocks write to password store"
    (async done
      (assert-blocked {:name "write" :arguments {:path "/home/user/.password-store/email.gpg"}} done))))

(deftest blocks-write-to-mail
  (testing "blocks write to Mail directory"
    (async done
      (assert-blocked {:name "write" :arguments {:path "/home/user/Mail/inbox/msg"}} done))))

(deftest blocks-write-to-dotenv
  (testing "blocks write to .env files"
    (async done
      (assert-blocked {:name "write" :arguments {:path ".env"}} done))))

(deftest blocks-write-to-git-dir
  (testing "blocks write to .git/ directory"
    (async done
      (assert-blocked {:name "edit" :arguments {:path ".git/config"}} done))))

(deftest blocks-write-to-node-modules
  (testing "blocks write to node_modules/"
    (async done
      (assert-blocked {:name "write" :arguments {:path "node_modules/foo/index.js"}} done))))

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
    (async done
      (assert-blocked {:name "bash" :arguments {:command (str "r" "m -rf /")}} done))))

(deftest blocks-privileged-exec
  (testing "blocks privileged execution"
    (async done
      (assert-blocked {:name "bash" :arguments {:command (str "su" "do apt install foo")}} done))))

(deftest blocks-disk-dump
  (testing "blocks disk dump commands"
    (async done
      (assert-blocked {:name "bash" :arguments {:command (str "d" "d if=/dev/zero of=/dev/sda")}} done))))

;; ── Bash Allowing ──────────────────────────────────────────────────────

(deftest allows-safe-bash
  (testing "allows safe bash commands"
    (let [call {:name "bash" :arguments {:command "ls -la"}}]
      (is (= call (gate-fn call state))))))

(deftest allows-git-status
  (testing "allows git status"
    (let [call {:name "bash" :arguments {:command "git status"}}]
      (is (= call (gate-fn call state))))))

;; ── Git Push Blocking ─────────────────────────────────────────────────────

(deftest blocks-git-push
  (testing "blocks git push"
    (async done
      (assert-blocked {:name "bash" :arguments {:command "git push origin main"}} done))))

(deftest blocks-git-push-force
  (testing "blocks git push --force"
    (async done
      (assert-blocked {:name "bash" :arguments {:command "git push --force"}} done))))

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

(deftest handles-uppercase-write
  (testing "handles PascalCase Write tool name"
    (async done
      (assert-blocked {:name "Write" :arguments {:path ".env"}} done))))

(deftest handles-uppercase-bash
  (testing "handles uppercase BASH tool name"
    (async done
      (assert-blocked {:name "BASH" :arguments {:command (str "r" "m -rf /")}} done))))
