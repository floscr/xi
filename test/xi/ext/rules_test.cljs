(ns xi.ext.rules-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.rules :as rules-ext]
            [xi.test-util :refer [silenced]]
            ["node:fs" :as fs]))

(defn- ctx [state]
  {:get-state (fn [] state)
   :room-id   "r1"
   :cwd       "/tmp"
   :confirm!  nil})

(defn- state-with [scope-path rule]
  (assoc-in {:rooms {"r1" {:cwd "/tmp"}}} scope-path [rule]))

(deftest allow-rule-runs-the-call
  (async done
    (let [rule  {:match {:tool :bash} :action {:type :allow}}
          state (state-with [:ext :rules :rules] rule)
          tc    {:name "bash" :arguments {:command "ls"}}]
      (-> (rules-ext/tool-policy tc (ctx state))
          (.then (fn [out]
                   (is (= tc out))
                   (done)))))))

(deftest deny-rule-intercepts-with-error
  (async done
    (let [rule  {:match {:tool :write :path "*.sh"} :action {:type :deny :message "no shell"}}
          state (state-with [:ext :rules :rules] rule)]
      (-> (rules-ext/tool-policy {:name "write" :arguments {:path "x.sh"}} (ctx state))
          (.then (fn [out]
                   (is (:intercepted out))
                   (is (:is-error (:result out)))
                   (is (= "no shell" (get-in out [:result :content 0 :text])))
                   (done)))))))

(deftest nudge-rule-intercepts-without-error
  (async done
    (let [rule  {:match {:tool :write :path "*.sh"} :action {:type :nudge :message "prefer bb"}}
          state (state-with [:ext :rules :rules] rule)]
      (-> (rules-ext/tool-policy {:name "write" :arguments {:path "x.sh"}} (ctx state))
          (.then (fn [out]
                   (is (:intercepted out))
                   (is (false? (:is-error (:result out))))
                   (is (= "prefer bb" (get-in out [:result :content 0 :text])))
                   (done)))))))

(deftest no-match-passes-through
  (async done
    (let [rule  {:match {:tool :write} :action {:type :deny}}
          state (state-with [:ext :rules :rules] rule)
          tc    {:name "bash" :arguments {:command "ls"}}]
      (-> (rules-ext/tool-policy tc (ctx state))
          (.then (fn [out]
                   (is (= tc out))
                   (done)))))))

(deftest hard-block-runs-before-data-rules
  (testing "an :allow rule cannot override the immutable rules-file hard-block"
    (async done
      (let [rule  {:match {:tool :write} :action {:type :allow}}
            state (state-with [:ext :rules :rules] rule)]
        (-> (rules-ext/tool-policy {:name "write"
                                    :arguments {:path "~/.config/xi/rules.edn"}}
                                   (ctx state))
            (.then (fn [out]
                     (is (:intercepted out))
                     (is (:is-error (:result out)))
                     (done))))))))

