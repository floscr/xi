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
  (:require [clojure.string :as str]
            [xi.core.events :as events]
            [xi.core.log :as log]))

(def ^:private RUNAWAY_BATCH_MS
  "Hard ceiling on a single synchronous dispatch drain. A batch that runs this
   long without the queue emptying is a livelock — a self-feeding dispatch cycle
   pegging the single-threaded event loop, so nothing else (new sessions, WS
   messages, agent turns) can make progress. Break it so the server recovers
   instead of hanging forever. Normal batches complete in milliseconds."
  15000)

(defn create-app
  "Options:
     :initial-state   required — see xi.core.state
     :handlers        event-type → pure handler (see xi.core.events), or a
                      0-arg fn returning that map: it is called per event, so
                      the set can change while the app runs (live extension
                      reload — see xi.ext.manager/live-view)
     :transform-event optional (fn [state event] → event'|nil) — pre-dispatch
                      transform (extension event hooks); nil blocks the event
     :effects         fx-type → (fn [{:keys [dispatch! state get-state]} payload]),
                      or a 0-arg fn returning that map (called per effect)
     :on-render       (fn [state dispatch!]) — called after state changes
     :schedule-render (fn [thunk]) — defaults to queueMicrotask (sync in tests)
     :ring            log ring buffer (xi.core.log/create-ring)
     :jsonl-writer    optional debug writer (xi.core.log/create-jsonl-writer)
     :on-runaway      optional (fn [msg]) — called when the dispatch drain is
                      broken as a livelock (server wires this to crash.log)
     :runaway-batch-ms optional override for the livelock ceiling (tests)

   Returns {:state :dispatch! :add-tap! :ring}."
  [{:keys [initial-state handlers transform-event effects on-render schedule-render ring jsonl-writer on-runaway runaway-batch-ms]}]
  (let [runaway-ms (or runaway-batch-ms RUNAWAY_BATCH_MS)
        ;; Built-in effect: re-dispatch an event (lets handlers chain flows,
        ;; e.g. draining a queued prompt by re-entering the normal code path).
        builtin-fx {:app/dispatch       (fn [{:keys [dispatch!]} event] (dispatch! event))
                    :app/dispatch-after (fn [{:keys [dispatch!]} {:keys [ms event]}]
                                          (js/setTimeout #(dispatch! event) ms))}
        ;; late-bound sets resolve per use; plain maps are fixed
        resolve-handlers (if (fn? handlers) handlers (constantly handlers))
        resolve-effects  (if (fn? effects)
                           (let [cache (atom nil)] ; [effects-map merged]
                             (fn []
                               (let [m (effects)]
                                 (if (identical? m (first @cache))
                                   (second @cache)
                                   (second (reset! cache [m (merge builtin-fx m)]))))))
                           (constantly (merge builtin-fx effects)))
        !state    (atom initial-state)
        get-state (fn [] @!state)
        schedule  (or schedule-render (fn [thunk] (js/queueMicrotask thunk)))
        ;; Contained mutation: dispatch queue + bookkeeping, all local to
        ;; this closure. Not application state.
        ctx      #js {:queue #js [] :processing false :renderScheduled false
                      :lastRendered initial-state :eventSeq 0 :taps #js []}]
    (letfn [(run-effect! [dispatch! [fx-type payload :as effect]]
              (if-let [fx-handler (get (resolve-effects) fx-type)]
                (try
                  (fx-handler {:dispatch! dispatch! :state @!state :get-state get-state} payload)
                  (catch :default e
                    (js/console.error "[app] effect failed:" (str fx-type) e)))
                (js/console.warn "[app] unknown effect:" (str fx-type))))

            (process-one! [dispatch! orig-event]
              (let [event (if transform-event
                            (try
                              (transform-event @!state orig-event)
                              (catch :default e
                                (js/console.error "[app] transform-event failed:"
                                                  (str (:type orig-event)) e)
                                orig-event))
                            orig-event)]
                (if (nil? event)
                  ;; Blocked by an event hook — JSONL debug log only.
                  (when jsonl-writer
                    ((:write! jsonl-writer) (log/prepare-entry
                                             (assoc orig-event :ext/blocked? true) [])))
                  (process-event! dispatch! event))))

            (process-event! [dispatch! event]
              (let [{state' :state fx :effects}
                    (try
                      (events/handle-event (resolve-handlers) @!state event)
                      (catch :default e
                        (js/console.error "[app] handler failed:" (str (:type event)) e)
                        {:state @!state :effects []}))]
                (when-not (identical? state' @!state)
                  (reset! !state state'))
                (let [entry (log/prepare-entry event fx)]
                  (when ring (log/append! ring entry))
                  (when jsonl-writer ((:write! jsonl-writer) entry)))
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

            (runaway! [counts ms]
              ;; Livelock breaker: the drain ran past RUNAWAY_BATCH_MS without
              ;; emptying, so the event loop is pegged. Log a histogram of what
              ;; it churned on and drop the backlog so the process recovers
              ;; (a dropped batch beats an endless hang needing a manual
              ;; restart). on-runaway persists it (the server writes crash.log).
              (let [pairs (->> (js-keys counts)
                               (map (fn [k] [k (aget counts k)]))
                               (sort-by second >))
                    total (reduce + 0 (map second pairs))
                    top   (->> pairs (take 15)
                               (map (fn [[k n]] (str "  " n "×  " k)))
                               (str/join "\n"))
                    msg   (str "dispatch livelock: drained " total " events in "
                               ms "ms without emptying the queue — dropping the "
                               "backlog to recover.\nTop event types:\n" top)]
                (js/console.error "[app]" msg)
                (when on-runaway (try (on-runaway msg) (catch :default _ nil)))
                (.splice (.-queue ctx) 0)))

            (dispatch! [event]
              (set! (.-eventSeq ctx) (inc (.-eventSeq ctx)))
              (.push (.-queue ctx)
                     (assoc event
                            :event/id (.-eventSeq ctx)
                            :event/ts (js/Date.now)))
              (when-not (.-processing ctx)
                (set! (.-processing ctx) true)
                (let [batch-start (js/Date.now)
                      counts      #js {}]
                  (try
                    (loop []
                      (when (pos? (.-length (.-queue ctx)))
                        (let [ev (.shift (.-queue ctx))
                              t  (str (:type ev))]
                          (aset counts t (inc (or (aget counts t) 0)))
                          (process-one! dispatch! ev))
                        ;; Guard AFTER each event: a livelock never lets the
                        ;; queue empty, so we'd otherwise loop here forever.
                        (if (> (- (js/Date.now) batch-start) runaway-ms)
                          (runaway! counts (- (js/Date.now) batch-start))
                          (recur))))
                    (finally
                      (set! (.-processing ctx) false))))
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
