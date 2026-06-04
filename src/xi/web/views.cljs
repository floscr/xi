(ns xi.web.views
  "Replicant view functions for the xi web client."
  (:require [clojure.string :as str]
            [xi.web.state :as state]
            [xi.web.ws :as ws]
            [xi.markdown.hiccup :as md]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.theme-css :as theme]
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
        (when-let [el (.querySelector js/document ".compose-input-wrapper textarea")]
          (set! (.-value el) ""))

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

(defn- file-extension
  "Extract file extension from a path (without dot), or nil."
  [path]
  (when (string? path)
    (let [idx (str/last-index-of path ".")]
      (when (and idx (pos? idx))
        (subs path (inc idx))))))

(defn- tool-language
  "Detect language for a tool result based on tool name and arguments."
  [tool-name arguments]
  (case tool-name
    ("Bash" "bash") "bash"
    ("Read" "read") (file-extension (or (get arguments "file_path")
                                         (get arguments "path")
                                         (get arguments :file_path)
                                         (get arguments :path)))
    ("Write" "write") (file-extension (or (get arguments "file_path")
                                           (get arguments "path")
                                           (get arguments :file_path)
                                           (get arguments :path)))
    ("Edit" "edit") (file-extension (or (get arguments "file_path")
                                         (get arguments "path")
                                         (get arguments :file_path)
                                         (get arguments :path)))
    nil))

(defn- highlight-text
  "Syntax-highlight a text string with the given language.
   Returns hiccup nodes, or the plain text string if no grammar found."
  [text lang]
  (if-let [grammar (when lang (grammars/get-grammar lang))]
    (let [tokens (hl/merge-adjacent (hl/tokenize grammar text))]
      (into [:code]
            (mapv (fn [{:keys [type value]}]
                    (if-let [cls (theme/token-class type)]
                      [:span {:class cls} value]
                      value))
                  tokens)))
    text))

(defn- tool-message [{:keys [title tool-name arguments result is-error finished]} idx]
  (let [block-id (str "tool-" idx)
        expanded? (not (contains? (:collapsed-blocks @state/app-state) block-id))
        lang (when (and result (not is-error))
               (tool-language tool-name arguments))]
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
       (let [truncated (truncate-lines result 30)]
         [:div {:class ["tool-call-content"]}
          [:pre {:class ["tool-call-code"]}
           (highlight-text truncated lang)]]))]))

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

;; ---------------------------------------------------------------------------
;; Dictation (voice recording)
;; ---------------------------------------------------------------------------

(defonce ^:private media-recorder (atom nil))

(def ^:private SEGMENT_MS
  "Duration of each recording segment in milliseconds."
  4000)

(defonce ^:private dictation-stream (atom nil))
(defonce ^:private pending-transcriptions (atom 0))

(defn- write-wav-header!
  "Write a 44-byte PCM WAV header into a DataView."
  [^js view sample-rate num-samples]
  (let [data-bytes (* num-samples 2)]
    (doseq [[i c] (map-indexed vector "RIFF")]
      (.setUint8 view i (.charCodeAt c 0)))
    (.setUint32 view 4 (+ 36 data-bytes) true)
    (doseq [[i c] (map-indexed vector "WAVE")]
      (.setUint8 view (+ 8 i) (.charCodeAt c 0)))
    (doseq [[i c] (map-indexed vector "fmt ")]
      (.setUint8 view (+ 12 i) (.charCodeAt c 0)))
    (.setUint32 view 16 16 true)
    (.setUint16 view 20 1 true)
    (.setUint16 view 22 1 true)
    (.setUint32 view 24 sample-rate true)
    (.setUint32 view 28 (* sample-rate 2) true)
    (.setUint16 view 32 2 true)
    (.setUint16 view 34 16 true)
    (doseq [[i c] (map-indexed vector "data")]
      (.setUint8 view (+ 36 i) (.charCodeAt c 0)))
    (.setUint32 view 40 data-bytes true)))

(defn- encode-pcm-wav
  "Encode a Float32Array of mono audio samples into a 16-bit PCM WAV ArrayBuffer."
  [^js channel-data sample-rate]
  (let [len (.-length channel-data)
        wav-buf (js/ArrayBuffer. (+ 44 (* len 2)))
        view (js/DataView. wav-buf)]
    (write-wav-header! view sample-rate len)
    (dotimes [i len]
      (let [s (aget channel-data i)
            v (js/Math.max -1 (js/Math.min 1 s))]
        (.setInt16 view (+ 44 (* i 2))
                   (if (< v 0) (* v 32768) (* v 32767))
                   true)))
    wav-buf))

