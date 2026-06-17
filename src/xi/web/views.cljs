(ns xi.web.views
  "Replicant view layer — pure (state → hiccup) over the same room state the
   TUI renders. Event handlers dispatch events; there is no view-local atom.

   Phase 7a: the online chat view (topbar, timeline of history entries,
   compose input, abort, permission dialogs). Home view + router land in 7b."
  (:require [clojure.string :as str]
            [xi.commands :as commands]
            [xi.core.state :as state]
            [xi.markdown.hiccup :as md]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.theme-css :as theme]
            [xi.diff :as diff]
            [xi.util :as util]
            [ui.icon :as icon]
            [ui.form :as form]
            [ui.button :as button]
            [ui.lightbox :as lightbox]
            [ui.sidebar :as sidebar]
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
          (let [shown (truncate-lines text 100)]
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
        [:div {:class ["post-content"]} (md/render (:text entry))])]]

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
   (quit, reload, diff, tree, events, buffers, prompt)."
  [{:name "help"     :description "Show available commands"}
   {:name "model"    :description "Show or set model"}
   {:name "resume"   :description "Resume a previous session"}
   {:name "sessions" :description "List previous sessions"}
   {:name "new"      :description "Start a new session"}
   {:name "clear"    :description "Clear current session"}
   {:name "truncate" :description "Summarize conversation to reduce context"}
   {:name "diff"     :description "Show changes from this session"
    :subcommands [{:name "git"      :description "All git changes (staged + unstaged + untracked)"}
                  {:name "staged"   :description "Staged changes"}
                  {:name "unstaged" :description "Unstaged changes"}]}
   {:name "commit"   :description "Review changes and create a git commit"}
   {:name "debug"    :description "Copy debug info to clipboard"}])

(def ^:private web-command-names
  (into #{} (map :name) web-commands))

(defn known-command?
  "True if `name` is a recognized web slash command. Used to decide whether a
   submission is worth recording into the recently-executed list."
  [name]
  (contains? web-command-names name))

(defn- expand-commands
  "Flatten commands + their subcommands into a single suggestion list, where
   each subcommand becomes a `parent sub` entry (e.g. \"diff staged\")."
  [commands]
  (mapcat (fn [{:keys [name description subcommands]}]
            (cons {:name name :description description}
                  (map (fn [{sub-name :name sub-desc :description}]
                         {:name (str name " " sub-name) :description sub-desc})
                       subcommands)))
          commands))

(defn- match-commands
  "Filter commands (and their subcommands) by prefix query (text after the /)."
  [query]
  (let [q (str/lower-case (or query ""))]
    (filterv #(str/starts-with? (:name %) q) (expand-commands web-commands))))

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

(def ^:private max-quick-commands 6)

(def ^:private default-quick-commands
  "Fallback commands shown in the quick-access bar before (and alongside) the
   user's recently-executed ones."
  ["diff" "commit" "truncate" "resume" "new" "clear"])

(defn- quick-command-list
  "Most-recently-executed commands first, then the defaults not already shown,
   capped at `max-quick-commands`."
  [recents]
  (let [recent-set (set recents)]
    (->> (concat recents (remove recent-set default-quick-commands))
         distinct
         (take max-quick-commands)
         vec)))

(defn- quick-command-bar [dispatch! room-id recents]
  [:div {:class ["quick-commands"]}
   [:button {:class ["quick-cmd"]
             :on {:click (fn [_] (dispatch! {:type :projects/picker-open}))}}
    (icon/icon {:icon-name :folder :size :sm})
    " Projects"]
   (map (fn [name]
          [:button {:class ["quick-cmd"]
                    :replicant/key name
                    :on {:click (fn [_]
                                 (dispatch! {:type :input/submit :room-id room-id
                                             :text (str "/" name)}))}}
           (str "/" name)])
        (quick-command-list recents))])

(defn- compose-box [dispatch! room busy? images draft-key draft session-id cmd-selected at-bottom? pa? recents]
  (let [room-id  (:id room)
        cmd-query (when (and (not pa?) (string? draft) (str/starts-with? draft "/"))
                    (subs draft 1))
        cmd-matches (when (some? cmd-query) (match-commands cmd-query))
        cmd-open?   (seq cmd-matches)
        has-input?  (seq (str/trim (or draft "")))
        show-quick? (and (not pa?) at-bottom? (not has-input?) (empty? images) (not cmd-open?))]
    [:div {:class ["compose-box"]}
     (compose-image-strip dispatch! images)
     (when show-quick?
       (quick-command-bar dispatch! room-id recents))
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
                                               (.. e -target -value))))))}}})
       (when busy? (spinner))]
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

