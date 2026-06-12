(ns xi.web.views
  "Replicant view layer — pure (state → hiccup) over the same room state the
   TUI renders. Event handlers dispatch events; there is no view-local atom.

   Phase 7a: the online chat view (topbar, timeline of history entries,
   compose input, abort, permission dialogs). Home view + router land in 7b."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.markdown.hiccup :as md]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.theme-css :as theme]
            [xi.util :as util]
            [ui.icon :as icon]
            [ui.form :as form]
            [ui.button :as button]
            [ui.lightbox :as lightbox]
            [ui.theme-toggle :as theme-toggle]))

;; ── Standalone (homescreen) detection ────────────────────────────────────────

(def standalone?
  "True when running as an installed PWA / homescreen app (no browser chrome)."
  (or (some-> js/navigator .-standalone)  ;; iOS Safari
      (and (exists? js/window.matchMedia)
           (.-matches (.matchMedia js/window "(display-mode: standalone)")))))

;; ── Timeline virtualization ──────────────────────────────────────────────────

(def ^:private initial-window-size
  "Number of history entries rendered initially; keeps the DOM light on
   long sessions."
  60)

(def ^:private window-step
  "How many more entries \"Show earlier\" reveals per click."
  40)

;; ── Spinner ──────────────────────────────────────────────────────────────────

(defn- spinner [] [:div {:class ["agent-status-spinner"]}])

;; ── Tool rendering ───────────────────────────────────────────────────────────

(defn- get-arg [args k]
  (or (get args (name k)) (get args k)))

(defn- tool-summary
  "Short one-line argument summary shown in the tool header."
  [tool args]
  (case tool
    ("Bash" "bash") (get-arg args :command)
    ("Read" "read")  (or (get-arg args :file_path) (get-arg args :path))
    ("Write" "write") (or (get-arg args :file_path) (get-arg args :path))
    ("Edit" "edit")  (or (get-arg args :file_path) (get-arg args :path))
    ("Grep" "grep")  (get-arg args :pattern)
    ("Glob" "find")  (get-arg args :pattern)
    ("ls")           (get-arg args :path)
    (let [v (some (fn [k] (let [x (get-arg args k)]
                            (when (and (string? x) (seq x)) x)))
                  [:command :file_path :path :pattern :query :url :prompt :description])]
      v)))

(defn- file-ext [path]
  (when (and (string? path) (str/includes? path "."))
    (-> path (str/split #"\.") last str/lower-case)))

(defn- tool-grammar
  "Highlighting grammar for a tool's output, or nil."
  [tool args]
  (let [path (case tool
               ("Read" "Write" "Edit") (get-arg args :file_path)
               ("read" "write" "edit") (get-arg args :path)
               nil)]
    (when path (grammars/get-grammar (file-ext path)))))

(defn- highlight-code
  "Tokenize + class-wrap text against a grammar → hiccup [:code ...]."
  [grammar text]
  (let [tokens (hl/merge-adjacent (hl/tokenize grammar text))]
    (into [:code]
          (mapv (fn [{:keys [type value]}]
                  (if-let [cls (theme/token-class type)]
                    [:span {:class cls} value]
                    value))
                tokens))))

(defn- truncate-lines [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) n)
      text
      (str (str/join "\n" (take n lines))
           "\n… (" (- (count lines) n) " more lines)"))))

(def ^:private expanded-tools
  "Tools whose output is shown expanded by default."
  #{"Bash" "bash" "Edit" "edit" "Write" "write" "web_search" "fetch"})

(defn- tool-post [{:keys [tool arguments result is-error status]}]
  (let [name      (util/strip-mcp-prefix tool)
        summary   (tool-summary name arguments)
        running?  (= :running status)
        text      (util/extract-text-content result)
        grammar   (when (and text (not is-error)) (tool-grammar name arguments))
        label     (str name (when (seq summary) (str " " (util/truncate (first (str/split-lines (str summary))) 80))))]
    [:div {:class ["post" "post--tool"]}
     [:details {:class ["tool-call-block"] :open (boolean (expanded-tools name))}
      [:summary {:class ["tool-call-toggle"]}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class ["tool-call-toggle-label"]} label]
       (cond
         running? (spinner)
         is-error [:span {:class ["error-text"]} " error"])]
      (when (seq text)
        [:div {:class ["tool-call-content"]}
         [:pre {:class ["tool-call-code"]}
          (let [shown (truncate-lines text 30)]
            (if grammar (highlight-code grammar shown) shown))]])]]))

