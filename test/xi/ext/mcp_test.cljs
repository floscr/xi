(ns xi.ext.mcp-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.mcp :as mcp]))

(deftest ext-id-namespaces-server-id
  (is (= :mcp-render (mcp/ext-id :render)))
  (is (= :mcp-context7 (mcp/ext-id :context7))))

(deftest qualify-name-follows-convention
  (is (= "mcp__render__list_services" (mcp/qualify-name :render "list_services"))))

(deftest normalize-tool-def-maps-schema-and-name
  (let [t {:name "get_docs"
           :description "Fetch docs"
           :inputSchema {:type "object" :properties {:q {:type "string"}}}}
        d (mcp/normalize-tool-def :context7 t)]
    (is (= "mcp__context7__get_docs" (:name d)))
    (is (= "Fetch docs" (:description d)))
    (is (= {:type "object" :properties {:q {:type "string"}}} (:input_schema d))
        "MCP :inputSchema becomes Xi :input_schema")
    (testing "missing description/schema get sane defaults"
      (let [d2 (mcp/normalize-tool-def :x {:name "t"})]
        (is (= "" (:description d2)))
        (is (= {:type "object" :properties {}} (:input_schema d2)))))))

(deftest normalize-result-maps-error-flag
  (let [ok (mcp/normalize-result #js {:content #js [#js {:type "text" :text "hi"}]})]
    (is (= [{:type "text" :text "hi"}] (:content ok)))
    (is (= false (:is-error ok))))
  (let [err (mcp/normalize-result #js {:content #js [#js {:type "text" :text "boom"}]
                                       :isError true})]
    (is (= true (:is-error err)) "MCP :isError becomes Xi :is-error"))
  (testing "absent content defaults to an empty text block"
    (is (= [{:type "text" :text ""}] (:content (mcp/normalize-result #js {}))))))

(deftest enabled-entry?-defaults-to-true
  (is (true?  (mcp/enabled-entry? {})))
  (is (true?  (mcp/enabled-entry? {:enabled true})))
  (is (false? (mcp/enabled-entry? {:enabled false}))))

(deftest parse-qualified-name-inverts-qualify-name
  (testing "external MCP tool names split into server + tool"
    (is (= {:server "render" :tool "list_services"}
           (mcp/parse-qualified-name "mcp__render__list_services")))
    (is (= {:server "context7" :tool "get_docs"}
           (mcp/parse-qualified-name (mcp/qualify-name :context7 "get_docs"))))
    (testing "a tool name containing __ keeps the split at the server boundary"
      (is (= {:server "srv" :tool "weird__tool"}
             (mcp/parse-qualified-name "mcp__srv__weird__tool")))))
  (testing "bare / non-MCP names yield nil so the gate skips built-in tools"
    (is (nil? (mcp/parse-qualified-name "bash")))
    (is (nil? (mcp/parse-qualified-name "Read")))
    (is (nil? (mcp/parse-qualified-name "mcp__nodelim")))
    (is (nil? (mcp/parse-qualified-name nil)))))

(deftest format-arguments-lists-every-key
  (testing "empty args get a placeholder"
    (is (= "  (no arguments)" (mcp/format-arguments {})))
    (is (= "  (no arguments)" (mcp/format-arguments nil))))
  (testing "strings verbatim, non-strings via pr-str, one per line"
    (let [out (mcp/format-arguments {:q "react" :limit 5})]
      (is (str/includes? out "  q: react"))
      (is (str/includes? out "  limit: 5")))))

(deftest gate-message-carries-server-tool-and-args
  (let [msg (mcp/gate-message "context7" "get_docs" {:library "react"})]
    (is (str/includes? msg "Server: context7"))
    (is (str/includes? msg "Tool:   get_docs"))
    (is (str/includes? msg "library: react"))))

(deftest tool-gate-confirms-mcp-and-passes-through-others
  (let [gate (:tool-gate (mcp/create {:manager :stub}))]
    (testing "the /mcp extension exposes a tool-gate"
      (is (fn? gate)))
    (testing "non-MCP (bare) tool calls pass through untouched, even with a gate"
      (let [tc {:name "bash" :arguments {:command "ls"}}]
        (is (= tc (gate tc {:confirm! (fn [_] (throw (js/Error. "should not ask")))})))))
    (testing "MCP calls with no :confirm! (client mirror) pass through"
      (let [tc {:name "mcp__render__list" :arguments {}}]
        (is (= tc (gate tc {})))))
    ;; shadow-cljs auto-awaits promise values in an async test body, so we can
    ;; bind the gate's promise result and compare it directly (see
    ;; ext.core-test/tool-gate-promise-value).
    (async done
      (let [tc     {:name "mcp__render__deploy" :arguments {:svc "web"}}
            asked  (atom nil)
            gated  (gate tc {:confirm! (fn [msg]
                                         (reset! asked msg)
                                         (js/Promise.resolve true))})]
        (is (= tc gated) "approval lets the call proceed")
        (is (str/includes? @asked "Server: render")
            "the confirm message is the rich gate block")
        (let [denied (gate tc {:confirm! (fn [_] (js/Promise.resolve false))})]
          (is (nil? denied) "denial blocks the call"))
        (done)))))

(deftest allow-tool-handler-remembers-per-room
  (let [handler (get (:handlers (mcp/create {:manager :stub})) :mcp/allow-tool)
        {st' :state} (handler {} {:room-id "r1" :tool "mcp__render__list_logs"})]
    (is (= #{"mcp__render__list_logs"}
           (get-in st' [:rooms "r1" :ext :mcp :allowed-tools])))
    (testing "a second tool joins the room's set"
      (let [{st2 :state} (handler st' {:room-id "r1" :tool "mcp__render__get_metrics"})]
        (is (= #{"mcp__render__list_logs" "mcp__render__get_metrics"}
               (get-in st2 [:rooms "r1" :ext :mcp :allowed-tools])))))))

(deftest tool-gate-allow-always-remembers-and-proceeds
  (let [gate (:tool-gate (mcp/create {:manager :stub}))
        tc   {:name "mcp__render__list_logs" :arguments {:svc "web"}}]
    (async done
      (let [dispatched (atom [])
            opts-seen  (atom nil)
            gated      (gate tc {:room-id   "r1"
                                 :get-state (fn [] {})
                                 :dispatch! (fn [ev] (swap! dispatched conj ev))
                                 :confirm!  (fn [_ opts]
                                              (reset! opts-seen opts)
                                              (js/Promise.resolve :always))})]
        (is (= tc gated) ":always lets the call proceed")
        (is (= {:allow-always? true} @opts-seen)
            "the gate offers the allow-always option")
        (is (= [{:type :mcp/allow-tool :room-id "r1" :tool "mcp__render__list_logs"}]
               @dispatched)
            ":always dispatches the per-session remember event")
        (done)))))

(deftest tool-gate-skips-remembered-tools
  (let [gate (:tool-gate (mcp/create {:manager :stub}))
        st   {:rooms {"r1" {:ext {:mcp {:allowed-tools #{"mcp__render__list_logs"}}}}}}]
    (testing "a remembered tool bypasses the confirm dialog (returned synchronously)"
      (let [tc {:name "mcp__render__list_logs" :arguments {}}]
        (is (= tc (gate tc {:room-id   "r1"
                            :get-state (fn [] st)
                            :confirm!  (fn [& _] (throw (js/Error. "should not ask again")))})))))
    (testing "a different, unremembered tool is still gated (can be denied)"
      (async done
        (let [denied (gate {:name "mcp__render__delete" :arguments {}}
                           {:room-id   "r1"
                            :get-state (fn [] st)
                            :confirm!  (fn [& _] (js/Promise.resolve false))})]
          (is (nil? denied) "unremembered tool is asked and denial blocks it")
          (done))))))

(deftest parse-command-covers-subs
  (testing "empty defaults to list"
    (is (= {:sub "list"} (mcp/parse-command "")))
    (is (= {:sub "list"} (mcp/parse-command "   "))))
  (testing "single-id subs"
    (is (= {:sub "enable" :id "render"} (mcp/parse-command "enable render")))
    (is (= {:sub "disable" :id "render"} (mcp/parse-command "disable render")))
    (is (= {:sub "remove" :id "render"} (mcp/parse-command "remove render")))
    (is (= {:sub "refresh" :id "render"} (mcp/parse-command "refresh render"))))
  (testing "add captures command + trailing args"
    (is (= {:sub "add" :id "context7" :command "npx"
            :args ["-y" "@upstash/context7-mcp"]}
           (mcp/parse-command "add context7 npx -y @upstash/context7-mcp")))
    (is (= {:sub "add" :id "foo" :command "server" :args []}
           (mcp/parse-command "add foo server")))))
