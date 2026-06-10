(ns xi.core.app
  "The one impure shell.

   create-app owns the single state atom and the dispatch loop:

     dispatch! → enqueue → reduce (pure) → log → taps → run effects → render

   - Events dispatched while processing are queued and drained FIFO, so
     handlers always see a consistent state and ordering is deterministic.
   - Effects are data [[:fx/type payload] ...]; effect handlers receive
     {:dispatch! :state} and payload. They never touch the atom directly —
     completion is reported by dispatching new events.
   - Render is scheduled once per drained batch (microtask-coalesced) and
     only when state actually changed since the last render. Events that
     don't change state (e.g. :render/done) therefore never cause a
     re-render — renderers can safely emit debug events.
   - Taps observe every processed event (extensions, WS transports, tests)."
  (:require [xi.core.events :as events]
            [xi.core.log :as log]))

(defn create-app
  "Options:
     :initial-state   required — see xi.core.state
     :handlers        event-type → pure handler (see xi.core.events)
     :effects         fx-type → (fn [{:keys [dispatch! state]} payload])
     :on-render       (fn [state dispatch!]) — called after state changes
     :schedule-render (fn [thunk]) — defaults to queueMicrotask (sync in tests)
     :ring            log ring buffer (xi.core.log/create-ring)
     :jsonl-writer    optional debug writer (xi.core.log/create-jsonl-writer)

   Returns {:state :dispatch! :add-tap! :ring}."
  [{:keys [initial-state handlers effects on-render schedule-render ring jsonl-writer]}]
  (let [!state   (atom initial-state)
        schedule (or schedule-render (fn [thunk] (js/queueMicrotask thunk)))
        ;; Contained mutation: dispatch queue + bookkeeping, all local to
        ;; this closure. Not application state.
        ctx      #js {:queue #js [] :processing false :renderScheduled false
                      :lastRendered initial-state :eventSeq 0 :taps #js []}]
    (letfn [(run-effect! [dispatch! [fx-type payload :as effect]]
              (if-let [fx-handler (get effects fx-type)]
                (try
                  (fx-handler {:dispatch! dispatch! :state @!state} payload)
                  (catch :default e
                    (js/console.error "[app] effect failed:" (str fx-type) e)))
                (js/console.warn "[app] unknown effect:" (str fx-type))))

            (process-one! [dispatch! event]
              (let [{state' :state fx :effects}
                    (try
                      (events/handle-event handlers @!state event)
                      (catch :default e
                        (js/console.error "[app] handler failed:" (str (:type event)) e)
                        {:state @!state :effects []}))]
                (when-not (identical? state' @!state)
                  (reset! !state state'))
                (when ring
                  (log/append! ring (log/prepare-entry event fx)))
                (when jsonl-writer
                  ((:write! jsonl-writer) (log/prepare-entry event fx)))
                (doseq [tap (vec (.-taps ctx))]
                  (try (tap event @!state)
                       (catch :default e (js/console.error "[app] tap failed:" e))))
                (doseq [effect fx]
                  (run-effect! dispatch! effect))))

            (render! [dispatch!]
              (set! (.-renderScheduled ctx) false)
              (let [st @!state]
                (when-not (identical? st (.-lastRendered ctx))
                  (set! (.-lastRendered ctx) st)
                  (try
                    (on-render st dispatch!)
                    (catch :default e
                      (js/console.error "[app] render failed:" e))))))

            (maybe-schedule-render! [dispatch!]
              (when (and on-render
                         (not (.-renderScheduled ctx))
                         (not (identical? @!state (.-lastRendered ctx))))
                (set! (.-renderScheduled ctx) true)
                (schedule #(render! dispatch!))))

            (dispatch! [event]
              (set! (.-eventSeq ctx) (inc (.-eventSeq ctx)))
              (.push (.-queue ctx)
                     (assoc event
                            :event/id (.-eventSeq ctx)
                            :event/ts (js/Date.now)))
              (when-not (.-processing ctx)
                (set! (.-processing ctx) true)
                (try
                  (while (pos? (.-length (.-queue ctx)))
                    (process-one! dispatch! (.shift (.-queue ctx))))
                  (finally
                    (set! (.-processing ctx) false)))
                (maybe-schedule-render! dispatch!))
              nil)]
      {:state     !state
       :dispatch! dispatch!
       :ring      ring
       :add-tap!  (fn [tap]
                    (.push (.-taps ctx) tap)
                    (fn remove-tap []
                      (let [idx (.indexOf (.-taps ctx) tap)]
                        (when (>= idx 0)
                          (.splice (.-taps ctx) idx 1)))))})))
