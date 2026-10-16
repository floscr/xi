;; Demo extension bundled with xi — enable with config.edn
;; `:demo-extensions ["deploys.cljs"]`. Adds an agent block kind,
;; `deploys/board` (docs/guide/agent-blocks.md): environments with their
;; version and health, a Promote button per environment and Roll back, which
;; posts the click to the agent. The agent shows one with ui_block; /deploys
;; posts a sample board itself through xi.api.blocks. The web half
;; (deploys/web.cljs) draws it.
(ns deploys
  (:require [clojure.string :as str]
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

(defn- show-sample [ctx {:keys [room-id]}]
  (blocks/show! ctx {:kind "board" :title "Deploys" :data sample :room-id room-id}))

(def extension
  {:id          :deploys
   :block-kinds {"board" {:describe "{envs: [{name, version, status: healthy|degraded|down}]} — environments; the user can promote one or roll back"
                          :actions ["promote"]
                          :send    ["rollback"]
                          :text    board-text}}
   :fx          {:ext.deploys/show show-sample}
   :commands    [{:name        "deploys"
                  :description "Post a sample deploy board"
                  :handler     (fn [_ {:keys [room-id]}]
                                 {:effects [[:ext.deploys/show {:room-id room-id}]]})}]})
