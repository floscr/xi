(ns xi.web.palette-items
  "Dynamic Cmd+K palette entries user extensions' web halves register
   (`:palette-items` of a web half, see xi.web.user-ext.guard). The palette's
   Navigate group asks `items` on every render, so entries follow the app
   state (e.g. one row per stored account).

   A provider is (fn [state] → [{:label str :icon kw :event {…}} …]); the guard
   has already confined its events and isolated its throws.

   The registry lives outside the app state because its values are fns (the
   state is persisted and synced); it is filled once per page load, when the
   web halves are evaluated.")

(defonce ^:private registry (atom {}))

(defn register!
  "Set extension `ext-id`'s palette-item provider `f`."
  [ext-id f]
  (swap! registry assoc ext-id f))

(defn items
  "Every registered provider's entries for `state`, in registration order."
  [state]
  (into [] (mapcat (fn [[_ f]] (f state))) @registry))