;; ── History entry → post ─────────────────────────────────────────────────────

(defn- entry->post [dispatch! entry]
  (case (:kind entry)
    :user
    [:div {:class ["post" "post--user"]}
     [:div {:class ["post-body"]}
      (if-let [imgs (seq (:images entry))]
        [:div {:class ["user-images"]}
         (map-indexed
          (fn [i {:keys [data media-type]}]
            (let [src (str "data:" media-type ";base64," data)]
              [:img {:replicant/key i
                     :class ["user-image" "lightbox-thumb"]
                     :src src
                     :alt "attached"
                     :on {:click (fn [_] (dispatch! {:type :lightbox/open :src src}))}}]))
          imgs)]
        (when-let [n (:image-count entry)]
          (when (pos? n)
            [:div {:class ["status-text"]} (str "📎 " n " image" (when (> n 1) "s"))])))
      (when (seq (:text entry))
        [:div {:class ["post-content"]} (:text entry)])]]

    :text
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-body"]}
      (md/render (:text entry))]]

    :thinking
    [:div {:class ["post" "post--assistant"]}
     [:details {:class ["thinking-block"] :open true}
      [:summary {:class ["thinking-toggle"]}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:style {:font-weight "500"}} "Thinking"]]
      [:pre {:class ["thinking-text"]} (:text entry)]]]

    :tool-call
    (tool-post entry)

    :status
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} (:text entry)]]

    :error
    (let [msg (or (:message (:error entry)) (pr-str (:error entry)))]
      (when-not (str/includes? (str msg) "null is not an object")
        [:div {:class ["post" "post--assistant"]}
         [:div {:class ["post-content" "error-text"]} (str "[Error] " msg)]]))

    :aborted
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} "Interrupted."]]

    nil))

;; ── Compose ──────────────────────────────────────────────────────────────────

(defn- compose-textarea-el []
  (.querySelector js/document ".compose-input-wrapper textarea"))

;; ── Image attachments ────────────────────────────────────────────────────────

(def ^:private MAX_IMAGE_DIMENSION
  "Max width/height before we downscale on the client."
  1568)

