(ns xi.ext.device.web
  "Browser half of the device round trip (xi.device): answers the server's
   :device/query with this browser's clock, a location fix or camera frames
   and sends it back as :device/reply. The location comes from
   navigator.geolocation and the frames from getUserMedia (xi.ext.device.camera),
   so the browser asks its own permission first, and only on a secure page
   (https, or localhost).

   The Camera page (/camera, \"Share camera\" in the ⋮ menu) starts the camera
   with a preview and tells the server this client shares it (:client/update
   :camera?), so a camera read lands on the phone pointed at the thing and not
   on the laptop that typed the prompt. Sharing outlives the page: back in the
   chat a floating thumbnail (an overlay, xi.web.overlays) shows the camera is
   live, with a stop button. Its state is browser-local at :web/camera."
  (:require [ui.button :as button]
            [ui.icon :as icon]
            [xi.ext.device.camera :as camera]
            [xi.web.views :as views]))

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
                 :camera   (camera/frames! opts)
                 (throw (js/Error. (str "unknown device query " what))))))
      (.then (fn [result] (dispatch! {:type :device/answered :id id :result result}))
             (fn [e] (dispatch! {:type :device/answered :id id
                                 :error (or (.-message e) (str e))})))))

;; ── The Camera page ──────────────────────────────────────────────────────────

(def ^:private page-id :device/camera)

(defn- camera-state [st] (:web/camera st))

(defn- sharing-flag
  "Tell the server whether this client shares its camera (xi.device picks it
   for a camera read)."
  [on?]
  [:ws/send {:type :client/update :camera? on?}])

(defn- start [st {:keys [facing]}]
  (let [facing (camera/effective-facing facing (:facing (camera-state st)))]
    {:state   (assoc st :web/camera {:starting? true :facing facing})
     :effects [[:device/camera-share {:facing facing}]]}))

(defn- started [st {:keys [facing width height]}]
  {:state   (assoc st :web/camera {:on? true :facing facing :width width :height height})
   :effects [(sharing-flag true)]})

(defn- failed [st {:keys [error]}]
  {:state   (assoc st :web/camera {:error error :facing (:facing (camera-state st))})
   :effects [(sharing-flag false)]})

(defn- stop [st _]
  {:state   (assoc st :web/camera {:facing (:facing (camera-state st))})
   :effects [[:device/camera-unshare nil]
             (sharing-flag false)]})

(defn- ended
  "The browser stopped the stream (the page left the screen, another app took
   the camera)."
  [st _]
  (when (:on? (camera-state st))
    {:state   (assoc st :web/camera {:facing (:facing (camera-state st))
                                     :error  "The camera stopped — the page left the screen, or another app took it. Start it again."})
     :effects [(sharing-flag false)]}))

(defn- flip [st _]
  (start st {:facing (if (= "user" (:facing (camera-state st))) "environment" "user")}))

(defn- resume-tap
  "Visible again (the core's :client/update): a shared stream takes its wake
   lock back. A tap, not a handler: a handler of a base event would replace
   the transport's forwarding of it."
  [_dispatch!]
  (fn [event state]
    (when (and (= :client/update (:type event))
               (:visible? event)
               (:on? (camera-state state)))
      (camera/resume!))))

(defn- share! [dispatch! {:keys [facing]}]
  (-> (camera/share! {:facing   facing
                      :on-ended (fn [] (dispatch! {:type :device/camera-ended}))})
      (.then (fn [info] (dispatch! (assoc info :type :device/camera-started)))
             (fn [e] (dispatch! {:type :device/camera-failed
                                 :error (or (.-message e) (str e))})))))

(defn- facing-label [facing]
  (if (= "user" facing) "front camera" "rear camera"))

(defn- live-video
  "A <video> showing the shared stream."
  [class]
  [:video {:class [class]
           :autoplay true :muted true :playsinline "true"
           :replicant/on-mount  (fn [{:replicant/keys [node]}] (camera/attach-preview! node))
           :replicant/on-update (fn [{:replicant/keys [node]}] (camera/attach-preview! node))}])