;; ── Diff view ────────────────────────────────────────────────────────────────

(defn- diff-file-grammar
  "Highlight grammar for a filename, or nil."
  [filename]
  (when filename
    (when-let [ext (file-ext filename)]
      (grammars/get-grammar ext))))

(defn- diff-line-view
  "Render a single selectable diff line with syntax highlighting. Tapping a
   code line dispatches :diff/select-line with its selection index."
  [dispatch! grammar selected? {:keys [type text old-line new-line]} sel-idx row-key]
  (let [cls (cond-> ["diff-line"]
              (= type :add)     (conj "diff-line--add")
              (= type :delete)  (conj "diff-line--del")
              (= type :meta)    (conj "diff-line--meta")
              sel-idx           (conj "diff-line--selectable")
              selected?         (conj "diff-line--selected"))
        old-nr (case type
                 (:delete :context) (str old-line)
                 "")
        new-nr (case type
                 (:add :context) (str new-line)
                 "")
        sign   (case type :add "+" :delete "-" " ")]
    [:div (cond-> {:class cls :replicant/key row-key}
            sel-idx (assoc :on {:click (fn [_] (dispatch! {:type :diff/select-line
                                                           :idx sel-idx}))}))
     [:span {:class ["diff-ln"]} old-nr]
     [:span {:class ["diff-ln"]} new-nr]
     [:span {:class ["diff-sign"]} sign]
     [:span {:class ["diff-text"]}
      (if grammar
        (highlight-code grammar (or text ""))
        (or text ""))]]))

(defn- diff-rows-view
  "Render flattened diff rows as a scrollable view with selection highlight.
   Rows are grouped by file: each file gets a sticky header and a horizontally
   scrollable body so long lines don't push the whole view."
  [dispatch! rows range]
  (let [grammar-cache (atom {})
        grammar-for (fn [f] (or (@grammar-cache f)
                                (let [g (diff-file-grammar f)]
                                  (swap! grammar-cache assoc f g) g)))
        ;; Partition rows into per-file groups (each starting with a :file row)
        file-groups (when (seq rows)
                      (reduce (fn [groups r]
                                (if (= :file (:row r))
                                  (conj groups [r])
                                  (update groups (dec (count groups)) conj r)))
                              [] rows))]
    [:div {:class ["diff-view"]}
     (if (seq file-groups)
       (map-indexed
        (fn [fi group]
          (let [{:keys [filename status]} (first group)
                status-label (case status
                               :added "added" :deleted "deleted"
                               :renamed "renamed" :binary "binary" nil)
                body-rows (rest group)]
            [:div {:class ["diff-file"] :replicant/key filename}
             [:div {:class ["diff-file-header"]}
              [:span {:class ["diff-file-name"]} filename]
              (when status-label
                [:span {:class ["diff-file-status"
                                (str "diff-file-status--" (name status))]}
                 status-label])]
             [:div {:class ["diff-file-body"]}
              (map-indexed
               (fn [ri {:keys [row header line sel-idx] :as r}]
                 (let [k (str fi "-" ri)]
                   (case row
                     :hunk
                     [:div {:class ["diff-hunk-header"] :replicant/key (str "h" k)} header]
                     :line
                     (let [selected? (boolean (and range sel-idx
                                                   (<= (first range) sel-idx (second range))))]
                       (diff-line-view dispatch! (grammar-for (:filename r))
                                       selected? line sel-idx (str "l" k)))
                     nil)))
               body-rows)]]))
        file-groups)
       [:div {:class ["empty-state"]} "No changes."])]))

