(ns xi.ext.terminal.web
  "Browser half of the terminal extension (xi.ext.terminal): forwards
   `:terminal/open` / `:terminal/restart` to the server with the active room,
   remembers terminals the server no longer runs (`:web/terminals-gone`,
   after a server restart) until a restart brings them back, offers the
   keys a touch keyboard lacks in the terminal's toolbar, and Run in
   terminal in a shell code block's menu (xi.web.block-menu). The view
   itself is core (xi.web.terminal)."
  (:require [xi.ext.terminal.core :as core]
            [xi.web.terminal :as terminal]))

(defn- forward [st {:keys [remote?] :as ev}]
  (when-not remote?
    {:effects [[:ws/send (-> ev
                             (update :room-id #(or % (:active-room st)))
                             (dissoc :event/id :event/ts))]]}))

(defn- revive-tap
  "A gone terminal's buffer came back without an exit (a restart): attach it."
  [dispatch!]
  (fn [{:keys [type buffer]} st]
    (when (and (= :ui/buffer-set type)
               (= :terminal (:kind buffer))
               (nil? (:exit-code buffer))
               (contains? (:web/terminals-gone st) (:term-id buffer)))
      (dispatch! {:type :terminal/revived :term-id (:term-id buffer)}))))

(defn- touch? []
  (.-matches (.matchMedia js/window "(pointer: coarse)")))

(def ^:private touch-keys
  [{:label "Esc" :send "\u001b" :title "Escape"}
   {:label "Tab" :send "\t"}
   {:label "^C"  :send "\u0003" :title "Ctrl+C"}
   {:label "↑"   :send "\u001b[A" :title "Up"}
   {:label "↓"   :send "\u001b[B" :title "Down"}])

(defn- block-menu-items [block _state]
  (when-let [command (core/block-command block)]
    [{:label "Run in terminal" :icon :terminal
      :event {:type :terminal/open :command command}}]))

(def extension
  {:id       :terminal
   :handlers {:terminal/open    forward
              :terminal/restart forward
              :terminal/gone    (fn [st {:keys [term-id]}]
                                  {:state (update st :web/terminals-gone (fnil conj #{}) term-id)})
              :terminal/revived (fn [st {:keys [term-id]}]
                                  {:state   (update st :web/terminals-gone disj term-id)
                                   :effects [[:terminal/reattach {:term-id term-id}]]})}
   :fx       {:terminal/reattach (fn [_ {:keys [term-id]}] (terminal/reattach! term-id))}
   :taps     [revive-tap]
   :nav-items [{:menu :palette :label "New terminal" :icon :terminal
                :event {:type :terminal/open}}
               {:menu :overflow :mode :room :label "New terminal" :icon :terminal
                :event {:type :terminal/open}}]
   :terminal-toolbar-items (fn [_buffer _state] (when (touch?) touch-keys))
   :block-menu-items block-menu-items})
