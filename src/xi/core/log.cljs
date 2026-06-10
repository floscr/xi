(ns xi.core.log
  "Event log — debuggability without memory blowup or slowdowns.

   Two layers:
   1. Always-on in-memory ring buffer (capped, default 2000 entries).
      Large payloads are elided and high-frequency delta events are
      coalesced, so a long agent turn costs O(1) log entries.
   2. Opt-in JSONL file writer (--debug-events) — node-only, lives in
      xi.core.jsonl so this namespace stays browser-safe for the web client.

   `prepare-entry` is pure; the ring buffer is the contained impure edge
   (a single JS object, no atoms).")

;; ── Elision (pure) ───────────────────────────────────────────────────────────

(def ^:private max-string-len 200)

(def coalescable-types
  "High-frequency stream events — summarized to char counts and merged."
  #{:agent/text-delta :agent/thinking-delta})

(defn elide-string [s]
  (if (> (count s) max-string-len)
    (str (subs s 0 max-string-len) "…(+" (- (count s) max-string-len) " chars)")
    s))

(defn elide-value
  "Recursively truncate long strings (covers base64 images, big tool output)."
  [v]
  (cond
    (string? v) (elide-string v)
    (map? v)    (into {} (map (fn [[k x]] [k (elide-value x)])) v)
    (vector? v) (mapv elide-value v)
    :else       v))

(defn prepare-entry
  "Event → log entry. Pure. Delta events drop :text and carry :log/chars;
   everything else gets long strings elided. Effect types are recorded."
  [event effects]
  (let [entry (if (coalescable-types (:type event))
                (-> event
                    (dissoc :text)
                    (assoc :log/chars (count (:text event))))
                (elide-value event))]
    (cond-> entry
      (seq effects) (assoc :log/effects (mapv first effects)))))

(defn coalesce?
  "Should `entry` be merged into the previous log entry?"
  [prev entry]
  (boolean
   (and prev
        (coalescable-types (:type entry))
        (= (:type prev) (:type entry))
        (= (:room-id prev) (:room-id entry)))))

(defn merge-coalesced [prev entry]
  (-> prev
      (update :log/coalesced (fnil inc 1))
      (update :log/chars (fnil + 0) (:log/chars entry 0))
      (assoc :event/ts (:event/ts entry))))

;; ── Ring buffer (contained mutation) ─────────────────────────────────────────

(defn create-ring
  ([] (create-ring 2000))
  ([capacity]
   #js {:buf (js/Array. capacity) :capacity capacity :head 0 :count 0 :last nil}))

(defn append!
  "Append a prepared entry, coalescing consecutive delta entries in place."
  [^js ring entry]
  (let [capacity (.-capacity ring)
        prev     (.-last ring)]
    (if (coalesce? prev entry)
      (let [merged (merge-coalesced prev entry)
            idx    (mod (dec (.-head ring)) capacity)]
        (aset (.-buf ring) idx merged)
        (set! (.-last ring) merged))
      (do
        (aset (.-buf ring) (.-head ring) entry)
        (set! (.-head ring) (mod (inc (.-head ring)) capacity))
        (set! (.-count ring) (min (inc (.-count ring)) capacity))
        (set! (.-last ring) entry)))
    ring))

(defn entries
  "Snapshot of logged entries, oldest first."
  [^js ring]
  (let [capacity (.-capacity ring)
        n        (.-count ring)
        start    (if (< n capacity) 0 (.-head ring))]
    (mapv #(aget (.-buf ring) (mod (+ start %) capacity)) (range n))))