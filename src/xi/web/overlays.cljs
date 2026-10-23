(ns xi.web.overlays
  "Floating views a web extension draws over every page: an extension's
   `:overlays` is `{kw (fn [state dispatch!] → hiccup | nil)}`. The root view
   (xi.web.views/root-view) renders each one after the dialogs, whatever the
   route; a view returns nil to show nothing. For state that outlives a page,
   like a camera the phone keeps sharing while it shows the chat.

   The registry holds fns, so it lives outside the app state (like
   xi.web.buffer-views); built-in web halves register at init.")

(defonce ^:private registry (atom {}))

(defn register!
  "Add extension `ext-id`'s `:overlays` (a kw → view fn map)."
  [_ext-id views]
  (swap! registry merge views))

(defn views
  "Every registered overlay's hiccup for this render, keyed for Replicant;
   nils left out."
  [state dispatch!]
  (keep (fn [[k f]]
          (when-let [node (f state dispatch!)]
            [:div {:replicant/key k} node]))
        @registry))
