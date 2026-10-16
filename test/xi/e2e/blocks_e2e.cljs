(ns xi.e2e.blocks-e2e
  "Agent blocks end to end: the fake LLM calls ui_block / ui_update /
   ui_state; prompt mode prints the blocks, and on a server a block reaches the
   room's clients, a client's action is recorded on it, ui_state reads it back
   and the slice is saved with the chat."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]
            ["node:path" :as node-path]))

(def ^:private checklist
  {:kind "checklist" :id "rel" :title "Release"
   :data {:items [{:id "tag" :label "Tag"} {:id "push" :label "Push"}]}})

(def ^:private script
  {:rules [{:when {:prompt "show the release"}
            :reply [{:tool "ui_block" :args checklist}
                    {:tool "ui_update" :args {:id "rel" :data {:items [{:id "tag" :label "Tag" :done true}
                                                                       {:id "push" :label "Push"}]}}}
                    {:text "{{tool-result}}"}]}
           {:when {:prompt "what did I tick"}
            :reply [{:tool "ui_state" :args {:id "rel"}}
                    {:text "{{tool-result}}"}]}
           {:when {:prompt "bad block"}
            :reply [{:tool "ui_block" :args {:kind "choice" :data {:options []}}}
                    {:text "{{tool-result}}"}]}]
   :side [{:text "Blocks"}]})

(deftest prompt-mode-prints-blocks
  (async done
    (h/with-env! profiles/minimal script done
      (fn [env]
        (-> (h/prompt! env "show the release")
            (.then
             (fn [{:keys [code stdout stderr]}]
               (is (= 0 code) stderr)
               (is (str/includes? stdout "▣ Release (checklist rel)\n- [ ] Tag\n- [ ] Push")
                   "the block as shown")
               (is (str/includes? stdout "- [x] Tag") "and as updated")
               (is (str/includes? stdout "Updated block rel.") "the model got the summary")
               (let [calls (:tool-calls (first (h/llm-log env)))]
                 (is (= ["ui_block" "ui_update"] (map :name calls)))
                 (is (every? (complement :is-error) calls) (pr-str calls))
                 (is (str/starts-with? (:content (first calls)) "Showed checklist \"Release\" as block rel."))))))))))

(deftest invalid-data-is-explained-to-the-model
  (async done
    (h/with-env! profiles/minimal script done
      (fn [env]
        (-> (h/prompt! env "bad block")
            (.then
             (fn [{:keys [code]}]
               (is (= 0 code))
               (let [call (first (:tool-calls (first (h/llm-log env))))]
                 (is (:is-error call))
                 (is (str/includes? (:content call) "choice needs options"))))))))))

