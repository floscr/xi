(ns mcp.server-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hello-mcp.main :as hello]
            [mcp.server :as server]))

(def ^:private srv {:name "hello-mcp" :version "0.1.0" :tools hello/tools})

(deftest handshake-and-list
  (is (= {:protocolVersion "2024-11-05" :capabilities {:tools {}}
          :serverInfo {:name "hello-mcp" :version "0.1.0"}}
         (:result (server/handle srv {:id 1 :method "initialize" :params {:protocolVersion "2024-11-05"}}))))
  (is (nil? (server/handle srv {:method "notifications/initialized"})) "notifications get no reply")
  (is (= ["git_log" "uuid" "uuids"] (map :name (get-in (server/handle srv {:id 2 :method "tools/list"}) [:result :tools]))))
  (is (not-any? :handler (get-in (server/handle srv {:id 2 :method "tools/list"}) [:result :tools]))))

(deftest tool-failures-are-results-protocol-faults-are-errors
  (testing "a tool without its context reports isError, the call itself succeeds"
    (let [r (:result (server/handle srv {:id 3 :method "tools/call" :params {:name "git_log" :arguments {}}}))]
      (is (true? (:isError r)))
      (is (str/includes? (-> r :content first :text) "no xi/cwd"))))
  (testing "unknown tool / method"
    (is (= -32602 (get-in (server/handle srv {:id 4 :method "tools/call" :params {:name "nope"}}) [:error :code])))
    (is (= -32601 (get-in (server/handle srv {:id 5 :method "prompts/list"}) [:error :code])))))

(deftest an-mcp-apps-view
  (let [srv (assoc srv :resources hello/resources)
        tool (some #(when (= "uuids" (:name %)) %)
                   (get-in (server/handle srv {:id 1 :method "tools/list"}) [:result :tools]))]
    (is (= {:tools {} :resources {}}
           (get-in (server/handle srv {:id 1 :method "initialize" :params {}}) [:result :capabilities])))
    (is (= "ui://hello-mcp/uuids" (get-in tool [:_meta :ui :resourceUri])) "the tool names its view")
    (let [[c] (get-in (server/handle srv {:id 2 :method "resources/read" :params {:uri "ui://hello-mcp/uuids"}})
                      [:result :contents])]
      (is (= "text/html;profile=mcp-app" (:mimeType c)))
      (is (str/includes? (:text c) "ui/initialize")))
    (is (= -32602 (get-in (server/handle srv {:id 3 :method "resources/read" :params {:uri "ui://nope"}})
                          [:error :code])))
    (let [r (:result (server/handle srv {:id 4 :method "tools/call" :params {:name "uuids" :arguments {:n 2}}}))]
      (is (= 2 (count (get-in r [:structuredContent :uuids])))))))

(deftest stdio-round-trip-with-meta
  ;; the real thing: a bb child speaking newline-delimited JSON-RPC
  (let [repo  (str (fs/parent (fs/parent (fs/canonicalize "."))))   ; xi's checkout
        input (str/join "\n" (map json/generate-string
                                  [{:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
                                   {:jsonrpc "2.0" :method "notifications/initialized"}
                                   {:jsonrpc "2.0" :id 2 :method "tools/call"
                                    :params {:name "git_log" :arguments {:n 1}
                                             :_meta {"xi/cwd" repo}}}]))
        out   (:out (process/shell {:in input :out :string :dir "."}
                                   "bb" "-m" "hello-mcp.main"))
        [init call] (map #(json/parse-string % true) (str/split-lines out))]
    (is (= "hello-mcp" (get-in init [:result :serverInfo :name])))
    (is (false? (get-in call [:result :isError])))
    (is (str/starts-with? (get-in call [:result :content 0 :text]) (str "Last 1 commits in " repo)))))
