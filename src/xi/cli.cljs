(ns xi.cli
  "Xi entry point — rebuild scaffold.

   Phase 2 core (state/events/log/app) is wired; boots a standalone
   connection with one room as a smoke test. Phase 3 adds providers,
   phase 4 the TUI. See docs/rebuild-plan.md."
  (:require [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.log :as log]
            [xi.core.state :as state]))

(defn main [& _args]
  (let [ring (log/create-ring)
        {:keys [dispatch! state]}
        (app/create-app {:initial-state (state/initial-state {:mode :standalone})
                         :handlers      events/core-handlers
                         :effects       {}
                         :ring          ring})]
    (dispatch! {:type :room/create :room-id "main"})
    (dispatch! {:type :history/append :room-id "main"
                :entry {:role :system :text "core online"}})
    (println "xi (rebuild) — core online:"
             (pr-str {:mode        (state/mode @state)
                      :active-room (:active-room @state)
                      :history     (count (:history (state/active-room @state)))
                      :events      (count (log/entries ring))}))
    (println "TUI not yet wired — see docs/rebuild-plan.md phase 4")))
