(ns xi.ext.blocks-user-test
  "Block kinds from a user extension, end to end in one process: the sandbox
   loads the file, the guard names the kind <id>/<name>, the manager composes
   it, ui_block lists and accepts it, and the extension posts blocks of its own
   through xi.api.blocks."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.api.core :as api-core]
            [xi.ext.blocks :as blocks]
            [xi.ext.blocks.app :as app]
            [xi.ext.blocks.handlers :as h]
            [xi.ext.manager :as manager]
            [xi.ext.user :as user]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private source
  "(ns deploys (:require [xi.api.blocks :as blocks]))
   (def extension
     {:id :deploys
      :block-kinds {\"board\" {:describe \"environments and their state\"
                              :actions [\"approve\"] :send [\"ship\"]
                              :text (fn [spec _] (str \"envs: \" (count (:envs spec))))}
                    \"Bad Name\" {:describe \"dropped\"}}
      :ui-resources {\"ui://deploys/map\" \"<p>map</p>\"
                     \"ui://other/stolen\" \"<p>no</p>\"}
      :tool-definitions [{:name \"deploy_report\" :description \"post the board\"
                          :input_schema {:type \"object\" :properties {}}}
                         {:name \"deploy_map\" :description \"the map\"
                          :input_schema {:type \"object\" :properties {}}
                          :ui {:resource-uri \"ui://deploys/map\"}}
                         {:name \"deploy_status\" :description \"status\"
                          :input_schema {:type \"object\" :properties {}}
                          :ui {:visibility [\"app\"]}}]
      :tool-registry {\"deploy_map\" (fn [_ _] {:content [{:type \"text\" :text \"1 env\"}]
                                                 :structured-content {:envs [\"a\"]}})
                      \"deploy_status\" (fn [_ _] {:content [{:type \"text\" :text \"ok\"}]
                                                    :structured-content {:envs [\"a\" \"b\"]}})
                      \"deploy_report\"
                      (fn [_ ctx]
                        (let [id (blocks/show! ctx {:kind \"board\" :title \"Deploys\" :data {:envs [\"staging\"]}})]
                          (blocks/update! ctx id {:data {:envs [\"staging\" \"prod\"]}})
                          {:content [{:type \"text\" :text id}]}))}})")

(defn- load-ext []
  (let [dir (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-blocks-ext-"))
        mgr (manager/create)]
    (fs/writeFileSync (node-path/join dir "deploys.cljs") source)
    (manager/seed! mgr [])
    (doseq [{:keys [extension error]} (user/load-dir dir)]
      (is (nil? error))
      (when extension (manager/register! mgr extension)))
    mgr))

(defn- app []
  (let [st (atom {:rooms {"r" {:id "r" :history [] :ext {:blocks {:blocks {}}}}}})]
    {:st st
     :dispatch! (fn [ev]
                  (when-let [f (get h/handlers (:type ev))]
                    (when-let [{s :state} (f @st ev)] (reset! st s))))
     :get-state (fn [] @st)}))

(deftest a-user-extension-kind
  (let [mgr  (load-ext)
        ext  (blocks/create {:manager mgr})
        {:keys [st dispatch! get-state]} (app)
        ctx  {:room-id "r" :dispatch! dispatch! :get-state get-state}]
    (testing "the kind is namespaced and composed; a malformed name is dropped"
      (is (= ["deploys/board"] (keys (:block-kinds (manager/composed mgr))))))
    (testing "ui_block lists it, re-read per turn"
      (let [d ((:resolve (first (:tool-definitions ext))))]
        (is (str/includes? (:description d) "- deploys/board: environments and their state"))
        (is (some #{"deploys/board"} (get-in d [:input_schema :properties :kind :enum])))))
    (testing "the model can show one"
      (let [r ((get (:tool-registry ext) "ui_block") {:kind "deploys/board" :id "b" :data {:envs ["a"]}} ctx)
            b (get-in @st [:rooms "r" :ext :blocks :blocks "b"])]
        (is (not (:is-error r)) (pr-str r))
        (is (= {:actions ["approve"] :send ["ship"]} (:rules b)))
        (is (= "envs: 1" (:text-form b)))))
    (testing "the extension posts and updates its own block through xi.api.blocks"
      (api-core/set-app-host! {:dispatch! dispatch! :get-state get-state})
      (api-core/set-block-kinds-host! #(:block-kinds (manager/composed mgr)))
      (try
        (let [tool (get (:tool-registry (manager/composed mgr)) "deploy_report")
              id   (get-in (tool {} ctx) [:content 0 :text])
              b    (get-in @st [:rooms "r" :ext :blocks :blocks id])]
          (is (str/starts-with? id "deploys-") id)
          (is (= "deploys/board" (:kind b)))
          (is (= {:type :ext :id :deploys} (:source b)))
          (is (= "envs: 2" (:text-form b)) "updated"))
        (finally
          (api-core/set-app-host! nil)
          (api-core/set-block-kinds-host! nil))))))

(deftest a-user-extension-app-view
  (async done
    (let [mgr    (load-ext)
          {:keys [st dispatch! get-state]} (app)
          opened (atom nil)
          sent   (atom [])
          ctx    {:room-id "r" :get-state get-state :call {:name "deploy_map" :arguments {}}
                  :dispatch! (fn [ev] (if (= :ext.blocks/app-open (:type ev)) (reset! opened ev) (dispatch! ev)))}
          fx     ((app/server-fx mgr nil) {:send! (fn [cid ev] (swap! sent conj [cid ev]))})]
      (is (= ["ui://deploys/map"] (keys (:ui-resources (manager/composed mgr))))
          "a resource outside ui://<id>/ is dropped")
      (-> (js/Promise.resolve ((get (:tool-registry (manager/composed mgr)) "deploy_map") {} ctx))
          (.then (fn [_] (js/Promise. (fn [res] (js/setTimeout res 0)))))
          (.then (fn [_]
                   (is (= {:type :ext :id :deploys} (:source @opened)) "the tool asked for its view")
                   (is (= {:envs ["a"]} (get-in @opened [:tool-result :structured-content])))
                   (app/open-fx mgr {:dispatch! dispatch! :get-state get-state} (dissoc @opened :type))))
          (.then (fn [_]
                   (let [b (first (vals (get-in @st [:rooms "r" :ext :blocks :blocks])))]
                     (is (= "app" (:kind b)))
                     (is (= "ui://deploys/map" (get-in b [:spec :resource-uri])))
                     ((:ext.blocks/app-load-reply fx) {:get-state get-state}
                      {:client-id "c1" :room-id "r" :block-id (:id b)})
                     ((:ext.blocks/app-rpc-reply fx) {:get-state get-state :dispatch! dispatch!}
                      {:client-id "c1" :room-id "r" :block-id (:id b) :rpc-id "1"
                       :method "tools/call" :params {:name "deploy_status" :arguments {}}})
                     ((:ext.blocks/app-rpc-reply fx) {:get-state get-state :dispatch! dispatch!}
                      {:client-id "c1" :room-id "r" :block-id (:id b) :rpc-id "2"
                       :method "tools/call" :params {:name "deploy_report_nope" :arguments {}}})
                     (js/Promise. (fn [res] (js/setTimeout #(res b) 50))))))
          (.then (fn [b]
                   (let [by (into {} (map (fn [[_ ev]] [(or (:rpc-id ev) (:type ev)) ev])) @sent)]
                     (is (= "<p>map</p>" (:html (get by :ext.blocks/app-html))))
                     (is (= {:envs ["a" "b"]} (get-in by ["1" :result :structuredContent]))
                         "the view called its app-only tool")
                     (is (= -32602 (get-in by ["2" :error :code])) "not one of its tools")
                     (is (= "tools/call" (:action (last (get-in @st [:rooms "r" :ext :blocks :blocks (:id b) :actions]))))
                         "noted for the agent"))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))