(defn- resize-image-file
  "Read a File, downscale if > MAX_IMAGE_DIMENSION; promise of
   {:data base64 :media-type mime} or nil on failure."
  [^js file]
  (js/Promise.
   (fn [resolve _]
     (let [reader (js/FileReader.)]
       (set! (.-onload reader)
             (fn [_]
               (let [img (js/Image.)]
                 (set! (.-onload img)
                       (fn [_]
                         (let [w (.-naturalWidth img)
                               h (.-naturalHeight img)
                               scale (if (or (> w MAX_IMAGE_DIMENSION) (> h MAX_IMAGE_DIMENSION))
                                       (/ MAX_IMAGE_DIMENSION (max w h))
                                       1)
                               nw (js/Math.round (* w scale))
                               nh (js/Math.round (* h scale))
                               canvas (js/document.createElement "canvas")
                               ctx (.getContext canvas "2d")]
                           (set! (.-width canvas) nw)
                           (set! (.-height canvas) nh)
                           (.drawImage ctx img 0 0 nw nh)
                           (let [data-url (.toDataURL canvas "image/jpeg" 0.85)
                                 [_ media-type b64] (re-matches #"data:([^;]+);base64,(.*)" data-url)]
                             (resolve {:data b64
                                       :media-type (or media-type "image/jpeg")})))))
                 (set! (.-onerror img) (fn [_] (resolve nil)))
                 (set! (.-src img) (.-result reader)))))
       (set! (.-onerror reader) (fn [_] (resolve nil)))
       (.readAsDataURL reader file)))))

(defn- add-image-files!
  "Resize a seq of Files and stage them as compose attachments."
  [dispatch! files]
  (when (seq files)
    (-> (js/Promise.all (to-array (map resize-image-file files)))
        (.then (fn [results]
                 (when-let [valid (seq (remove nil? (array-seq results)))]
                   (dispatch! {:type :compose/add-images :images (vec valid)}))))
        (.catch (fn [err] (js/console.error "[xi-web] image read failed:" err))))))

(defn- handle-compose-paste! [dispatch! ^js e]
  (let [items (.. e -clipboardData -items)
        files (->> (range (.-length items))
                   (keep (fn [i]
                           (let [^js item (aget items i)]
                             (when (str/starts-with? (.-type item) "image/")
                               (.getAsFile item))))))]
    (when (seq files)
      (.preventDefault e)
      (add-image-files! dispatch! files))))

(defn- compose-image-strip [dispatch! images]
  (when (seq images)
    [:div {:class ["compose-images"]}
     (map-indexed
      (fn [idx {:keys [data media-type]}]
        [:div {:replicant/key idx :class ["compose-image-thumb"]}
         (let [src (str "data:" media-type ";base64," data)]
           [:img {:src src :alt "attachment" :class ["lightbox-thumb"]
                  :on {:click (fn [_] (dispatch! {:type :lightbox/open :src src}))}}])
         [:button {:class ["compose-image-remove"]
                   :on {:click (fn [_] (dispatch! {:type :compose/remove-image :idx idx}))}}
          (icon/icon {:icon-name :x :size :sm})]])
      images)]))

;; ── Command suggestions ───────────────────────────────────────────────────────

(def ^:private web-commands
  "Commands shown in the web suggestion popup. Excludes TUI-only commands
   (quit, reload, diff, tree, events, buffers, debug, prompt)."
  [{:name "help"     :description "Show available commands"}
   {:name "model"    :description "Show or set model"}
   {:name "resume"   :description "Resume a previous session"}
   {:name "sessions" :description "List previous sessions"}
   {:name "new"      :description "Start a new session"}
   {:name "clear"    :description "Clear current session"}
   {:name "truncate" :description "Summarize conversation to reduce context"}
   {:name "commit"   :description "Review changes and create a git commit"}])

(defn- match-commands
  "Filter commands by prefix query (text after the /)."
  [query]
  (let [q (str/lower-case (or query ""))]
    (filterv #(str/starts-with? (:name %) q) web-commands)))

(defn- command-suggestions
  "Popup list of matching slash commands above the compose box."
  [dispatch! room-id draft-key commands selected-index]
  (when (seq commands)
    [:div {:class ["slash-dropdown"]}
     (map-indexed
      (fn [i {:keys [name description]}]
        [:button {:class ["slash-item"
                          (when (= i selected-index) "slash-item--selected")]
                  :on {:click (fn [_]
                                (dispatch! {:type :input/submit :room-id room-id
                                            :text (str "/" name)})
                                (when-let [^js el (compose-textarea-el)]
                                  (set! (.-value el) ""))
                                (dispatch! {:type :compose/clear-draft :draft-key draft-key}))
                       :mouseenter (fn [_]
                                     (dispatch! {:type :cmd/select :index i}))}}
         [:span {:class ["slash-item-name"]} (str "/" name)]
         [:span {:class ["slash-item-desc"]} description]])
      commands)]))

(defn- submit-compose! [dispatch! room-id session-id images draft-key draft]
  (let [text (str/trim (or draft ""))]
    (when (or (seq text) (seq images))
      (when-let [^js el (compose-textarea-el)]
        (set! (.-value el) ""))
      (dispatch! {:type :compose/clear-draft :draft-key draft-key})
      (when (seq images)
        (dispatch! {:type :compose/clear-images}))
      (if room-id
        (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                     (seq images) (assoc :images (vec images))))
        ;; No active room yet (cached session view) — stash the message and
        ;; let the pending-submit tap fire it once :room/joined arrives.
        (dispatch! (cond-> {:type :submit/pending :session-id session-id :text text}
                     (seq images) (assoc :images (vec images))))))))

(defn- compose-box [dispatch! room busy? images draft-key draft session-id cmd-selected]
  (let [room-id  (:id room)
        cmd-query (when (and (string? draft) (str/starts-with? draft "/"))
                    (subs draft 1))
        cmd-matches (when (some? cmd-query) (match-commands cmd-query))
        cmd-open?   (seq cmd-matches)]
    [:div {:class ["compose-box"]}
     (compose-image-strip dispatch! images)
     (when cmd-open?
       (command-suggestions dispatch! room-id draft-key cmd-matches
                            (min (or cmd-selected 0) (dec (count cmd-matches)))))
     [:div {:class ["compose-input-row"]}
      [:button {:class ["icon-btn" "compose-attach-btn"]
                :on {:click (fn [_]
                              (some-> (.getElementById js/document "compose-image-input")
                                      (.click)))}}
       (icon/icon {:icon-name :image :size :md})]
      [:input {:id "compose-image-input" :type "file"
               :accept "image/*" :multiple true
               :style {:display "none"}
               :on {:change (fn [^js e]
                              (add-image-files! dispatch! (array-seq (.. e -target -files)))
                              (set! (.. e -target -value) ""))}}]
      [:div {:class ["compose-input-wrapper"]}
       (form/form-textarea-auto
        {:placeholder (if busy? "Working…" "Message…")
         :value (or draft "")
         :max-rows 6
         :attrs {:on {:input (fn [^js e]
                               (dispatch! {:type :compose/set-draft
                                           :draft-key draft-key
                                           :text (.. e -target -value)}))
                      :paste (fn [^js e] (handle-compose-paste! dispatch! e))
                      :keydown
                      (fn [^js e]
                        (if cmd-open?
                          (let [sel (min (or cmd-selected 0) (dec (count cmd-matches)))]
                            (case (.-key e)
                              "ArrowUp"
                              (do (.preventDefault e)
                                  (dispatch! {:type :cmd/select
                                              :index (mod (dec sel) (count cmd-matches))}))
                              "ArrowDown"
                              (do (.preventDefault e)
                                  (dispatch! {:type :cmd/select
                                              :index (mod (inc sel) (count cmd-matches))}))
                              ("Enter" "Tab")
                              (do (.preventDefault e)
                                  (let [cmd-name (:name (nth cmd-matches sel))]
                                    (dispatch! {:type :input/submit :room-id room-id
                                                :text (str "/" cmd-name)})
                                    (when-let [^js el (compose-textarea-el)]
                                      (set! (.-value el) ""))
                                    (dispatch! {:type :compose/clear-draft
                                                :draft-key draft-key})))
                              "Escape"
                              (do (.preventDefault e)
                                  (when-let [^js el (compose-textarea-el)]
                                    (set! (.-value el) ""))
                                  (dispatch! {:type :compose/clear-draft
                                              :draft-key draft-key}))
                              nil))
                          (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                            (.preventDefault e)
                            (when-not busy?
                              (submit-compose! dispatch! room-id session-id
                                               images draft-key
                                               (.. e -target -value))))))}}})]
      (if busy?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
         (icon/icon {:icon-name :circle-x :size :md})]
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (submit-compose! dispatch! room-id session-id
                                                       images draft-key draft))}}
         (icon/icon {:icon-name :arrow-up :size :md})])]]))

