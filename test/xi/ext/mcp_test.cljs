(ns xi.ext.mcp-test
  (:require [cljs.test :refer [deftest is testing]]
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
