(ns xi.agent-profile-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.agent-profile :as profile]))

(deftest parse-tools
  (testing "a vector of names becomes the allowlist"
    (is (= #{"web_search" "fetch"}
           (:tools (profile/parse "root" {:tools ["web_search" "fetch"]})))))
  (testing ":all lifts the filter"
    (is (nil? (:tools (profile/parse "dev" {:tools :all})))))
  (testing "absent tools = none (fail closed)"
    (is (= #{} (:tools (profile/parse "root" {:system-prompt "hi"})))))
  (testing "malformed tools = none, with an error"
    (let [p (profile/parse "root" {:tools "web_search"})]
      (is (= #{} (:tools p)))
      (is (some #(str/includes? % ":tools") (:errors p))))))

(deftest parse-missing-profile
  (let [p (profile/parse "coach" nil)]
    (is (= #{} (:tools p)) "no profile → no tools")
    (is (nil? (:system-prompt p)))
    (is (some #(str/includes? % "no profile") (:errors p)))))

(deftest parse-prompt-sources
  (testing "inline prompt wins over the file"
    (let [p (profile/parse "a" {:system-prompt "  inline  " :system-prompt-file "x.md"})]
      (is (= "inline" (:system-prompt p)))
      (is (nil? (:system-prompt-file p)))))
  (testing "relative prompt files resolve against the config dir"
    (is (= (str profile/CONFIG_DIR "/agents/root.md")
           (:system-prompt-file (profile/parse "a" {:system-prompt-file "agents/root.md"})))))
  (testing "absolute and ~ paths pass through"
    (is (= "/tmp/p.md" (:system-prompt-file (profile/parse "a" {:system-prompt-file "/tmp/p.md"}))))
    (is (str/ends-with? (:system-prompt-file (profile/parse "a" {:system-prompt-file "~/p.md"}))
                        "/p.md"))))

(deftest parse-extensions
  (testing "absent = the global rules.edn list"
    (is (nil? (:extensions (profile/parse "a" {})))))
  (testing "a vector of file names becomes the set"
    (is (= #{"freesearch.cljs" "web.cljs"}
           (:extensions (profile/parse "a" {:extensions ["freesearch.cljs" "web.cljs"]})))))
  (testing "empty vector = no user extensions at all"
    (is (= #{} (:extensions (profile/parse "a" {:extensions []})))))
  (testing "malformed = global list, with an error"
    (let [p (profile/parse "a" {:extensions "web.cljs"})]
      (is (nil? (:extensions p)))
      (is (some #(str/includes? % ":extensions") (:errors p))))))

(deftest parse-config-is-typed-and-version-locked
  (let [err (fn [data] (:error (profile/parse-config data)))]
    (is (re-find #"not valid EDN" (err nil)))
    (is (re-find #"must be a map" (err [1 2])))
    (is (re-find #"missing required :version" (err {:type :xi/config :agents {}})))
    (is (re-find #"unsupported :version 2" (err {:type :xi/config :version 2})))
    (is (re-find #"missing required :type" (err {:version 1 :agents {}})))
    (is (re-find #"wrong :type :xi/rules" (err {:type :xi/rules :version 1})))
    (is (re-find #"unknown key\(s\) :agent" (err {:type :xi/config :version 1 :agent {}})))
    (is (re-find #":agents must be a map" (err {:type :xi/config :version 1 :agents []}))))
  (testing "a valid file parses; :agents {} unless set"
    (is (= {:agents {}} (profile/parse-config {:type :xi/config :version 1})))
    (is (= {:agents {"root" {:tools ["fetch"]}}}
           (profile/parse-config {:type :xi/config :version 1
                                  :agents {"root" {:tools ["fetch"]}}})))))

(deftest parse-model
  (is (= "claude-haiku-4-5-20251001"
         (:model (profile/parse "a" {:model "claude-haiku-4-5-20251001"}))))
  (is (nil? (:model (profile/parse "a" {})))))

(deftest helpers
  (is (= [{:source "agent:root" :text "p"}]
         (profile/system-parts {:id "root" :system-prompt "p"})))
  (is (= {:agent {:id "coach"}} (profile/room-ext {:id "coach"})))
  (is (str/ends-with? (profile/agent-dir nil) "/personal-agent/root"))
  (is (str/ends-with? (profile/agent-dir "coach") "/personal-agent/coach")))
