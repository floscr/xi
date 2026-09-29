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
