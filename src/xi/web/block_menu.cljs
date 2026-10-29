(ns xi.web.block-menu
  "Extension entries of the code-block menu (right-click / tap on a code
   block, xi.web.views/code-copy-menu). A provider is an extension's
   `:block-menu-items`: (fn [block state] → [{:label str :icon kw :event {…}}
   …]), asked each time the menu opens, where `block` is {:text :path :line
   :lang :markdown :diff-path} — :path when the block belongs to a tool call
   or diff naming a file (:line for a diff line), :lang for a fenced code
   block, :markdown :block / :inline for a message's fenced block / inline
   code (not a tool block's output). Its
   entries show below the core ones; one with `:header? true` is also a
   button in a file's header (diff, file buffer), asked with {:path}
   (xi.web.views/file-header-actions).

   The registry holds fns, so it lives outside the app state (like
   xi.web.palette-items); built-in web halves register at init, user web
   halves when they load (their providers are guarded, xi.web.user-ext.guard).")

(defonce ^:private registry (atom {}))

(defn register!
  [ext-id f]
  (swap! registry assoc ext-id f))

(defn items
  "Every provider's entries for `block`, in registration order; a provider
   that throws adds none."
  [block state]
  (into []
        (comp (mapcat (fn [[_ f]] (try (f block state) (catch :default _ nil))))
              (filter #(and (string? (:label %)) (map? (:event %)))))
        @registry))