(defn- diff-action-bar
  "Bottom action bar shown when diff lines are selected: Explain / Modify /
   Clear, plus an inline prompt input for Modify."
  [dispatch! room-id range modify?]
  (let [n (when range (inc (- (second range) (first range))))
        submit-modify!
        (fn [_]
          (when-let [el (.getElementById js/document "diff-modify-input")]
            (dispatch! {:type :diff/modify-submit
                        :room-id room-id :text (.-value el)})))]
    [:div {:class ["diff-action-bar"]}
     (when modify?
       [:div {:class ["diff-modify-row"]}
        [:textarea {:id "diff-modify-input"
                    :class ["form-textarea" "diff-modify-input"]
                    :placeholder "Describe the change to make to the selected code…"
                    :rows 2
                    :on {:keydown (fn [^js e]
                                    (when (and (= "Enter" (.-key e)) (.-metaKey e))
                                      (.preventDefault e) (submit-modify! e)))}}]
        (button/button
         {:variant :primary :size :sm
          :on-click submit-modify!}
         "Send")])
     [:div {:class ["diff-action-row"]}
      [:span {:class ["diff-sel-count"]}
       (str n " line" (when (not= 1 n) "s") " selected")]
      [:div {:class ["diff-action-buttons"]}
       (button/button
        {:variant :ghost :size :sm
         :on-click (fn [_] (dispatch! {:type :diff/clear-selection}))}
        "Clear")
       (button/button
        {:variant :ghost :size :sm
         :on-click (fn [_] (dispatch! {:type :diff/modify-toggle}))}
        "Modify")
       (button/button
        {:variant :ghost :size :sm
         :on-click (fn [_] (dispatch! {:type :diff/goto :room-id room-id}))}
        "Goto")
       (button/button
        {:variant :primary :size :sm
         :on-click (fn [_] (dispatch! {:type :diff/explain :room-id room-id}))}
        "Explain")]]]))

(defn- diff-tab-view
  "Full diff buffer view rendered as the active tab, with line selection and
   an action bar for Explain / Modify."
  [dispatch! room-id diff-buffer sel modify?]
  (let [rows  (diff/diff-rows (diff/parse-diff-text (:text diff-buffer)))
        range (diff/selection-range sel)]
    [:div {:class ["diff-tab"]}
     (diff-rows-view dispatch! rows range)
     (when range
       (diff-action-bar dispatch! room-id range modify?))]))

;; ── Tab bar ──────────────────────────────────────────────────────────────────

(defn- tab-bar
  "Tab bar for switching between chat and buffer views."
  [dispatch! room-id active-buffer buffers]
  [:div {:class ["tab-bar"]}
   [:button {:class ["tab-bar-item" (when (= active-buffer :chat) "tab-bar-item--active")]
             :on {:click (fn [_] (dispatch! {:type :ui/buffer-switch
                                             :room-id room-id :buffer-id :chat}))}}
    "Chat"]
   (when (:diff buffers)
     [:button {:class ["tab-bar-item" (when (= active-buffer :diff) "tab-bar-item--active")]
               :on {:click (fn [_] (dispatch! {:type :ui/buffer-switch
                                               :room-id room-id :buffer-id :diff}))}}
      "Diff"])])

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


(defn- shorten-path
  "~/Code/Projects/xi → xi, ~/Code/Work/Hyma/studio → studio"
  [path]
  (when path
    (let [parts (str/split path #"/")]
      (last parts))))

(defn- project-picker [dispatch! dirs draft-key]
  [:div {:class ["project-picker-backdrop"]
         :on {:click (fn [_] (dispatch! {:type :projects/picker-close}))}}
   [:div {:class ["project-picker"]}
    (if (seq dirs)
      (for [path dirs]
        [:button {:class ["project-picker-item"]
                  :replicant/key path
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (dispatch! {:type :projects/picker-insert
                                            :path path :draft-key draft-key}))}}
         [:span {:class ["project-picker-icon"]}
          (icon/icon {:icon-name :folder :size :sm})]
         [:span {:class ["project-picker-path"]} (shorten-path path)]
         [:span {:class ["project-picker-full"]} path]])
      [:div {:class ["project-picker-empty"]} "Loading…"])]])

(defn- menu-button
  "Framework hamburger toggle that opens the recent-sessions drawer. Lives on
   every topbar; toggles the :web/sidebar-open? app state."
  [dispatch!]
  (sidebar/sidebar-mobile-toggle
   {:on-click (fn [_] (dispatch! {:type :sidebar/toggle}))}))

