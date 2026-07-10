(ns xi.tools.registry
  "Tool registry — maps tool names to definitions and execute fns."
  (:require [xi.tools.read :as read]
            [xi.tools.write :as write]
            [xi.tools.edit :as edit]
            [xi.tools.bash :as bash]
            [xi.tools.grep :as grep]
            [xi.tools.find :as find]
            [xi.tools.ls :as ls]
            [xi.tools.view :as view]))

(def ^:private builtin-tools
  [{:def read/definition  :exec read/execute}
   {:def write/definition :exec write/execute}
   {:def edit/definition  :exec edit/execute}
   {:def bash/definition  :exec bash/execute}
   {:def grep/definition  :exec grep/execute}
   {:def find/definition  :exec find/execute}
   {:def ls/definition    :exec ls/execute}
   {:def view/definition  :exec view/execute}])

(defn tool-definitions
  "Return vec of tool definitions in Anthropic API format for the tools parameter."
  []
  (mapv :def builtin-tools))

(defn tool-registry
  "Return map of tool-name → execute fn."
  []
  (into {} (map (fn [{:keys [def exec]}] [(:name def) exec]) builtin-tools)))
