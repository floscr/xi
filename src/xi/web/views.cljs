(ns xi.web.views
  "Replicant view functions for the xi web client."
  (:require [clojure.string :as str]
            [xi.web.state :as state]
            [xi.web.ws :as ws]
            [xi.markdown.hiccup :as md]
            [ui.icon :as icon]
            [ui.button :as button]
            [ui.lightbox :as lightbox]
            [ui.spinner :as spinner]
            [ui.badge :as badge]
            [ui.form :as form]))

;; ---------------------------------------------------------------------------
;; Lightbox
;; ---------------------------------------------------------------------------

(defn- open-lightbox! [src]
  (swap! state/app-state assoc :lightbox-image src))

(defn- close-lightbox! []
  (swap! state/app-state assoc :lightbox-image nil))

;; ---------------------------------------------------------------------------
;; Chat actions
;; ---------------------------------------------------------------------------

(defn- send-message! []
  (let [text (str/trim (or (:compose-text @state/app-state) ""))
        images (:compose-images @state/app-state)]
    (when (or (seq text) (seq images))
      (let [final-text (if (and (empty? text) (seq images))
                         (let [n (count images)]
                           (if (= 1 n) "[Attached image]" (str "[Attached " n " images]")))
                         text)]
        (swap! state/app-state assoc :compose-text "" :compose-images [])
        (when-let [el (.querySelector js/document ".compose-editable")]
          (set! (.-textContent el) ""))
        ;; Optimistically show user message in timeline
        (swap! state/app-state update :messages conj
               (cond-> {:type :user :text final-text}
                 (seq images) (assoc :images (mapv #(select-keys % [:data :media-type]) images))))
        (if (seq images)
          (ws/dispatch-with-images! final-text
                                    (mapv #(select-keys % [:data :media-type]) images))
          (ws/dispatch! final-text))))))

;; ---------------------------------------------------------------------------
;; Topbar
;; ---------------------------------------------------------------------------

(defn- topbar [{:keys [title subtitle actions]}]
  [:div {:class ["topbar"]}
   [:div {:class ["topbar-title"]}
    title
    (when subtitle
      [:span {:class ["topbar-subtitle"]} (str " · " subtitle)])]
   (when (seq actions)
     (into [:div {:style {:display "flex" :gap "var(--size-1)" :margin-left "auto"}}]
           actions))])

;; ---------------------------------------------------------------------------
;; Message rendering
;; ---------------------------------------------------------------------------

(defn- truncate-lines
  "Keep at most n lines of text."
  [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) n)
      text
      (str (str/join "\n" (take n lines))
           "\n... (" (- (count lines) n) " more lines)"))))

(defn- tool-message [{:keys [title result is-error finished]} idx]
  (let [block-id (str "tool-" idx)
        expanded? (not (contains? (:collapsed-blocks @state/app-state) block-id))]
    [:div {:class ["tool-call-block"]}
     [:button {:class ["tool-call-toggle"]
               :on {:click (fn [_]
                             (swap! state/app-state update :collapsed-blocks
                                    (fn [s] (if (contains? s block-id)
                                              (disj s block-id)
                                              (conj (or s #{}) block-id)))))}}
      [:span {:class ["tool-call-toggle-icon"]}
       (icon/icon {:icon-name (if expanded? :chevron-down :chevron-right) :size :sm})]
      [:span {:class ["tool-call-toggle-label"]} (or title "tool")]
      (when finished
        (if is-error
          (badge/badge {:variant :danger} "error")
          (badge/badge {:variant :success} "done")))]
     (when (and expanded? result)
       [:div {:class ["tool-call-content"]}
        [:pre {:class ["tool-call-code"]}
         (truncate-lines result 30)]])]))

(defn- thinking-message [text idx]
  (let [block-id (str "thinking-" idx)
        expanded? (not (contains? (:collapsed-blocks @state/app-state) block-id))]
    [:div {:class ["thinking-block"]}
     [:button {:class ["thinking-toggle"]
               :on {:click (fn [_]
                             (swap! state/app-state update :collapsed-blocks
                                    (fn [s] (if (contains? s block-id)
                                              (disj s block-id)
                                              (conj (or s #{}) block-id)))))}}
      (icon/icon {:icon-name (if expanded? :chevron-down :chevron-right) :size :sm})
      [:span {:style {:font-weight "500" :margin-left "var(--size-1)"}} "Thinking"]]
     (when expanded?
       [:div {:style {:padding "0 0 var(--size-2)"}}
        [:pre {:class ["thinking-text"]} text]])]))

(defn- message-view [msg idx]
  (case (:type msg)
    :user
    [:div {:class ["post" "post--user"]}
     [:div {:class ["post-body"]}
      [:div {:class ["post-content"]}
       (when (seq (:images msg))
         [:div {:class ["user-images"]}
          (map-indexed
           (fn [i img]
             (let [src (str "data:" (:media-type img) ";base64," (:data img))]
               (lightbox/image-thumbnail
                {:key i
                 :src src
                 :class ["user-image"]
                 :alt "attached image"
                 :on-click #(open-lightbox! src)})))
           (:images msg))])
       [:p (:text msg)]]]]

    :assistant
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-body"]}
      [:div {:class ["post-content"]}
       (md/render (:text msg))]]]

    :thinking
    [:div {:class ["post"]}
     [:div {:class ["post-body"]}
      (thinking-message (:text msg) idx)]]

    :tool
    [:div {:class ["post" "post--tool"]}
     [:div {:class ["post-body"]}
      (tool-message msg idx)]]

    :error
    [:div {:class ["post"]}
     [:div {:class ["post-body"]}
      [:div {:class ["post-content" "error-text"]}
       [:p (:text msg)]]]]

    :status
    [:div {:class ["post"]}
     [:div {:class ["post-body"]}
      [:div {:class ["post-content" "status-text"]}
       [:p (:text msg)]]]]

    ;; fallback
    nil))

;; ---------------------------------------------------------------------------
;; Status indicators (rendered inline in timeline)
;; ---------------------------------------------------------------------------

(defn- pending-indicator []
  (let [pending (:pending-messages @state/app-state)]
    (when (seq pending)
      [:div {:class ["post" "post--assistant"]}
       [:div {:class ["post-body"]}
        [:div {:class ["post-content" "status-bubble"]}
         [:div {:class ["agent-status-spinner"]}]
         [:span
          (str (count pending) " pending " (if (= 1 (count pending)) "message" "messages") " — waiting for connection...")]]]])))

(defn- working-indicator []
  (if (:busy? @state/app-state)
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-body"]}
      [:div {:class ["post-content" "status-bubble"]}
       [:div {:class ["agent-status-spinner"]}]
       [:span "Working..."]
       [:button {:class ["icon-btn" "icon-btn--sm"]
                 :on {:click (fn [_] (ws/dispatch! {:type :abort}))}}
        (icon/icon {:icon-name :x :size :sm})]]]]
    (pending-indicator)))

;; ---------------------------------------------------------------------------
;; Compose box
;; ---------------------------------------------------------------------------

(defn- read-file-as-base64
  "Read a File object as base64 data. Returns a promise of {:data :media-type}."
  [^js file]
  (js/Promise.
   (fn [resolve _reject]
     (let [reader (js/FileReader.)]
       (set! (.-onload reader)
             (fn [_]
               (let [result (.-result reader)
                     base64 (second (.split result ","))]
                 (resolve {:data base64
                           :media-type (.-type file)
                           :preview-url (.createObjectURL js/URL file)}))))
       (.readAsDataURL reader file)))))

(defn- add-image-files!
  "Read image files and add to compose-images."
  [files]
  (when (pos? (.-length files))
    (-> (js/Promise.all
         (.map (js/Array.from files)
               (fn [file] (read-file-as-base64 file))))
        (.then (fn [results]
                 (swap! state/app-state update :compose-images
                        into (js->clj results :keywordize-keys true))))
        (.catch (fn [err]
                  (js/console.error "[xi-web] Failed to read image:" err))))))

(defn- handle-image-input!
  "Process files from a file input or camera capture."
  [^js event]
  (add-image-files! (.. event -target -files))
  (set! (.. event -target -value) ""))

(defn- handle-paste!
  "Handle paste events — extract images from clipboard."
  [^js event]
  (let [items (.. event -clipboardData -items)
        image-files (atom [])]
    (dotimes [i (.-length items)]
      (let [^js item (aget items i)]
        (when (str/starts-with? (.-type item) "image/")
          (when-let [file (.getAsFile item)]
            (swap! image-files conj file)))))
    (when (seq @image-files)
      (.preventDefault event)
      (add-image-files! (clj->js @image-files)))))

(defn- remove-compose-image! [idx]
  (swap! state/app-state update :compose-images
         (fn [imgs]
           (into (subvec imgs 0 idx)
                 (subvec imgs (inc idx))))))

(defn- image-preview-strip
  "Render thumbnail previews of pending image attachments."
  [images]
  (when (seq images)
    [:div {:class ["compose-images"]}
     (map-indexed
      (fn [idx img]
        [:div {:class ["compose-image-thumb"] :key idx}
         (lightbox/image-thumbnail
          {:src (:preview-url img)
           :alt "attachment"
           :on-click #(open-lightbox! (:preview-url img))})
         [:button {:class ["compose-image-remove"]
                   :on {:click (fn [_] (remove-compose-image! idx))}}
          (icon/icon {:icon-name :x :size :sm})]])
      images)]))

(defn- compose-box []
  (let [{:keys [compose-text compose-images busy?]} @state/app-state
        can-send? (and (not busy?)
                       (or (seq (str/trim (or compose-text "")))
                           (seq compose-images)))]
    [:div {:class ["compose-box"]}
     (image-preview-strip compose-images)
     [:div {:class ["compose-input-row"]}
      [:button {:class ["icon-btn" "compose-attach-btn"]
                :title "Attach image"
                :on {:click (fn [_]
                              (when-let [input (.querySelector js/document "#image-file-input")]
                                (.click input)))}}
       (icon/icon {:icon-name :image :size :sm})]
      [:input {:id "image-file-input"
               :type "file"
               :accept "image/*"
               :multiple true
               :style {:display "none"}
               :on {:change handle-image-input!}}]
      [:div {:class ["compose-input-wrapper"]}
       (form/form-input {:type :text
                          :placeholder "Message..."
                          :value (or compose-text "")
                          :attrs {:on {:input (fn [e]
                                               (swap! state/app-state assoc :compose-text (.. e -target -value)))
                                       :paste handle-paste!
                                       :keydown (fn [e]
                                                  (when (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                                                    (.preventDefault e)
                                                    (send-message!)))}}})]
      [:button {:class ["icon-btn"]
                :disabled (not can-send?)
                :on {:click (fn [_] (send-message!))}}
       (icon/icon {:icon-name :arrow-up :size :sm})]]]))


;; ---------------------------------------------------------------------------
;; Resume session picker
;; ---------------------------------------------------------------------------

(defn- resume-overlay []
  (when-let [sessions (:resume-sessions @state/app-state)]
    [:div {:class ["resume-overlay"]
           :on {:click (fn [_] (swap! state/app-state assoc :resume-sessions nil))}}
     [:div {:class ["resume-panel"]
            :on {:click (fn [e] (.stopPropagation e))}}
      [:div {:class ["resume-panel-header"]}
       [:span {:style {:font-weight "600"}} "Load Session"]
       [:button {:class ["icon-btn"]
                 :on {:click (fn [_] (swap! state/app-state assoc :resume-sessions nil))}}
        (icon/icon {:icon-name :x :size :sm})]]
      (if (empty? sessions)
        [:div {:class ["resume-empty"]} "No saved sessions."]
        [:div {:class ["resume-list"]}
         (for [s sessions]
           [:button {:class ["resume-item"]
                     :on {:click (fn [_]
                                  (swap! state/app-state assoc :resume-sessions nil)
                                  (ws/dispatch! (str "/resume " (:index s))))}}
            [:div {:class ["resume-item-name"]} (:name s)]
            [:div {:class ["resume-item-meta"]}
             (str (:timestamp s)
                  (when (:user-messages s)
                    (str " · " (:user-messages s) " msgs")))]])])]]))

;; ---------------------------------------------------------------------------
;; Chat view
;; ---------------------------------------------------------------------------

(defn- chat-view [{:keys [messages model connected? personal-agent?]}]
  [:div {:class ["container"]}
   (topbar {:title [:div {:style {:display "flex" :align-items "center" :gap "var(--size-2)"}}
                    [:button {:class ["icon-btn"]
                              :on {:click (fn [_] (ws/leave-room!))}}
                     (icon/icon {:icon-name :arrow-left :size :sm})]
                    (when model
                      [:span {:class ["topbar-subtitle"]
                              :style {:margin 0}} model])]
            :actions (into []
                          (remove nil?)
                          [(when-not connected?
                             [:span {:class ["offline-label"]} "Offline"])
                           (when-not personal-agent?
                             [:button {:class ["icon-btn"]
                                       :on {:click (fn [_] (ws/dispatch! "/resume"))}}
                              (icon/icon {:icon-name :chevron-down :size :sm})])
                           [:button {:class ["icon-btn"]
                                    :on {:click (fn [_] (ws/new-room!))}}
                            (icon/icon {:icon-name :plus :size :sm})]])})
   (resume-overlay)
   (lightbox/lightbox {:src (:lightbox-image @state/app-state)
                       :on-close close-lightbox!})
   [:div {:class ["timeline"]}
    [:div {:class ["timeline-content"]}
     (map-indexed
      (fn [idx msg] (message-view msg idx))
      messages)
     (working-indicator)]]
   (compose-box)])

;; ---------------------------------------------------------------------------
;; Home view (disconnected / room selection)
;; ---------------------------------------------------------------------------

(defn- format-session-time
  "Format an ISO timestamp to a short relative form."
  [ts]
  (when ts
    (try
      (let [d (js/Date. ts)
            now (js/Date.)
            diff-ms (- (.getTime now) (.getTime d))
            diff-min (/ diff-ms 60000)
            diff-hr (/ diff-min 60)
            diff-day (/ diff-hr 24)]
        (cond
          (< diff-min 60) (str (js/Math.floor diff-min) "m ago")
          (< diff-hr 24) (str (js/Math.floor diff-hr) "h ago")
          (< diff-day 7) (str (js/Math.floor diff-day) "d ago")
          :else (.toLocaleDateString d)))
      (catch :default _ ts))))

(defn- session-card
  "Render a single session/room card in the home list."
  [{:keys [sid name timestamp active? busy? unread? room-id connected? on-click]}]
  [:div {:class ["project-card"
                 (when active? "project-card--active")
                 (when-not connected? "project-card--offline")]
         :key (or sid room-id)
         :on {:click (fn [_] (on-click))}}
   [:div {:class ["project-card-icon"]}
    (cond
      busy?   (spinner/spinner {:size :sm})
      active? [:div {:class ["active-dot"]}]
      :else   (icon/icon {:icon-name :message-square}))]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]}
     (or name "New session")]
    [:span {:class ["project-card-path"]}
     (str (when timestamp (format-session-time timestamp))
          (cond
            busy?   " · working..."
            active? " · active"
            :else   ""))]]
   (when unread?
     [:div {:class ["unread-dot"]}])])

(defn- home-view [{:keys [connected? rooms home-sessions active-sessions
                          watched-sessions response-counts personal-agent?]}]
  (let [;; Build session-id → room-id lookup from rooms list
        session->room (into {}
                            (keep (fn [r]
                                    (when (:session-id r)
                                      [(:session-id r) (:id r)])))
                            rooms)
        ;; Build session-id → busy? lookup
        session-busy? (into #{}
                            (comp (filter :busy?)
                                  (keep :session-id))
                            rooms)
        ;; Active rooms without a matching home-session (e.g. first turn not done)
        known-sids (into #{} (keep :session-id) home-sessions)
        orphan-rooms (filterv (fn [r]
                                (and (or (nil? (:session-id r))
                                         (not (contains? known-sids (:session-id r))))
                                     ;; Only show rooms that exist (have clients or are busy)
                                     (or (pos? (:clients r)) (:busy? r))))
                              rooms)
        has-content? (or (seq home-sessions) (seq orphan-rooms))]
    [:div {:class ["container"]}
     (topbar {:title "Xi"
              :actions (into []
                             (remove nil?)
                             [(when-not connected?
                                [:span {:class ["offline-label"]} "Offline"])
                              (when connected?
                                [:button {:class ["icon-btn"]
                                          :on {:click (fn [_] (ws/join-room! "new"))}}
                                 (icon/icon {:icon-name :plus :size :sm})])])})
     [:div {:class ["home"]}
      (if has-content?
        [:div
         [:div {:class ["section"]}
          [:div {:class ["project-list"]}
           ;; Orphan rooms first (active but no saved session yet)
           (for [r orphan-rooms]
             (session-card
              {:sid nil
               :name (:session-name r)
               :timestamp nil
               :active? true
               :busy? (:busy? r)
               :room-id (:id r)
               :connected? connected?
               :on-click #(ws/join-session-room! (:id r))}))
           ;; Then saved sessions
           (map-indexed
            (fn [idx s]
              (let [sid (:session-id s)
                    active? (contains? active-sessions sid)
                    busy? (contains? session-busy? sid)
                    room-id (get session->room sid)
                    watched-count (get watched-sessions sid)
                    server-count (get response-counts sid)
                    unread? (and watched-count server-count
                                 (> server-count watched-count))]
                (session-card
                 {:sid sid
                  :name (:name s)
                  :timestamp (or (:last-accessed s) (:timestamp s))
                  :active? active?
                  :busy? busy?
                  :unread? unread?
                  :room-id room-id
                  :connected? connected?
                  :on-click #(do
                               ;; Clear unread immediately on click
                               (when sid
                                 (ws/unwatch-session! sid))
                               (if connected?
                                 (if room-id
                                   (ws/join-session-room! room-id)
                                   (ws/join-and-resume! (inc idx)))
                                 (ws/open-cached-session! sid)))})))
            home-sessions)]]]
        ;; No sessions at all (no cache, not connected)
        [:div {:class ["empty-state"]}
         [:div
          [:p "Connecting to xi server..."]
          [:p {:class ["status-text"]}
           (str "ws://" (.-hostname js/window.location) ":7474")]]])]]))
;; Root
;; ---------------------------------------------------------------------------

(defn root-view [app-state]
  (case (get-in app-state [:route :page])
    :chat (chat-view app-state)
    (home-view app-state)))