(defn- optimistic-post
  "An optimistic user bubble rendered at the tail of the timeline the instant a
   prompt is sent, before the server echoes the real :user entry back (instant
   feedback on slow/mobile links). Suppressed once the matching entry lands in
   history so it never duplicates the authoritative message."
  [dispatch! state room sid history]
  (when-let [{:keys [room-id session-id text images]} (:web/optimistic state)]
    (let [for-this? (or (and room-id (= room-id (:id room)))
                        (and session-id sid (= session-id sid)))
          last-user (->> history (filter #(= :user (:kind %))) last)
          confirmed? (and last-user
                          (= (not-empty (some-> text str/trim))
                             (not-empty (some-> (:text last-user) str/trim)))
                          (= (count images) (count (:images last-user))))]
      (when (and for-this? (not confirmed?))
        (entry->post dispatch! {:kind :user :text text :images images})))))

(defn- chat-back-route
  "Where the chat-view back arrow should land: the listing the session belongs
   to, regardless of how the chat was reached (drill-down or a sidebar jump).
   Personal-agent mode has no projects → the root home. Otherwise the project's
   session listing (/projects/:cwd) when the cwd is known, falling back to the
   projects root."
  [state]
  (if (get-in state [:lobby :personal-agent?])
    {:type :route/navigate :page :home}
    (let [sid (get-in state [:web/route :session-id])
          cwd (or (:cwd (state/active-room state))
                  (some (fn [s] (when (= sid (:session-id s)) (:cwd s)))
                        (get-in state [:lobby :sessions]))
                  (some (fn [r] (when (= sid (:session-id r)) (:cwd r)))
                        (get-in state [:lobby :rooms])))]
      (cond-> {:type :route/navigate :page :home}
        cwd (assoc :dir cwd)))))

(defn- chat-view [state dispatch!]
  (let [room    (state/active-room state)
        sid     (get-in state [:web/route :session-id])
        cached  (get-in state [:web/cache sid])
        history (or (:history room) (:history cached))
        busy?   (get-in room [:agent :busy?])
        model   (or (get-in room [:agent :model]) (:model cached))
        ready?  (or room (seq history))
        draft-key (or (get-in room [:session :id]) sid :new)
        model-list (:web/model-list state)
        buffers    (get-in room [:ui :buffers])
        active-buf (get-in room [:ui :active-buffer] :chat)
        has-tabs?  (boolean (:diff buffers))]
    [:div {:class ["container"] :replicant/key "chat"}
     [:div {:class ["topbar"]}
      (menu-button dispatch!)
      [:button {:class ["icon-btn" "icon-btn--sm"]
                :on {:click (fn [_] (dispatch! (chat-back-route state)))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]}
       (when model
         [:span {:class ["topbar-subtitle" "topbar-subtitle--clickable"]
                 :on {:click (fn [_] (dispatch! {:type :models/web-list}))}}
          model])]
      (when model-list
        (model-selector dispatch! (:id room) model-list model))
      (when room
        [:button {:class ["icon-btn" "icon-btn--sm"]
                  :on {:click (fn [_]
                                (let [text (commands/debug-text room)]
                                  (-> (.writeText js/navigator.clipboard text)
                                      (.catch (fn [_]
                                                (let [el (.createElement js/document "textarea")]
                                                  (set! (.-value el) text)
                                                  (set! (.-style.position el) "fixed")
                                                  (set! (.-style.opacity el) "0")
                                                  (.appendChild js/document.body el)
                                                  (.focus el)
                                                  (.select el)
                                                  (.execCommand js/document "copy")
                                                  (.removeChild js/document.body el)))))))}}
         (icon/icon {:icon-name :copy :size :md})])
      (when standalone?
        [:button {:class ["icon-btn" "icon-btn--sm"]
                  :on {:click (fn [_] (.reload js/location))}}
         (icon/icon {:icon-name :refresh :size :md})])
      (offline-badge state)]
     (when has-tabs?
       (tab-bar dispatch! (:id room) active-buf buffers))
     (case active-buf
       :diff
       (diff-tab-view dispatch! (:id room) (:diff buffers)
                      (:web/diff-sel state)
                      (:web/diff-modify? state))

       ;; default: :chat
       (list
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
               (keep (partial entry->post dispatch!) (subvec entries start total))
               (optimistic-post dispatch! state room sid history)))
            [:div {:class ["empty-state"]} (spinner) [:p "Connecting…"]])]]
        (dialog-overlay dispatch! room)
        (lightbox/lightbox {:src (:web/lightbox state)
                            :on-close (fn [] (dispatch! {:type :lightbox/close}))})
        (when (:web/project-picker? state)
          (project-picker dispatch! (:web/project-dirs state) draft-key))
        (compose-box dispatch! room busy? (:web/compose-images state)
                     draft-key (get-in state [:web/drafts draft-key]) sid
                     (:web/cmd-selected state)
                     (get state :web/at-bottom? true)
                     (get-in state [:lobby :personal-agent?])
                     (:web/recent-commands state))))]))

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

