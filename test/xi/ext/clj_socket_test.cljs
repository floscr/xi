(ns xi.ext.clj-socket-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.clj-socket :as sock]))

(deftest loopback-host-test
  (testing "loopback hosts are allowed"
    (is (sock/loopback-host? "localhost"))
    (is (sock/loopback-host? "LOCALHOST"))
    (is (sock/loopback-host? "127.0.0.1"))
    (is (sock/loopback-host? "127.1.2.3"))
    (is (sock/loopback-host? "::1"))
    (is (sock/loopback-host? "0:0:0:0:0:0:0:1"))
    (is (sock/loopback-host? " localhost ")))
  (testing "everything else is refused"
    (is (not (sock/loopback-host? "example.com")))
    (is (not (sock/loopback-host? "192.168.1.10")))
    (is (not (sock/loopback-host? "10.0.0.1")))
    (is (not (sock/loopback-host? "126.0.0.1")))
    (is (not (sock/loopback-host? "1270.0.0.1")))
    (is (not (sock/loopback-host? "127.0.0")))
    (is (not (sock/loopback-host? "127.0.0.1.evil.com")))
    (is (not (sock/loopback-host? "")))
    (is (not (sock/loopback-host? nil)))))

(deftest ring-used-test
  (testing "no wrap"
    (is (= 0 (sock/ring-used 0 0 16)))
    (is (= 5 (sock/ring-used 5 0 16)))
    (is (= 3 (sock/ring-used 8 5 16))))
  (testing "wrapped head"
    (is (= 4 (sock/ring-used 2 14 16)))
    (is (= 15 (sock/ring-used 4 5 16)))))

(deftest index-of-subseq-test
  (let [buf   (fn [& xs] (to-array xs))
        delim (fn [& xs] (to-array xs))]
    (is (= 0 (sock/index-of-subseq (buf 1 2 3) (delim 1))))
    (is (= 2 (sock/index-of-subseq (buf 1 2 3 4) (delim 3 4))))
    (is (= -1 (sock/index-of-subseq (buf 1 2 3) (delim 4))))
    (is (= -1 (sock/index-of-subseq (buf 1 2) (delim 1 2 3))))
    (is (= 1 (sock/index-of-subseq (buf 9 58 9 58) (delim 58))))
    (is (= -1 (sock/index-of-subseq (buf) (delim 1))))))
