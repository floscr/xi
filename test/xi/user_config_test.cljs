(ns xi.user-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.user-config :as cfg]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(deftest parse-config-is-typed-and-version-locked
  (let [err (fn [data] (:error (cfg/parse-config data)))]
    (is (re-find #"not valid EDN" (err nil)))
    (is (re-find #"must be a map" (err [1 2])))
    (is (re-find #"missing required :version" (err {:type :xi/config :agents {}})))
    (is (re-find #"unsupported :version 2" (err {:type :xi/config :version 2})))
    (is (re-find #"missing required :type" (err {:version 1 :agents {}})))
    (is (re-find #"wrong :type :xi/rules" (err {:type :xi/rules :version 1})))
    (is (re-find #"unknown key\(s\) :agent" (err {:type :xi/config :version 1 :agent {}})))
    (is (re-find #":extensions must be a vector" (err {:type :xi/config :version 1 :extensions [:kb]})))
    (is (re-find #":extensions must be a vector" (err {:type :xi/config :version 1 :extensions "kb.cljs"})))
    (is (re-find #":agents must be a map" (err {:type :xi/config :version 1 :agents []}))))
  (testing "a valid file parses; keys empty unless set"
    (is (= {:extensions #{} :agents {}} (cfg/parse-config {:type :xi/config :version 1})))
    (is (= {:extensions #{"kb.cljs"} :agents {"root" {:tools ["fetch"]}}}
           (cfg/parse-config {:type :xi/config :version 1
                              :extensions ["kb.cljs"]
                              :agents {"root" {:tools ["fetch"]}}})))))

(deftest extensions-are-enabled-by-the-config-file-only
  (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-config-ext-"))
        file (node-path/join dir "config.edn")
        with (fn [content f]
               (when content (fs/writeFileSync file content))
               (cfg/set-config-file! file)
               (try (f) (finally (cfg/set-config-file! nil))))]
    (testing "no file, no key, or an invalid file → nothing is enabled"
      (with nil #(is (= #{} (cfg/enabled-extensions))))
      (with "{:type :xi/config :version 1}" #(is (= #{} (cfg/enabled-extensions))))
      (with "{:type :xi/config :version 1 :extensions [:kb]}" #(is (= #{} (cfg/enabled-extensions))))
      (with "{:version 1 :extensions [\"kb.cljs\"]}"
        #(is (= #{} (cfg/enabled-extensions)) "missing :type fails closed")))
    (with "{:type :xi/config :version 1 :extensions [\"kb.cljs\" \"web.cljs\"]}"
      #(is (= #{"kb.cljs" "web.cljs"} (cfg/enabled-extensions))))
    (fs/rmSync dir #js {:recursive true :force true})))
