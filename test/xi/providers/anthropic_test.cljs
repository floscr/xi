(ns xi.providers.anthropic-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.anthropic :as anthropic]))

(def ^:private base-query-opts #'anthropic/base-query-opts)

(deftest setting-sources
  (testing "main turns let the CLI load only user-level instructions — project
            AGENTS.md/CLAUDE.md is already in the appended system prompt"
    (is (= ["user"] (:settingSources (base-query-opts {:cwd "/tmp"} "sys")))))
  (testing "text-only side turns load no instruction files at all"
    (is (= [] (:settingSources (base-query-opts {:cwd "/tmp" :no-tools? true} nil))))))

(deftest side-turns-carry-no-tools-or-preset
  (let [opts (base-query-opts {:cwd "/tmp" :no-tools? true} nil)]
    (is (= [] (:allowedTools opts)))
    (is (not (contains? opts :systemPrompt)))))

(deftest main-turn-system-prompt
  (let [opts (base-query-opts {:cwd "/tmp" :model "m"} "appended")]
    (is (= {:type "preset" :preset "claude_code" :append "appended"}
           (:systemPrompt opts)))
    (is (= ["mcp__xi-tools__*"] (:allowedTools opts)))))

(deftest parse-partial-json
  (testing "an open string value is closed"
    (is (= {:code "(println \"hi"} (anthropic/parse-partial-json "{\"code\": \"(println \\\"hi"))))
  (testing "a dangling escape is dropped"
    (is (= {:code "a"} (anthropic/parse-partial-json "{\"code\": \"a\\")))
    (is (= {:code "a"} (anthropic/parse-partial-json "{\"code\": \"a\\u00"))))
  (testing "nested containers are closed"
    (is (= {:edits [{:oldText "x"}]}
           (anthropic/parse-partial-json "{\"edits\": [{\"oldText\": \"x"))))
  (testing "trailing comma, dangling key and half-written scalar are dropped"
    (is (= {:path "a"} (anthropic/parse-partial-json "{\"path\": \"a\", ")))
    (is (= {:path "a"} (anthropic/parse-partial-json "{\"path\": \"a\", \"sta")))
    (is (= {:path "a"} (anthropic/parse-partial-json "{\"path\": \"a\", \"start\":")))
    (is (= {:path "a"} (anthropic/parse-partial-json "{\"path\": \"a\", \"x\": tr"))))
  (testing "empty / complete input"
    (is (nil? (anthropic/parse-partial-json "")))
    (is (= {} (anthropic/parse-partial-json "{")))
    (is (= {:a 1} (anthropic/parse-partial-json "{\"a\": 1}")))))

(defn- stream-event [event]
  (clj->js {:type "stream_event" :event event}))

(deftest tool-input-streams-partial-args
  (let [calls  (atom [])
        cbs    {:on-tool-start (fn [m] (swap! calls conj [:start m]))
                :on-tool-args  (fn [m] (swap! calls conj [:args (:arguments m)]))}
        state  (atom {})
        feed!  #(anthropic/process-sdk-message (stream-event %) cbs state)]
    (feed! {:type "content_block_start" :index 1
            :content_block {:type "tool_use" :id "t1" :name "mcp__xi-tools__clj" :input {}}})
    (feed! {:type "content_block_delta" :index 1
            :delta {:type "input_json_delta" :partial_json "{\"code\": \"(+ 1"}})
    ;; Arrives within the throttle window — folded in, not emitted.
    (feed! {:type "content_block_delta" :index 1
            :delta {:type "input_json_delta" :partial_json " 2"}})
    (feed! {:type "content_block_delta" :index 1
            :delta {:type "input_json_delta" :partial_json ")\"}"}})
    (feed! {:type "content_block_stop" :index 1})
    (is (= [[:start {:id "t1" :name "clj" :arguments {}}]
            [:args {:code "(+ 1"}]
            [:args {:code "(+ 1 2)"}]]
           @calls))))
