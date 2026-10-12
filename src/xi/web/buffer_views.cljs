(ns xi.web.buffer-views
  "Buffer kinds a web extension renders (xi.buffers): an extension's
   `:buffer-views` is `{kind {:view (fn [state dispatch! room id buffer] →
   hiccup) :icon kw}}`. The chat view (xi.web.views) shows a buffer of a kind
   it has no built-in view for through this registry, below the chat topbar
   and its view switch; `:icon` marks the kind in the switch and the sidebar.

   The registry holds fns, so it lives outside the app state (like
   xi.web.block-menu); built-in web halves register at init.")

(defonce ^:private registry (atom {}))

(defn register!
  "Add extension `ext-id`'s `:buffer-views` (a kind → {:view :icon} map)."
  [_ext-id views]
  (swap! registry merge views))

(defn view
  "The view fn registered for buffer `kind`, or nil."
  [kind]
  (get-in @registry [kind :view]))

(defn icon
  "The icon registered for buffer `kind`, or nil."
  [kind]
  (get-in @registry [kind :icon]))
