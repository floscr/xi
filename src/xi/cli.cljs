(ns xi.cli
  "Xi entry point — rebuild scaffold.

   Phase 2 core + phase 3 providers are wired; boots a standalone
   connection with one room as a smoke test. Phase 4 adds the TUI.
   See docs/rebuild-plan.md."
  (:require [xi.agent :as agent]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.provider.claude :as claude]
            [xi.provider.ollama :as ollama]))

(def providers
  {:claude claude/provider
   :ollama ollama/provider})

(defn main [& _args]
  (let [ring (log/create-ring)
        {:keys [dispatch! state]}
        (app/create-app {:initial-state (state/initial-state {:mode :standalone})
                         :handlers      (merge events/core-handlers agent/handlers)
                         :effects       (agent/create-fx providers)
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