;; ── Permission dialog ────────────────────────────────────────────────────────

(defn- dialog-overlay [dispatch! room]
  (when-let [{:keys [id type message text options]} (first (get-in room [:ui :dialogs]))]
    (let [room-id (:id room)
          answer! (fn [value]
                    (dispatch! {:type :ui/dialog-response
                                :room-id room-id :dialog-id id :value value})
                    (dispatch! {:type :ui/dialog-close
                                :room-id room-id :dialog-id id}))]
      [:div {:class ["confirm-overlay"]}
       [:div {:class ["confirm-panel"]}
        [:div {:class ["confirm-message"]} (or message text)]
        [:div {:class ["confirm-actions"]}
         (case type
           :select
           (for [{:keys [label value]} options]
             [:button {:class ["confirm-btn" "confirm-btn--allow"]
                       :on {:click (fn [_] (answer! value))}} label])
           :alert
           [:button {:class ["confirm-btn" "confirm-btn--allow"]
                     :on {:click (fn [_] (answer! nil))}} "OK"]
           ;; :confirm (default)
           (list
            [:button {:class ["confirm-btn" "confirm-btn--deny"]
                      :on {:click (fn [_] (answer! false))}} "Deny"]
            [:button {:class ["confirm-btn" "confirm-btn--allow"]
                      :on {:click (fn [_] (answer! true))}} "Allow"]))]]])))

;; ── Chat view ────────────────────────────────────────────────────────────────

(defn- offline-badge [state]
  (when (false? (:web/connected? state))
    [:span {:class ["offline-label"]} "Offline"]))

(defn- model-selector [dispatch! room-id models current-model]
  [:div {:class ["model-selector-backdrop"]
         :on {:click (fn [_] (dispatch! {:type :models/close}))}}
   [:div {:class ["model-selector"]}
    (for [m models]
      [:button {:class ["model-selector-item"
                        (when (= m current-model) "model-selector-item--active")]
                :replicant/key m
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (dispatch! {:type :models/select :model m :room-id room-id}))}}
       m])]])

