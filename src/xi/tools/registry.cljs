(ns xi.tools.registry
  "Tool registry — maps tool names to definitions and execute fns."
  (:require [xi.util :as util]
            [xi.image :as image]
            [xi.tools.read :as read]
            [xi.tools.write :as write]
            [xi.tools.edit :as edit]
            [xi.tools.bash :as bash]
            [xi.tools.grep :as grep]
            [xi.tools.find :as find]
            [xi.tools.ls :as ls]
            [xi.tools.sleep :as sleep]
            [xi.tools.view :as view]))

(def ^:private builtin-tools
  [{:def read/definition  :exec read/execute}
   {:def write/definition :exec write/execute}
   {:def edit/definition  :exec edit/execute}
   {:def bash/definition  :exec bash/execute}
   {:def grep/definition  :exec grep/execute}
   {:def find/definition  :exec find/execute}
   {:def ls/definition    :exec ls/execute}
   {:def sleep/definition :exec sleep/execute}
   {:def view/definition  :exec view/execute}])

(defn tool-definitions
  "Return vec of tool definitions in Anthropic API format for the tools parameter."
  []
  (mapv :def builtin-tools))

(defn tool-registry
  "Return map of tool-name → execute fn."
  []
  (into {} (map (fn [{:keys [def exec]}] [(:name def) exec]) builtin-tools)))

(defn with-extensions
  "The builtin registry merged with the extensions' :tool-registry (a map or a
   0-arg fn returning one — the manager's live seam). Extension entries win, so
   an extension can override a builtin by name (treesitter's `read`)."
  [extra-registry]
  (merge (tool-registry)
         (if (fn? extra-registry) (extra-registry) extra-registry)))

(defn run-tool
  "Run a tool's `exec-fn` with `args`/`ctx` and normalize its result to a
   promise of {:content <blocks> :is-error bool}. Every tool result is capped
   via util/cap-tool-result-content here, so oversized-output trimming is the
   default for any provider that runs tools through this fn — providers never
   have to remember to cap. Image blocks are resized to fit API pixel limits
   (image/ensure-content-images-within-limits) so an oversized screenshot
   (e.g. chrome take_screenshot fullPage) can never poison the session with
   a permanently rejected message. Errors are turned into an error tool
   result."
  [exec-fn args ctx]
  ;; Invoke exec-fn inside .then so a synchronous throw becomes a rejected
  ;; promise too (not just an async rejection) — both land in .catch.
  (-> (js/Promise.resolve)
      (.then (fn [_] (exec-fn args ctx)))
      (.then (fn [result]
               {:content (-> (:content result)
                             (util/cap-tool-result-content)
                             (image/ensure-content-images-within-limits))
                :is-error (boolean (:is-error result))}))
      (.catch (fn [err]
                {:content [{:type "text" :text (str "Tool error: " (.-message err))}]
                 :is-error true}))))
