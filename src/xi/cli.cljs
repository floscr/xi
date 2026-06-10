(ns xi.cli
  "Xi entry point — standalone TUI over the rebuilt core.

   Assembly: pure handlers (core + agent + commands + compaction, with
   turn-end/abort chains) + effect handlers (providers, sessions/images,
   compaction, TUI-owned quit/reload/clipboard) wired into one app whose
   renderer is the TUI client.

   Flags:
     --model M        override the model for this run
     --debug-events   write the full event stream to
                      ~/.pi/agent/logs/<session>.events.jsonl

   Connection modes (server/join/create) return in phase 5 — see
   docs/rebuild-plan.md."
  (:require [xi.agent :as agent]
            [xi.client.tui :as client-tui]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.jsonl :as core-jsonl]
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.fx :as fx]
            [xi.provider.claude :as claude]
            [xi.provider.ollama :as ollama]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]))

(def providers
  {:claude claude/provider
   :ollama ollama/provider})

(def ^:private DEFAULT_MODEL "claude-sonnet-4-20250514")

(def ^:private THINKING_TO_EFFORT
  "Map Pi thinking levels → Claude SDK effort levels."
  {"minimal" "low"
   "low"     "low"
   "medium"  "medium"
   "high"    "high"
   "xhigh"   "max"})

(defn- load-settings []
  (try
    (let [path (str (aget js/process.env "HOME") "/.pi/agent/settings.json")
          content (.readFileSync (js/require "node:fs") path "utf8")]
      (js->clj (js/JSON.parse content) :keywordize-keys true))
    (catch :default _e {})))

(defn- parse-args [args]
  (loop [args (seq args) opts {}]
    (if-not args
      opts
      (let [arg (first args)]
        (case arg
          "--debug-events" (recur (next args) (assoc opts :debug-events? true))
          "--model"        (recur (nnext args) (assoc opts :model (second args)))
          (recur (next args) opts))))))

(defn- make-handlers []
  (-> (merge events/core-handlers
             agent/handlers
             commands/handlers
             compaction/handlers)
      ;; Persist the session once the provider reports a session id
      (assoc :agent/turn-end (events/chain (:agent/turn-end agent/handlers)
                                           commands/turn-end-session-sync)
             ;; Escape also stops an in-flight compaction
             :agent/abort (events/chain (:agent/abort agent/handlers)
                                        compaction/abort-handler))))

(defn main [& args]
  (let [{:keys [debug-events? model]} (parse-args args)
        settings (load-settings)
        cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
        model (or model
                  (aget js/process.env "XI_MODEL")
                  (:defaultModel settings)
                  DEFAULT_MODEL)
        effort (or (aget js/process.env "XI_EFFORT")
                   (get THINKING_TO_EFFORT (:defaultThinkingLevel settings))
                   "high")
        agents-files (system-prompt/find-agents-md cwd)
        system (system-prompt/load-agents-md cwd)
        sess (session/create-session cwd)
        ring (log/create-ring)
        jsonl-writer (when debug-events?
                       (core-jsonl/create-writer
                        (str (aget js/process.env "HOME")
                             "/.pi/agent/logs/" (:id sess) ".events.jsonl")))
        client (client-tui/create!
                {:ring ring
                 :on-exit (fn [] (when jsonl-writer ((:flush! jsonl-writer))))})
        {:keys [dispatch!]}
        (app/create-app {:initial-state (state/initial-state {:mode :standalone})
                         :handlers      (make-handlers)
                         :effects       (merge (agent/create-fx providers)
                                               (fx/create-fx)
                                               (compaction/create-fx providers)
                                               (:effects client))
                         :on-render     (:render client)
                         :ring          ring
                         :jsonl-writer  jsonl-writer})]
    (when jsonl-writer
      (js/process.on "exit" (fn [] ((:flush! jsonl-writer)))))
    (dispatch! {:type :room/create
                :room-id "main"
                :room {:model model
                       :cwd cwd
                       :effort effort
                       :system system
                       :agents-files agents-files
                       :session sess}})
    ;; Auto-resume after /reload (env var set by the :app/reload effect)
    (when-let [reload-sid (aget js/process.env "XI_RELOAD_SESSION")]
      (js-delete js/process.env "XI_RELOAD_SESSION")
      (when-let [summary (session/find-session-by-id reload-sid)]
        (dispatch! {:type :session/resumed
                    :room-id "main"
                    :session (session/load-session summary)
                    :summary summary
                    :messages (session/read-session-messages summary)})))))