(defn- chat-view [state dispatch!]
  (let [room    (state/active-room state)
        sid     (get-in state [:web/route :session-id])
        cached  (get-in state [:web/cache sid])
        history (or (:history room) (:history cached))
        busy?   (get-in room [:agent :busy?])
        model   (or (get-in room [:agent :model]) (:model cached))
        ready?  (or room (seq history))
        draft-key (or (get-in room [:session :id]) sid :new)
        model-list (:web/model-list state)]
    [:div {:class ["container"] :replicant/key "chat"}
     [:div {:class ["topbar"]}
      [:button {:class ["icon-btn" "icon-btn--sm"]
                :on {:click (fn [_] (dispatch! {:type :route/navigate :page :home}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]}
       "Xi"
       (when model
         [:span {:class ["topbar-subtitle" "topbar-subtitle--clickable"]
                 :on {:click (fn [_] (dispatch! {:type :models/web-list}))}}
          (str " · " model)])]
      (when model-list
        (model-selector dispatch! (:id room) model-list model))
      (theme-toggle/theme-toggle
       {:mode (or (:web/theme-mode state) "auto")
        :size :sm
        :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))})
      (when standalone?
        [:button {:class ["icon-btn" "icon-btn--sm"]
                  :on {:click (fn [_] (.reload js/location))}}
         (icon/icon {:icon-name :refresh :size :md})])
      (offline-badge state)]
     [:div {:class ["timeline"]}
      [:div {:class ["timeline-content"]}
       (if ready?
         (let [entries (vec history)
               total   (count entries)
               win     (or (:web/timeline-window state) initial-window-size)
               start   (max 0 (- total win))]
           (list
            (when (pos? start)
              [:div {:class ["load-earlier"]}
               (button/button
                {:variant :ghost :size :sm
                 :on-click (fn [_] (dispatch! {:type :timeline/set-window
                                               :window (+ win window-step)}))}
                (str "Show " (min window-step start) " earlier messages"
                     " (" start " hidden)"))])
            (keep (partial entry->post dispatch!) (subvec entries start total))))
         [:div {:class ["empty-state"]} (spinner) [:p "Connecting…"]])]]
     (when busy?
       [:div {:class ["working-indicator"]}
        (spinner) [:span "Working…"]])
     (dialog-overlay dispatch! room)
     (lightbox/lightbox {:src (:web/lightbox state)
                         :on-close (fn [] (dispatch! {:type :lightbox/close}))})
     (compose-box dispatch! room busy? (:web/compose-images state)
                  draft-key (get-in state [:web/drafts draft-key]) sid
                  (:web/cmd-selected state))]))

;; ── Home view ────────────────────────────────────────────────────────────────

(defn- format-relative-time [t]
  (let [ms (cond (number? t) t
                 (string? t) (let [n (.getTime (js/Date. t))] (when-not (js/isNaN n) n))
                 :else nil)]
    (when ms
      (let [m (/ (- (js/Date.now) ms) 60000)]
        (cond (< m 1)    "just now"
              (< m 60)   (str (js/Math.floor m) "m ago")
              (< m 1440) (str (js/Math.floor (/ m 60)) "h ago")
              :else      (str (js/Math.floor (/ m 1440)) "d ago"))))))

(defn- session-card [dispatch! {:keys [session-id name timestamp active? busy? has-dialog? unread?]}]
  [:div {:class ["project-card" (when active? "project-card--active")
                 (when has-dialog? "project-card--dialog")]
         :replicant/key (or session-id (str "card-" name))
         :on {:click (fn [_] (dispatch! {:type :route/navigate
                                         :page :chat :session-id session-id}))}}
   [:div {:class ["project-card-icon"]}
    (cond
      has-dialog? (icon/icon {:icon-name :alert-circle :size :sm})
      busy?       (spinner)
      active?     [:div {:class ["active-dot"]}]
      :else       (icon/icon {:icon-name :message-circle :size :sm}))]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} (or name "New session")]
    [:span {:class ["project-card-path"]}
     (str (or (format-relative-time timestamp) "")
          (cond has-dialog? " · needs response"
                busy? " · working…"
                active? " · active"
                :else ""))]]
   (when unread? [:div {:class ["unread-dot"]}])])