(defn- session-status
  "Enrich a session map with live indicator flags derived from app state:
   :active? (has a live room), :busy?, :has-dialog? (needs response),
   :unread? (more responses than last watched). Centralizes the logic shared
   by every session listing so indicators aren't computed twice."
  [state s]
  (let [sid     (:session-id s)
        rooms   (get-in state [:lobby :rooms])
        counts  (:web/response-counts state)
        watched (:web/watched state)
        room    (some (fn [r] (when (= (:session-id r) sid) r)) rooms)
        w       (get watched sid)]
    {:session-id  sid
     :name        (:name s)
     :timestamp   (or (:last-accessed s) (:timestamp s))
     :current?    (and sid (= sid (get-in state [:web/route :session-id])))
     :active?     (boolean room)
     :busy?       (boolean (:busy? room))
     :has-dialog? (boolean (:has-dialog? room))
     :unread?     (boolean (and w (> (get counts sid 0) w)))}))

(defn- orphan-rooms
  "Live rooms from the lobby mirror that have no matching disk session in
   `sessions`. These are freshly created rooms whose session hasn't been
   persisted to disk yet, so the disk-session-first listings would otherwise
   miss them entirely. Optionally restrict to a single `cwd`. Returns
   session-card-ready data maps, newest first (room-summaries is pre-sorted)."
  ([state sessions] (orphan-rooms state sessions nil))
  ([state sessions cwd]
   (let [known-sids (set (keep :session-id sessions))]
     (->> (get-in state [:lobby :rooms])
          (filter (fn [r] (and (:session-id r)
                               (not (known-sids (:session-id r)))
                               (or (nil? cwd) (= cwd (:cwd r))))))
          ;; Several rooms can share one session-id (e.g. a lingering
          ;; clients:0 room plus a freshly reopened one). Collapse them to a
          ;; single card so the list shows one row — and one spinner — per
          ;; session instead of colliding on :replicant/key.
          (group-by :session-id)
          (mapv (fn [[sid rooms]]
                  {:session-id  sid
                   :name        (or (some :session-name rooms) "New session")
                   :active?     true
                   :busy?       (boolean (some :busy? rooms))
                   :has-dialog? (boolean (some :has-dialog? rooms))}))))))

(defn- session-card [dispatch! {:keys [session-id name timestamp current? active? busy? has-dialog? unread?]}]
  [:div {:class ["project-card" (when active? "project-card--active")
                 (when has-dialog? "project-card--dialog")
                 (when current? "project-card--current")]
         :replicant/key (or session-id (str "card-" name))
         :on {:click (fn [_] (dispatch! {:type :route/navigate
                                         :page :chat :session-id session-id}))}}
   [:div {:class ["project-card-icon"]}
    (cond
      has-dialog? (icon/icon {:icon-name :alert-circle :size :sm})
      :else       (icon/icon {:icon-name :message-circle :size :sm}))]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} (or name "New session")]
    [:span {:class ["project-card-path"]}
     (str (or (format-relative-time timestamp) "")
          (cond has-dialog? " · needs response"
                busy? " · working…"
                active? " · active"
                :else ""))]]
   ;; Keep the trailing indicator slot ALWAYS present (hidden via CSS when
   ;; empty). A bare conditional here is an unkeyed child that flips between an
   ;; element and nil; as the keyed card list churns/reorders, Replicant can
   ;; mis-reconcile that slot and append a second spinner. A stable wrapper
   ;; keeps each card's child structure invariant so the indicator only ever
   ;; swaps content inside a node that never moves on its own.
   [:div {:class ["project-card-status"]}
    (cond
      busy?   (spinner)
      unread? [:div {:class ["unread-dot"]}])]])



