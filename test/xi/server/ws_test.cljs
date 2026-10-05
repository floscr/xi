(ns xi.server.ws-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.server.ws :as ws]))

(defn- with-xi-host
  "Run (f) with XI_HOST set to `v` (nil = unset), restoring it afterwards."
  [v f]
  (let [old (aget js/process.env "XI_HOST")]
    (if v
      (aset js/process.env "XI_HOST" v)
      (js-delete js/process.env "XI_HOST"))
    (try (f)
         (finally
           (if old
             (aset js/process.env "XI_HOST" old)
             (js-delete js/process.env "XI_HOST"))))))

(deftest resolve-hosts-test
  (testing "defaults to all interfaces (localhost, 127.0.0.1, Tailscale, LAN)"
    (is (= "0.0.0.0" ws/DEFAULT_HOST))
    (is (= ["0.0.0.0"] (with-xi-host nil #(ws/resolve-hosts nil)))))
  (testing "a specific address widens: loopback stays bound next to it"
    (is (= ["127.0.0.1" "100.64.0.2"] (with-xi-host "100.64.0.2" #(ws/resolve-hosts nil)))))
  (testing "comma-separated lists; localhost means 127.0.0.1; no duplicates"
    (is (= ["127.0.0.1" "100.64.0.2" "192.168.1.5"]
           (with-xi-host "localhost, 100.64.0.2,192.168.1.5,127.0.0.1" #(ws/resolve-hosts nil)))))
  (testing "0.0.0.0 already covers everything and stands alone"
    (is (= ["0.0.0.0"] (with-xi-host "100.64.0.2,0.0.0.0" #(ws/resolve-hosts nil)))))
  (testing "an explicit host wins over XI_HOST"
    (is (= ["127.0.0.1" "100.64.0.10"] (with-xi-host "0.0.0.0" #(ws/resolve-hosts "100.64.0.10")))))
  (testing "blank values count as unset"
    (is (= ["0.0.0.0"] (with-xi-host "  " #(ws/resolve-hosts "")))))
  (testing "values are trimmed"
    (is (= ["127.0.0.1" "100.64.0.2"] (with-xi-host nil #(ws/resolve-hosts " 100.64.0.2 "))))))
