(ns xi.ext.favorites.web
  "Favorites in the web client: the sessions a user starred. Which sessions
   those are is per user and lives in core (xi.user-state :favorites, the
   `:favorites/toggle` event, the `:favorite?` flag the server puts on every
   session of the lobby); this extension is what puts them on screen:

   - a **Favorites** group in the drawer sidebar — the five most recently
     visited favorites, closed by an \"All favorites\" row once there are more;
   - **Add to favorites / Remove from favorites** in every session card's
     context menu;
   - a **Favorites** entry in the ⋮ menu, opening the full list.

   Leave it out of `xi.config/web` and none of the three appears (the
   Favorites view itself, and starring from the chat topbar and the command
   palette, are core)."
  )

(def sidebar-group-limit
  "How many favorites the sidebar group lists; the rest are in the view."
  5)

(def ^:private open-view
  "Route to the full Favorites list."
  {:type :route/navigate :page :home :dir :favorites})

(defn- open
  "Handler of `:favorites/open`, the event of the ⋮ menu entry: the overflow
   menu merges its own ctx (:cwd, :room-id) into a nav item's event, which
   would clobber the route, so the entry goes through an event of its own."
  [_st _ev]
  {:effects [[:app/dispatch open-view]]})

(def extension
  {:id       :favorites
   :handlers {:favorites/open open}
   :sidebar-groups
   [{:id    :favorites
     :label "Favorites"
     :where :favorite?
     :limit sidebar-group-limit
     :more  {:label "All favorites" :icon :star :event open-view}}]
   :session-menu-items
   [{:label    "Add to favorites"
     :label-on "Remove from favorites"
     :flag     :favorite?
     :icon     :star
     :event    {:type :favorites/toggle}}]
   :nav-items
   [{:menu :overflow :label "Favorites" :icon :star
     :event {:type :favorites/open}}]})
