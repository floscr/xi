(ns xi.providers.fake-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.providers.fake :as fake]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private script
  {:rules [{:when {:prompt "edit"} :reply [{:text "editing"}]}
           {:when {:re "^go+$" :user "bob"} :reply [{:text "bob goes"}]}
           {:when {:tool "notes_add"} :reply [{:text "has notes"}]}
           {:when {:side? true :prompt "title"} :reply [{:text "A Title"}]}]
   :default [{:text "default"}]})

(deftest select-reply-picks-the-first-matching-rule
  (is (= {:rule 0 :steps [{:text "editing"}]}
         (fake/select-reply script {:prompt "please edit it"})))
  (testing "every :when key must hold"
    (is (= 1 (:rule (fake/select-reply script {:prompt "gooo" :user "bob"}))))
    (is (= :default (:rule (fake/select-reply script {:prompt "gooo" :user "alice"})))))
  (testing ":tool matches an advertised tool"
    (is (= 2 (:rule (fake/select-reply script {:prompt "x" :tools ["read" "notes_add"]})))))
  (testing "side turns only match :side? rules, then :side, then a fallback title"
    (is (= :default (:rule (fake/select-reply script {:prompt "title"}))))
    (is (= 3 (:rule (fake/select-reply script {:prompt "make a title: edit" :side? true}))))
    (is (= {:rule :side :steps [{:text "Fake chat"}]}
           (fake/select-reply script {:prompt "summarize: edit" :side? true})))
    (is (= [{:text "S"}]
           (:steps (fake/select-reply (assoc script :side [{:text "S"}])
                                      {:prompt "x" :side? true})))))
  (is (= [{:text "[fake-llm] no rule matched"}]
         (:steps (fake/select-reply {} {:prompt "x"})))))

(deftest expand-fills-known-placeholders-only
  (is (= "got 42 in /p, {{nope}}"
         (fake/expand "got {{tool-result}} in {{cwd}}, {{nope}}" {:tool-result 42 :cwd "/p"}))))

(deftest parse-script-rejects-non-scripts
  (is (= {:rules []} (fake/parse-script "{:rules []}")))
  (is (thrown-with-msg? js/Error #"must be a map" (fake/parse-script "[1 2]")))
  (is (thrown-with-msg? js/Error #":rules must be a vector" (fake/parse-script "{:rules 1}"))))

(deftest transcript-messages-reads-claude-transcripts
  (is (= [{:role "user" :text "hi"}
          {:role "assistant" :text "calling"}
          {:role "assistant" :tool "read" :arguments {:path "a"}}
          {:role "user" :tool-result "data" :is-error false}]
         (fake/transcript-messages
          [(js/JSON.stringify (clj->js {:type "user" :message {:role "user" :content "hi"}}))
           (js/JSON.stringify (clj->js {:type "summary"}))
           (js/JSON.stringify (clj->js {:type "assistant"
                                        :message {:role "assistant"
                                                  :content [{:type "text" :text "calling"}
                                                            {:type "tool_use" :id "1" :name "read"
                                                             :input {:path "a"}}]}}))
           (js/JSON.stringify (clj->js {:type "user"
                                        :message {:role "user"
                                                  :content [{:type "tool_result" :tool_use_id "1"
                                                             :content "data"}]}}))
           "not json"]))))

(defn- with-env [m f]
  (let [saved (into {} (map (fn [k] [k (aget js/process.env k)])) (keys m))
        restore (fn []
                  (doseq [[k v] saved]
                    (if (some? v) (aset js/process.env k v) (js-delete js/process.env k))))]
    (doseq [[k v] m] (aset js/process.env k v))
    (-> (js/Promise.resolve) (.then f) (.finally restore))))

(deftest a-turn-runs-tools-through-the-policy-and-resumes
  (async done
    (let [tmp    (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-fake-"))
          file   (node-path/join tmp "data.txt")
          _      (fs/writeFileSync file "contents")
          _      (fs/writeFileSync
                  (node-path/join tmp "script.edn")
                  (pr-str {:rules [{:when {:prompt "read"}
                                    :reply [{:thinking "hm"}
                                            {:tool "read" :args {:path "{{cwd}}/data.txt"}}
                                            {:tool "write" :args {:path "x" :content "y"}}
                                            {:tool "nope" :args {}}
                                            {:text "saw: {{tool-result}}"}]}
                                   {:when {:prompt "again"} :reply [{:text "second"}]}]}))
          seen   (atom [])
          cb     (fn [k] (fn [x] (swap! seen conj [k x])))
          prov   (fake/provider :anthropic)
          opts   {:cwd tmp :model "m" :tool-ctx {:user "root"}
                  :only-tools #{"read" "write"}
                  ;; the policy blocks writes, like a deny rule would
                  :tool-policy (fn [call]
                                 (js/Promise.resolve (when (not= "write" (:name call)) call)))
                  :on-text (cb :text) :on-thinking (cb :thinking)
                  :on-tool-start (cb :start) :on-tool-result (cb :result)
                  :on-session (cb :session)}
          log    (fn [] (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)
                              (str/split-lines (fs/readFileSync (node-path/join tmp "log.jsonl") "utf8"))))]
      (-> (with-env {"XI_FAKE_LLM" (node-path/join tmp "script.edn")
                 "XI_FAKE_LLM_LOG" (node-path/join tmp "log.jsonl")
                 "CLAUDE_CONFIG_DIR" (node-path/join tmp "claude")}
        (fn []
          (-> (:promise ((:start-turn! prov) (assoc opts :prompt "read it")))
              (.then
               (fn [result]
                 (let [sid (:session-id result)
                       results (keep (fn [[k x]] (when (= k :result) x)) @seen)]
                   (is (string? sid))
                   (is (= [:session sid] (first @seen)))
                   (is (str/starts-with? (:content (first results)) "contents"))
                   (is (= ["Blocked by Xi permission gate" "Unknown tool: nope"]
                          (map :content (rest results))))
                   (is (= [false true true] (map :is-error results)))
                   (is (= [:text "saw: Unknown tool: nope"] (last @seen)))
                   (is (= ["read" "write"] (:tools (first (log)))))
                   (is (= "stop" (:stop-reason (first (log)))))
                   (:promise ((:start-turn! prov)
                              (assoc opts :prompt "again" :resume-session-id sid))))))
              (.then
               (fn [result]
                 (let [entry (second (log))]
                   (is (= 1 (:rule entry)))
                   (is (= (:session-id result) (:resume-session-id entry)))
                   (is (= {:role "user" :text "read it"} (first (:transcript entry))))
                   (is (= {:role "assistant" :text "saw: Unknown tool: nope"}
                          (last (:transcript entry)))))
                 (:promise ((:start-turn! prov)
                            (assoc opts :prompt "x" :resume-session-id "gone")))))
              (.then (fn [result] (is (:resume-failed result)))))))
          (.catch (fn [e] (is false (str "unexpected: " e))))
          (.finally (fn []
                      (fs/rmSync tmp #js {:recursive true :force true})
                      (done)))))))
