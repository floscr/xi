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

(deftest resolve-host-test
  (testing "defaults to all interfaces (localhost + Tailscale + LAN)"
    (is (= "0.0.0.0" ws/DEFAULT_HOST))
    (is (= "0.0.0.0" (with-xi-host nil #(ws/resolve-host nil)))))
  (testing "XI_HOST overrides the default"
    (is (= "127.0.0.1" (with-xi-host "127.0.0.1" #(ws/resolve-host nil)))))
  (testing "an explicit host wins over XI_HOST"
    (is (= "100.64.0.10" (with-xi-host "0.0.0.0" #(ws/resolve-host "100.64.0.10")))))
  (testing "blank values count as unset"
    (is (= "0.0.0.0" (with-xi-host "  " #(ws/resolve-host "")))))
  (testing "values are trimmed"
    (is (= "127.0.0.1" (with-xi-host nil #(ws/resolve-host " 127.0.0.1 "))))))
