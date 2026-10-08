(ns xi.providers.anthropic-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.anthropic :as anthropic]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private base-query-opts #'anthropic/base-query-opts)
(def ^:private access-token-stale? #'anthropic/access-token-stale?)

(defn- stale-with
  "access-token-stale? against a CLAUDE_CONFIG_DIR whose .credentials.json is
   `credentials` (nil: no file)."
  [credentials]
  (let [dir  (.mkdtempSync fs (.join path (os/tmpdir) "xi-test-fresh-"))
        prev (aget js/process.env "CLAUDE_CONFIG_DIR")]
    (when credentials
      (fs/writeFileSync (.join path dir ".credentials.json") credentials "utf8"))
    (aset js/process.env "CLAUDE_CONFIG_DIR" dir)
    (try (access-token-stale?)
         (finally
           (if prev
             (aset js/process.env "CLAUDE_CONFIG_DIR" prev)
             (js-delete js/process.env "CLAUDE_CONFIG_DIR"))
           (fs/rmSync dir #js {:recursive true :force true})))))

(defn- login-expiring [ms-from-now]
  (js/JSON.stringify
   #js {:claudeAiOauth #js {:accessToken "a" :refreshToken "r"
                            :expiresAt (+ (js/Date.now) ms-from-now)}}))

(deftest stale-access-token
  (testing "a token valid for hours is fresh"
    (is (false? (stale-with (login-expiring (* 3 60 60 1000))))))
  (testing "an expired or nearly expired token is stale"
    (is (true? (stale-with (login-expiring (- 1000)))))
    (is (true? (stale-with (login-expiring 60000)))))
  (testing "a blanked login (expiresAt 0) is stale"
    (is (true? (stale-with "{\"claudeAiOauth\":{\"accessToken\":\"\",\"expiresAt\":0}}"))))
  (testing "no readable login means nothing to refresh"
    (is (not (stale-with nil)))
    (is (not (stale-with "not json")))
    (is (not (stale-with "{}")))))

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

(deftest api-retry-fails-fast-on-unretryable-errors
  (let [errors (atom [])
        cbs    {:on-error #(swap! errors conj %)}
        state  (atom {})
        retry! #(anthropic/process-sdk-message
                 (clj->js {:type "system" :subtype "api_retry" :attempt 1
                           :max_retries 10 :retry_delay_ms 1000
                           :error_status %1 :error %2})
                 cbs state)]
    (testing "transient errors are left to the CLI's own retries"
      (retry! 529 "server_error")
      (is (empty? @errors))
      (is (not (:fatal-error @state))))
    (testing "an expired login is reported once, on the first retry"
      (retry! 401 "authentication_failed")
      (retry! 401 "authentication_failed")
      (is (:fatal-error @state))
      (is (= 1 (count @errors)))
      (is (re-find #"login has expired" (:message (first @errors)))))))

(deftest flagged-assistant-message-is-an-error-not-prose
  (let [texts  (atom [])
        errors (atom [])
        cbs    {:on-text  #(swap! texts conj %)
                :on-error #(swap! errors conj %)}
        state  (atom {})
        notice "You've hit your session limit · resets 6pm (Europe/Vienna)"
        feed!  #(anthropic/process-sdk-message (clj->js %) cbs state)]
    (feed! {:type "assistant" :error "rate_limit"
            :message {:content [{:type "text" :text notice}]}})
    (testing "the notice is reported as an error, not streamed as text"
      (is (empty? @texts))
      (is (= [{:type "error" :message notice}] @errors)))
    (testing "the failed result repeating it is not reported twice"
      (feed! {:type "result" :is_error true :result notice})
      (is (= 1 (count @errors))))))

(deftest failed-result-with-known-failure-is-reported
  (let [errors (atom [])
        cbs    {:on-error #(swap! errors conj %)}
        feed!  (fn [result]
                 (anthropic/process-sdk-message
                  (clj->js {:type "result" :is_error true :result result})
                  cbs (atom {})))]
    (feed! "You've hit your session limit · resets 6pm")
    (is (= 1 (count @errors)))
    (feed! "Something unrelated broke")
    (is (= 1 (count @errors)) "unrecognised failures stay quiet")))

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