(defn- project-dir-card
  "Card for a project directory in the home view."
  [dispatch! path]
  [:div {:class ["project-card"]
         :replicant/key (str "dir-" path)
         :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd path}))}}
   [:div {:class ["project-card-icon"]}
    (icon/icon {:icon-name :folder :size :sm})]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} (shorten-path path)]
    [:span {:class ["project-card-path"]} path]]
   [:div {:class ["project-card-chevron"]}
    (icon/icon {:icon-name :chevron-right :size :sm})]])

(defn- project-sessions-view
  "Drill-down: sessions for a selected project directory."
  [state dispatch!]
  (let [cwd       (:web/selected-project-dir state)
        sessions  (:web/project-sessions state)
        orphans   (orphan-rooms state sessions cwd)
        loading?  (:web/project-sessions-loading? state)]
    [:div {:class ["container"] :replicant/key "project-sessions"}
     [:div {:class ["topbar"]}
      (menu-button dispatch!)
      [:button {:class ["icon-btn"]
                :on {:click (fn [_] (dispatch! {:type :route/navigate :page :home}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]} (shorten-path cwd)]
      [:button {:class ["icon-btn"]
                :title "New session"
                :on {:click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}}
       (icon/icon {:icon-name :plus :size :md})]]
     [:div {:class ["home"]}
      (cond
        loading?
        [:div {:class ["empty-state"]} (spinner) [:p "Loading sessions…"]]

        (and (empty? sessions) (empty? orphans))
        [:div {:class ["empty-state"]}
         [:p "No sessions yet."]
         [:button {:class ["btn" "btn--primary"]
                   :on {:click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}}
          "Start new session"]]

        :else
        [:div {:class ["project-list"]}
         (for [o orphans]
           (session-card dispatch! o))
         (for [s sessions]
           (session-card dispatch! (session-status state s)))])]]))

(defn- all-sessions-view
  "Flat list of all sessions (the old home view)."
  [state dispatch!]
  (let [sessions   (get-in state [:lobby :sessions])
        connected? (:web/connected? state)
        orphans    (orphan-rooms state sessions)]
    [:div {:class ["container"] :replicant/key "all-sessions"}
     [:div {:class ["topbar"]}
      (menu-button dispatch!)
      [:button {:class ["icon-btn"]
                :on {:click (fn [_] (dispatch! {:type :route/navigate :page :home}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]} "All sessions"]
      (when connected?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :room/new}))}}
         (icon/icon {:icon-name :plus :size :md})])]
     [:div {:class ["home"]}
      (if (or (seq sessions) (seq orphans))
        [:div {:class ["project-list"]}
         (for [o orphans]
           (session-card dispatch! o))
         (for [s sessions]
           (session-card dispatch! (session-status state s)))]
        [:div {:class ["empty-state"]} [:p "No sessions yet."]])]]))

(defn- personal-agent-home-view
  "Home view for personal-agent mode: a flat session list with no project
   navigation (the personal agent has no projects)."
  [state dispatch!]
  (let [sessions    (get-in state [:lobby :sessions])
        connected?  (:web/connected? state)
        orphans     (orphan-rooms state sessions)]
    [:div {:class ["container"] :replicant/key "home"}
     [:div {:class ["topbar"]}
      (menu-button dispatch!)
      [:div {:class ["topbar-title"]} "Xi"]
      (offline-badge state)
      (when connected?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :room/new}))}}
         (icon/icon {:icon-name :plus :size :md})])]
     [:div {:class ["home"]}
      (cond
        (not connected?)
        [:div {:class ["empty-state"]}
         (spinner)
         [:p "Connecting to server…"]]

        (or (seq sessions) (seq orphans))
        [:div {:class ["project-list"]}
         (for [o orphans]
           (session-card dispatch! o))
         (for [s sessions]
           (session-card dispatch! (session-status state s)))]

        :else
        [:div {:class ["empty-state"]} [:p "No sessions yet."]])]]))

