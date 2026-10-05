(ns xi.ext.rules-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.rules :as rules-ext]))

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

(deftest policy-fails-closed
  (testing "a throw while deciding denies the call instead of letting it run"
    (async done
      (-> (rules-ext/tool-policy {:name "bash" :arguments {:command "ls"}}
                                 (assoc (ctx {}) :get-state (fn [] (throw (js/Error. "boom")))))
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

;; /tmp has no trusted bb.edn, so a bb call there hits the bb-trust rule.

(deftest untrusted-bb-asks-and-always-trusts-via-the-option-event
  (async done
    (let [tc         {:name "bb" :arguments {:task "test"}}
          asked      (atom nil)
          dispatched (atom [])
          c          (assoc (ctx {})
                            :confirm!  (fn [msg opts] (reset! asked [msg opts])
                                         (js/Promise.resolve :trust-bb))
                            :dispatch! (fn [ev] (swap! dispatched conj ev)))]
      (-> (rules-ext/tool-policy tc c)
          (.then (fn [out]
                   (is (= tc out) "the call runs")
                   (is (re-find #"not trusted" (first @asked)))
                   (is (re-find #"bb test" (first @asked)))
                   (is (some #(= :trust-bb (:value %)) (:options (second @asked))))
                   (is (= [{:type :ext.clj/trust-bb :room-id "r1" :cwd "/tmp"}] @dispatched)
                       "trusts bb.edn instead of saving a session rule")
                   (done)))))))

(deftest untrusted-bb-is-refused-when-nobody-can-answer
  (async done
    (-> (rules-ext/tool-policy {:name "bb" :arguments {:task "test"}} (ctx {}))
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
