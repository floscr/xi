(ns xi.ext.device.web
  "Browser half of the device round trip (xi.device): answers the server's
   :device/query with this browser's clock or a location fix and sends it
   back as :device/reply. The location comes from navigator.geolocation, so
   the browser asks its own permission first, and only on a secure page
   (https, or localhost).")

(defn- pad [n] (.padStart (str n) 2 "0"))

(defn- local-iso
  "2026-10-13T14:03:12+02:00 — the local wall time with its UTC offset."
  [^js d offset-min]
  (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d))
       "T" (pad (.getHours d)) ":" (pad (.getMinutes d)) ":" (pad (.getSeconds d))
       (if (neg? offset-min) "-" "+")
       (pad (quot (abs offset-min) 60)) ":" (pad (mod (abs offset-min) 60))))

(defn- clock []
  (let [d      (js/Date.)
        offset (- (.getTimezoneOffset d))]
    {:epoch-ms           (.getTime d)
     :local              (local-iso d offset)
     :weekday            (.toLocaleDateString d "en-US" #js {:weekday "long"})
     :time-zone          (.. (js/Intl.DateTimeFormat) resolvedOptions -timeZone)
     :utc-offset-minutes offset
     :locale             (.-language js/navigator)}))

(defn- finite [x] (when (and (number? x) (js/isFinite x)) x))

(defn- position->map [^js pos]
  (let [^js c (.-coords pos)]
    (into {}
          (filter (comp some? val))
          {:latitude            (.-latitude c)
           :longitude           (.-longitude c)
           :accuracy-m          (finite (.-accuracy c))
           :altitude-m          (finite (.-altitude c))
           :altitude-accuracy-m (finite (.-altitudeAccuracy c))
           :heading-deg         (finite (.-heading c))
           :speed-mps           (finite (.-speed c))
           :timestamp           (.-timestamp pos)})))

(def ^:private geo-errors
  {1 "location permission was denied in the browser"
   2 "the device couldn't determine its position"
   3 "the device took too long to find its position"})

(defn- geo-error
  "→ Promise of the reason for a failed fix. Chrome counts the time its
   permission prompt stays open against the timeout, so a timeout while the
   permission is still undecided means nobody answered the prompt."
  [^js err]
  (-> (if-let [^js perms (some-> js/navigator .-permissions)]
        (-> (.query perms #js {:name "geolocation"})
            (.then (fn [^js s] (.-state s)) (constantly nil)))
        (js/Promise.resolve nil))
      (.then (fn [state]
               (if (and (= 3 (.-code err)) (= "prompt" state))
                 "the browser's location permission prompt wasn't answered in time"
                 (get geo-errors (.-code err) (.-message err)))))))

(defn- location
  "→ Promise of the position map; rejects with a readable reason."
  [{:keys [high-accuracy? max-age-ms timeout-ms]}]
  (js/Promise.
   (fn [resolve reject]
     (cond
       (not js/window.isSecureContext)
       (reject (js/Error. "the page isn't served over https, so the browser offers no location"))

       (not (some-> js/navigator .-geolocation))
       (reject (js/Error. "this browser has no location support"))

       :else
       (.getCurrentPosition
        (.-geolocation js/navigator)
        (fn [pos] (resolve (position->map pos)))
        (fn [err] (.then (geo-error err) #(reject (js/Error. %))))
        #js {:enableHighAccuracy (boolean high-accuracy?)
             :maximumAge         (or max-age-ms 0)
             ;; under the server's own timeout, so the reason gets back
             :timeout            (max 1000 (- (or timeout-ms 60000) 2000))})))))

(defn- answer! [dispatch! {:keys [id what opts]}]
  (-> (js/Promise.resolve nil)
      (.then (fn [_]
               (case what
                 :clock    (clock)
                 :location (location opts)
                 (throw (js/Error. (str "unknown device query " what))))))
      (.then (fn [result] (dispatch! {:type :device/answered :id id :result result}))
             (fn [e] (dispatch! {:type :device/answered :id id
                                 :error (or (.-message e) (str e))})))))

(def extension
  {:id       :device
   :handlers {;; from the server only: a local event can't make this browser answer
              :device/query    (fn [_st {:keys [remote?] :as ev}]
                                 (when remote?
                                   {:effects [[:device/answer (select-keys ev [:id :what :opts])]]}))
              :device/answered (fn [_st {:keys [id result error]}]
                                 {:effects [[:ws/send (cond-> {:type :device/reply :id id}
                                                        result (assoc :result result)
                                                        error  (assoc :error error))]]})}
   :fx       {:device/answer (fn [{:keys [dispatch!]} payload] (answer! dispatch! payload))}})