(defn- home-view [state dispatch!]
  (let [sessions   (get-in state [:lobby :sessions])
        rooms      (get-in state [:lobby :rooms])
        counts     (:web/response-counts state)
        watched    (:web/watched state)
        connected? (:web/connected? state)
        room-by-sid (into {} (keep (fn [r] (when (:session-id r) [(:session-id r) r])) rooms))
        known-sids  (set (keep :session-id sessions))
        orphans     (filter (fn [r] (and (:session-id r)
                                         (not (known-sids (:session-id r)))))
                            rooms)
        unread?     (fn [sid] (when-let [w (get watched sid)]
                               (> (get counts sid 0) w)))
        has-content? (or (seq sessions) (seq orphans))]
    [:div {:class ["container"] :replicant/key "home"}
     [:div {:class ["topbar"]}
      [:div {:class ["topbar-title"]} "Xi"]
      (theme-toggle/theme-toggle
       {:mode (or (:web/theme-mode state) "auto")
        :size :sm
        :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))})
      (when standalone?
        [:button {:class ["icon-btn" "icon-btn--sm"]
                  :on {:click (fn [_] (.reload js/location))}}
         (icon/icon {:icon-name :refresh :size :md})])
      (offline-badge state)
      (when connected?
        [:button {:class ["icon-btn"]
                  :title "GTD Tasks"
                  :on {:click (fn [_] (dispatch! {:type :route/navigate :page :gtd}))}}
         (icon/icon {:icon-name :list :size :md})])
      (when connected?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :room/new}))}}
         (icon/icon {:icon-name :plus :size :md})])]
     [:div {:class ["home"]}
      (if (or has-content? connected?)
        [:div {:class ["project-list"]}
         (for [r orphans]
           (session-card dispatch! {:session-id (:session-id r)
                                    :name (or (:session-name r) "New session")
                                    :active? true :busy? (:busy? r)
                                    :has-dialog? (:has-dialog? r)}))
         (for [s sessions]
           (let [sid (:session-id s)
                 room (get room-by-sid sid)]
             (session-card dispatch!
                           {:session-id sid
                            :name (:name s)
                            :timestamp (or (:last-accessed s) (:timestamp s))
                            :active? (boolean room)
                            :busy? (boolean (:busy? room))
                            :has-dialog? (boolean (:has-dialog? room))
                            :unread? (unread? sid)})))]

        [:div {:class ["empty-state"]}
         (spinner)
         [:p "Connecting to server…"]])]]))

;; ── GTD View ─────────────────────────────────────────────────────────────────

