(ns xi.web.models
  "Client-side cache of the model picker's list.

   Listing models asks every provider (Ollama, Zen over the network, …), so
   the picker used to show a spinner on every open. Now it paints from
   :web/model-list, hydrated from localStorage (xi.web.cache), and only asks
   the server when there is no list yet or it is older than `list-ttl-ms`;
   a stale list still paints at once while the fresh one loads.

   A cached list can outlive a model (an Ollama model removed, a provider
   down). So a pick is checked against a fresh list in the background: if the
   model is gone, the picker re-opens on the fresh list with an error instead
   of leaving the chat on a model that can't answer. Rare, and the pick is
   applied optimistically, so the common case costs no latency.

   State keys:
     :web/model-list     [model-id …], nil until a list is known
     :web/model-list-at  when that list was fetched (ms)
     :web/model-check    the picked model awaiting validation
     :web/model-error    message shown above the picker's list")

(def list-ttl-ms
  "How long a fetched model list is trusted before the picker refetches it."
  (* 60 60 1000))

(def ^:private request [:ws/send {:type :models/web-list}])

(defn stale?
  "Does the picker need a fresh list at `now`? True with no list, or one
   older than `list-ttl-ms`."
  [st now]
  (let [at (:web/model-list-at st)]
    (or (empty? (:web/model-list st))
        (not (number? at))
        (> (- now at) list-ttl-ms))))

(defn- show-picker
  "Open the palette on the model page (the one-shot drilling cycle of
   :palette/open-models, see xi.web.core)."
  [st]
  (assoc st :web/palette-page {:kind :model}
            :web/palette-open? true
            :web/palette-drilling? true))

(def ^:private picker-effects
  "Re-open the palette, then re-run its filter: the cached list renders at
   once into the rows the previous page (e.g. the \"change model\" search)
   left filtered, so without the reset they stay hidden."
  [[:palette/reopen nil] [:palette/reset-filter nil]])

(defn open
  "Change model / /model: show the picker, fetching a list only when the
   cached one is missing or stale."
  [st now]
  {:state   (-> st show-picker (dissoc :web/model-error))
   :effects (cond-> []
              (stale? st now) (conj request)
              true            (into picker-effects))})

(defn with-check
  "Add the background validation of a just-picked `model` to the pick's
   handler `result`: remember the pick and refetch the list, which
   `list-result` then compares against."
  [result model]
  (-> result
      (update :state #(-> % (assoc :web/model-check model) (dissoc :web/model-error)))
      (update :effects (fnil conj []) request)))

(defn- unavailable [st model]
  (-> st
      (cond-> (= model (get-in st [:web/pending-room :model]))
        (update :web/pending-room dissoc :model))
      (assoc :web/model-error (str model " is not available anymore. Pick another model."))
      show-picker))

(defn list-result
  "The server's model list arrived. An empty one means every provider failed
   (each degrades to []): keep the cached list rather than wiping it, and
   skip a pending check (it can't be decided). Otherwise cache the list and,
   if a pick was awaiting validation and is missing, re-open the picker with
   an error."
  [st {:keys [models]} now]
  (let [check (:web/model-check st)
        st    (dissoc st :web/model-check)]
    (if (empty? models)
      {:state (cond-> st
                (nil? (:web/model-list st)) (assoc :web/model-list []))}
      (let [models (vec models)
            st     (assoc st :web/model-list models :web/model-list-at now)
            fx     [[:cache/model-list {:models models :at now}]]]
        (if (and check (not (some #{check} models)))
          {:state   (unavailable st check)
           :effects (into fx picker-effects)}
          {:state st :effects fx})))))
