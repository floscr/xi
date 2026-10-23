(ns xi.web.palette-items
  "Cmd+K palette entries and drill-down menus that user extensions' web halves
   register (`:palette-items` and `:palette-menus`, see
   xi.web.user-ext.guard).

   A provider is (fn [state] → [row …]); the palette's Navigate group asks
   `items` on every render, so entries follow the app state (e.g. one row per
   stored account). A menu is {:title :placeholder :items provider}, opened
   as a palette sub-page by :palette/open-menu (xi.web.core) or the key
   action :<ext>/<menu> (xi.web.user-ext). The guard has already confined the
   rows: each has a :label and one of :event, :children (rows, drilled into
   as a sub-page) or :menu ({:ext :name}, a named menu of its extension), and
   their throws are isolated.

   The registry lives outside the app state because its values are fns (the
   state is persisted and synced); it is filled once per page load, when the
   web halves are evaluated.")

(defonce ^:private registry (atom {}))
(defonce ^:private menus (atom {}))

(defn register!
  "Set extension `ext-id`'s palette-item provider `f`."
  [ext-id f]
  (swap! registry assoc ext-id f))

(defn items
  "Every registered provider's entries for `state`, in registration order."
  [state]
  (into [] (mapcat (fn [[_ f]] (f state))) @registry))

(defn register-menus!
  "Set extension `ext-id`'s menus, name (string) → {:title :placeholder :items}."
  [ext-id named]
  (swap! menus assoc ext-id named))

(defn menu-page
  "A palette page of kind :ext-menu → {:title :placeholder :rows}: a named
   menu's rows computed for `state`, or the rows a drill stored in the page.
   nil for a menu that isn't registered (yet: the web halves load after
   connecting)."
  [state {:keys [ext menu title placeholder rows]}]
  (if menu
    (when-let [{:keys [items] :as m} (get-in @menus [ext menu])]
      {:title       (or (:title m) menu)
       :placeholder (:placeholder m)
       :rows        (items state)})
    {:title title :placeholder placeholder :rows rows}))
