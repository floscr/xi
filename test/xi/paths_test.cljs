(ns xi.paths-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.paths :as paths]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

;; ── path-within? ─────────────────────────────────────────────────────────────

(deftest path-within-test
  (is (paths/path-within? "/a/b/c" "/a/b"))
  (is (paths/path-within? "/a/b" "/a/b"))
  (is (not (paths/path-within? "/a/bc" "/a/b")) "no prefix false-positive")
  (is (not (paths/path-within? "/a/b/../../etc" "/a/b")) "resolves traversal")
  (is (not (paths/path-within? "/etc" "/a/b"))))

(deftest expand-home-test
  (is (str/starts-with? (paths/expand-home "~/x") "/"))
  (is (not (str/includes? (paths/expand-home "~/x") "~")))
  (is (= "/abs/x" (paths/expand-home "/abs/x")))
  (testing "$VAR / ${VAR} expansion for allowlisted vars"
    (is (= (paths/expand-home "~/x") (paths/expand-home "$HOME/x")))
    (is (= (paths/expand-home "~/x") (paths/expand-home "${HOME}/x")))
    (is (str/starts-with? (paths/expand-home "$HOME") "/")))
  (testing "non-allowlisted vars are left untouched"
    (is (= "$SECRET_TOKEN/x" (paths/expand-home "$SECRET_TOKEN/x")))
    (is (= "a$HOME/x" (paths/expand-home "a$HOME/x")) "only leading $ expands")))

;; ── real-resolve / real-resolve-nofollow ───────────────────────────────────────

(deftest real-resolve-nofollow-test
  ;; A symlink at the final component: real-resolve canonicalizes through it,
  ;; real-resolve-nofollow stops at the link (parent still canonical). The
  ;; difference is what keeps (rm link) from deleting the linked tree.
  (let [dir    (fs/realpathSync (fs/mkdtempSync (node-path/join (os/tmpdir) "paths-")))
        target (node-path/join dir "target")
        link   (node-path/join dir "link")
        dirl   (node-path/join dir "dirlink")]
    (fs/mkdirSync target)
    (fs/writeFileSync (node-path/join target "f.txt") "x")
    (fs/symlinkSync target link)
    (fs/symlinkSync dir dirl)
    (testing "final-component symlink"
      (is (= target (paths/real-resolve dir "link")))
      (is (= link   (paths/real-resolve-nofollow dir "link")))
      (is (= link   (paths/real-resolve-nofollow dir "link/")) "trailing slash can't re-follow"))
    (testing "a symlink in the PARENT is still canonicalized"
      (is (= (node-path/join target "f.txt")
             (paths/real-resolve-nofollow dir "link/f.txt")))
      (is (= link (paths/real-resolve-nofollow dir "dirlink/link"))))
    (testing "plain paths agree"
      (is (= (paths/real-resolve dir "target") (paths/real-resolve-nofollow dir "target")))
      (is (= (paths/real-resolve dir "missing/x") (paths/real-resolve-nofollow dir "missing/x"))))
    (fs/rmSync dir #js {:recursive true :force true})))

;; ── env scrubbing ────────────────────────────────────────────────────────────

(deftest scrub-env-test
  (let [env (paths/scrub-env)]
    (is (some? (aget env "PATH")) "PATH survives")
    (is (nil? (aget env "ANTHROPIC_API_KEY")) "secrets scrubbed")
    (is (nil? (aget env "GITHUB_TOKEN")))))
