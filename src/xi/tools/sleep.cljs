(ns xi.tools.sleep
  "Sleep / pause tool — a deliberate fixed delay.")

(def ^:private MAX_MS 60000)

(defn execute
  "Pause for `seconds` (fractional allowed). Returns after the delay."
  [{:keys [seconds]} _ctx]
  (let [secs (or seconds 1)
        ms (js/Math.min MAX_MS (js/Math.max 0 (* secs 1000)))]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn []
          (resolve {:content [{:type "text"
                               :text (str "Slept " (/ ms 1000) "s")}]}))
        ms)))))

(def definition
  {:name "sleep"
   :description
   (str "Pause for a fixed number of seconds, then continue. Use ONLY for "
        "deliberate pacing (e.g. spacing out rate-limited API calls). Do NOT "
        "use it to wait for something to become ready — a server to boot, a "
        "file to appear, a job to finish — there is no guarantee the delay is "
        "long enough; poll for the actual condition instead. Max 60s.")
   :input_schema {:type "object"
                  :properties {:seconds {:type "number"
                                         :description "Seconds to sleep (fractional allowed, max 60)"}}
                  :required ["seconds"]}})