(defn- home-view [state dispatch!]
  (let [selected-dir (:web/selected-project-dir state)]
    (cond
      (get-in state [:lobby :personal-agent?])
      (personal-agent-home-view state dispatch!)

      (= selected-dir :all)
      (all-sessions-view state dispatch!)

      selected-dir
      (project-sessions-view state dispatch!)

      :else
      (let [dirs       (:web/project-dirs state)
            loading?   (:web/projects-loading? state)
            rooms      (get-in state [:lobby :rooms])
            connected? (:web/connected? state)
            ;; Active rooms without a known project
            orphans    (filter (fn [r] (:session-id r)) rooms)]
        [:div {:class ["container"] :replicant/key "home"}
         [:div {:class ["topbar"]}
          (menu-button dispatch!)
          [:div {:class ["topbar-title"]} "Xi"]
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
          (cond
            (not connected?)
            [:div {:class ["empty-state"]}
             (spinner)
             [:p "Connecting to server…"]]

            loading?
            [:div {:class ["empty-state"]}
             (spinner)
             [:p "Loading projects…"]]

            :else
            [:div {:class ["project-list"]}
             ;; Active orphan rooms first
             (for [r orphans]
               (session-card dispatch! {:session-id (:session-id r)
                                        :name (or (:session-name r) "New session")
                                        :active? true :busy? (:busy? r)
                                        :has-dialog? (:has-dialog? r)}))
             ;; All sessions link
             [:div {:class ["project-card"]
                    :replicant/key "all-sessions"
                    :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd :all}))}}
              [:div {:class ["project-card-icon"]}
               (icon/icon {:icon-name :message-circle :size :sm})]
              [:div {:class ["project-card-info"]}
               [:span {:class ["project-card-name"]} "All sessions"]]
              [:div {:class ["project-card-chevron"]}
               (icon/icon {:icon-name :chevron-right :size :sm})]]
             ;; Project directories
             (for [d dirs]
               (project-dir-card dispatch! d))])]]))))

;; ── GTD View ─────────────────────────────────────────────────────────────────


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
                         (dispatch! {:type :gtd/select-task
                                     :file file :task-id id})))
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

(defn- render-org-body
  "Render pre-built HTML body from the server."
  [html-str]
  (when (and (string? html-str) (seq html-str))
    [:div {:class ["org-body"] :innerHTML html-str}]))

(defn- gtd-task-detail [dispatch! task]
  (let [{:keys [title todo-state html-body file cwd tags]} task]
    [:div {:class ["container"] :replicant/key "gtd-detail"}
     [:div {:class ["topbar"]}
      [:button {:class ["icon-btn"]
                :on {:click (fn [_]
                              (dispatch! {:type :nav/back :fallback {:page :gtd :file file}}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]} "Task"]]
     [:div {:class ["gtd-detail-scroll"]}
      [:div {:class ["gtd-detail"]}
       [:h2 {:class ["gtd-detail-title"]} title]
       [:div {:class ["gtd-detail-meta"]}
        (when todo-state
          [:span {:class ["gtd-detail-badge"
                          (case todo-state
                            "ACTIVE" "gtd-detail-badge--active"
                            "WAITING" "gtd-detail-badge--waiting"
                            "gtd-detail-badge--default")]}
           todo-state])
        (when file [:span {:class ["gtd-detail-file"]} file])
        (when (and (string? tags) (seq tags))
          (for [tag (.split tags " ")]
            [:span {:class ["gtd-detail-tag"]} tag]))]
       (when html-body
         (render-org-body html-body))
       (when cwd
         [:div {:class ["gtd-detail-actions"]}
          [:button {:class ["btn" "btn-primary"]
                    :on {:click (fn [_]
                                  (dispatch! {:type :gtd/web-start-task
                                              :task-id (:id task)
                                              :title title
                                              :cwd cwd}))}}
           (icon/icon {:icon-name :play :size :sm})
           [:span "Launch Agent"]]])]]]))


(defn- gtd-view [state dispatch!]
  (let [tasks        (:web/gtd-tasks state)
        loading?     (:web/gtd-loading? state)
        selected-file (:web/gtd-file state)
        task-id      (:web/gtd-task-id state)
        ctx-menu     (:web/gtd-context-menu state)
        ;; Look up selected task by id
        selected-task (when task-id
                        (some #(when (= (:id %) task-id) %) tasks))
        grouped      (when tasks
                       (->> tasks
                            (group-by :file)
                            (sort-by key)))]
    (cond
      ;; Task detail view
      selected-task
      (gtd-task-detail dispatch! selected-task)

      ;; Task list / file list
      :else
      [:div {:class ["container"] :replicant/key "gtd"}
       [:div {:class ["topbar"]}
        (menu-button dispatch!)
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_]
                                (dispatch! {:type :nav/back
                                            :fallback (if selected-file
                                                        {:page :gtd}
                                                        {:page :home})}))}}
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
         (gtd-context-menu dispatch! ctx-menu))])))


