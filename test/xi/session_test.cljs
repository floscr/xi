(ns xi.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session :as session]))

(deftest encode-cwd-xi-basic
  (testing "absolute path is encoded with leading dash"
    (is (= "-home-floscr-Code-Projects-xi"
           (session/encode-cwd-xi "/home/floscr/Code/Projects/xi")))))

(deftest encode-cwd-xi-root
  (testing "root path"
    (is (= "-"
           (session/encode-cwd-xi "/")))))

(deftest encode-cwd-xi-relative
  (testing "relative path gets leading dash"
    (is (= "-foo-bar"
           (session/encode-cwd-xi "foo/bar")))))
