(ns xi.web.tool-views
  "Result views user extensions' web halves register for their own tools
   (`:tool-views` of a web half, see xi.web.user-ext.guard). A tool block
   (xi.web.views/tool-post) asks `render` first and falls back to the plain
   text result when there is no view or it returns nil.

   A view is (fn [call slice] → hiccup or nil): `call` is the finished tool
   call, `slice` the extension's own room slice ([:rooms <id> :ext <ext-id>]),
   so a view can show data its server half keeps there beyond the result text.

   The registry lives outside the app state because its values are fns (the
   state is persisted and synced); it is filled once per page load, when the
   web halves are evaluated.")

(defonce ^:private registry (atom {}))

(defn register!
  "Add extension `ext-id`'s `views` ({tool-name view-fn}) to the registry."
  [ext-id views]
  (swap! registry merge (update-vals views (fn [view] {:ext-id ext-id :view view}))))

(defn- keywordize [args]
  (if (map? args)
    (update-keys args #(if (string? %) (keyword %) %))
    args))

(defn render
  "The registered view of a finished tool call, or nil. `call` is
   {:tool name (MCP prefix stripped) :arguments map :text result-text
   :is-error bool}, argument keys reach the view as keywords; `room-ext` is
   the room's :ext map."
  [{:keys [tool] :as call} room-ext]
  (when-let [{:keys [ext-id view]} (get @registry tool)]
    (view (update call :arguments keywordize) (get room-ext ext-id))))