(defn- shorten-path
  "~/Code/Projects/xi → xi, ~/Code/Work/Hyma/studio → studio"
  [path]
  (when path
    (let [parts (str/split path #"/")]
      (last parts))))

(defn- gtd-context-menu [dispatch! {:keys [task x y]}]
  [:div {:class ["gtd-context-backdrop"]
         :on {:click (fn [_] (dispatch! {:type :gtd/context-menu-close}))}}
   [:div {:class ["gtd-context-menu"]
          :style {:top (str y "px") :left (str x "px")}}
    [:button {:class ["gtd-context-item"]
              :on {:click (fn [e]
                            (.stopPropagation e)
                            (dispatch! {:type :gtd/web-task-action
                                        :task-id (:id task) :action "done"}))}}
     (icon/icon {:icon-name :circle-check :size :sm})
     [:span "Mark Done"]]
    [:button {:class ["gtd-context-item" "gtd-context-item--danger"]
              :on {:click (fn [e]
                            (.stopPropagation e)
                            (dispatch! {:type :gtd/web-task-action
                                        :task-id (:id task) :action "archive"}))}}
     (icon/icon {:icon-name :trash :size :sm})
     [:span "Archive"]]]])

(def ^:private long-press-state (atom nil))

(defn- touch-start [dispatch! task e]
  (let [touch (aget (.-touches e) 0)
        x     (.-clientX touch)
        y     (.-clientY touch)
        timer (js/setTimeout
               (fn []
                 (reset! long-press-state :fired)
                 (dispatch! {:type :gtd/context-menu
                             :task task :x x :y y}))
               500)]
    (reset! long-press-state {:timer timer})))

(defn- touch-end [_e]
  (when-let [st @long-press-state]
    (when (map? st) (js/clearTimeout (:timer st))))
  ;; If long-press fired, keep :fired so the subsequent click is suppressed.
  ;; Clear it on next tick after click has been processed.
  (if (= :fired @long-press-state)
    (js/setTimeout #(reset! long-press-state nil) 0)
    (reset! long-press-state nil)))

(defn- touch-move [_e]
  (when-let [st @long-press-state]
    (when (map? st) (js/clearTimeout (:timer st))))
  (reset! long-press-state nil))

(defn- gtd-task-card [dispatch! {:keys [id title todo-state file cwd tags] :as task}]
  [:div {:class ["project-card" "gtd-task-card"]
         :replicant/key (str "gtd-" id)
         :on {:click (fn [_]
                       (when-not (= :fired @long-press-state)
                         (dispatch! {:type :gtd/web-start-task
                                     :task-id id :title title
                                     :cwd cwd})))
              :contextmenu (fn [e]
                             (.preventDefault e)
                             (dispatch! {:type :gtd/context-menu
                                         :task task
                                         :x (.-clientX e)
                                         :y (.-clientY e)}))
              :touchstart (fn [e] (touch-start dispatch! task e))
              :touchend touch-end
              :touchmove touch-move}}
   [:div {:class ["project-card-icon"]}
    (case todo-state
      "ACTIVE"  [:div {:class ["active-dot"]}]
      "WAITING" (icon/icon {:icon-name :pause :size :sm})
      (icon/icon {:icon-name :circle-check :size :sm}))]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} title]
    [:span {:class ["project-card-path"]}
     (str (or todo-state "TODO")
          (when cwd (str " \u00B7 " (shorten-path cwd)))
          (when (and (string? tags) (seq tags)) (str " \u00B7 " tags)))]]])

(defn- gtd-file-card [dispatch! file-name task-count]
  [:div {:class ["project-card"]
         :replicant/key (str "gtd-file-" file-name)
         :on {:click (fn [_] (dispatch! {:type :gtd/select-file :file file-name}))}}
   [:div {:class ["project-card-icon"]}
    (icon/icon {:icon-name :folder :size :sm})]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} (or file-name "Uncategorized")]
    [:span {:class ["project-card-path"]} (str task-count " tasks")]]])

(defn- gtd-view [state dispatch!]
  (let [tasks        (:web/gtd-tasks state)
        loading?     (:web/gtd-loading? state)
        selected-file (:web/gtd-file state)
        ctx-menu     (:web/gtd-context-menu state)
        grouped      (when tasks
                       (->> tasks
                            (group-by :file)
                            (sort-by key)))]
    [:div {:class ["container"] :replicant/key "gtd"}
     [:div {:class ["topbar"]}
      [:button {:class ["icon-btn"]
                :on {:click (fn [_]
                              (if selected-file
                                (dispatch! {:type :gtd/back-to-files})
                                (dispatch! {:type :route/navigate :page :home})))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]}
       (if selected-file
         selected-file
         "Tasks")]
      [:button {:class ["icon-btn"]
                :on {:click (fn [_] (dispatch! {:type :gtd/web-list}))}}
       (icon/icon {:icon-name :refresh :size :md})]]
     [:div {:class ["home"]}
      (cond
        loading?
        [:div {:class ["empty-state"]} (spinner) [:p "Loading tasks..."]]

        (empty? tasks)
        [:div {:class ["empty-state"]} [:p "No open tasks."]]

        selected-file
        (let [file-tasks (get (into {} grouped) selected-file)]
          (if (seq file-tasks)
            [:div {:class ["project-list"]}
             (for [t file-tasks]
               (gtd-task-card dispatch! t))]
            [:div {:class ["empty-state"]} [:p (str "No tasks in " selected-file)]]))

        :else
        [:div {:class ["project-list"]}
         (for [[file-name file-tasks] grouped]
           (gtd-file-card dispatch! file-name (count file-tasks)))])]
     (when ctx-menu
       (gtd-context-menu dispatch! ctx-menu))]))


;; ── Root ─────────────────────────────────────────────────────────────────────

(defn root-view
  "Top-level view, route-driven: the session list at /, a room at /chat/:id."
  [state dispatch!]
  (case (get-in state [:web/route :page])
    :chat (chat-view state dispatch!)
    :gtd  (gtd-view state dispatch!)
    (home-view state dispatch!)))