(defn- array-buffer->base64
  "Convert an ArrayBuffer to a base64 string."
  [^js buf]
  (let [uint8 (js/Uint8Array. buf)
        binary (apply str (map #(js/String.fromCharCode %) uint8))]
    (js/btoa binary)))

(defn- append-dictation-text!
  "Append transcribed text to the compose box."
  [text]
  (swap! state/app-state update :compose-text
         (fn [prev]
           (let [prev (or prev "")]
             (if (seq prev)
               (str prev " " text)
               text)))))

(defn- send-segment-for-transcription!
  "Convert a segment's audio chunks to WAV, send to server, append result."
  [chunks]
  (swap! pending-transcriptions inc)
  (let [blob (js/Blob. (clj->js chunks))]
    (-> (.arrayBuffer blob)
        (.then (fn [buf]
                 (let [audio-ctx (js/AudioContext. #js {:sampleRate 16000})]
                   (-> (.decodeAudioData audio-ctx buf)
                       (.then (fn [decoded]
                                (let [wav-buf (encode-pcm-wav (.getChannelData decoded 0)
                                                              (.-sampleRate decoded))
                                      b64 (array-buffer->base64 wav-buf)]
                                  (ws/send-dictation!
                                   b64
                                   (fn [text]
                                     (swap! pending-transcriptions dec)
                                     (when (seq text)
                                       (append-dictation-text! text)))))))))))
        (.catch (fn [err]
                  (js/console.error "[dictation] segment transcription failed:" err)
                  (swap! pending-transcriptions dec))))))

(declare start-segment!)

(defn- stop-segment!
  "Stop the current MediaRecorder segment (triggers onstop → transcribe → next segment)."
  []
  (when-let [recorder @media-recorder]
    (when (= "recording" (.-state recorder))
      (.stop recorder))))

(defn- start-segment!
  "Start a new recording segment on the given mic stream.
   After SEGMENT_MS, auto-stops and starts the next segment."
  [^js stream]
  (when @dictation-stream
    (let [chunks (atom [])
          recorder (js/MediaRecorder. stream #js {:mimeType "audio/webm;codecs=opus"})]
      (set! (.-ondataavailable recorder)
            (fn [e]
              (when (pos? (.-size (.-data e)))
                (swap! chunks conj (.-data e)))))
      (set! (.-onstop recorder)
            (fn [_]
              (when (seq @chunks)
                (send-segment-for-transcription! @chunks))
              ;; Start next segment if still recording
              (when @dictation-stream
                (start-segment! stream))))
      (.start recorder)
      (reset! media-recorder recorder)
      ;; Auto-stop after SEGMENT_MS to trigger next segment
      (js/setTimeout
       (fn []
         (when (and @dictation-stream
                    (= "recording" (.-state recorder)))
           (.stop recorder)))
       SEGMENT_MS))))

(defn- start-dictation! []
  (-> (.getUserMedia js/navigator.mediaDevices
                     #js {:audio #js {:sampleRate 16000 :channelCount 1}})
      (.then (fn [stream]
               (reset! dictation-stream stream)
               (reset! pending-transcriptions 0)
               (swap! state/app-state assoc :recording? true)
               (start-segment! stream)))
      (.catch (fn [err]
                (js/console.error "[dictation] mic access denied:" err)))))

(defn- stop-dictation! []
  (when-let [stream @dictation-stream]
    ;; Clear stream first so onstop won't start another segment
    (reset! dictation-stream nil)
    ;; Stop current recorder (will trigger final transcription)
    (stop-segment!)
    ;; Release mic
    (doseq [track (.getTracks stream)]
      (.stop track))
    (reset! media-recorder nil)
    (swap! state/app-state dissoc :recording?)))

(defn- toggle-dictation! []
  (if @dictation-stream
    (stop-dictation!)
    (start-dictation!)))

;; ---------------------------------------------------------------------------
;; Slash command autocomplete
;; ---------------------------------------------------------------------------

(def ^:private web-hidden-commands
  "Commands that don't make sense in the web client."
  #{"quit" "dictate" "project" "join"})

(defn- slash-command-matches
  "Filter commands matching the current slash input."
  [commands compose-text]
  (when (and (string? compose-text)
             (str/starts-with? compose-text "/")
             (not (str/includes? compose-text " ")))
    (let [query (subs compose-text 1)]
      (->> commands
           (remove #(contains? web-hidden-commands (:name %)))
           (filter #(str/starts-with? (:name %) query))
           vec))))

(defn- select-slash-command! [cmd-name]
  (let [text (str "/" cmd-name)]
    (swap! state/app-state assoc :compose-text "" :slash-selected 0)
    (when-let [el (.querySelector js/document ".compose-input-wrapper textarea")]
      (set! (.-value el) ""))
    (ws/dispatch! text)))

(defn- slash-command-dropdown [matches selected-idx]
  (when (seq matches)
    [:div {:class ["slash-dropdown"]}
     (map-indexed
      (fn [idx {:keys [name description]}]
        [:button {:class ["slash-item" (when (= idx selected-idx) "slash-item--selected")]
                  :key name
                  :on {:click (fn [e]
                                (.preventDefault e)
                                (select-slash-command! name))
                       :mousedown (fn [e]
                                    ;; Prevent textarea blur
                                    (.preventDefault e))}}
         [:span {:class ["slash-item-name"]} (str "/" name)]
         (when description
           [:span {:class ["slash-item-desc"]} description])])
      matches)]))

;; ---------------------------------------------------------------------------
;; Compose box
;; ---------------------------------------------------------------------------

(defn- compose-box []
  (let [{:keys [compose-text compose-images busy? recording? commands personal-agent?]} @state/app-state
        slash-matches (when-not personal-agent?
                        (slash-command-matches commands compose-text))
        selected-idx (or (:slash-selected @state/app-state) 0)
        transcribing? (and (not recording?) (pos? @pending-transcriptions))
        can-send? (and (not busy?)
                       (or (seq (str/trim (or compose-text "")))
                           (seq compose-images)))]
    [:div {:class ["compose-box"]}
     (slash-command-dropdown slash-matches selected-idx)
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
       (form/form-textarea-auto {:placeholder "Message..."
                                  :value (or compose-text "")
                                  :max-rows 3
                                  :attrs {:on {:input (fn [e]
                                                       (let [v (.. e -target -value)]
                                                         (swap! state/app-state assoc
                                                                :compose-text v
                                                                :slash-selected 0)))
                                               :paste handle-paste!
                                               :keydown (fn [e]
                                                          (if (seq slash-matches)
                                                            (case (.-key e)
                                                              "ArrowUp"
                                                              (do (.preventDefault e)
                                                                  (swap! state/app-state update :slash-selected
                                                                         (fn [i] (mod (dec (or i 0)) (count slash-matches)))))
                                                              "ArrowDown"
                                                              (do (.preventDefault e)
                                                                  (swap! state/app-state update :slash-selected
                                                                         (fn [i] (mod (inc (or i 0)) (count slash-matches)))))
                                                              "Tab"
                                                              (do (.preventDefault e)
                                                                  (let [cmd (:name (nth slash-matches (or selected-idx 0)))]
                                                                    (swap! state/app-state assoc :compose-text (str "/" cmd))
                                                                    (when-let [el (.-target e)]
                                                                      (set! (.-value el) (str "/" cmd)))))
                                                              "Enter"
                                                              (do (.preventDefault e)
                                                                  (let [cmd (:name (nth slash-matches (or selected-idx 0)))]
                                                                    (select-slash-command! cmd)))
                                                              "Escape"
                                                              (do (.preventDefault e)
                                                                  (swap! state/app-state assoc :compose-text "" :slash-selected 0)
                                                                  (when-let [el (.-target e)]
                                                                    (set! (.-value el) "")))
                                                              ;; default — let it through
                                                              nil)
                                                            ;; No slash matches — normal behavior
                                                            (when (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                                                              (.preventDefault e)
                                                              (send-message!))))}}})]
      [:button {:class ["icon-btn" (when recording? "recording-active")]
                :title (cond
                         transcribing? "Transcribing..."
                         recording? "Stop recording"
                         :else "Voice input")
                :disabled transcribing?
                :on {:click (fn [_] (toggle-dictation!))}}
       (cond
         transcribing? (spinner/spinner {:size :sm})
         recording? [:span {:style {:font-size "16px"}} "⏹"]
         :else [:span {:style {:font-size "16px"}} "🎤"])]
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
     [:div {:class ["unread-dot"]}])
   (when (and sid connected? (not active?))
     [:button {:class ["icon-btn" "icon-btn--sm" "session-delete-btn"]
               :on {:click (fn [e]
                             (.stopPropagation e)
                             (when (js/confirm "Delete this session?")
                               (ws/delete-session! sid)))}}
      (icon/icon {:icon-name :trash :size :sm})])])

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
      (if (or has-content? connected?)
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
        ;; Not connected and no cached sessions
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
