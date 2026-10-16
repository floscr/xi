(ns xi.web.block-kinds
  "Renderers user extensions' web halves register for their own agent block
   kinds (`:block-kinds`, xi.web.user-ext.guard): \"<ext-id>/<name>\" →
   (fn [spec state] → hiccup or nil). The agent blocks' web half
   (xi.ext.blocks.web) asks `render` for a kind it has no view of, and runs
   the result through the hiccup allowlist (xi.blocks.hiccup) like an agent's
   tree, so its controls only record block actions.

   The registry holds fns, so it lives outside the app state (like
   xi.web.tool-views); it is filled when the web halves load.")

(defonce ^:private registry (atom {}))

(defn register!
  "Add a web half's (guarded, already namespaced) `kinds`."
  [_ext-id kinds]
  (swap! registry merge kinds))

(defn render
  "The registered renderer's hiccup for a block of `kind`, or nil."
  [kind spec state]
  (when-let [f (get @registry kind)]
    (f spec state)))

(defn version
  "Changes whenever a renderer is registered (a render cache keys on it)."
  []
  @registry)
