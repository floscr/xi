(ns xi.ext.device.camera
  "The browser's camera for xi.ext.device.web: one MediaStream, opened on
   demand by a server :camera query or kept open while the Camera page
   shares it, and JPEG frames grabbed from it.

   On demand the stream closes after a while without a grab; shared (share!)
   it stays open, with a screen wake lock so a propped-up phone keeps
   streaming, until unshare! or the browser ends it (a backgrounded iOS
   Safari does), which reports through the share's :on-ended.

   getUserMedia needs a secure page (https, or localhost) and the browser's
   own permission prompt."
  (:require [clojure.string :as str]))

;; {:stream :video :facing :shared? :on-ended :idle-timer} — the open camera
(defonce ^:private cam (atom nil))
;; the WakeLockSentinel while sharing
(defonce ^:private wake (atom nil))

(def facings #{"environment" "user"})

(def defaults
  {:facing "environment" :count 1 :interval-ms 1000 :max-width 1280 :quality 0.75})

(def ^:private limits
  {:count [1 8] :interval-ms [200 10000] :max-width [320 1920] :quality [0.3 0.95]})

(def idle-close-ms
  "How long an on-demand stream stays open after a grab, so a series of
   snapshots neither re-prompts nor re-warms the camera."
  90000)

;; ── Pure ─────────────────────────────────────────────────────────────────────

(defn- clamp [k v]
  (let [[lo hi] (limits k)]
    (if (number? v) (-> v (max lo) (min hi)) (defaults k))))

(defn clamp-opts
  "A query's opts within `limits`; `:count` whole. `:facing` only when valid:
   absent means \"whatever is open, else the rear camera\" (effective-facing)."
  [opts]
  (cond-> {:count       (js/Math.round (clamp :count (:count opts)))
           :interval-ms (clamp :interval-ms (:interval-ms opts))
           :max-width   (clamp :max-width (:max-width opts))
           :quality     (clamp :quality (:quality opts))}
    (facings (:facing opts)) (assoc :facing (:facing opts))))

(defn effective-facing
  "The camera to use: the one asked for, else the open one, else the rear."
  [requested current]
  (or (facings requested) current (:facing defaults)))

(defn strip-data-url
  "The base64 payload of a data: URL."
  [s]
  (let [i (str/index-of s ",")]
    (if i (subs s (inc i)) s)))

(defn scaled-size
  "[w h] of a `vw`×`vh` frame scaled down to at most `max-width` wide."
  [vw vh max-width]
  (let [scale (min 1 (/ max-width (max vw 1)))]
    [(js/Math.round (* vw scale)) (js/Math.round (* vh scale))]))

(defn device-label
  "A short name for the device behind `user-agent`."
  [user-agent]
  (let [ua (or user-agent "")]
    (cond
      (re-find #"iPhone" ua)  "iPhone"
      (re-find #"iPad" ua)    "iPad"
      (re-find #"Android" ua) "Android device"
      (re-find #"Mac" ua)     "Mac"
      (re-find #"Windows" ua) "Windows PC"
      (re-find #"Linux" ua)   "Linux PC"
      :else                   "browser")))

;; ── The stream ───────────────────────────────────────────────────────────────

(defn- unsupported-reason []
  (cond
    (not js/window.isSecureContext)
    "the page isn't served over https, so the browser offers no camera"

    (not (some-> js/navigator .-mediaDevices .-getUserMedia))
    "this browser has no camera support"))

(def ^:private gum-errors
  {"NotAllowedError"      "camera permission was denied in the browser"
   "NotFoundError"        "this device has no camera"
   "NotReadableError"     "the camera is in use by another app"
   "OverconstrainedError" "no camera faces that way"
   "AbortError"           "the camera could not be started"})

(defn- gum-error [^js e]
  (or (get gum-errors (.-name e)) (.-message e) (str e)))

(defn- make-video [stream]
  (let [^js v (js/document.createElement "video")]
    (set! (.-muted v) true)
    (set! (.-playsInline v) true)
    (.setAttribute v "playsinline" "")
    (set! (.-autoplay v) true)
    (set! (.-srcObject v) stream)
    v))

(defn- wait-ready
  "→ Promise of `video` once it shows a frame (readyState ≥ HAVE_CURRENT_DATA
   with a size), or rejects after 10 s."
  [^js video]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (-> (.play video) (.catch (fn [_] nil)))
       (letfn [(tick []
                 (cond
                   (and (>= (.-readyState video) 2) (pos? (.-videoWidth video)))
                   (resolve video)

                   (> (- (js/Date.now) start) 10000)
                   (reject (js/Error. "the camera gave no picture"))

                   :else (js/setTimeout tick 50)))]
         (tick))))))

(defn- stop-tracks! [^js stream]
  (doseq [^js t (array-seq (.getTracks stream))] (.stop t)))

(defn close!
  "Stop the camera, shared or not."
  []
  (when-let [{:keys [stream idle-timer]} @cam]
    (js/clearTimeout idle-timer)
    (reset! cam nil)
    (stop-tracks! stream)))

(defn- ended!
  "The browser ended `stream` (not our stop: stop() fires no `ended`)."
  [stream]
  (when (identical? stream (:stream @cam))
    (let [{:keys [on-ended]} @cam]
      (close!)
      (when on-ended (on-ended)))))

(defn open!
  "→ Promise of the <video> playing the camera facing `facing`; the open
   stream is reused when it faces that way, else replaced."
  [facing]
  (if-let [{:keys [video] :as c} @cam]
    (if (= facing (:facing c))
      (js/Promise.resolve video)
      (let [{:keys [shared? on-ended]} c]
        (close!)
        (-> (open! facing)
            (.then (fn [v]
                     (swap! cam assoc :shared? shared? :on-ended on-ended)
                     v)))))
    (if-let [why (unsupported-reason)]
      (js/Promise.reject (js/Error. why))
      (-> (.getUserMedia (.-mediaDevices js/navigator)
                         #js {:video #js {:facingMode facing
                                          :width  #js {:ideal 1920}
                                          :height #js {:ideal 1080}}
                              :audio false})
          (.catch (fn [e] (throw (js/Error. (gum-error e)))))
          (.then (fn [^js stream]
                   (let [video (make-video stream)]
                     (doseq [^js t (array-seq (.getTracks stream))]
                       (.addEventListener t "ended" (fn [_] (ended! stream))))
                     (reset! cam {:stream stream :video video :facing facing})
                     (-> (wait-ready video)
                         (.catch (fn [e] (close!) (throw e)))))))))))

(defn- touch-idle-timer!
  "An on-demand stream closes idle-close-ms after its last grab; a shared one
   stays open."
  []
  (when-let [{:keys [idle-timer shared?]} @cam]
    (js/clearTimeout idle-timer)
    (when-not shared?
      (swap! cam assoc :idle-timer
             (js/setTimeout (fn [] (when-not (:shared? @cam) (close!))) idle-close-ms)))))

;; ── Frames ───────────────────────────────────────────────────────────────────

(defn- grab!
  "A JPEG of `video`'s current frame → {:data :width :height :taken-at}."
  [^js video {:keys [max-width quality]}]
  (let [[w h]  (scaled-size (.-videoWidth video) (.-videoHeight video) max-width)
        ^js canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    (.drawImage (.getContext canvas "2d") video 0 0 w h)
    {:data     (strip-data-url (.toDataURL canvas "image/jpeg" quality))
     :width    w
     :height   h
     :taken-at (js/Date.now)}))

(defn- delay-ms [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn frames!
  "A server :camera query: `count` frames `interval-ms` apart from the camera
   → Promise of {:frames [{:data :width :height :taken-at}] :media-type
   :facing :device}. Opens the camera when nothing is shared."
  [opts]
  (let [{:keys [interval-ms] :as o} (clamp-opts opts)
        wanted (:count o)
        facing (effective-facing (:facing opts) (:facing @cam))]
    (-> (open! facing)
        (.then (fn [video]
                 (letfn [(step [acc n]
                           (if (zero? n)
                             (js/Promise.resolve acc)
                             (-> (if (seq acc) (delay-ms interval-ms) (js/Promise.resolve nil))
                                 (.then (fn [_] (step (conj acc (grab! video o)) (dec n)))))))]
                   (step [] wanted))))
        (.then (fn [frames]
                 (touch-idle-timer!)
                 {:frames     frames
                  :media-type "image/jpeg"
                  :facing     facing
                  :device     (device-label (.-userAgent js/navigator))})))))

;; ── Sharing (the Camera page) ────────────────────────────────────────────────

(defn- wake-lock! []
  (when-let [^js wl (some-> js/navigator .-wakeLock)]
    (-> (.request wl "screen")
        (.then (fn [s] (reset! wake s)))
        (.catch (fn [_] nil)))))

(defn- release-wake-lock! []
  (when-let [^js s @wake]
    (reset! wake nil)
    (-> (.release s) (.catch (fn [_] nil)))))

(defn share!
  "Keep the camera facing `facing` open and the screen awake until unshare!.
   `on-ended` runs when the browser ends the stream. → Promise of {:facing
   :width :height} once it plays."
  [{:keys [facing on-ended]}]
  (let [facing (effective-facing facing (:facing @cam))]
    (-> (open! facing)
        (.then (fn [^js video]
                 (swap! cam (fn [c] (-> c
                                        (assoc :shared? true :on-ended on-ended)
                                        (dissoc :idle-timer))))
                 (js/clearTimeout (:idle-timer @cam))
                 (wake-lock!)
                 {:facing facing
                  :width  (.-videoWidth video)
                  :height (.-videoHeight video)})))))

(defn unshare! []
  (release-wake-lock!)
  (close!))

(defn shared? [] (boolean (:shared? @cam)))

(defn resume!
  "The page is visible again: a shared stream takes the wake lock back (the
   browser drops it when the page hides)."
  []
  (when (shared?) (wake-lock!)))

(defn attach-preview!
  "Show the open stream in `node`, a <video> of the Camera page."
  [^js node]
  (when-let [s (:stream @cam)]
    (when-not (identical? (.-srcObject node) s)
      (set! (.-muted node) true)
      (set! (.-playsInline node) true)
      (set! (.-srcObject node) s)
      (-> (.play node) (.catch (fn [_] nil))))))
