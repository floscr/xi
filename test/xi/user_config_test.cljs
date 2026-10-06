(ns xi.user-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.projects :as projects]
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
    (is (re-find #":agents must be a map" (err {:type :xi/config :version 1 :agents []})))
    (is (re-find #":projects must be a map" (err {:type :xi/config :version 1 :projects []})))
    (is (re-find #"unknown :projects key" (err {:type :xi/config :version 1 :projects {:dirs []}})))
    (is (re-find #":trusted-mcp-servers must be a vector" (err {:type :xi/config :version 1 :trusted-mcp-servers [:chrome]}))))
  (testing "a valid file parses; keys empty unless set"
    (is (= {:extensions #{} :agents {} :projects projects/default-spec :users {}
            :trusted-mcp-servers #{}}
           (cfg/parse-config {:type :xi/config :version 1})))
    (is (= {:extensions #{"kb.cljs"} :agents {"root" {:tools ["fetch"]}}
            :projects projects/default-spec :users {} :trusted-mcp-servers #{"chrome"}}
           (cfg/parse-config {:type :xi/config :version 1
                              :extensions ["kb.cljs"]
                              :agents {"root" {:tools ["fetch"]}}
                              :trusted-mcp-servers ["chrome"]}))))
  (testing ":projects is validated and normalized"
    (is (= {:browse [{:dir "~/Code" :depth 2 :git? true}]
            :repos ["~/.config/dotfiles"]
            :remember-limit 50
            :settings {}}
           (:projects (cfg/parse-config {:type :xi/config :version 1
                                         :projects {:browse [{:dir "~/Code" :depth 2}]
                                                    :repos ["~/.config/dotfiles"]}}))))))

(deftest users-are-declared-in-the-config-file
  (let [err   (fn [users] (:error (cfg/parse-config {:type :xi/config :version 1 :users users})))
        users (fn [users] (:users (cfg/parse-config {:type :xi/config :version 1 :users users})))]
    (testing "a declared user may have a name and read-only metadata"
      (is (= {"alice" {:name "Alice" :meta {:team "ops" :admin? true}}
              "bob"   {}
              "root"  {:name "Operator"}}
             (users {"alice" {:name "Alice" :meta {:team "ops" :admin? true}}
                     "bob"   {}
                     "root"  {:name "Operator"}}))))
    (testing "a user may declare an avatar image"
      (is (= {"alice" {:name "Alice" :avatar "https://example.com/a.png"}}
             (users {"alice" {:name "Alice" :avatar "https://example.com/a.png"}}))))
    (testing "malformed declarations reject the whole file"
      (is (re-find #":users must be a map" (err ["alice"])))
      (is (re-find #"ids must be lowercase" (err {"Alice" {}})) "ids are the normalized slug")
      (is (re-find #"ids must be lowercase" (err {"has space" {}})))
      (is (re-find #"ids must be lowercase" (err {:alice {}})))
      (is (re-find #"must be a map like" (err {"alice" "Alice"})))
      (is (re-find #"allows only :name, :avatar and :meta" (err {"alice" {:role :admin}})))
      (is (re-find #":avatar must be an http\(s\) image URL" (err {"alice" {:avatar "javascript:alert(1)"}})))
      (is (re-find #":avatar must be an http\(s\) image URL" (err {"alice" {:avatar "/local.png"}})))
      (is (re-find #":name must be a non-blank string" (err {"alice" {:name ""}})))
      (is (re-find #":name must be a non-blank string" (err {"alice" {:name :alice}})))
      (is (re-find #":meta must be a map of plain data" (err {"alice" {:meta [1 2]}})))
      (is (re-find #":meta must be a map of plain data" (err {"alice" {:meta {:f inc}}}))
          "functions are not data"))))

(deftest users-reader-returns-the-declared-users
  (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-config-users-"))
        file (node-path/join dir "config.edn")
        prev (cfg/config-file)
        with (fn [content f]
               (when content (fs/writeFileSync file content))
               (cfg/set-config-file! file)
               (try (f) (finally (cfg/set-config-file! prev))))]
    (with "{:type :xi/config :version 1 :users {\"alice\" {:name \"Alice\" :meta {:team \"ops\"}}}}"
      #(is (= {"alice" {:name "Alice" :meta {:team "ops"}}} (cfg/users))))
    (with "{:type :xi/config :version 1}"
      #(is (= {} (cfg/users)) "no key, no users"))
    (with "{:type :xi/config :version 1 :users {\"BAD\" {}}}"
      #(is (= {} (cfg/users)) "an invalid file declares nobody (fails closed)"))
    (fs/rmSync dir #js {:recursive true :force true})))

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
