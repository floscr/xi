(ns xi.sandbox.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.sandbox.bwrap :as bwrap]
            [xi.sandbox.firejail :as firejail]
            [xi.sandbox.core :as sandbox]))

(def ^:private policy
  {:home         "/home/u"
   :writable     ["/home/u/proj"]
   :hidden-dirs  ["/home/u/.ssh" "/home/u/.gnupg"]
   :hidden-files ["/home/u/.netrc"]
   :network      :none})

(def ^:private argv ["setsid" "bash" "-c" "echo hi"])

;; ── path-within? ─────────────────────────────────────────────────────────────

(deftest path-within-test
  (is (sandbox/path-within? "/a/b/c" "/a/b"))
  (is (sandbox/path-within? "/a/b" "/a/b"))
  (is (not (sandbox/path-within? "/a/bc" "/a/b")) "no prefix false-positive")
  (is (not (sandbox/path-within? "/a/b/../../etc" "/a/b")) "resolves traversal")
  (is (not (sandbox/path-within? "/etc" "/a/b"))))

(deftest expand-home-test
  (is (str/starts-with? (sandbox/expand-home "~/x") "/"))
  (is (not (str/includes? (sandbox/expand-home "~/x") "~")))
  (is (= "/abs/x" (sandbox/expand-home "/abs/x"))))

;; ── bwrap backend ────────────────────────────────────────────────────────────

(deftest bwrap-wrap-argv-test
  (let [wrapped (bwrap/wrap-argv argv policy)]
    (testing "starts with bwrap, ends with the original argv"
      (is (= "bwrap" (first wrapped)))
      (is (= argv (take-last (count argv) wrapped))))
    (testing "read-only root + writable bind"
      (is (some #{"--ro-bind"} wrapped))
      (let [i (.indexOf wrapped "--bind")]
        (is (= ["/home/u/proj" "/home/u/proj"] (subvec wrapped (inc i) (+ i 3))))))
    (testing "hidden dirs masked with tmpfs, files with /dev/null binds"
      (is (>= (count (filter #{"--tmpfs"} wrapped)) 3)) ; /tmp + 2 hidden dirs
      (is (some #{"/home/u/.netrc"} wrapped)))
    (testing "network unshared by default"
      (is (some #{"--unshare-all"} wrapped))
      (is (not-any? #{"--share-net"} wrapped)))
    (testing "dies with parent"
      (is (some #{"--die-with-parent"} wrapped)))))

(deftest bwrap-network-all-test
  (let [wrapped (bwrap/wrap-argv argv (assoc policy :network :all))]
    (is (some #{"--unshare-all"} wrapped))
    (is (some #{"--share-net"} wrapped))))

;; ── firejail backend ─────────────────────────────────────────────────────────

(deftest firejail-wrap-argv-test
  (let [wrapped (firejail/wrap-argv argv policy)]
    (testing "starts with firejail, ends with the original argv"
      (is (= "firejail" (first wrapped)))
      (is (= argv (take-last (count argv) wrapped))))
    (testing "home read-only, cwd read-write, secrets blacklisted"
      (is (some #{"--read-only=/home/u"} wrapped))
      (is (some #{"--read-write=/home/u/proj"} wrapped))
      (is (some #{"--blacklist=/home/u/.ssh"} wrapped))
      (is (some #{"--blacklist=/home/u/.netrc"} wrapped)))
    (testing "network dropped by default"
      (is (some #{"--net=none"} wrapped)))))

(deftest firejail-network-all-test
  (let [wrapped (firejail/wrap-argv argv (assoc policy :network :all))]
    (is (not-any? #{"--net=none"} wrapped))))

;; ── env scrubbing ────────────────────────────────────────────────────────────

(deftest scrub-env-test
  (let [env (sandbox/scrub-env)]
    (is (some? (aget env "PATH")) "PATH survives")
    (is (nil? (aget env "ANTHROPIC_API_KEY")) "secrets scrubbed")
    (is (nil? (aget env "GITHUB_TOKEN")))))