(deftest repo-rules-file-asks-before-data-rules
  (let [rule  {:match {:tool :write} :action {:type :allow}}
        state (state-with [:ext :rules :rules] rule)
        tc    {:name "write" :arguments {:path ".xi/rules.edn" :content "{}"}}
        asked (atom nil)
        answering (fn [ans]
                    (assoc (ctx state) :confirm! (fn [text opts]
                                                   (reset! asked [text opts])
                                                   (js/Promise.resolve ans))))]
    (async done
      (-> (rules-ext/tool-policy tc (ctx state))
          (.then (fn [out]
                   (is (:is-error (:result out))
                       "an :allow rule doesn't skip it, and no client to ask denies")
                   (rules-ext/tool-policy tc (answering true))))
          (.then (fn [out]
                   (is (= tc out) "yes runs the write")
                   (let [[text opts] @asked]
                     (is (str/includes? text ".xi/rules.edn"))
                     (is (:hardened? opts) "never answered by yolo mode")
                     (is (not-any? #{:always :repo} (:options opts)) "no lasting grant"))
                   (rules-ext/tool-policy tc (answering false))))
          (.then (fn [out]
                   (is (nil? out) "no blocks it")
                   (done)))))))

(deftest policy-fails-closed
  (testing "a throw while deciding denies the call instead of letting it run"
    (async done
      (-> (silenced
           #(rules-ext/tool-policy {:name "bash" :arguments {:command "ls"}}
                                   (assoc (ctx {}) :get-state (fn [] (throw (js/Error. "boom"))))))
          (.then (fn [out]
                   (is (:intercepted out))
                   (is (:is-error (:result out)))
                   (is (re-find #"boom" (get-in out [:result :content 0 :text])))
                   (done)))))))

(deftest chained-bash-is-denied-by-default
  (async done
    (-> (js/Promise.all
         #js [(rules-ext/tool-policy {:name "bash" :arguments {:command "ls src | head -3"}}
                                     (ctx {}))
              (rules-ext/tool-policy {:name "bash" :arguments {:command "git status"}}
                                     (ctx {}))])
        (.then (fn [[chained plain]]
                 (is (:is-error (:result chained)))
                 (is (re-find #"chained/piped" (get-in chained [:result :content 0 :text])))
                 (is (= {:name "bash" :arguments {:command "git status"}} plain))
                 (done))))))

;; /tmp has no trusted bb.edn, so a run_task call there hits the task-trust rule.

(deftest untrusted-task-file-asks-and-always-trusts-via-the-option-event
  (async done
    (let [tc         {:name "run_task" :arguments {:task "test" :runner "bb"}}
          asked      (atom nil)
          dispatched (atom [])
          c          (assoc (ctx {})
                            :confirm!  (fn [msg opts] (reset! asked [msg opts])
                                         (js/Promise.resolve :trust-task-file))
                            :dispatch! (fn [ev] (swap! dispatched conj ev)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out) "the call runs")
                   (is (re-find #"No project file found" (first @asked)))
                   (is (re-find #"bb test" (first @asked)))
                   (is (some #(= :trust-task-file (:value %)) (:options (second @asked))))
                   (is (= [{:type :ext.clj/trust-task-file :room-id "r1" :cwd "/tmp"}] @dispatched)
                       "trusts the task file instead of saving a session rule")
                   (done)))))))

(deftest untrusted-task-file-is-refused-when-nobody-can-answer
  (async done
    (-> (rules-ext/tool-policy {:name "run_task" :arguments {:task "test" :runner "bb"}} (ctx {}))
        (.then (fn [out]
                 (is (:is-error (:result out)))
                 (is (re-find #"no client" (get-in out [:result :content 0 :text])))
                 (done))))))

(deftest option-events-are-allowlisted
  (testing "a rule's option can't dispatch an arbitrary event"
    (async done
      (let [rule       {:match {:tool :bash}
                        :action {:type :ask
                                 :options [:yes :no {:value :go :key "g" :label "Go"
                                                     :event {:type :prompt/submit :text "pwn"}}]}}
            state      (state-with [:ext :rules :rules] rule)
            dispatched (atom [])
            tc         {:name "bash" :arguments {:command "ls"}}
            c          (assoc (ctx state)
                              :confirm!  (fn [_ _] (js/Promise.resolve :go))
                              :dispatch! (fn [ev] (swap! dispatched conj ev)))]
        (-> (rules-ext/tool-policy tc c)
            (.then (fn [out]
                     (is (= tc out) "a truthy answer still approves")
                     (is (empty? @dispatched))
                     (done))))))))

(deftest ask-rule-allows-on-yes
  (async done
    (let [rule  {:match {:tool :bash} :action {:type :ask}}
          state (state-with [:ext :rules :rules] rule)
          tc    {:name "bash" :arguments {:command "ls"}}
          c     (assoc (ctx state) :confirm! (fn [_ _] (js/Promise.resolve true)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out))
                   (done)))))))

(deftest ask-rule-blocks-on-no
  (async done
    (let [rule  {:match {:tool :bash} :action {:type :ask}}
          state (state-with [:ext :rules :rules] rule)
          tc    {:name "bash" :arguments {:command "ls"}}
          c     (assoc (ctx state) :confirm! (fn [_ _] (js/Promise.resolve false)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (nil? out))
                   (done)))))))

(deftest ask-rule-deny-with-reason-tells-the-model
  (async done
    (let [rule  {:match {:tool :bash} :action {:type :ask}}
          state (state-with [:ext :rules :rules] rule)
          tc    {:name "bash" :arguments {:command "rm -rf build"}}
          c     (assoc (ctx state)
                       :confirm! (fn [_ {:keys [on-reason]}]
                                   (on-reason "run make clean instead")
                                   (js/Promise.resolve false)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (get-in out [:result :is-error]))
                   (is (= (str "The user denied this tool call.\n"
                               "To tell you how to proceed, the user said:\n"
                               "run make clean instead")
                          (get-in out [:result :content 0 :text])))
                   (done)))))))

(deftest ask-rule-persists-session-allow-on-always
  (async done
    (let [rule       {:match {:tool :bash} :action {:type :ask}}
          state      (state-with [:ext :rules :rules] rule)
          tc         {:name "bash" :arguments {:command "ls"}}
          dispatched (atom nil)
          c          (assoc (ctx state)
                            :confirm!  (fn [_ _] (js/Promise.resolve :always))
                            :dispatch! (fn [ev] (reset! dispatched ev)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out))
                   (is (= :ext.rules/add (:type @dispatched)))
                   (is (= :session (:scope @dispatched)))
                   (is (= :allow (get-in @dispatched [:rule :action :type])))
                   (done)))))))

;; Yolo mode auto-answers confirms in create-dialogs (xi.ext.core); the
;; engine's part is tagging a hardened-tier ask so it is exempt.

(deftest hardened-asks-are-tagged-for-yolo-mode
  (async done
    (let [seen  (atom [])
          c     (assoc (ctx (state-with [:ext :rules :rules]
                                        {:match {:tool :bash} :action {:type :ask}}))
                       :confirm! (fn [_ opts] (swap! seen conj opts) (js/Promise.resolve true)))]
      (-> (js/Promise.all
           #js [(rules-ext/tool-policy {:name "write"
                                        :arguments {:path ".git/hooks/pre-commit" :content "x"}}
                                       c)
                (rules-ext/tool-policy {:name "bash" :arguments {:command "ls"}} c)])
          (.then (fn [_]
                   (let [[hardened plain] @seen]
                     (is (true? (:hardened? hardened)) "the .git/hooks ask carries :hardened?")
                     (is (not (contains? plain :hardened?)) "a session-rule ask does not"))
                   (done)))))))

(deftest an-ask-carries-its-yolo-guard
  (async done
    (let [seen (atom [])
          ask  (fn [guard]
                 (let [action (cond-> {:type :ask} (some? guard) (assoc :guard guard))
                       c      (assoc (ctx (state-with [:ext :rules :rules]
                                                      {:match {:tool :bash} :action action}))
                                     :confirm! (fn [_ opts]
                                                 (swap! seen conj (:guard opts))
                                                 (js/Promise.resolve true)))]
                   (rules-ext/tool-policy {:name "bash" :arguments {:command "ls"}} c)))]
      (-> (reduce (fn [p g] (.then p #(ask g))) (js/Promise.resolve nil)
                  [nil true :sane :always])
          (.then (fn [_]
                   (is (= [nil :sane :sane :always] @seen)
                       "`:guard true` is :sane; no guard, no key")
                   (done)))))))

(deftest ask-rule-recommend-spawns-subagent-and-blocks
  (async done
    (let [rule       {:match {:tool :bash} :action {:type :ask}}
          state      (state-with [:ext :rules :rules] rule)
          tc         {:name "bash" :arguments {:command "ls"}}
          dispatched (atom [])
          c          (assoc (ctx state)
                            :recommend-rule? true
                            :confirm!  (fn [_ _] (js/Promise.resolve :recommend))
                            :dispatch! (fn [ev] (swap! dispatched conj ev)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (:intercepted out))
                   (is (:is-error (:result out)))
                   (is (some #(= :subagent/spawn (:type %)) @dispatched))
                   (done)))))))

(deftest recommend-option-is-gated-by-the-flag
  (let [rule  {:match {:tool :bash} :action {:type :ask}}
        state (state-with [:ext :rules :rules] rule)
        tc    {:name "bash" :arguments {:command "ls"}}
        opts  (fn [flag?]
                (let [seen (atom nil)
                      c    (cond-> (assoc (ctx state)
                                          :confirm! (fn [_ o] (reset! seen (:options o))
                                                      (js/Promise.resolve true)))
                             flag? (assoc :recommend-rule? true))]
                  (-> (rules-ext/tool-policy tc c)
                      (.then (fn [_] @seen)))))]
    (async done
      (-> (opts false)
          (.then (fn [off]
                   (is (not (some #{:recommend-rule} off)))
                   (opts true)))
          (.then (fn [on]
                   (is (some #{:recommend-rule} on))
                   (done)))))))

(deftest extract-rule-parses-fenced-and-bare
  (testing "fenced clojure block"
    (is (= {:match {:tool :bash} :action {:type :allow}}
           (rules-ext/extract-rule
            "Rationale here.\n```clojure\n{:match {:tool :bash} :action {:type :allow}}\n```"))))
  (testing "regex literals round-trip"
    (let [r (rules-ext/extract-rule "```clojure\n{:match {:path #\"\\.sh$\"} :action {:type :nudge}}\n```")]
      (is (regexp? (get-in r [:match :path])))))
  (testing "falls back to first brace"
    (is (= {:match {:tool :read} :action {:type :allow}}
           (rules-ext/extract-rule "here: {:match {:tool :read} :action {:type :allow}}"))))
  (testing "nil on garbage"
    (is (nil? (rules-ext/extract-rule "no rule here")))
    (is (nil? (rules-ext/extract-rule nil)))))

(deftest on-subagent-turn-end-fires-only-for-recommend-subs
  (testing "recommend sub emits the recommend-done effect"
    (let [out (rules-ext/on-subagent-turn-end
               {} {:room-id "r1" :sub-id "rules-rec-123" :aborted? false})]
      (is (= [[:rules/recommend-done {:room-id "r1" :sub-id "rules-rec-123"}]]
             (:effects out)))))
  (testing "aborted recommend sub does nothing"
    (is (nil? (rules-ext/on-subagent-turn-end
               {} {:room-id "r1" :sub-id "rules-rec-123" :aborted? true}))))
  (testing "non-recommend sub passes through"
    (is (nil? (rules-ext/on-subagent-turn-end
               {} {:room-id "r1" :sub-id "sa-9" :aborted? false})))))

(deftest save-recommended-routes-by-scope
  (testing "session scope dispatches :ext.rules/add"
    (let [evs (atom [])]
      (rules-ext/save-recommended!
       (fn [e] (swap! evs conj e)) "r1" "/tmp"
       {:rule "{:match {:tool :bash} :action {:type :allow}}" :scope "session"})
      (let [add (first (filter #(= :ext.rules/add (:type %)) @evs))]
        (is (some? add))
        (is (= :session (:scope add)))
        (is (= :allow (get-in add [:rule :action :type]))))))
  (testing "regex rules parse and persist (full reader)"
    (let [evs (atom [])]
      (rules-ext/save-recommended!
       (fn [e] (swap! evs conj e)) "r1" "/tmp"
       {:rule "{:match {:tool :write :path #\"\\.sh$\"} :action {:type :nudge}}" :scope "session"})
      (let [add (first (filter #(= :ext.rules/add (:type %)) @evs))]
        (is (some? add))
        (is (regexp? (get-in add [:rule :match :path]))))))
  (testing "invalid rule text yields a status, not an add"
    (let [evs (atom [])]
      (rules-ext/save-recommended!
       (fn [e] (swap! evs conj e)) "r1" "/tmp" {:rule "not a map" :scope "session"})
      (is (every? #(= :history/append (:type %)) @evs))
      (is (not-any? #(= :ext.rules/add (:type %)) @evs))))
  (testing "unknown scope yields a status"
    (let [evs (atom [])]
      (rules-ext/save-recommended!
       (fn [e] (swap! evs conj e)) "r1" "/tmp"
       {:rule "{:match {:tool :bash} :action {:type :allow}}" :scope "bogus"})
      (is (every? #(= :history/append (:type %)) @evs))
      (is (not-any? #(= :ext.rules/add (:type %)) @evs)))))

(deftest ask-rule-persists-repo-allow-on-repo-answer
  (async done
    (let [cwd        (.cwd js/process)
          rule       {:match {:tool #{:write :edit}}
                      :action {:type :ask :options [:yes :no :repo]}}
          state      (assoc-in {:rooms {"r1" {:cwd cwd}}}
                               [:ext :rules :rules] [rule])
          tc         {:name "write" :arguments {:path "src/xi/rules.cljs"}}
          dispatched (atom nil)
          asked      (atom nil)
          c          {:get-state (fn [] state) :room-id "r1" :cwd cwd
                      :confirm!  (fn [_ opts] (reset! asked opts) (js/Promise.resolve :repo))
                      :dispatch! (fn [ev] (reset! dispatched ev))}]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out) "call is allowed through")
                   (is (some #{:repo} (:options @asked)) "the dialog offers [r]")
                   (is (= :ext.rules/add (:type @dispatched)))
                   (is (= :session (:scope @dispatched)))
                   (is (= #{:write :edit} (get-in @dispatched [:rule :match :tool]))
                       "repo allow-rule groups write+edit")
                   (is (string? (get-in @dispatched [:rule :match :repo]))
                       "repo allow-rule is scoped to the repo root")
                   (is (= :allow (get-in @dispatched [:rule :action :type])))
                   (done)))))))

(deftest allow-rule-answer-stops-the-rule-asking-for-the-chat
  (async done
    (let [rule  {:match {:tool #{:write :edit} :path #"^/x/ext/"}
                 :action {:type :ask :options [:yes :no :rule]}}
          state (state-with [:ext :rules :rules] rule)
          write (fn [path] {:name "write" :arguments {:path path}})
          evs   (atom [])
          asked (atom 0)
          c     (fn [st answer]
                  {:get-state (fn [] st) :room-id "r1" :cwd "/tmp"
                   :confirm!  (fn [_ _] (swap! asked inc) (js/Promise.resolve answer))
                   :dispatch! #(swap! evs conj %)})]
      (-> (rules-ext/tool-policy (write "/x/ext/a.cljs") (c state :rule))
          (.then (fn [out]
                   (is (= (write "/x/ext/a.cljs") out) "[s] allows this call")
                   (let [grant   (first @evs)
                         granted (:state (rules-ext/grant-rule state grant))]
                     (is (= :ext.rules/grant (:type grant)))
                     (-> (rules-ext/tool-policy (write "/x/ext/b.cljs") (c granted false))
                         (.then (fn [out]
                                  (is (= (write "/x/ext/b.cljs") out)
                                      "another call the rule matches runs")
                                  (is (= 1 @asked) "without asking again")
                                  (rules-ext/tool-policy (write "/x/ext/c.cljs") (c state false))))
                         (.then (fn [out]
                                  (is (= 2 @asked) "a chat without the grant still asks")
                                  (is (nil? out) "and a no blocks it")
                                  (done)))))))))))

(deftest mcp-default-rule-asks-with-rich-message
  (testing "an external MCP call is gated by the built-in default rule with an
           informative server/tool/arguments confirm block"
    (async done
      (let [state (state-with [:ext :rules :rules] {:match {:tool :read}
                                                    :action {:type :allow}})
            tc    {:name "mcp__context7__get_docs"
                   :arguments {:library "react" :topic "hooks"}}
            asked (atom nil)
            c     (assoc (ctx state)
                        :confirm! (fn [msg _] (reset! asked msg)
                                    (js/Promise.resolve true)))]
        (-> (rules-ext/tool-policy tc c)
            (.then (fn [out]
                     (is (= tc out) "approval lets the call proceed")
                     (is (str/includes? @asked "Server: context7"))
                     (is (str/includes? @asked "Tool:   get_docs"))
                     (is (str/includes? @asked "library: react"))
                     (done))))))))

(deftest ask-rule-on-edit-previews-diff
  (testing "a guarded edit passes the change it would make to the confirm dialog
           as :diff, without touching the file"
    (async done
      (let [rule  {:match {:tool :edit} :action {:type :ask}}
            state (state-with [:ext :rules :rules] rule)
            path  "/tmp/xi-rules-diff-preview-missing.txt"
            tc    {:name "edit"
                   :arguments {:path path :edits [{:oldText "" :newText "hello"}]}}
            opts  (atom nil)
            c     (assoc (ctx state)
                         :confirm! (fn [_ o] (reset! opts o)
                                     (js/Promise.resolve false)))]
        (-> (rules-ext/tool-policy tc c)
            (.then (fn [_]
                     (is (= path (get-in @opts [:diff :path])))
                     (is (str/includes? (get-in @opts [:diff :text]) "+ hello"))
                     (done))))))))

(deftest ask-rule-on-doomed-edit-fails-without-asking
  (testing "an edit whose oldText isn't in the file errors out right away — the
           user is never asked to approve a call that can only fail"
    (async done
      (let [rule   {:match {:tool :edit} :action {:type :ask}}
            state  (state-with [:ext :rules :rules] rule)
            path   "/tmp/xi-rules-doomed-edit.txt"
            _      (fs/writeFileSync path "actual content" "utf8")
            tc     {:name "edit"
                    :arguments {:path path :edits [{:oldText "missing" :newText "x"}]}}
            asked? (atom false)
            c      (assoc (ctx state)
                          :confirm! (fn [_ _] (reset! asked? true)
                                      (js/Promise.resolve true)))]
        (-> (rules-ext/tool-policy tc c)
            (.then (fn [out]
                     (is (false? @asked?))
                     (is (true? (get-in out [:result :is-error])))
                     (is (str/includes? (get-in out [:result :content 0 :text])
                                        "Could not find the exact text"))
                     (done))))))))

(deftest mcp-default-rule-always-trusts-the-server
  (async done
    (let [state      (state-with [:ext :rules :rules] {:match {:tool :read}
                                                       :action {:type :allow}})
          tc         {:name "mcp__render__list_services" :arguments {}}
          dispatched (atom nil)
          c          (assoc (ctx state)
                            ;; the test registry has no servers, so render is
                            ;; untrusted and the call asks
                            :confirm!  (fn [_ _] (js/Promise.resolve :trust-mcp))
                            :dispatch! (fn [ev] (reset! dispatched ev)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out) "approved")
                   (is (= :mcp/trust (:type @dispatched)))
                   (is (= "render" (:mcp-server @dispatched))
                       "[a]lways trusts the server of this call")
                   (done)))))))

(deftest subagent-spawn-asks-with-task-and-always-pins-the-tool
  (async done
    (let [state      (state-with [:ext :rules :rules] {:match {:tool :read}
                                                       :action {:type :allow}})
          tc         {:name "spawn_subagent"
                      :arguments {:task "review the diff" :label "review"}}
          asked      (atom nil)
          dispatched (atom nil)
          c          (assoc (ctx state)
                            :confirm!  (fn [msg _] (reset! asked msg)
                                         (js/Promise.resolve :always))
                            :dispatch! (fn [ev] (reset! dispatched ev)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out))
                   (is (str/includes? @asked "Tool: spawn_subagent"))
                   (is (str/includes? @asked "task: review the diff"))
                   (is (= {:tool :other :tool-name "spawn_subagent"}
                          (get-in @dispatched [:rule :match]))
                       ":always must not allow every :other tool")
                   (done)))))))

(deftest add-rule-handler-prepends-at-scope
  (testing "session scope goes room-scoped, server scope process-local"
    (let [st0 {:rooms {"r1" {:ext {:rules {:rules [{:existing true}]}}}}
               :ext   {:rules {:rules []}}}
          out (rules-ext/add-rule st0 {:room-id "r1" :scope :session
                                       :rule {:new true}})]
      (is (= [{:new true} {:existing true}]
             (get-in out [:state :rooms "r1" :ext :rules :rules]))))
    (let [st0 {:rooms {"r1" {}} :ext {:rules {:rules []}}}
          out (rules-ext/add-rule st0 {:room-id "r1" :scope :server
                                       :rule {:s true}})]
      (is (= [{:s true}] (get-in out [:state :ext :rules :rules]))))))

(deftest clear-session-rules-leaves-server-rules
  (let [st0 {:rooms {"r1" {:ext {:rules {:rules [{:a 1}]}}}}
             :ext   {:rules {:rules [{:s true}]}}}
        out (:state (rules-ext/clear-session-rules st0 {:room-id "r1"}))]
    (is (= [] (get-in out [:rooms "r1" :ext :rules :rules])))
    (is (= [{:s true}] (get-in out [:ext :rules :rules])))))