(defn- preview [{:keys [on? starting? error facing]}]
  (cond
    on?
    (live-video "camera-video")

    :else
    [:div {:class ["camera-placeholder"]}
     (icon/icon {:icon-name :camera :size :xl})
     [:p {:class ["camera-note"]}
      (cond
        starting? (str "Starting the " (facing-label facing) "…")
        error     error
        :else     "Share this device's camera with the chat: the agent can take pictures from it while this page is open.")]]))

(defn- camera-page [state dispatch!]
  (let [{:keys [on? starting? facing width height] :as cam} (camera-state state)]
    [:div {:class ["container"] :replicant/key "camera"}
     [:div {:class ["topbar"]}
      (views/menu-button dispatch!)
      [:div {:class ["topbar-title"]} "Camera"]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["camera-page"]}
      [:div {:class ["camera-preview"]} (preview cam)]
      (when on?
        [:p {:class ["camera-note"]}
         (str "Sharing the " (facing-label facing)
              (when width (str " (" width " × " height ")"))
              " with this chat. It stays on when you go back to the chat; the screen stays awake.")])
      [:div {:class ["camera-actions"]}
       (if on?
         (button/button {:variant :outline :size :md :icon-left :x
                         :on-click (fn [_] (dispatch! {:type :device/camera-stop}))}
                        "Stop sharing")
         (button/button {:variant :primary :size :md :icon-left :camera
                         :disabled (boolean starting?)
                         :on-click (fn [_] (dispatch! {:type :device/camera-start}))}
                        "Share camera"))
       (when on?
         (button/button {:variant :ghost :size :md :icon-left :refresh
                         :on-click (fn [_] (dispatch! {:type :device/camera-flip}))}
                        (if (= "user" facing) "Use rear camera" "Use front camera")))]]]))

(defn- camera-pip
  "The floating thumbnail on every other page while the camera is shared:
   a tap opens the Camera page, the button stops sharing."
  [state dispatch!]
  (when (and (:on? (camera-state state))
             (not= page-id (get-in state [:web/route :page])))
    [:div {:class ["camera-pip"]}
     [:button {:class ["camera-pip-video-btn"]
               :title "Camera: open the page"
               :aria-label "Camera shared: open the Camera page"
               :on {:click (fn [_] (dispatch! {:type :route/navigate :page page-id}))}}
      (live-video "camera-pip-video")]
     [:button {:class ["icon-btn" "camera-pip-stop"]
               :title "Stop sharing the camera"
               :aria-label "Stop sharing the camera"
               :on {:click (fn [_] (dispatch! {:type :device/camera-stop}))}}
      (icon/icon {:icon-name :x :size :sm})]]))

(def extension
  {:id       :device
   :handlers {;; from the server only: a local event can't make this browser answer
              :device/query          (fn [_st {:keys [remote?] :as ev}]
                                       (when remote?
                                         {:effects [[:device/answer (select-keys ev [:id :what :opts])]]}))
              :device/answered       (fn [_st {:keys [id result error]}]
                                       {:effects [[:ws/send (cond-> {:type :device/reply :id id}
                                                              result (assoc :result result)
                                                              error  (assoc :error error))]]})
              :device/camera-start   start
              :device/camera-started started
              :device/camera-failed  failed
              :device/camera-stop    stop
              :device/camera-flip    flip
              :device/camera-ended   ended}
   :fx       {:device/answer         (fn [{:keys [dispatch!]} payload] (answer! dispatch! payload))
              :device/camera-share   (fn [{:keys [dispatch!]} payload] (share! dispatch! payload))
              :device/camera-unshare (fn [_ _] (camera/unshare!))}
   :taps     [resume-tap]
   :routes   {"camera" {:parse (fn [_] {:page page-id})
                        :path  {page-id (fn [_] "/camera")}}}
   :pages    {page-id camera-page}
   :overlays {:device/camera camera-pip}
   :nav-items [{:menu :overflow :mode :room :label "Share camera" :icon :camera
                :event {:type :route/navigate :page page-id}}
               {:menu :palette :label "Share camera" :icon :camera
                :event {:type :route/navigate :page page-id}}]})
