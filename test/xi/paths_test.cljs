(ns xi.paths-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.paths :as paths]))

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

;; ── env scrubbing ────────────────────────────────────────────────────────────

(deftest scrub-env-test
  (let [env (paths/scrub-env)]
    (is (some? (aget env "PATH")) "PATH survives")
    (is (nil? (aget env "ANTHROPIC_API_KEY")) "secrets scrubbed")
    (is (nil? (aget env "GITHUB_TOKEN")))))
