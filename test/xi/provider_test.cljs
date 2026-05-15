(ns xi.provider-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.provider :as provider]))

;; -- strip-mcp-prefix --

(deftest strip-mcp-prefix-removes-prefix
  (testing "strips mcp__xi-tools__ prefix"
    (is (= "bash" (provider/strip-mcp-prefix "mcp__xi-tools__bash")))
    (is (= "read" (provider/strip-mcp-prefix "mcp__xi-tools__read")))
    (is (= "edit" (provider/strip-mcp-prefix "mcp__xi-tools__edit")))))

(deftest strip-mcp-prefix-passthrough
  (testing "passes through names without prefix"
    (is (= "bash" (provider/strip-mcp-prefix "bash")))
    (is (= "custom_tool" (provider/strip-mcp-prefix "custom_tool")))))

(deftest strip-mcp-prefix-other-server
  (testing "strips prefix from any MCP server, not just xi-tools"
    (is (= "bash" (provider/strip-mcp-prefix "mcp__other__bash")))))

(deftest strip-mcp-prefix-nil
  (testing "handles nil"
    (is (nil? (provider/strip-mcp-prefix nil)))))

;; -- map-stop-reason --

(deftest map-stop-reason-tool-use
  (is (= "toolUse" (provider/map-stop-reason "tool_use"))))

(deftest map-stop-reason-max-tokens
  (is (= "length" (provider/map-stop-reason "max_tokens"))))

(deftest map-stop-reason-end-turn
  (is (= "stop" (provider/map-stop-reason "end_turn"))))

(deftest map-stop-reason-unknown
  (testing "unknown reason defaults to stop"
    (is (= "stop" (provider/map-stop-reason "something_else")))))
