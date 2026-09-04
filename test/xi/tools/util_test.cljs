(ns xi.tools.util-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tools.util :as util]))

;; ── content-hash (edit freshness token) ──

(deftest content-hash-is-deterministic
  (testing "same content yields the same token (stable across processes)"
    (is (= (util/content-hash "hello world")
           (util/content-hash "hello world")))))

(deftest content-hash-detects-changes
  (testing "any change to content yields a different token"
    (is (not= (util/content-hash "line one\nline two\n")
              (util/content-hash "line one\nline TWO\n")))
    (is (not= (util/content-hash "abc")
              (util/content-hash "abc\n")))))

(deftest content-hash-is-short
  (testing "token is a short hex string"
    (let [h (util/content-hash "anything")]
      (is (= 8 (count h)))
      (is (re-matches #"[0-9a-f]{8}" h)))))
