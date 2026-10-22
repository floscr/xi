(ns xi.providers.routing
  "Which provider serves a model id, from the providers' published specs
   (the app state's :providers, set by :providers/changed — xi.cli publishes
   them, xi.server.ws sends them every client). Shared with the browser.

   A spec is the handler-free part of a provider (xi.providers.manifest):
   {:id :label :routes {:prefixes [..] :ids [..] :default? bool}
    :side-turns? :prompt-note :login-name}."
  (:require [clojure.string :as str]))

(defn- serves?
  [{:keys [routes]} model]
  (and (string? model)
       (or (some #(and (string? %) (str/starts-with? model %)) (:prefixes routes))
           (some #(= model %) (:ids routes)))))

(defn provider-for-model
  "The id of the provider serving `model`: the first spec whose prefixes or
   ids match, else the one marked :default?, else nil."
  [specs model]
  (or (some #(when (serves? % model) (:id %)) specs)
      (some #(when (get-in % [:routes :default?]) (:id %)) specs)))

(defn spec
  "The spec of provider `id`, or nil."
  [specs id]
  (some #(when (= id (:id %)) %) specs))

(defn agent-spec
  "The spec of the provider a room's :agent ({:provider :model}) runs on."
  [specs {:keys [provider model]}]
  (spec specs (or provider (provider-for-model specs model))))

(defn side-turn-id
  "The provider that runs titles, summaries, quick replies and compaction:
   the first spec with :side-turns?."
  [specs]
  (some #(when (:side-turns? %) (:id %)) specs))

(defn side-turn-provider
  "Of `providers` (id → provider map, picker order), the one that runs side
   turns: the first with :side-turns?, else nil."
  [providers]
  (some #(when (:side-turns? %) %) (vals providers)))

(defn side-model
  "The model the side-turn `provider` runs a side turn of `tier` on: :fast
   (titles, summaries, quick replies) or :strong (compaction, a rollover's
   summary), from its manifest's sideTurns. nil leaves it to the provider."
  [provider tier]
  (get-in provider [:side-models tier]))

(defn default-model
  "The model a chat starts on when nobody picked one: the first of
   `providers` (picker order) whose manifest names a defaultModel."
  [providers]
  (some :default-model (vals providers)))

(defn- providers-changed
  [st {:keys [providers]}]
  (when (not= providers (:providers st))
    {:state (assoc st :providers (vec providers))}))

(def handlers
  {:providers/changed providers-changed})