;; ── Root ─────────────────────────────────────────────────────────────────────

(defn- session-time
  "Numeric last-visited timestamp for a session, for sorting."
  [s]
  (let [t (or (:last-accessed s) (:timestamp s))]
    (cond
      (number? t) t
      (string? t) (let [n (.getTime (js/Date. t))] (if (js/isNaN n) 0 n))
      :else 0)))

(defn- recent-sessions
  "Sessions from the lobby mirror: rooms with a running agent pinned to the
   top, then by most-recently visited."
  [state]
  (let [rooms  (get-in state [:lobby :rooms])
        busy?  (fn [s] (boolean (some (fn [r] (and (= (:session-id r) (:session-id s))
                                                   (:busy? r)))
                                      rooms)))]
    (->> (get-in state [:lobby :sessions])
         (sort-by (juxt busy? session-time) #(compare %2 %1))
         (take 25))))

(defn- recent-projects
  "Distinct project directories ordered by most-recently used, derived from
   live rooms (active now) followed by saved sessions sorted by last visited.
   Personal-agent sessions carry no cwd, so the list collapses to empty there."
  [state]
  (let [room-cwds (->> (get-in state [:lobby :rooms]) (keep :cwd))
        sess-cwds (->> (get-in state [:lobby :sessions])
                       (filter :cwd)
                       (sort-by session-time #(compare %2 %1))
                       (map :cwd))]
    (->> (concat room-cwds sess-cwds)
         distinct
         (take 5))))

(defn- recent-sidebar
  "The drawer panel: framework sidebar listing recently-used projects above
   recent sessions, both sorted by last visited. Slid in/out by the floating
   layout's data-sidebar-open attribute. Reuses session-card/project-dir-card
   so indicators render the same way as the home listings (navigation
   auto-closes the drawer)."
  [state dispatch!]
  (let [projects (recent-projects state)
        sessions (recent-sessions state)
        orphans  (orphan-rooms state sessions)
        ;; Render one card per session-id from a single keyed sequence.
        ;; Replicant renders BOTH siblings when two share a :replicant/key
        ;; (it does not dedupe), so any duplicate stacks cards on top of each
        ;; other — surfacing as doubled spinners. Drop junk entries with no
        ;; id (their key collapses to a constant) and keep the first card seen
        ;; per id so every key is unique.
        cards    (->> (concat orphans (map #(session-status state %) sessions))
                      (filter :session-id)
                      (reduce (fn [{:keys [seen acc]} c]
                                (if (seen (:session-id c))
                                  {:seen seen :acc acc}
                                  {:seen (conj seen (:session-id c))
                                   :acc  (conj acc c)}))
                              {:seen #{} :acc []})
                      :acc)]
    (sidebar/sidebar
     {}
     (sidebar/sidebar-content
      {:attrs {:style {:padding 0}}}
      (when (seq projects)
        (sidebar/sidebar-group {:label "Projects"}
          (for [p projects]
            (project-dir-card dispatch! p))))
      (sidebar/sidebar-group {:label "Recent"}
        (if (seq cards)
          (for [c cards]
            (session-card dispatch! c))
          [:div {:class ["sidebar-group-label"]} "No recent sessions"])))
     (sidebar/sidebar-footer {}
       (theme-toggle/theme-toggle
        {:mode (or (:web/theme-mode state) "auto")
         :size :sm
         :attrs {:style {:align-self "flex-start"}}
         :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))})))))

(defn root-view
  "Top-level view, route-driven: the session list at /, a room at /chat/:id.
   Wrapped in a floating sidebar layout so every topbar's hamburger reveals
   the recent-sessions drawer over the content."
  [state dispatch!]
  (let [open? (boolean (:web/sidebar-open? state))]
    (sidebar/sidebar-layout
     {:class "sidebar-layout--floating"
      :attrs (cond-> {:style {:height "100%"}}
               open? (assoc :data-sidebar-open true))}
     (recent-sidebar state dispatch!)
     (sidebar/sidebar-overlay {:on-click (fn [_] (dispatch! {:type :sidebar/close}))})
     (sidebar/sidebar-layout-main
      {:attrs {:style {:min-height "0"}}}
      (case (get-in state [:web/route :page])
        :chat (chat-view state dispatch!)
        :gtd  (gtd-view state dispatch!)
        (home-view state dispatch!))))))