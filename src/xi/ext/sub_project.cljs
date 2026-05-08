(ns xi.ext.sub-project
  "Sub-project extension — reads Babashka profile EDN to find focus context."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private PROFILE_PATH
  (.join node-path (aget js/process.env "HOME") ".config" "babashka" "profiles.edn"))

(defn- load-sub-projects
  "Load sub-project definitions from Babashka profiles.edn."
  []
  ;; Sub-project loading from EDN would require an EDN parser.
  ;; For now, return empty — this extension is a stub for future porting.
  [])

(def extension
  {:name "sub-project"
   :hooks {}})
