(ns xi.tui.buffers
  "Session buffers — named append-only logs for capturing output.
   Each buffer is a named sequence of entries with timestamps.
   Used to redirect stray stdout/stderr (e.g. shadow-cljs hot-reload)
   away from the TUI display into a viewable log.")

(def ^:private MAX_ENTRIES 1000)

(defn create-manager
  "Create a buffer manager. Returns an atom holding buffer state.
   Pre-creates named buffers from initial-buffers (vec of strings)."
  ([] (create-manager []))
  ([initial-buffers]
   (atom (into {} (map (fn [name] [name []]) initial-buffers)))))

(defn append!
  "Append text to a named buffer. Caps at MAX_ENTRIES, dropping oldest."
  [manager buf-name text]
  (let [entry {:text text :timestamp (js/Date.now)}]
    (swap! manager update buf-name
           (fn [entries]
             (let [entries (or entries [])
                   entries (conj entries entry)]
               (if (> (count entries) MAX_ENTRIES)
                 (vec (drop (- (count entries) MAX_ENTRIES) entries))
                 entries))))))

(defn get-entries
  "Get all entries from a named buffer."
  [manager buf-name]
  (get @manager buf-name []))

(defn list-buffers
  "List all buffer names with entry counts."
  [manager]
  (->> @manager
       (mapv (fn [[name entries]]
               {:name name :count (count entries)}))
       (sort-by :name)))

(defn clear!
  "Clear a named buffer."
  [manager buf-name]
  (swap! manager dissoc buf-name))
