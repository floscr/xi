(ns xi.providers.runner
  "Spawn a provider's program for one request and speak the protocol's
   framing (docs/provider-protocol.md): the request as the first line on
   stdin, then one JSON object per line each way. What the frames mean is
   xi.providers.process's business.

   The program runs with its provider directory as cwd; a leading \"bun\" in
   the manifest's command is the Bun binary running Xi, so it needs nothing
   on PATH. stderr is passed through to ours (the server log) and its tail is
   kept for the error when the program dies without a `done`."
  (:require ["node:child_process" :as child-process]
            [clojure.string :as str]))

(defn split-frames
  "Append `chunk` to the pending `buf` and split off the complete lines.
   Returns {:lines [non-blank complete lines] :rest unterminated tail}."
  [buf chunk]
  (let [parts (str/split (str buf chunk) #"\n" -1)]
    {:lines (vec (remove str/blank? (butlast parts)))
     :rest  (last parts)}))

(defn resolve-command
  "The manifest's command with \"bun\" as this process's own executable."
  [[exe & args]]
  (into [(if (= "bun" exe) (.-execPath js/process) exe)] args))

(def ^:private stderr-tail-chars 2000)

(defn start!
  "Spawn `command` in `dir` and send `request` (a JSON-serializable map).
   `on-frame` gets every frame from the program as a keywordized map.
   Returns {:send! (fn [frame]) :kill! (fn [] ) :exited Promise<{:code :signal
   :stderr}>}; :exited also resolves when the spawn itself fails (:error)."
  [{:keys [command dir request on-frame log-tag]}]
  (let [[exe & args] (resolve-command command)
        ^js proc (child-process/spawn exe (clj->js args)
                                      #js {:cwd dir :stdio #js ["pipe" "pipe" "pipe"]})
        tail     (atom "")
        send!    (fn [m]
                   (try
                     (.write (.-stdin proc) (str (js/JSON.stringify (clj->js m)) "\n"))
                     (catch :default _ nil)))
        exited   (js/Promise.
                  (fn [resolve _]
                    (.once proc "close" (fn [code signal]
                                          (resolve {:code code :signal signal :stderr @tail})))
                    (.once proc "error" (fn [err]
                                          (resolve {:error (.-message err) :stderr @tail})))))
        buf      (atom "")]
    (.on (.-stdin proc) "error" (fn [_] nil))
    (.setEncoding (.-stdout proc) "utf8")
    (.on (.-stdout proc) "data"
         (fn [chunk]
           (let [{:keys [lines rest]} (split-frames @buf chunk)]
             (reset! buf rest)
             (doseq [line lines]
               (let [frame (try (js->clj (js/JSON.parse line) :keywordize-keys true)
                                (catch :default e
                                  (js/console.error (str "[" log-tag "] bad frame:") (str e) line)
                                  nil))]
                 (when (map? frame) (on-frame frame)))))))
    (.setEncoding (.-stderr proc) "utf8")
    (.on (.-stderr proc) "data"
         (fn [chunk]
           (.write js/process.stderr chunk)
           (swap! tail (fn [t] (let [t (str t chunk) n (count t)]
                                 (if (> n stderr-tail-chars) (subs t (- n stderr-tail-chars)) t))))))
    (send! request)
    {:send!  send!
     :kill!  (fn [] (try (.kill proc) (catch :default _ nil)))
     :exited exited}))
