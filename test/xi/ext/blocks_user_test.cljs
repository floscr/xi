(ns xi.ext.blocks-user-test
  "Block kinds from a user extension, end to end in one process: the sandbox
   loads the file, the guard names the kind <id>/<name>, the manager composes
   it, ui_block lists and accepts it, and the extension posts blocks of its own
   through xi.api.blocks."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.api.core :as api-core]
            [xi.ext.blocks :as blocks]
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
      :tool-definitions [{:name \"deploy_report\" :description \"post the board\"
                          :input_schema {:type \"object\" :properties {}}}]
      :tool-registry {\"deploy_report\"
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
