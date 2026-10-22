(ns xi.providers.process-test
  (:require [cljs.test :refer [async deftest is testing]]
            [xi.providers.locks :as locks]
            [xi.providers.manifest :as manifest]
            [xi.providers.process :as process]))

;; ── Manifests ────────────────────────────────────────────────────────────────

(deftest normalize-a-package-json-manifest
  (let [{m :manifest} (manifest/normalize
                       {"id" "zen" "label" "OpenCode" "command" ["bun" "src/main.ts"]
                        "routes" {"prefixes" ["opencode/"] "default" false}
                        "capabilities" ["models" "login"]
                        "login" {"name" "opencode" "label" "OpenCode Go"}}
                       "/p/zen")]
    (is (= {:id :zen :label "OpenCode" :dir "/p/zen" :command ["bun" "src/main.ts"]
            :routes {:prefixes ["opencode/"] :ids [] :default? false}
            :side-turns? false :base-prompt? true :transcripts :host
            :capabilities #{:models :login}
            :login {:name "opencode" :label "OpenCode Go"}}
           m))))

(deftest normalize-an-edn-manifest
  (let [{m :manifest} (manifest/normalize
                       {:id :mine :command ["bb" "main.clj"] :routes {:default? true}
                        :side-turns? true :base-prompt? false :transcripts :self
                        :models ["a" "b"] :prompt-note "note"}
                       "/p/mine")]
    (is (= :mine (:id m)))
    (is (= "mine" (:label m)) "the label defaults to the id")
    (is (true? (get-in m [:routes :default?])))
    (is (= [true false :self ["a" "b"] "note"]
           ((juxt :side-turns? :base-prompt? :transcripts :models :prompt-note) m)))))

