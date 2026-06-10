(ns xi.core.jsonl
  "Opt-in JSONL event log writer (--debug-events). Node-only — kept out of
   xi.core.log so the rest of the core stays browser-safe.

   Buffered: flushes every `max-buffer` lines or after `flush-ms` idle —
   never a sync write per event. Call (:flush! writer) on process exit."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn- entry->json-line [entry]
  (try
    (js/JSON.stringify (clj->js entry))
    (catch :default _
      (js/JSON.stringify #js {:unserializable (pr-str entry)}))))

(defn create-writer
  ([path] (create-writer path nil))
  ([path {:keys [max-buffer flush-ms] :or {max-buffer 50 flush-ms 2000}}]
   (.mkdirSync fs (.dirname node-path path) #js {:recursive true})
   (let [state #js {:lines #js [] :timer nil}
         flush! (fn []
                  (when (.-timer state)
                    (js/clearTimeout (.-timer state))
                    (set! (.-timer state) nil))
                  (when (pos? (.-length (.-lines state)))
                    (let [chunk (str (str/join "\n" (.-lines state)) "\n")]
                      (set! (.-lines state) #js [])
                      (.appendFileSync fs path chunk))))]
     {:path   path
      :flush! flush!
      :write! (fn [entry]
                (.push (.-lines state) (entry->json-line entry))
                (if (>= (.-length (.-lines state)) max-buffer)
                  (flush!)
                  (when-not (.-timer state)
                    (set! (.-timer state) (js/setTimeout flush! flush-ms)))))})))