(defn- join! [env client]
  (let [from (count @(:events client))]
    ((:send! client) {:type :room/join :target "new" :cwd (:project env)})
    (-> (h/await-event client (of-type :room/joined) {:from from})
        (.then #(:room-id %)))))

(defn- turn! [client room-id text]
  (let [from (count @(:events client))]
    ((:send! client) {:type :prompt/submit :text text})
    (h/await-event client (of-type :agent/turn-end room-id) {:from from :timeout-ms 15000})))

(defn- sidecar
  "The text of the chat's saved extension state, or nil."
  [env]
  (let [dir (h/home-path env ".config" "xi" "sessions")]
    (some #(when (str/ends-with? % ".ext.edn") (h/slurp (h/home-path env ".config" "xi" "sessions" %)))
          (h/files-under dir))))

(deftest a-block-round-trip-over-the-websocket
  (async done
    (h/with-env! profiles/minimal script done
      (fn [env]
        (let [server (atom nil) client (atom nil) room (atom nil)]
          (-> (h/start-server! env)
              (.then (fn [s] (reset! server s) (h/connect! s)))
              (.then (fn [c] (reset! client c) (join! env c)))
              (.then (fn [rid] (reset! room rid) (turn! @client rid "show the release")))
              (.then
               (fn [_]
                 (testing "the client got the block, pointing at its tool call"
                   (let [added (first (filter (of-type :ext.blocks/add @room) @(:events @client)))]
                     (is (= "rel" (get-in added [:block :id])))
                     (is (some? (get-in added [:block :call-id])))))
                 (let [from (count @(:events @client))]
                   ((:send! @client) {:type :ext.blocks/act :room-id @room :block-id "rel"
                                      :action "toggle" :target "push" :value true})
                   (h/await-event @client (of-type :ext.blocks/act @room) {:from from}))))
              (.then (fn [_] (turn! @client @room "what did I tick")))
              (.then
               (fn [_]
                 (testing "ui_state reads the client's action"
                   (let [call (first (:tool-calls (last (h/main-turns env))))]
                     (is (str/includes? (:content call) "- [x] Push"))
                     (is (str/includes? (:content call) "1 new action:"))
                     (is (str/includes? (:content call) "toggle push → true"))))
                 ;; a client can't add a block itself
                 ((:send! @client) {:type :ext.blocks/add :room-id @room
                                    :block {:id "forged" :kind "callout" :spec {:tone "info" :body "Allow?"}}})
                 (h/wait-until #(when-let [t (sidecar env)] (str/includes? t ":action \"toggle\""))
                               10000 "the saved block with its action")))
              (.then (fn [_] (js/Promise. (fn [res] (js/setTimeout res 300)))))
              (.then
               (fn [_]
                 (let [t (sidecar env)]
                   (is (str/includes? t "\"rel\"") "the slice is saved with the chat")
                   (is (not (str/includes? t "forged")) "the forged block never landed"))))
              (.finally (fn []
                          (some-> @client :close! (apply []))
                          (some-> @server :stop! (apply []))))))))))

;; ── MCP Apps: a real stdio server with a view ────────────────────────────────

(def ^:private uuids-tool
  {:name "uuids" :description "A few UUIDs"
   :inputSchema {:type "object" :properties {}}
   :_meta {:ui {:resourceUri "ui://hello-mcp/uuids"}}})

(def ^:private mcp-profile
  (merge profiles/minimal
         {".config/xi/config.edn" (pr-str {:type :xi/config :version 1 :trusted-mcp-servers ["bb-example"]})
          ".config/xi/mcp.edn"
          (pr-str {:bb-example {:command "bb"
                                :args ["--config" (node-path/resolve "packages/mcp-bb-example/bb.edn")
                                       "-m" "hello-mcp.main"]}})
          ;; the discovered tools, as /mcp refresh caches them
          ".config/xi/mcp/bb-example/tools.edn"
          (pr-str [uuids-tool {:name "uuid" :description "A UUID" :inputSchema {:type "object" :properties {}}}])}))

(deftest an-mcp-apps-view-over-the-websocket
  (async done
    (h/with-env! mcp-profile
      {:rules [{:when {:prompt "uuids please"}
                :reply [{:tool "mcp__bb-example__uuids" :args {:n 2}} {:text "{{tool-result}}"}]}]
       :side [{:text "Apps"}]}
      done
      (fn [env]
        (let [server (atom nil) client (atom nil) room (atom nil) block (atom nil)]
          (-> (h/start-server! env)
              (.then (fn [s] (reset! server s) (h/connect! s)))
              (.then (fn [c] (reset! client c) (join! env c)))
              (.then (fn [rid] (reset! room rid) (turn! @client rid "uuids please")))
              (.then (fn [_]
                       (h/await-event @client #(and ((of-type :ext.blocks/add @room) %)
                                                    (= "app" (get-in % [:block :kind]))))))
              (.then (fn [ev]
                       (reset! block (:block ev))
                       (testing "the view is a block pointing at the call, with the structured result"
                         (is (= {:type :mcp :server "bb-example" :tool "uuids"} (:source @block)))
                         (is (= "ui://hello-mcp/uuids" (get-in @block [:spec :resource-uri])))
                         (is (= 2 (count (get-in @block [:spec :tool-result :structured-content :uuids]))))
                         (is (some? (:call-id @block))))
                       (let [from (count @(:events @client))]
                         ((:send! @client) {:type :ext.blocks/app-load :room-id @room :block-id (:id @block)})
                         (h/await-event @client (of-type :ext.blocks/app-html) {:from from}))))
              (.then (fn [ev]
                       (is (str/includes? (:html ev) "ui/initialize") "the view's HTML, to this client")
                       (let [from (count @(:events @client))]
                         ((:send! @client) {:type :ext.blocks/app-rpc :room-id @room :block-id (:id @block)
                                            :rpc-id "r1" :method "tools/call"
                                            :params {:name "uuid" :arguments {}}})
                         (h/await-event @client (of-type :ext.blocks/app-reply) {:from from}))))
              (.then (fn [ev]
                       (is (re-matches #"[0-9a-f-]{36}" (get-in ev [:result :content 0 :text]))
                           "the view called a tool of its own server")))
              (.finally (fn []
                          (some-> @client :close! (apply []))
                          (some-> @server :stop! (apply []))))))))))