(deftest invalid-manifests
  (is (re-find #"no :id" (:error (manifest/normalize {"command" ["x"]} "/d"))))
  (is (re-find #"no :command" (:error (manifest/normalize {"id" "x"} "/d"))))
  (is (re-find #":login needs a :name" (:error (manifest/normalize {"id" "x" "command" ["x"] "login" {}} "/d")))))

(deftest spec-is-what-clients-need
  (is (= {:id :zen :label "Z" :routes {:prefixes ["z/"] :ids [] :default? false}
          :side-turns? false :login-name "opencode"}
         (manifest/spec {:id :zen :label "Z" :dir "/secret" :command ["bun"]
                         :routes {:prefixes ["z/"] :ids [] :default? false}
                         :side-turns? false :login {:name "opencode" :label "Go"}}))))

;; ── Requests and results ─────────────────────────────────────────────────────

(def ^:private host-m
  {:id :ollama :label "Ollama" :dir "/p" :command ["bun" "x"] :transcripts :host
   :base-prompt? false :routes {}})

(deftest options-become-camel-case
  (is (= {"baseUrl" "http://gpu" "verbose" true "x" 1}
         (process/camel-keys {:base-url "http://gpu" :verbose? true "x" 1}))))

(deftest a-turn-request
  (let [req (process/turn-request
             host-m {:base-url "u"} [{:name "ls"}]
             {:model "m" :prompt "p" :cwd "/tmp" :system "sys" :room-id :r1
              :images [{:media-type "image/png" :data "AAA" :path "/x.png"}]
              :history [{:kind :user :text "hi"} {:kind :text :text "hello"}]
              :resume-session-id "s1" :reasoning-effort "high"})]
    (is (= {:type "turn" :protocol 1 :providerId "ollama" :options {"baseUrl" "u"}
            :model "m" :prompt "p" :cwd "/tmp" :system "sys" :roomId "r1"
            :tools [{:name "ls"}] :images [{:mediaType "image/png" :data "AAA"}]
            :history [{:role "user" :text "hi"} {:role "assistant" :text "hello"}]
            :reasoningEffort "high"}
           (dissoc req :dataDir)))
    (is (not (contains? req :sessionId)) "a sessionless provider replays history instead")))

(deftest data-less-attachments-stay-out-of-the-request
  (let [turn #(process/turn-request host-m {} [] {:model "m" :prompt "p" :cwd "/tmp" :images %})]
    (is (= [{:mediaType "image/png" :data "AAA"}]
           (:images (turn [{:media-type "image/png" :data "AAA"} {:name "a.md" :path "/u/a.md"}]))))
    (is (not (contains? (turn [{:name "a.md" :path "/u/a.md"}]) :images)))))

(deftest a-provider-with-its-own-session
  (let [req (process/turn-request (assoc host-m :transcripts :self) {} []
                                  {:model "m" :prompt "p" :cwd "/tmp" :resume-session-id "s1"
                                   :history [{:kind :user :text "hi"}]})]
    (is (= "s1" (:sessionId req)))
    (is (not (contains? req :history)))))

(deftest a-side-turn-request
  (let [req (process/turn-request host-m {} [{:name "ls"}]
                                  {:model "m" :prompt "p" :cwd "/tmp" :no-tools? true :no-thinking? true
                                   :env {"CLAUDE_CONFIG_DIR" "/tmp/c"}})]
    (is (= [] (:tools req)))
    (is (= [true true {"CLAUDE_CONFIG_DIR" "/tmp/c"}] ((juxt :noTools :noThinking :env) req)))))

(deftest done-frames-become-results
  (is (= {:usage {:input_tokens 3} :stop-reason "stop" :aborted false :is-error false :done true
          :cost 0.1 :session-id "s" :result-text "ok" :model "m"}
         (process/done->result {:usage {:input_tokens 3} :stopReason "stop" :cost 0.1
                                :sessionId "s" :resultText "ok" :model "m" :aborted false})))
  (is (:resume-failed (process/done->result {:failure "resume-failed"})))
  (is (:fatal-error (process/done->result {:failure "fatal"}))))

(deftest readings-become-kebab-case
  (is (= {:id "codex/me" :provider :codex :title "me"
          :windows [{:id "w" :used 3 :resets-at "t" :period-ms 5}]
          :badges [{:label "Limit reached" :tone :danger}]
          :fetched-at 9}
         (process/reading {:id "codex/me" :provider "codex" :title "me"
                           :windows [{:id "w" :used 3 :resetsAt "t" :periodMs 5}]
                           :badges [{:label "Limit reached" :tone "danger"}]
                           :fetchedAt 9}))))

(deftest a-blocked-or-unknown-tool
  (async done
    (-> (js/Promise.all
         #js [(process/run-call {:name "ls" :arguments {}}
                                {:registry {"ls" (fn [_ _] {:content [{:type "text" :text "ran"}]})}
                                 :tool-policy (fn [_] (js/Promise.resolve nil))})
              (process/run-call {:name "nope" :arguments {}} {:registry {}})
              (process/run-call {:name "ls" :arguments {}}
                                {:registry {"ls" (fn [_ _] {:content [{:type "text" :text "ran"}]})}})])
        (.then (fn [[blocked unknown ran]]
                 (is (= {:content [{:type "text" :text "Blocked by Xi permission gate"}] :is-error true} blocked))
                 (is (= {:content [{:type "text" :text "Unknown tool: nope"}] :is-error true} unknown))
                 (is (= {:content [{:type "text" :text "ran"}] :is-error false} ran))
                 (done))))))

;; ── Locks ────────────────────────────────────────────────────────────────────

(deftest locks-serve-waiters-in-order
  (async done
    (let [a #js {} b #js {} c #js {} order (atom [])]
      (-> (locks/acquire! "t/refresh" a)
          (.then (fn [_]
                   (swap! order conj :a)
                   (.then (locks/acquire! "t/refresh" b) (fn [_] (swap! order conj :b)))
                   (.then (locks/acquire! "t/refresh" c) (fn [_] (swap! order conj :c)))
                   (is (= [:a] @order) "b and c wait")
                   (locks/release! "t/refresh" a)
                   (js/Promise.resolve)))
          (.then (fn [_] (js/Promise.resolve)))
          (.then (fn [_]
                   (is (= [:a :b] @order))
                   ;; c's process dies while b still holds it: c's wait is gone too
                   (locks/release-all! c)
                   (locks/release-all! b)
                   (is (nil? (get (locks/held) "t/refresh")))
                   (done)))))))
