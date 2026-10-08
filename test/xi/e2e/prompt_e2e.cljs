(ns xi.e2e.prompt-e2e
  "`xi prompt` end to end: one-shot turns, resume, ephemeral runs, tool
   round trips and provider errors against the scripted fake LLM."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h]
            [xi.e2e.profiles :as profiles]))

(def ^:private chat-script
  {:rules [{:when {:prompt "hello"} :reply [{:text "hello from the fake"}]}
           {:when {:prompt "what did I say"} :reply [{:text "you said hello"}]}]})

(defn- session-files [env]
  (h/files-under (h/home-path env ".config/xi/sessions")))

(defn- plain-turn [profile done]
  (h/with-env! profile chat-script done
    (fn [env]
      (-> (h/prompt! env "hello")
          (.then
           (fn [{:keys [code stdout stderr]}]
             (is (= 0 code) stderr)
             (is (= "hello from the fake\n" stdout))
             (is (= 1 (count (session-files env))) "the chat is saved")
             (let [[turn & more] (h/llm-log env)]
               (is (empty? more) "one prompt, one model turn, no side turns")
               (is (= "hello" (:prompt turn)))
               (is (= "anthropic" (:provider turn)) "the default model routes to Claude")
               (is (some #{"write"} (:tools turn)))
               (is (str/includes? (:system turn) "E2E-PROJECT-INSTRUCTIONS")
                   "the project's AGENTS.md is in the system prompt"))))))))

(deftest a-first-run-user-gets-an-answer
  (async done (plain-turn profiles/bare done)))

(deftest a-minimal-config-gets-an-answer
  (async done (plain-turn profiles/minimal done)))

(deftest every-model-is-the-fake
  (async done
    (h/with-env! profiles/minimal chat-script done
      (fn [env]
        (-> (js/Promise.all #js [(h/prompt! env "hello" "--model" "qwen3:8b")
                                 (h/prompt! env "hello" "--model" "opencode/some-model")])
            (.then
             (fn [results]
               (doseq [{:keys [code stdout stderr]} results]
                 (is (= 0 code) stderr)
                 (is (= "hello from the fake\n" stdout)))
               (testing "the log names the provider the model would have used"
                 (is (= #{["ollama" "qwen3:8b"] ["zen" "opencode/some-model"]}
                        (set (map (juxt :provider :model) (h/llm-log env)))))))))))))

(deftest a-session-resumes-with-its-history
  (async done
    (h/with-env! profiles/minimal chat-script done
      (fn [env]
        (-> (h/prompt! env "hello" "--json")
            (.then
             (fn [{:keys [code json stderr]}]
               (is (= 0 code) stderr)
               (is (= "hello from the fake" (:text json)))
               (-> (h/prompt! env "what did I say" "--json" "--session" (:session-id json))
                   (.then (fn [second-run] [json second-run])))))
            (.then
             (fn [[first-json {:keys [code json stderr]}]]
               (is (= 0 code) stderr)
               (is (= (:session-id first-json) (:session-id json)) "the same Xi session")
               (is (= 1 (count (session-files env))))
               (let [[t1 t2] (h/main-turns env)]
                 (is (= (:session-id t1) (:resume-session-id t2))
                     "the provider session is resumed")
                 (is (= [{:role "user" :text "hello"}
                         {:role "assistant" :text "hello from the fake"}]
                        (:transcript t2))
                     "the second turn sees the first")))))))))

(deftest an-unknown-session-fails-cleanly
  (async done
    (h/with-env! profiles/minimal chat-script done
      (fn [env]
        (-> (h/prompt! env "hello" "--session" "no-such-session")
            (.then (fn [{:keys [code stderr]}]
                     (is (= 1 code))
                     (is (str/includes? stderr "session not found"))
                     (is (empty? (h/llm-log env)) "the model is never asked"))))))))

(deftest no-store-leaves-nothing-behind
  (async done
    (h/with-env! profiles/minimal chat-script done
      (fn [env]
        (-> (h/prompt! env "hello" "--no-store")
            (.then (fn [{:keys [code stdout stderr]}]
                     (is (= 0 code) stderr)
                     (is (= "hello from the fake\n" stdout))
                     (is (= [] (session-files env)) "no Xi session")
                     (is (= [] (h/files-under (h/home-path env ".claude/projects")))
                         "no transcript"))))))))

(deftest tool-calls-change-the-project-and-feed-back
  (async done
    (h/with-env! profiles/minimal
      {:rules [{:when {:prompt "update the readme"}
                :reply [{:thinking "edit, then add a file"}
                        {:tool "edit" :args {:path "README.md"
                                             :edits [{:oldText "# e2e project"
                                                      :newText "# edited by the fake"}]}}
                        {:tool "write" :args {:path "notes/todo.md" :content "- ship it\n"}}
                        {:tool "read" :args {:path "{{cwd}}/README.md"}}
                        {:text "README now: {{tool-result}}"}]}]}
      done
      (fn [env]
        (-> (h/prompt! env "update the readme")
            (.then
             (fn [{:keys [code stdout stderr]}]
               (is (= 0 code) stderr)
               (is (= "# edited by the fake\n" (h/slurp (h/path env "README.md"))))
               (is (= "- ship it\n" (h/slurp (h/path env "notes/todo.md"))))
               (is (str/includes? stdout "README now: # edited by the fake")
                   "the read result came back to the model")
               (let [calls (:tool-calls (first (h/llm-log env)))]
                 (is (= ["edit" "write" "read"] (map :name calls)))
                 (is (every? (complement :is-error) calls) (pr-str calls))))))))))

(deftest a-provider-error-fails-the-run
  (async done
    (h/with-env! profiles/minimal {:default [{:text "partial "} {:error "fake 529 overloaded"}]}
      done
      (fn [env]
        (-> (h/prompt! env "anything")
            (.then (fn [{:keys [code stderr]}]
                     (is (= 1 code))
                     (is (str/includes? stderr "fake 529 overloaded")))))))))
