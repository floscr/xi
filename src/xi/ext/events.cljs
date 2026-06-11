(ns xi.ext.events
  "Agent tool for inspecting the session event log.

   Exposes `xi_events` — a tool the agent can call to see what happened
   during the current session (dispatched events, effects, timing).
   The ring buffer is closed over at extension creation time."
  (:require [clojure.string :as str]
            [xi.core.log :as log]))

(def ^:private tool-def
  {:name "xi_events"
   :description "Show the event log for the current Xi session. Returns timestamped events with effects. Useful for debugging session state, agent turns, and extension behavior."
   :input_schema {:type "object" :properties {} :additionalProperties false}})

(defn create
  "Build the events extension, closed over the ring buffer."
  [ring]
  {:id               :events
   :tool-definitions [tool-def]
   :tool-registry    {"xi_events"
                      (fn [_args _ctx]
                        (let [entries (when ring (log/entries ring))
                              text (if (seq entries)
                                     (str/join "\n" (map log/format-entry-line entries))
                                     "(no events)")]
                          {:content [{:type "text" :text text}]
                           :is-error false}))}})
