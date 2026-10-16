;; Demo extension bundled with xi — enable with config.edn
;; `:demo-extensions ["deploys.cljs"]`. Adds an agent block kind,
;; `deploys/board` (docs/guide/agent-blocks.md): environments with their
;; version and health, a Promote button per environment and Roll back, which
;; posts the click to the agent. The agent shows one with ui_block; /deploys
;; posts a sample board itself through xi.api.blocks. The web half
;; (deploys/web.cljs) draws it.
;;
;; It also has an MCP Apps view (deploys/map.cljs): the deploy_map tool shows
;; it, and the view refreshes through deploy_status, a tool only the view can
;; call.
(ns deploys
  (:require [clojure.string :as str]
            [deploys.map :as view]
            [xi.api.blocks :as blocks]))

(defn- board-text [{:keys [envs]} state]
  (str (str/join "\n" (for [{:keys [name version status]} envs]
                        (str name "  " version "  " status)))
       (when-let [{:keys [action target value]} (:last state)]
         (str "\nlast: " action " " (or target value)))))

(def ^:private sample
  {:envs [{:name "dev" :version "1.5.0-rc.2" :status "healthy"}
          {:name "staging" :version "1.4.2" :status "healthy"}
          {:name "prod" :version "1.4.1" :status "degraded"}]})

(defn- status-result [envs]
  {:content            [{:type "text"
                         :text (str/join "\n" (for [{:keys [name version status]} envs]
                                                 (str name " " version " " status)))}]
   :structured-content {:envs envs}})

(defn- show-sample [ctx {:keys [room-id]}]
  (blocks/show! ctx {:kind "board" :title "Deploys" :data sample :room-id room-id}))

(def extension
  {:id          :deploys
   :block-kinds {"board" {:describe "{envs: [{name, version, status: healthy|degraded|down}]} — environments; the user can promote one or roll back"
                          :actions ["promote"]
                          :send    ["rollback"]
                          :text    board-text}}
   :ui-resources {"ui://deploys/map" {:html view/html}}
   :tool-definitions [{:name "deploy_map"
                       :description "Show the deploy environments as an interactive view the user can click through."
                       :input_schema {:type "object" :properties {}}
                       :ui {:resource-uri "ui://deploys/map"}}
                      {:name "deploy_status"
                       :description "The environments' current versions and health."
                       :input_schema {:type "object" :properties {}}
                       :ui {:visibility ["app"]}}]
   :tool-registry {"deploy_map"    (fn [_ _] (status-result (:envs sample)))
                   "deploy_status" (fn [_ _] (status-result (update-in (:envs sample) [2 :status]
                                                                        (constantly "healthy"))))}
   :fx          {:ext.deploys/show show-sample}
   :commands    [{:name        "deploys"
                  :description "Post a sample deploy board"
                  :handler     (fn [_ {:keys [room-id]}]
                                 {:effects [[:ext.deploys/show {:room-id room-id}]]})}]})
