(ns xi.runtime.events
  "Event bus — simple pub/sub for runtime events.
   Events are plain maps with a :type keyword.
   Clients subscribe by type or :* for all events.")

(defn create-bus
  "Create a new event bus. Returns a map with emit!, subscribe!, unsubscribe!."
  []
  (let [;; type-keyword → set of handler fns
        subs (atom {})]
    {:emit!
     (fn [event]
       (let [t (:type event)]
         ;; Notify type-specific subscribers
         (doseq [handler (get @subs t)]
           (try
             (handler event)
             (catch :default e
               (js/console.error (str "[event-bus] Error in " (name t) " handler:") e))))
         ;; Notify wildcard subscribers
         (doseq [handler (get @subs :*)]
           (try
             (handler event)
             (catch :default e
               (js/console.error "[event-bus] Error in wildcard handler:" e))))))

     :subscribe!
     (fn [event-type handler]
       (swap! subs update event-type (fnil conj #{}) handler)
       ;; Return unsubscribe fn
       (fn [] (swap! subs update event-type disj handler)))

     :unsubscribe-all!
     (fn []
       (reset! subs {}))}))
