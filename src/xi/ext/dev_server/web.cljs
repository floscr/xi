(ns xi.ext.dev-server.web
  "Web half of the dev-server restart: a \"Restart server\" item in the
   three-dots overflow menu. Only present in dev (goog.DEBUG) builds — a
   release build compiles the :nav-items to nil, so the item never ships.
   Clicking forwards :dev/restart-server to the server, which restarts its
   tmux dev session (see xi.ext.dev-server).")

(defn- restart-server
  [_st ev]
  {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]})

(def extension
  {:id        :dev-server-web
   :handlers  {:dev/restart-server restart-server}
   :nav-items (when ^boolean js/goog.DEBUG
                [{:menu  :overflow
                  :label "Restart server"
                  :icon  :refresh
                  :event {:type :dev/restart-server}}])})
