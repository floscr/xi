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
            [ui.toolbar :as toolbar]
            [ui.lightbox :as lightbox]
            [ui.sidebar :as sidebar]
            [ui.command :as cmd]
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

;; ── Clipboard ─────────────────────────────────────────────────────────────────

(defn- fallback-copy!
  "Synchronous textarea-based clipboard copy. Works on iOS Safari (including
   non-secure LAN/HTTP contexts where navigator.clipboard is unavailable).

   iOS quirks (mirrors clipboard.js): the textarea must be `readonly` (stops the
   on-screen keyboard from popping up and is required for a reliable selection),
   positioned within the viewport at the current scroll offset (off-screen
   `display:none`/negative-top elements cannot be selected on iOS), use a
   non-zooming font-size, and select via `.select()` + `setSelectionRange`
   rather than `.select()` alone (which is a no-op on iOS)."
  [text]
  (let [el      (.createElement js/document "textarea")
        scroll-y (or (.-pageYOffset js/window)
                     (.. js/document -documentElement -scrollTop)
                     0)]
    (set! (.-value el) text)
    (.setAttribute el "readonly" "")
    (set! (.. el -style -position) "absolute")
    (set! (.. el -style -left) "-9999px")
    (set! (.. el -style -top) (str scroll-y "px"))
    (set! (.. el -style -fontSize) "12pt")    ;; prevent iOS auto-zoom
    (.appendChild js/document.body el)
    (.focus el)
    (.select el)
    (.setSelectionRange el 0 (.. el -value -length))
    (.execCommand js/document "copy")
    (.removeChild js/document.body el)))

(defn- copy-to-clipboard!
  "Copy text to the clipboard, falling back to a synchronous textarea when the
   async Clipboard API is unavailable (e.g. iOS over non-secure HTTP). Calling
   navigator.clipboard.writeText directly when navigator.clipboard is undefined
   throws synchronously, bypassing any .catch — so guard on it explicitly."
  [text]
  (if (and (some? js/navigator.clipboard) js/window.isSecureContext)
    (-> (.writeText js/navigator.clipboard text)
        (.catch (fn [_] (fallback-copy! text))))
    (fallback-copy! text)))

(def ios?
  "True on iOS/iPadOS, where programmatic clipboard writes are unavailable over
   plain HTTP (no secure context) and unreliable in a standalone PWA. iPadOS 13+
   reports as \"Macintosh\", so also treat a touch-capable Mac as iOS."
  (let [ua       (or (some-> js/navigator .-userAgent) "")
        platform (or (some-> js/navigator .-platform) "")
        touch    (or (some-> js/navigator .-maxTouchPoints) 0)]
    (boolean
     (or (re-find #"iPad|iPhone|iPod" ua)
         (and (re-find #"Mac" platform) (> touch 1))))))

(defn- copy-dialog-overlay
  "iOS manual-copy fallback. Shows the text in a pre-selected, read-only
   textarea so the user can copy it via the native long-press menu, since the
   programmatic Clipboard API is unavailable over plain HTTP on iOS. Tracked for
   a proper HTTPS fix in GTD."
  [dispatch! text]
  (when text
    (let [close! (fn [] (dispatch! {:type :copy/close}))]
      [:div {:class ["confirm-overlay"]
             :on {:click (fn [_] (close!))}}
       [:div {:class ["confirm-panel"]
              :on {:click (fn [e] (.stopPropagation e))}}
        [:div {:class ["confirm-message"]} "Long-press the selected text to copy:"]
        [:textarea
         {:class ["copy-dialog-textarea"]
          :readonly true
          :rows 8
          :replicant/on-mount
          (fn [e]
            (let [node (:replicant/node e)]
              (set! (.-value node) text)
              (.focus node)
              (.select node)
              (.setSelectionRange node 0 (.. node -value -length))))}
         text]
        [:div {:class ["confirm-actions"]}
         [:button {:class ["confirm-btn" "confirm-btn--allow"]
                   :on {:click (fn [_] (close!))}} "Done"]]]])))

;; ── Spinner ──────────────────────────────────────────────────────────────────

(defn spinner [] [:div {:class ["agent-status-spinner"]}])

(defn nav-items-for
  "Extension nav items (from ext/compose :nav-items, stored in state at
   init) scoped to one menu surface."
  [state menu]
  (filter #(= menu (:menu %)) (:web/nav-items state)))

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

(defn- edit-diff-code
  "Render an edit tool's unified-diff result with per-line tinting: + lines get
   a subtle green wash, - lines a subtle red one, over the code box. The
   path/context/gap lines stay neutral. Code is still syntax-highlighted."
  [grammar text]
  (into [:pre {:class ["tool-call-code" "tool-call-diff"]}]
        (map (fn [line]
               (let [add? (str/starts-with? line "+ ")
                     del? (str/starts-with? line "- ")
                     ctx? (str/starts-with? line "  ")
                     cls  (cond add? "tool-diff-line--add" del? "tool-diff-line--del")
                     body (if (or add? del? ctx?) (subs line 2) line)]
                 (if (or add? del? ctx?)
                   [:span {:class (cond-> ["tool-diff-line"] cls (conj cls))}
                    [:span {:class ["tool-diff-sign"]
                            :data-sign (cond add? "+" del? "-" :else " ")}]
                    (if grammar (highlight-code grammar body) body)]
                   [:span {:class ["tool-diff-line"]} line]))))
        (str/split-lines text)))

(defn- tool-post [{:keys [tool arguments result is-error status]}]
  (let [name      (util/strip-mcp-prefix tool)
        summary   (tool-summary name arguments)
        running?  (= :running status)
        text      (util/extract-text-content result)
        grammar   (when (and text (not is-error)) (tool-grammar name arguments))
        bash?     (contains? #{"Bash" "bash"} name)
        label     (str name (when (seq summary)
                              (str " " (if bash?
                                         (str summary)
                                         (util/truncate (first (str/split-lines (str summary))) 80)))))]
    [:div {:class ["post" "post--tool"]}
     [:details {:class ["tool-call-block"] :open (boolean (expanded-tools name))}
      [:summary {:class (cond-> ["tool-call-toggle"] bash? (conj "tool-call-toggle--wrap"))}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class (cond-> ["tool-call-toggle-label"] bash? (conj "tool-call-toggle-label--wrap"))} label]
       (cond
         running? (spinner)
         is-error [:span {:class ["error-text"]} " error"])]
      (when (seq text)
        [:div {:class ["tool-call-content"]}
         (let [shown (truncate-lines text 100)]
           (if (and (contains? #{"Edit" "edit"} name) (not is-error))
             (edit-diff-code grammar shown)
             [:pre {:class ["tool-call-code"]}
              (if grammar (highlight-code grammar shown) shown)]))])]]))

;; ── History entry → post ─────────────────────────────────────────────────────

(defn- entry->post [dispatch! entry]
  (case (:kind entry)
    :user
    (let [idx (:history-index entry)]
      (if (:editing? entry)
        [:div {:class ["post" "post--user" "post--editing"]}
         [:div {:class ["post-body"]}
          [:textarea {:class ["bubble-edit-textarea"]
                      :value (:edit-text entry)
                      :replicant/on-mount
                      (fn [{:replicant/keys [^js node]}]
                        ;; preventScroll stops iOS Safari from jumping to a
                        ;; weird position before the keyboard/visual viewport
                        ;; has settled; we scroll it into view ourselves once
                        ;; the keyboard has animated in.
                        (.focus node #js {:preventScroll true})
                        (let [n (.. node -value -length)]
                          (.setSelectionRange node n n))
                        (js/setTimeout
                         (fn []
                           (.scrollIntoView node #js {:block "center"
                                                      :behavior "smooth"}))
                         300))
                      :on {:input (fn [^js e]
                                    (dispatch! {:type :bubble/edit-change
                                                :text (.. e -target -value)}))
                           :keydown (fn [^js e]
                                      (when (= "Escape" (.-key e))
                                        (dispatch! {:type :bubble/edit-cancel})))}}]
          [:div {:class ["bubble-edit-actions"]}
           (button/button
            {:variant :ghost :size :sm
             :on-click (fn [_] (dispatch! {:type :bubble/edit-cancel}))}
            "Cancel")
           (button/button
            {:variant :primary :size :sm
             :on-click (fn [_] (dispatch! {:type :bubble/edit-save}))}
            "Save")]]]
        [:div (cond-> {:class ["post" "post--user" (when idx "post--tappable")]}
                idx (assoc :data-history-index idx)
                idx (assoc :on {:click (fn [^js e]
                                         (dispatch! {:type :bubble/menu-open
                                                     :index idx
                                                     :text (:text entry)
                                                     :x (.-clientX e)
                                                     :y (.-clientY e)}))}))
         [:div {:class ["post-body"]}
          (if-let [imgs (seq (:images entry))]
            [:div {:class ["user-images"]}
             (map-indexed
              (fn [i {:keys [data media-type name]}]
                (if (= media-type "application/pdf")
                  [:div {:replicant/key i :class ["user-attachment-chip"]}
                   (icon/icon {:icon-name :file-text :size :md})
                   [:span {:class ["user-attachment-name"]} (or name "document.pdf")]]
                  (let [src (str "data:" media-type ";base64," data)]
                    [:img {:replicant/key i
                           :class ["user-image" "lightbox-thumb"]
                           :src src
                           :alt "attached"
                           :on {:click (fn [^js e]
                                         (.stopPropagation e)
                                         (dispatch! {:type :lightbox/open :src src}))}}])))
              imgs)]
            (when-let [n (:image-count entry)]
              (when (pos? n)
                [:div {:class ["status-text"]} (str "📎 " n " image" (when (> n 1) "s"))])))
          (when (seq (:text entry))
            [:div {:class ["post-content"]} (md/render (:text entry))])]]))

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

(defn- read-pdf-file
  "Read a PDF File as base64; promise of
   {:data base64 :media-type \"application/pdf\" :name filename} or nil on failure."
  [^js file]
  (js/Promise.
   (fn [resolve _]
     (let [reader (js/FileReader.)]
       (set! (.-onload reader)
             (fn [_]
               (let [[_ media-type b64] (re-matches #"data:([^;]+);base64,(.*)"
                                                    (.-result reader))]
                 (resolve {:data b64
                           :media-type (or media-type "application/pdf")
                           :name (.-name file)}))))
       (set! (.-onerror reader) (fn [_] (resolve nil)))
       (.readAsDataURL reader file)))))

(defn- add-files!
  "Stage a seq of Files as compose attachments: images are downscaled, PDFs are
   read as-is."
  [dispatch! files]
  (when (seq files)
    (-> (js/Promise.all
         (to-array
          (map (fn [^js f]
                 (if (= "application/pdf" (.-type f))
                   (read-pdf-file f)
                   (resize-image-file f)))
               files)))
        (.then (fn [results]
                 (when-let [valid (seq (remove nil? (array-seq results)))]
                   (dispatch! {:type :compose/add-images :images (vec valid)}))))
        (.catch (fn [err] (js/console.error "[xi-web] attachment read failed:" err))))))

(defn- handle-compose-paste! [dispatch! ^js e]
  (let [items (.. e -clipboardData -items)
        files (->> (range (.-length items))
                   (keep (fn [i]
                           (let [^js item (aget items i)]
                             (when (str/starts-with? (.-type item) "image/")
                               (.getAsFile item))))))]
    (when (seq files)
      (.preventDefault e)
      (add-files! dispatch! files))))

(defn- compose-image-strip [dispatch! images]
  (when (seq images)
    [:div {:class ["compose-images"]}
     (map-indexed
      (fn [idx {:keys [data media-type name]}]
        (if (= media-type "application/pdf")
          [:div {:replicant/key idx :class ["compose-attachment-chip"]}
           (icon/icon {:icon-name :file-text :size :md})
           [:span {:class ["compose-attachment-name"]} (or name "document.pdf")]
           [:button {:class ["compose-attachment-remove"]
                     :on {:click (fn [_] (dispatch! {:type :compose/remove-image :idx idx}))}}
            (icon/icon {:icon-name :x :size :sm})]]
          [:div {:replicant/key idx :class ["compose-image-thumb"]}
           (let [src (str "data:" media-type ";base64," data)]
             [:img {:src src :alt "attachment" :class ["lightbox-thumb"]
                    :on {:click (fn [_] (dispatch! {:type :lightbox/open :src src}))}}])
           [:button {:class ["compose-image-remove"]
                     :on {:click (fn [_] (dispatch! {:type :compose/remove-image :idx idx}))}}
            (icon/icon {:icon-name :x :size :sm})]]))
      images)]))

;; ── Command suggestions ───────────────────────────────────────────────────────

(def ^:private web-commands
  "Commands shown in the web suggestion popup. `:while-busy?` marks
   non-interrupting commands — read-only views that neither mutate session
   state nor interrupt the running turn, so they may be submitted while the
   agent is busy."
  [{:name "help"     :description "Show available commands" :while-busy? true}
   {:name "model"    :description "Show or set model"}
   {:name "resume"   :description "Resume a previous session"}
   {:name "sessions" :description "List previous sessions"}
   {:name "new"      :description "Start a new session"}
   {:name "clear"    :description "Clear current session"}
   {:name "truncate" :description "Summarize conversation to reduce context"}
   {:name "diff"     :description "Show changes from this session" :while-busy? true
    :subcommands [{:name "git"             :description "All git changes (staged + unstaged + untracked)"}
                  {:name "staged"          :description "Staged changes"}
                  {:name "unstaged"        :description "Unstaged changes"}
                  {:name "session-edits"   :description "Diff of files edited this session"}
                  {:name "session-commits" :description "Diff of commits made this session"}]}
   {:name "commit"   :description "Review changes and create a git commit"}
   {:name "debug"    :description "Copy debug info to clipboard" :while-busy? true}])

(def ^:private web-command-names
  (into #{} (map :name) web-commands))

(defn draft-key
  "Per-chat identity key for state that should be scoped to a single chat
   (drafts, scroll position, …). The room's session id once joined, else the
   route's session id, else the virtual room's id, else `:new`."
  [state]
  (or (get-in (state/active-room state) [:session :id])
      (get-in state [:web/route :session-id])
      (get-in state [:web/pending-room :id])
      :new))

(defn known-command?
  "True if `name` is a recognized web slash command. Used to decide whether a
   submission is worth recording into the recently-executed list."
  [name]
  (contains? web-command-names name))

(def ^:private while-busy-command-names
  (into #{} (comp (filter :while-busy?) (map :name)) web-commands))

(defn command-while-busy?
  "True when `text` is a slash command flagged :while-busy? — safe to submit
   while the agent is busy (read-only, non-interrupting). The leading token
   after the / is matched, so subcommands like \"/diff staged\" qualify too."
  [text]
  (let [t (str/trim (or text ""))]
    (and (str/starts-with? t "/")
         (contains? while-busy-command-names
                    (-> t (subs 1) (str/split #"\s+") first)))))

(defn submittable-while-busy?
  "True when `text` may be submitted while the agent is busy: any plain
   prompt (queued for after the turn) or a :while-busy? command (runs now).
   Only non-while-busy slash commands are held back."
  [text]
  (let [t (str/trim (or text ""))]
    (or (not (str/starts-with? t "/"))
        (command-while-busy? t))))

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

(defn- prompt-nav-controls
  "Jump-to-previous-prompt control shown left of the Projects button. Collapsed
   it is a single up-arrow; once opened it expands into a button group showing
   the current position / total and up (older) / down (newer) navigation.
   `nav-ctx` carries the full-history user-prompt indices so navigation reaches
   prompts that aren't in the rendered timeline window yet."
  [dispatch! nav-idx nav-ctx]
  (let [total (:count nav-ctx)
        prev! (fn [_] (dispatch! (assoc nav-ctx :type :prompt-nav/prev)))
        next! (fn [_] (dispatch! (assoc nav-ctx :type :prompt-nav/next)))]
    (if (nil? nav-idx)
      [:button {:class ["quick-cmd" "prompt-nav-btn"]
                :title "Jump to previous prompt"
                :on {:click prev!}}
       (icon/icon {:icon-name :arrow-up :size :sm})]
      [:div {:class ["prompt-nav-group"]}
       [:button {:class ["quick-cmd" "prompt-nav-btn"]
                 :title "Previous prompt"
                 :disabled (<= nav-idx 0)
                 :on {:click prev!}}
        (icon/icon {:icon-name :arrow-up :size :sm})]
       [:button {:class ["quick-cmd" "prompt-nav-count"]
                 :title "Close prompt navigation"
                 :on {:click (fn [_] (dispatch! {:type :prompt-nav/close}))}}
        (str (inc nav-idx) "/" total)]
       [:button {:class ["quick-cmd" "prompt-nav-btn"]
                 :title "Next prompt"
                 :disabled (>= nav-idx (dec total))
                 :on {:click next!}}
        (icon/icon {:icon-name :arrow-down :size :sm})]])))

(defn- quick-command-bar [dispatch! room-id recents prompt-nav nav-ctx]
  [:div {:class ["quick-commands"]}
   [:div {:class ["quick-cmd-group"]}
    (when (pos? (or (:count nav-ctx) 0))
      (prompt-nav-controls dispatch! prompt-nav nav-ctx))
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
         (quick-command-list recents))]])

(defn- queue-popover
  "Popover listing prompts queued while the agent is busy. Each can be removed
   before the current turn ends and the queue is sent as one combined prompt."
  [dispatch! room-id queued]
  [:div {:class ["queue-popover"]}
   [:div {:class ["queue-popover-header"]}
    [:span (str (count queued) " queued")]
    [:button {:class ["icon-btn" "icon-btn--sm"]
              :on {:click (fn [_] (dispatch! {:type :queue/close-popover}))}}
     (icon/icon {:icon-name :x :size :sm})]]
   (map-indexed
    (fn [idx {:keys [text images]}]
      [:div {:class ["queue-item"] :replicant/key idx}
       [:div {:class ["queue-item-text"]}
        (let [t (str/trim (or text ""))]
          (if (seq t)
            t
            (str (count images) " image" (when (not= 1 (count images)) "s"))))]
       [:button {:class ["icon-btn" "icon-btn--sm" "queue-item-remove"]
                 :on {:click (fn [_]
                               (dispatch! {:type :prompt/queue-remove
                                           :room-id room-id :index idx}))}}
        (icon/icon {:icon-name :x :size :sm})]])
    queued)])

(defn- compose-box [dispatch! room busy? images draft-key draft session-id cmd-selected pa? recents queue-open? prompt-nav nav-ctx]
  (let [room-id  (:id room)
        cmd-query (when (and (not pa?) (string? draft) (str/starts-with? draft "/"))
                    (subs draft 1))
        cmd-matches (when (some? cmd-query) (match-commands cmd-query))
        cmd-open?   (seq cmd-matches)
        has-input?  (seq (str/trim (or draft "")))
        queued      (get-in room [:agent :queued])
        qcount      (count queued)
        new?        (nil? session-id)
        show-quick? (and (not pa?) (not cmd-open?))]
    [:div {:class ["compose-box"]}
     (when (and busy? queue-open? (pos? qcount))
       (queue-popover dispatch! room-id queued))
     (compose-image-strip dispatch! images)
     (when show-quick?
       (quick-command-bar dispatch! room-id recents prompt-nav nav-ctx))
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
               :accept "image/*,application/pdf" :multiple true
               :style {:display "none"}
               :on {:change (fn [^js e]
                              (add-files! dispatch! (array-seq (.. e -target -files)))
                              (set! (.. e -target -value) ""))}}]
      [:div {:class ["compose-input-wrapper"]}
       (form/form-textarea-auto
        {:placeholder (if busy? "Working…" "Message…")
         :value (or draft "")
         :max-rows 6
         :attrs {:replicant/on-mount
                 (fn [{:replicant/keys [^js node]}]
                   ;; Autofocus the input when starting a fresh chat so the
                   ;; user can type immediately. preventScroll avoids a jump
                   ;; before the layout settles (matches the bubble-edit box).
                   (when new?
                     (.focus node #js {:preventScroll true})))
                 :on {:input (fn [^js e]
                               (dispatch! {:type :compose/set-draft
                                           :draft-key draft-key
                                           :text (.. e -target -value)}))
                      :paste (fn [^js e] (handle-compose-paste! dispatch! e))
                      ;; iOS Safari's soft-keyboard Return key does not fire a
                      ;; keydown with key==="Enter" in a textarea; it fires a
                      ;; beforeinput with inputType "insertLineBreak". Handle it
                      ;; so Enter-to-send works on iPhone. (On desktop the
                      ;; keydown handler preventDefaults, so this never fires.)
                      :beforeinput
                      (fn [^js e]
                        (when (= "insertLineBreak" (.-inputType e))
                          (.preventDefault e)
                          (if cmd-open?
                            (let [sel (min (or cmd-selected 0) (dec (count cmd-matches)))
                                  cmd-name (:name (nth cmd-matches sel))]
                              (dispatch! {:type :input/submit :room-id room-id
                                          :text (str "/" cmd-name)})
                              (when-let [^js el (compose-textarea-el)]
                                (set! (.-value el) ""))
                              (dispatch! {:type :compose/clear-draft
                                          :draft-key draft-key}))
                            (let [v (.. e -target -value)]
                              (when (or (not busy?) (submittable-while-busy? v))
                                (submit-compose! dispatch! room-id session-id
                                                 images draft-key v))))))
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
                            (let [v (.. e -target -value)]
                              (when (or (not busy?) (submittable-while-busy? v))
                                (submit-compose! dispatch! room-id session-id
                                                 images draft-key v))))))}}})
       (when busy? (spinner))]
      (if (and busy? (not (command-while-busy? draft)))
        ;; Busy: queue-send button (when there's something to queue) next to
        ;; the abort button, which carries the queued-message count badge.
        [:div {:class ["compose-actions"]}
         (when (or has-input? (seq images))
           [:button {:class ["icon-btn"]
                     :on {:click (fn [_] (submit-compose! dispatch! room-id session-id
                                                          images draft-key draft))}}
            (icon/icon {:icon-name :arrow-up :size :md})])
         [:div {:class ["abort-wrap"]}
          [:button {:class ["icon-btn"]
                    :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
           (icon/icon {:icon-name :circle-x :size :md})]
          (when (pos? qcount)
            [:button {:class ["queue-count"]
                      :on {:click (fn [_] (dispatch! {:type :queue/toggle-popover}))}}
             (str qcount)])]]
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (submit-compose! dispatch! room-id session-id
                                                       images draft-key draft))}}
         (icon/icon {:icon-name :arrow-up :size :md})])]]))

;; ── Permission dialog ────────────────────────────────────────────────────────

(def ^:private cwd-custom-sentinel "__custom__")

(defn- cwd-select-body
  "Radio group (+ custom text input) + submit for the missing-cwd dialog.
   Transient selection lives in app state under :web/dialog-form."
  [dispatch! state options answer!]
  (let [form   (:web/dialog-form state)
        radios (mapv (fn [{:keys [label value]}]
                       {:label label
                        :value (if (= value :custom) cwd-custom-sentinel value)})
                     options)
        choice (or (:choice form) (:value (first radios)))
        custom (or (:custom form) "")
        final  (if (= choice cwd-custom-sentinel) (str/trim custom) choice)]
    [:div {:class ["cwd-select"]}
     (form/form-radio-group
      {:radio-name  "cwd-select"
       :options     radios
       :radio-value choice
       :on-change   (fn [^js e]
                      (dispatch! {:type :web/dialog-form-set
                                  :patch {:choice (.. e -target -value)}}))})
     (when (= choice cwd-custom-sentinel)
       (form/form-input
        {:type        :text
         :placeholder "/absolute/path"
         :value       custom
         :attrs       {:value custom}
         :on-change   (fn [^js e]
                        (dispatch! {:type :web/dialog-form-set
                                    :patch {:custom (.. e -target -value)}}))}))
     [:div {:class ["confirm-actions"]}
      (button/button
       {:variant :primary :size :sm
        :disabled (empty? final)
        :on-click (fn [_]
                    (when (seq final)
                      (dispatch! {:type :web/dialog-form-reset})
                      (answer! final)))}
       "Submit")]]))

(defn- dialog-decision-label
  "Human label for the choice the user made on a now-resolved dialog."
  [type options value]
  (case type
    :confirm    (if value "Allowed" "Denied")
    :select     (or (some #(when (= (:value %) value) (:label %)) options)
                    (str value))
    :alert      "Dismissed"
    :cwd-select (str value)
    (str value)))

(defn- resolved-dialog-post
  "Static bubble for an already-answered dialog: the original message plus a
   pill showing which decision the user made. Rendered from the web-only
   :web/resolved-dialogs log so answered confirms stay visible in the timeline."
  [key {:keys [message type value label]}]
  (let [deny?  (and (= type :confirm) (not value))
        allow? (and (= type :confirm) value)]
    [:div {:class ["post" "post--assistant" "post--dialog" "post--dialog-resolved"]
           :replicant/key (str "rdlg-" key)}
     [:div {:class ["post-body" "dialog-bubble" "dialog-bubble--resolved"]}
      [:div {:class ["dialog-message"]} message]
      [:div {:class ["dialog-decision"
                     (cond deny?  "dialog-decision--deny"
                           allow? "dialog-decision--allow"
                           :else  "dialog-decision--neutral")]}
       (icon/icon {:icon-name (if deny? :x :check) :size :sm})
       [:span label]]]]))

(defn- dialog-post
  "Render a pending dialog (confirm/select/alert/cwd-select) as an inline,
   assistant-side chat bubble in the timeline flow. Visually distinct from a
   normal answer via .post--dialog so the user sees it needs a response.
   On answer we log the decision into :web/resolved-dialogs (anchored to the
   current history length) so the bubble persists as a static record."
  [dispatch! state room history]
  (when-let [{:keys [id type message text options]} (first (get-in room [:ui :dialogs]))]
    (let [room-id (:id room)
          answer! (fn [value]
                    ;; Optimistically log the decision and drop the live dialog
                    ;; in one render (see the :web/dialog-resolved handler), so
                    ;; the interactive bubble swaps to its static record without
                    ;; a flash while the server round-trips the removal.
                    (dispatch! {:type :web/dialog-resolved
                                :room-id room-id
                                :dialog-id id
                                :entry {:key    id
                                        :anchor (count history)
                                        :message (or message text)
                                        :type   type
                                        :value  value
                                        :label  (dialog-decision-label type options value)}})
                    (dispatch! {:type :ui/dialog-response
                                :room-id room-id :dialog-id id :value value})
                    ;; Propagate the removal to other clients: the client-side
                    ;; :ui/dialog-response handler is a no-op on remote echoes,
                    ;; so the mirrored :ui/dialog-close core handler is what
                    ;; actually clears the live dialog everywhere.
                    (dispatch! {:type :ui/dialog-close
                                :room-id room-id :dialog-id id}))]
      [:div {:class ["post" "post--assistant" "post--dialog"]}
       [:div {:class ["post-body" "dialog-bubble"]}
        [:div {:class ["dialog-message"]} (or message text)]
        (if (= type :cwd-select)
          (cwd-select-body dispatch! state options answer!)
          [:div {:class ["dialog-actions"]}
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
                        :on {:click (fn [_] (answer! true))}} "Allow"]))])]])))

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
     [:span {:class ["diff-ln"] :data-ln old-nr}]
     [:span {:class ["diff-ln"] :data-ln new-nr}]
     [:span {:class ["diff-sign"] :data-sign sign}]
     [:span {:class ["diff-text"]}
      (if grammar
        (highlight-code grammar (or text ""))
        (or text ""))]]))

(defn diff-rows-view
  "Render flattened diff rows as a scrollable view with selection highlight.
   Rows are grouped by file: each file gets a sticky header and a horizontally
   scrollable body so long lines don't push the whole view. The selection
   toolbar, when present, is anchored to the bottom of the selected range."
  [dispatch! rows range toolbar]
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
       [:div {:class ["empty-state"]} "No changes."])
     toolbar]))

(defn- position-sel-toolbar!
  "Anchor the selection toolbar to the bottom edge of the last selected diff
   line, within the scrollable diff view. Runs on mount and on every update so
   the toolbar follows the range as the selection changes."
  [{:replicant/keys [^js node]}]
  (when-let [view (some-> node (.closest ".diff-view"))]
    (let [sels (.querySelectorAll view ".diff-line--selected")
          n    (.-length sels)]
      (when (pos? n)
        (let [last-el (.item sels (dec n))
              vr      (.getBoundingClientRect view)
              lr      (.getBoundingClientRect last-el)
              top     (+ (- (.-bottom lr) (.-top vr)) (.-scrollTop view))]
          (set! (.. node -style -top) (str top "px")))))))

(defn- pin-above-keyboard!
  "Publish the VisualViewport metrics as CSS custom properties on the modify
   panel so the stylesheet can pin it just above the on-screen keyboard and,
   on small screens, size it to fill the visible area. The visual viewport
   shrinks when the keyboard opens (iOS); its bottom in layout coords is
   offsetTop + height (the top edge of the keyboard)."
  [^js node]
  (when-let [vv (.-visualViewport js/window)]
    (let [^js st (.-style node)]
      (.setProperty st "--vv-top" (str (.-offsetTop vv) "px"))
      (.setProperty st "--vv-height" (str (.-height vv) "px"))
      (.setProperty st "--vv-bottom" (str (+ (.-offsetTop vv) (.-height vv)) "px")))))

(defn- position-modify-panel!
  "Keep the modify panel pinned above the keyboard instead of anchored to the
   diff range, so focusing the textarea doesn't cause the weird iOS scroll.
   Repositions on mount/update and tracks the VisualViewport so it follows the
   keyboard as it animates open/closed and as the page scrolls; the listeners
   are torn down on unmount."
  [{:replicant/keys [^js node life-cycle]}]
  (let [vv (.-visualViewport js/window)]
    (if (= life-cycle :replicant.life-cycle/unmount)
      (when-let [h (.-_xiVvHandler node)]
        (some-> vv (.removeEventListener "resize" h))
        (some-> vv (.removeEventListener "scroll" h))
        (set! (.-_xiVvHandler node) nil))
      (do
        (pin-above-keyboard! node)
        (when (and vv (not (.-_xiVvHandler node)))
          (let [h (fn [_] (pin-above-keyboard! node))]
            (set! (.-_xiVvHandler node) h)
            (.addEventListener vv "resize" h)
            (.addEventListener vv "scroll" h)))))))

(defn- diff-region-view
  "Read-only, syntax-highlighted preview of the selected diff lines, shown
   above the textarea in the full-screen modify dialog on small screens."
  [rows range]
  (let [grammar-cache (atom {})
        grammar-for (fn [f] (or (@grammar-cache f)
                                (let [g (diff-file-grammar f)]
                                  (swap! grammar-cache assoc f g) g)))
        sel-rows (filter (fn [{:keys [row sel-idx]}]
                           (and (= :line row) sel-idx
                                (<= (first range) sel-idx (second range))))
                         rows)]
    [:div {:class ["diff-region"]}
     (map-indexed
      (fn [i {:keys [line filename]}]
        (diff-line-view (fn [_]) (grammar-for filename)
                        false line nil (str "region-" i)))
      sel-rows)]))

(defn- diff-action-bar
  "Toolbar shown when diff lines are selected, anchored to the bottom of the
   selected range. By default it's a segmented pill (clj-ui toolbar) with the
   selection actions (Clear / Modify / Goto / Explain); toggling Modify swaps it
   for a popover panel (clj-ui popover-content surface) that sends an
   instruction for the selected region. On small screens the modify panel
   becomes a full-screen dialog with a scrollable preview of the selection
   above the textarea."
  [dispatch! room-id rows range modify?]
  (let [n (when range (inc (- (second range) (first range))))
        anchor-attrs {:replicant/key "diff-sel-toolbar"
                      :replicant/on-mount position-sel-toolbar!
                      :replicant/on-update position-sel-toolbar!}
        submit-modify!
        (fn [_]
          (when-let [el (.getElementById js/document "diff-modify-input")]
            (dispatch! {:type :diff/modify-submit
                        :room-id room-id :text (.-value el)})))]
    (if modify?
      [:div {:replicant/key "diff-modify-panel"
             :replicant/on-render position-modify-panel!
             :class ["diff-sel-anchor" "diff-sel-anchor--modify"
                     "popover-content" "popover-content--bottom"]}
       [:div {:class ["diff-modify-head"]}
        [:span {:class ["popover-title"]}
         (str "Modify " n " line" (when (not= 1 n) "s"))]
        (button/button
         {:variant :ghost :size :sm
          :on-click (fn [_] (dispatch! {:type :diff/modify-toggle}))}
         "Cancel")]
       (diff-region-view rows range)
       [:textarea {:id "diff-modify-input"
                   :class ["form-textarea" "diff-modify-input"]
                   :placeholder "Describe the change to make to the selected code…"
                   :rows 3
                   :replicant/on-mount (fn [{:replicant/keys [^js node]}]
                                         (.focus node #js {:preventScroll true}))
                   :on {:keydown (fn [^js e]
                                   (when (and (= "Enter" (.-key e)) (.-metaKey e))
                                     (.preventDefault e) (submit-modify! e)))}}]
       [:div {:class ["diff-modify-foot"]}
        (button/button
         {:variant :primary :size :sm
          :on-click submit-modify!}
         "Send")]]
      [:div (assoc anchor-attrs :class ["diff-sel-anchor"])
       (toolbar/toolbar
        {}
        (button/button
         {:variant :ghost :size :sm
          :aria-label "Clear selection"
          :on-click (fn [_] (dispatch! {:type :diff/clear-selection}))}
         (icon/icon {:icon-name :x :size :sm}))
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
         "Explain"))])))

(def ^:private diff-methods
  "Selectable diff sources. :title is the buffer title :diff/load assigns for
   each, used to reflect the active method back into the select."
  [{:value "session-edits"   :label "Session edits"   :title "Session Edits"}
   {:value "session-commits" :label "Session commits" :title "Session Commits"}
   {:value "git"             :label "All git changes" :title "All Git Changes"}
   {:value "git-upstream"    :label "Upstream"        :title "Upstream"}
   {:value "staged"          :label "Staged"          :title "Staged Changes"}
   {:value "unstaged"        :label "Unstaged"        :title "Unstaged Changes"}])

(def ^:private diff-title->method
  (into {} (map (juxt :title :value)) diff-methods))

(def ^:private diff-engines
  "Selectable diff renderers. :git → native unified diff (interactive viewer);
   :difft → difftastic structural output (read-only)."
  [{:value "git"   :label "git"}
   {:value "difft" :label "difftastic"}])

(defn- diff-method-for-title [title]
  (or (get diff-title->method title)
      ;; git-upstream's title carries the ref, e.g. "Upstream (origin/main)"
      (when (str/starts-with? (or title "") "Upstream") "git-upstream")
      "session-edits"))

(defn- select-value-hook
  "Replicant on-render hook that forces a <select>'s value. Replicant does not
   reliably bind a select's value on initial mount (the value is set before the
   option children exist), so we set it after the node + options are in place."
  [v]
  (fn [{:replicant/keys [^js node]}]
    (set! (.-value node) v)))

(defn- diff-method-bar
  "Selects above the diff to switch the source and the renderer. Each fires
   :diff/reopen, which re-runs /diff on the server (measuring the difftastic
   column width from the browser viewport first, so it fills the page)."
  [dispatch! room-id title engine]
  (let [method (diff-method-for-title title)
        engine (or engine :git)]
    [:div {:class ["diff-method-bar"]}
     (form/form-select
      {:options   diff-methods
       :value     method
       :attrs     {:value method :replicant/on-render (select-value-hook method)}
       :on-change (fn [^js e]
                    (dispatch! {:type :diff/reopen :room-id room-id
                                :method (.. e -target -value) :engine engine}))})
     (form/form-select
      {:options   diff-engines
       :value     (name engine)
       :attrs     {:value (name engine) :replicant/on-render (select-value-hook (name engine))}
       :on-change (fn [^js e]
                    (dispatch! {:type :diff/reopen :room-id room-id
                                :method method :engine (keyword (.. e -target -value))}))})]))

(defn- ansi-sgr-state
  "Fold one SGR escape's `;`-separated codes into the running style state.
   Only the codes difftastic emits (syntax-highlight off) are meaningful:
   reset, bold, dim, red (removed), green (added), yellow (header)."
  [state codes]
  (reduce (fn [st code]
            (case code
              ("" "0")     {}
              "1"          (assoc st :bold true)
              "2"          (assoc st :dim true)
              "22"         (dissoc st :bold :dim)
              ("31" "91")  (assoc st :fg :red)
              ("32" "92")  (assoc st :fg :green)
              ("33" "93")  (assoc st :fg :yellow)
              "39"         (dissoc st :fg)
              st))
          state
          (str/split (or codes "") #";")))

(defn- ansi-span [{:keys [fg bold dim]} text]
  [:span {:class (cond-> []
                   (= fg :red)    (conj "difft-del")
                   (= fg :green)  (conj "difft-add")
                   (= fg :yellow) (conj "difft-hdr")
                   dim            (conj "difft-dim")
                   bold           (conj "difft-bold"))}
   text])

(defn- difft-spans
  "Parse difftastic's ANSI-colored output into styled hiccup spans, so the web
   shows the same red (removed) / green (added) signal as the terminal."
  [text]
  (let [re (js/RegExp. "\\u001b\\[([0-9;]*)m" "g")]
    (loop [pos 0 state {} out []]
      (if-let [m (.exec re text)]
        (let [idx   (.-index m)
              chunk (subs text pos idx)
              out'  (cond-> out (seq chunk) (conj (ansi-span state chunk)))]
          (recur (+ idx (.-length (aget m 0)))
                 (ansi-sgr-state state (aget m 1))
                 out'))
        (let [chunk (subs text pos)]
          (cond-> out (seq chunk) (conj (ansi-span state chunk))))))))

(defn- diff-tab-view
  "Full diff buffer view rendered as the active tab. Git diffs use the
   interactive unified-diff viewer (line selection, Explain / Modify);
   difftastic diffs are read-only structural text colored from their ANSI
   (red = removed, green = added)."
  [dispatch! room-id diff-buffer sel modify?]
  (let [engine (or (:engine diff-buffer) :git)]
    [:div {:class ["diff-tab"]}
     (diff-method-bar dispatch! room-id (:title diff-buffer) engine)
     (if (= engine :difft)
       (into [:pre {:class ["diff-difft"]}] (difft-spans (:text diff-buffer)))
       (let [rows  (diff/diff-rows (diff/parse-diff-text (:text diff-buffer)))
             range (diff/selection-range sel)]
         (diff-rows-view dispatch! rows range
                         (when range
                           (diff-action-bar dispatch! room-id rows range modify?)))))]))

;; ── Tab bar ──────────────────────────────────────────────────────────────────

(defn- tab-bar
  "Segmented pill for switching between chat and buffer views. Lives inline in
   the topbar next to the overflow menu; only rendered when a buffer exists."
  [dispatch! room-id active-buffer buffers]
  [:div {:class ["tab-pill"]}
   [:button {:class ["tab-pill-item" (when (= active-buffer :chat) "tab-pill-item--active")]
             :on {:click (fn [_] (dispatch! {:type :ui/buffer-switch
                                             :room-id room-id :buffer-id :chat}))}}
    "Chat"]
   (when (:diff buffers)
     [:button {:class ["tab-pill-item" (when (= active-buffer :diff) "tab-pill-item--active")]
               :on {:click (fn [_] (dispatch! {:type :ui/buffer-switch
                                               :room-id room-id :buffer-id :diff}))}}
      "Diff"])])

;; ── Chat view ────────────────────────────────────────────────────────────────

(defn- offline-badge [state]
  (when (false? (:web/connected? state))
    [:span {:class ["offline-label"]} "Offline"]))

(defn- selector-menu
  "Full-width dropdown panel rendered below the topbar — the web analog of the
   TUI completion menu. `id` scopes the replicant keys; `items` is a seq of
   {:value :label :desc :active?} maps; `on-select` receives the chosen item's
   :value; `on-close` fires on backdrop click; `empty-label` shows when there
   are no items.

   When `search` is provided ({:query :placeholder :on-search}) a filter input
   is rendered at the top; items are filtered case-insensitively on their label
   and desc, and `on-search` receives the live query string."
  [{:keys [id items on-select on-close empty-label search]}]
  (let [q       (some-> (:query search) str/trim str/lower-case not-empty)
        visible (if q
                  (filter (fn [{:keys [label desc]}]
                            (or (str/includes? (str/lower-case (str label)) q)
                                (and desc (str/includes? (str/lower-case (str desc)) q))))
                          items)
                  items)]
    (list
     [:div {:class ["selector-menu-backdrop"]
            :replicant/key (str id "-backdrop")
            :on {:click (fn [_] (on-close))}}]
     [:div {:class ["selector-menu"]
            :replicant/key (str id "-menu")}
      (when search
        [:input {:class ["selector-menu-search"]
                 :replicant/key (str id "-search")
                 :type "text"
                 :placeholder (or (:placeholder search) "Search…")
                 :value (or (:query search) "")
                 :replicant/on-mount (fn [{:replicant/keys [^js node]}]
                                       (.focus node #js {:preventScroll true}))
                 :on {:input (fn [^js e] ((:on-search search) (.. e -target -value)))
                      :click (fn [^js e] (.stopPropagation e))}}])
      (if (seq visible)
        (for [{:keys [value label desc active?]} visible]
          [:button {:class ["selector-menu-item"
                            (when active? "selector-menu-item--active")]
                    :replicant/key value
                    :on {:click (fn [e]
                                  (.stopPropagation e)
                                  (on-select value))}}
           [:span {:class ["selector-menu-name"]} label]
           (when (seq desc)
             [:span {:class ["selector-menu-desc"]} desc])])
        [:div {:class ["selector-menu-empty"]} (or empty-label "Nothing found")])])))

(defn- model-selector [dispatch! room-id models current-model query]
  (selector-menu
   {:id "model"
    :items (for [m models]
             {:value m :label m :active? (= m current-model)})
    :on-select (fn [m] (dispatch! {:type :models/select :model m :room-id room-id}))
    :on-close  (fn [] (dispatch! {:type :models/close}))
    :search {:query query
             :placeholder "Search models…"
             :on-search (fn [q] (dispatch! {:type :selector/search :id "model" :query q}))}}))

(defn- skill-selector
  "Overlay menu of on-demand skills (name + description). Selecting one loads
   it into the current room via `/skill load`."
  [dispatch! room-id skills]
  (selector-menu
   {:id "skill"
    :items (for [{:keys [name description]} skills]
             {:value name :label name :desc description})
    :on-select (fn [name] (dispatch! {:type :skill/select :name name :room-id room-id}))
    :on-close  (fn [] (dispatch! {:type :skill/close}))
    :empty-label "No skills found"}))


(defn shorten-path
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

(defn nav-group
  "Burger toggle + back arrow rendered as one segmented button split by a
   divider. Used on every header bar that has both. `on-back` is the back
   arrow's click handler."
  [dispatch! on-back]
  [:div {:class ["nav-group"]}
   (menu-button dispatch!)
   [:button {:class ["icon-btn"]
             :on {:click on-back}}
    (icon/icon {:icon-name :arrow-left :size :md})]])

(defn- more-vertical-icon
  "Inline three-dots (vertical ellipsis) SVG — there is no ellipsis icon in the
   shared icon set, so we render a Lucide-compatible one matching the icon
   component's stroke style and sizing."
  []
  [:svg {:class ["icon"]
         :xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "2"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:circle {:cx "12" :cy "5" :r "1"}]
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "12" :cy "19" :r "1"}]])

(defn overflow-menu
  "Three-dots overflow menu shown on the right of every topbar. Holds the
   debug-copy action (which used to be a standalone topbar button). Toggles the
   :web/overflow-menu? app state; a backdrop closes it on outside click.

   `git-ctx` enables the \"Git status\" item: {:mode :room :room-id …} opens the
   working-tree diff in the room's :diff buffer (via /diff git); {:mode :project
   :cwd …} navigates to the roomless git-status page. nil hides the item."
  ([dispatch! state] (overflow-menu dispatch! state nil))
  ([dispatch! state git-ctx]
   (let [open? (:web/overflow-menu? state)
         room  (state/active-room state)]
    [:div {:class ["overflow-menu-wrap"]}
     [:button {:class ["icon-btn" "icon-btn--sm"]
               :title "More"
               :on {:click (fn [e]
                             (.stopPropagation e)
                             (dispatch! {:type :overflow/toggle}))}}
      (more-vertical-icon)]
     (when open?
       (list
        [:div {:class ["overflow-menu-backdrop"]
               :replicant/key "overflow-backdrop"
               :on {:click (fn [_] (dispatch! {:type :overflow/close}))}}]
        [:div {:class ["overflow-menu"]
               :replicant/key "overflow-menu"}
         [:button {:class ["overflow-menu-item"]
                   :on {:click (fn [e]
                                 (.stopPropagation e)
                                 (dispatch! {:type :overflow/close})
                                 (dispatch! {:type :room/new}))}}
          (icon/icon {:icon-name :plus :size :sm})
          [:span "New chat"]]
         (when (#{:room :project} (:mode git-ctx))
           [:button {:class ["overflow-menu-item"]
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :overflow/close})
                                   (case (:mode git-ctx)
                                     :room    (dispatch! {:type :diff/reopen
                                                          :room-id (:room-id git-ctx)
                                                          :method "git" :engine :git})
                                     :project (dispatch! {:type :git-status/open
                                                          :cwd (:cwd git-ctx)})))}}
            (icon/icon {:icon-name :code :size :sm})
            [:span "Git status"]])
         ;; Extension nav items: :mode-less items always show; :mode-scoped
         ;; ones only in the matching git-ctx mode, with the ctx keys merged
         ;; into their event (so e.g. a :project item receives the :cwd).
         (for [item (nav-items-for state :overflow)
               :when (or (nil? (:mode item)) (= (:mode item) (:mode git-ctx)))]
           [:button {:class ["overflow-menu-item"]
                     :replicant/key (str "nav-" (:label item))
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :overflow/close})
                                   (dispatch! (merge (:event item)
                                                     (select-keys git-ctx [:cwd :room-id :number]))))}}
            (icon/icon {:icon-name (:icon item) :size :sm})
            [:span (:label item)]])
         (when room
           [:button {:class ["overflow-menu-item"]
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :overflow/close})
                                   (dispatch! {:type :models/web-list}))}}
            (icon/icon {:icon-name :layers :size :sm})
            [:span "Change model"]])
         (when room
           [:button {:class ["overflow-menu-item"]
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :overflow/close})
                                   (dispatch! {:type :skill/web-list}))}}
            (icon/icon {:icon-name :zap :size :sm})
            [:span "Skills"]])
         [:div {:class ["overflow-menu-divider"]}]
         [:button {:class ["overflow-menu-item"]
                   :on {:click (fn [e]
                                 (.stopPropagation e)
                                 (dispatch! {:type :overflow/close})
                                 (let [text (commands/debug-text room)]
                                   (if ios?
                                     (dispatch! {:type :copy/open :text text})
                                     (copy-to-clipboard! text))))}}
          (icon/icon {:icon-name :copy :size :sm})
          [:span "Copy debug info"]]
         [:button {:class ["overflow-menu-item"]
                   :on {:click (fn [e]
                                 (.stopPropagation e)
                                 (dispatch! {:type :overflow/close})
                                 (.reload js/location))}}
          (icon/icon {:icon-name :refresh :size :sm})
          [:span "Reload"]]]))])))

(defn- optimistic-post
  "An optimistic user bubble rendered at the tail of the timeline the instant a
   prompt is sent, before the server echoes the real :user entry back (instant
   feedback on slow/mobile links). Suppressed once the matching entry lands in
   history so it never duplicates the authoritative message."
  [dispatch! state room sid history]
  (when-let [{:keys [room-id session-id text images]} (:web/optimistic state)]
    (let [for-this? (or (and room-id (= room-id (:id room)))
                        (and session-id sid (= session-id sid))
                        ;; A virtual new chat whose room hasn't joined yet: no
                        ;; session on either side and no room adopted. Show the
                        ;; bubble instantly while :room/join round-trips.
                        (and (nil? sid) (nil? session-id) (nil? (:id room))))
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
          ;; A virtual new chat started from a project listing carries that
          ;; folder in :web/pending-room — go back to it instead of the root.
          cwd (or (get-in state [:web/pending-room :cwd])
                  (:cwd (state/active-room state))
                  (some (fn [s] (when (= sid (:session-id s)) (:cwd s)))
                        (get-in state [:lobby :sessions]))
                  (some (fn [r] (when (= sid (:session-id r)) (:cwd r)))
                        (get-in state [:lobby :rooms])))]
      (cond-> {:type :route/navigate :page :home}
        cwd (assoc :dir cwd)))))

(defn- launch-header
  "Welcome/info block shown at the top of an empty chat — mirrors the TUI
   launch header (Xi banner, model, cwd, AGENTS.md files). Works for a virtual
   (not-yet-joined) room too: model/agents-files are simply omitted until the
   first prompt creates the real room."
  [{:keys [model cwd agents-files pa?]}]
  [:div {:class ["launch-header"]}
   [:div {:class ["launch-title"]}
    [:span {:class ["launch-brand"]} "Xi"]
    [:span {:class ["launch-tagline"]}
     (if pa? " — personal agent" " — coding agent")]]
   (when model
     [:div {:class ["launch-meta"]}
      [:span {:class ["launch-label"]} "Model: "]
      [:span {:class ["launch-value"]} model]])
   (when cwd
     [:div {:class ["launch-meta"]}
      [:span {:class ["launch-label"]} "cwd: "]
      [:span {:class ["launch-value"]} (shorten-path cwd)]])
   [:div {:class ["launch-hint"]} "Type /help for commands."]
   (when-let [files (seq agents-files)]
     [:div {:class ["launch-meta"]}
      [:span {:class ["launch-label"]} "Loaded "]
      [:span {:class ["launch-value"]} (str (count files) " AGENTS.md")]
      [:span {:class ["launch-label"]}
       (str " file" (when (> (count files) 1) "s"))]])])

(defn- clamp-bubble-menu!
  "Keep the tap menu inside the visible viewport: flip it above the tap point
   when it would overflow the bottom (e.g. a bubble tapped just above the
   compose box / keyboard) and nudge it left when it would run past the right
   edge. Uses the visual viewport height so it accounts for the iOS keyboard."
  [{:replicant/keys [^js node]}]
  (let [vw     (.-innerWidth js/window)
        vh     (or (some-> js/window .-visualViewport .-height) (.-innerHeight js/window))
        r      (.getBoundingClientRect node)
        margin 8
        left   (js/parseFloat (.. node -style -left))
        top    (js/parseFloat (.. node -style -top))
        left'  (max margin (min left (- vw (.-width r) margin)))
        top'   (if (> (+ top (.-height r)) (- vh margin))
                 (max margin (- top (.-height r)))
                 top)]
    (set! (.. node -style -left) (str left' "px"))
    (set! (.. node -style -top) (str top' "px"))))

(defn- bubble-menu
  "Action sheet shown when a user chat bubble is tapped. Edit switches the
   bubble into an inline editor (Cancel / Save); only Save forks the
   conversation from that message (truncates history to before it, like
   /tree edit) and resubmits the edited text. Delete forks the same way but
   discards the message. Copy uses the iOS long-press fallback when the async
   Clipboard API is unavailable."
  [dispatch! room-id {:keys [index text x y]}]
  (let [close! (fn [] (dispatch! {:type :bubble/menu-close}))]
    [:div {:class ["bubble-menu-backdrop"]
           :on {:click (fn [_] (close!))}}
     [:div {:class ["bubble-menu"]
            :style {:top (str y "px") :left (str x "px")}
            :replicant/on-mount clamp-bubble-menu!
            :on {:click (fn [e] (.stopPropagation e))}}
      [:button {:class ["bubble-menu-item"]
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (close!)
                              (dispatch! {:type :bubble/edit-start
                                          :index index :text (or text "")}))}}
       (icon/icon {:icon-name :edit :size :sm})
       [:span "Edit"]]
      [:button {:class ["bubble-menu-item"]
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (close!)
                              (if ios?
                                (dispatch! {:type :copy/open :text text})
                                (copy-to-clipboard! text)))}}
       (icon/icon {:icon-name :copy :size :sm})
       [:span "Copy"]]
      [:button {:class ["bubble-menu-item" "bubble-menu-item--danger"]
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (close!)
                              (dispatch! {:type :tree/navigate
                                          :room-id room-id :index index}))}}
       (icon/icon {:icon-name :trash :size :sm})
       [:span "Delete"]]]]))

(defn- chat-view [state dispatch!]
  (let [active  (state/active-room state)
        sid     (get-in state [:web/route :session-id])
        ;; Mid-switch the client stays attached to the previous room until
        ;; :room/joined for the target arrives. Only treat the active room as
        ;; the one being viewed when its session id matches the route — a new
        ;; chat (sid nil) only adopts a not-yet-saved room (session id nil),
        ;; never the previous large room we're still attached to. Otherwise
        ;; we'd render the old room's history instead of a spinner / launch
        ;; header / the target's cached history.
        room    (when (= (get-in active [:session :id]) sid)
                  active)
        cached  (get-in state [:web/cache sid])
        history (or (:history room) (:history cached))
        busy?   (get-in room [:agent :busy?])
        model   (or (get-in room [:agent :model]) (:model cached))
        new?    (nil? sid)
        ;; An existing session whose history hasn't streamed in yet (and that
        ;; has no optimistic/pending content to show) is still loading — keep
        ;; the spinner up instead of flashing the empty-room launch header for
        ;; a frame before the messages render.
        loading? (and (not new?)
                      (empty? history)
                      (not (:web/optimistic state))
                      (not (:web/pending-submit state)))
        ready?  (not loading?)
        pa?     (get-in state [:lobby :personal-agent?])
        dkey    (draft-key state)
        model-list (:web/model-list state)
        buffers    (get-in room [:ui :buffers])
        active-buf (get-in room [:ui :active-buffer] :chat)
        has-tabs?  (boolean (:diff buffers))
        ;; Prompt navigation over the FULL history (not just the rendered
        ;; window): collect every user entry's absolute history index so we can
        ;; jump to prompts scrolled off the top, expanding the window on demand.
        nav-ctx (let [entries (vec history)
                      total   (count entries)
                      win     (or (:web/timeline-window state) initial-window-size)
                      user-indices (vec (keep-indexed
                                         (fn [i e] (when (= :user (:kind e)) i))
                                         entries))]
                  {:user-indices user-indices
                   :count (count user-indices)
                   :total total
                   :cur-window win})]
    [:div {:class ["container"] :replicant/key "chat"}
     [:div {:class ["topbar" "topbar--chat"]}
      (nav-group dispatch! (fn [_] (dispatch! (chat-back-route state))))
      [:div {:class ["topbar-title"]}]
      (offline-badge state)
      (when has-tabs?
        (tab-bar dispatch! (:id room) active-buf buffers))
      (overflow-menu dispatch! state (when room {:mode :room :room-id (:id room)}))]
     (when model-list
       (model-selector dispatch! (:id room) model-list model
                       (get-in state [:web/selector-search "model"])))
     (when-let [skills (:web/skill-list state)]
       (skill-selector dispatch! (:id room) skills))
     (case active-buf
       :diff
       (diff-tab-view dispatch! (:id room) (:diff buffers)
                      (:web/diff-sel state)
                      (:web/diff-modify? state))

       ;; default: :chat
       (list
        [:div {:class ["timeline" (when-not pa? "timeline--float-footer")]}
         [:div {:class ["timeline-content"]}
          (if ready?
            (let [entries (vec history)
                  total   (count entries)
                  win     (or (:web/timeline-window state) initial-window-size)
                  start   (max 0 (- total win))]
              (list
               (when (and (zero? total)
                          (not (:web/optimistic state))
                          (not (:web/pending-submit state)))
                 (launch-header
                  {:model model
                   :cwd (or (:cwd room) (get-in state [:web/pending-room :cwd]))
                   :agents-files (get-in room [:agent :agents-files])
                   :pa? pa?}))
               (when (pos? start)
                 [:div {:class ["load-earlier"]}
                  (button/button
                   {:variant :ghost :size :sm
                    :on-click (fn [_] (dispatch! {:type :timeline/set-window
                                                  :window (+ win window-step)}))}
                   (str "Show " (min window-step start) " earlier messages"
                        " (" start " hidden)"))])
               (let [editing   (:web/editing-bubble state)
                     ;; Answered dialogs live in a web-only log, each anchored
                     ;; to the history length at answer time so its static
                     ;; bubble stays in chronological place as the turn resumes.
                     by-anchor (group-by :anchor (get-in state [:web/resolved-dialogs (:id room)]))
                     rposts    (fn [p] (map (fn [e] (resolved-dialog-post (:key e) e))
                                            (get by-anchor p)))]
                 (concat
                  ;; Resolved bubbles anchored above the visible window: pin at top.
                  (mapcat rposts (sort (filter #(< % start) (keys by-anchor))))
                  (mapcat
                   (fn [p]
                     (concat
                      (rposts p)
                      (when (< p total)
                        (let [entry (nth entries p)
                              post  (entry->post
                                     dispatch!
                                     (cond-> (assoc entry :history-index p)
                                       (and (= :user (:kind entry)) (= p (:index editing)))
                                       (assoc :editing? true :edit-text (:text editing))))]
                          (when post [post])))))
                   (range start (inc total)))))
               (optimistic-post dispatch! state room sid history)
               (dialog-post dispatch! state room history)))
            [:div {:class ["empty-state"]} (spinner) [:p "Connecting…"]])]]
        (copy-dialog-overlay dispatch! (:web/copy-text state))
        (when-let [menu (:web/bubble-menu state)]
          (bubble-menu dispatch! (:id room) menu))
        (lightbox/lightbox {:src (:web/lightbox state)
                            :on-close (fn [] (dispatch! {:type :lightbox/close}))})
        (when (:web/project-picker? state)
          (project-picker dispatch! (:web/project-dirs state) dkey))
        (compose-box dispatch! room busy? (:web/compose-images state)
                     dkey (get-in state [:web/drafts dkey]) sid
                     (:web/cmd-selected state)
                     (get-in state [:lobby :personal-agent?])
                     (:web/recent-commands state)
                     (:web/queue-popover? state)
                     (:web/prompt-nav state)
                     nav-ctx)))]))

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
     :favorite?   (boolean (:favorite? s))
     :current?    (and sid (= sid (get-in state [:web/route :session-id])))
     :active?     (boolean room)
     :busy?       (boolean (:busy? room))
     :has-dialog? (boolean (:has-dialog? room))
     :unread?     (boolean (and w (> (get counts sid 0) w)))}))

(defn- active-first
  "Enrich disk sessions with live indicators (via session-status) and pin the
   ones backed by a live room to the top, preserving the incoming
   (last-visited) order within each group. Keeps working/active rooms visible
   at the top of every session listing instead of buried by newer sessions."
  [state sessions]
  (let [{active true inactive false}
        (group-by (comp boolean :active?)
                  (map #(session-status state %) sessions))]
    (concat active inactive)))

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

(defn- session-card [dispatch! {:keys [session-id name timestamp current? active? busy? has-dialog? unread? favorite?]}]
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
      unread? [:div {:class ["unread-dot"]}])]
   (when session-id
     [:button {:class ["project-card-action" "project-card-favorite"
                       (when favorite? "project-card-favorite--on")]
               :title (if favorite? "Remove bookmark" "Bookmark session")
               :on {:click (fn [^js e]
                             (.stopPropagation e)
                             (dispatch! {:type :favorites/toggle :session-id session-id}))}}
      (icon/icon {:icon-name :star :size :sm})])])



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
   [:button {:class ["project-card-action"]
             :title "New chat"
             :on {:click (fn [^js e]
                           (.stopPropagation e)
                           (dispatch! {:type :projects/new-session :cwd path}))}}
    (icon/icon {:icon-name :plus :size :sm})]])


(defn- session-matches?
  "Case-insensitive substring match of `query` against a session/orphan name."
  [query name]
  (str/includes? (str/lower-case (or name "")) query))

(defn- filter-sessions
  "Filter `sessions` by the active search. `query` is already lowercased/trimmed.
   In content mode, keep only sessions whose id the server returned in `matches`
   (nil while a search is in flight -> keep none)."
  [sessions query content? matches]
  (cond
    (empty? query)  sessions
    content?        (let [ids (or matches #{})]
                      (filter #(contains? ids (:session-id %)) sessions))
    :else           (filter #(session-matches? query (:name %)) sessions)))


(defn- search-box
  "Generic search input. `search-key` is the state key for the query string.
   When `content?` is non-nil a toggle button is rendered next to the input
   that switches between name-only and message-content search (true = content
   mode active)."
  ([dispatch! search-key placeholder query]
   (search-box dispatch! search-key placeholder query nil))
  ([dispatch! search-key placeholder query content?]
   [:div {:class ["sessions-search"]}
    [:div {:class ["sidebar-search"]}
     [:span {:class ["sidebar-search-icon"]}]
     [:input {:class ["sidebar-search-input"]
              :type "text"
              :placeholder (if content? "Search message content\u2026" placeholder)
              :value (or query "")
              :on {:input (fn [^js e]
                            (dispatch! {:type :web/session-search
                                        :key search-key
                                        :query (.. e -target -value)}))}}]]
    (when (some? content?)
      [:button {:class ["search-toggle" (when content? "search-toggle--active")]
                :type "button"
                :title (if content?
                         "Searching message content — click to search names only"
                         "Searching names — click to search message content")
                :on {:click (fn [_] (dispatch! {:type :web/toggle-content-search
                                                :key search-key}))}}
       (icon/icon {:icon-name :file-text :size :md})])]))

(defn- project-sessions-view
  "Drill-down: sessions for a selected project directory."
  [state dispatch!]
  (let [cwd       (:web/selected-project-dir state)
        raw-query (get-in state [:web/search :project-sessions])
        query     (str/lower-case (str/trim (or raw-query "")))
        content?  (boolean (get-in state [:web/content-search :project-sessions]))
        matches   (get-in state [:web/content-matches :project-sessions])
        sessions  (:web/project-sessions state)
        orphans   (orphan-rooms state sessions cwd)
        sessions  (filter-sessions sessions query content? matches)
        orphans   (filter-sessions orphans query content? matches)
        loading?  (:web/project-sessions-loading? state)]
    [:div {:class ["container"] :replicant/key "project-sessions"}
     [:div {:class ["topbar"]}
      (nav-group dispatch! (fn [_] (dispatch! {:type :route/navigate :page :home})))
      [:div {:class ["topbar-title"]} (shorten-path cwd)]
      [:button {:class ["icon-btn"]
                :title "New session"
                :on {:click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}}
       (icon/icon {:icon-name :plus :size :md})]
      (overflow-menu dispatch! state {:mode :project :cwd cwd})]
     [:div {:class ["home"]}
      (when-not loading?
        (search-box dispatch! :project-sessions "Search sessions…" raw-query content?))
      (cond
        loading?
        [:div {:class ["empty-state"]} (spinner) [:p "Loading sessions…"]]

        (and (empty? sessions) (empty? orphans))
        (if (seq query)
          [:div {:class ["empty-state"]} [:p "No matching sessions."]]
          [:div {:class ["empty-state"]}
           [:p "No sessions yet."]
           [:button {:class ["btn" "btn--primary"]
                     :on {:click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}}
            "Start new session"]])

        :else
        [:div {:class ["project-list"]}
         (for [o orphans]
           (session-card dispatch! o))
         (for [s (active-first state sessions)]
           (session-card dispatch! s))])]]))



(defn- all-sessions-view
  "Flat list of all sessions (the old home view)."
  [state dispatch!]
  (let [raw-query  (get-in state [:web/search :all-sessions])
        query      (str/lower-case (str/trim (or raw-query "")))
        content?   (boolean (get-in state [:web/content-search :all-sessions]))
        matches    (get-in state [:web/content-matches :all-sessions])
        sessions   (get-in state [:lobby :sessions])
        connected? (:web/connected? state)
        orphans    (orphan-rooms state sessions)
        sessions   (filter-sessions sessions query content? matches)
        orphans    (filter-sessions orphans query content? matches)]
    [:div {:class ["container"] :replicant/key "all-sessions"}
     [:div {:class ["topbar"]}
      (nav-group dispatch! (fn [_] (dispatch! {:type :route/navigate :page :home})))
      [:div {:class ["topbar-title"]} "All sessions"]
      (when connected?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :room/new}))}}
         (icon/icon {:icon-name :plus :size :md})])
      (overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (search-box dispatch! :all-sessions "Search sessions\u2026" raw-query content?)
      (cond
        (or (seq sessions) (seq orphans))
        [:div {:class ["project-list"]}
         (for [o orphans]
           (session-card dispatch! o))
         (for [s (active-first state sessions)]
           (session-card dispatch! s))]

        (seq query)
        [:div {:class ["empty-state"]} [:p "No matching sessions."]]

        :else
        [:div {:class ["empty-state"]} [:p "No sessions yet."]])]]))

(defn- favorites-view
  "Flat list of bookmarked sessions (filtered from the lobby sessions)."
  [state dispatch!]
  (let [raw-query  (get-in state [:web/search :favorites])
        query      (str/lower-case (str/trim (or raw-query "")))
        content?   (boolean (get-in state [:web/content-search :favorites]))
        matches    (get-in state [:web/content-matches :favorites])
        sessions   (->> (get-in state [:lobby :sessions])
                        (filter :favorite?))
        sessions   (filter-sessions sessions query content? matches)]
    [:div {:class ["container"] :replicant/key "favorites"}
     [:div {:class ["topbar"]}
      (nav-group dispatch! (fn [_] (dispatch! {:type :route/navigate :page :home})))
      [:div {:class ["topbar-title"]} "Favorites"]
      (overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (search-box dispatch! :favorites "Search favorites\u2026" raw-query content?)
      (cond
        (seq sessions)
        [:div {:class ["project-list"]}
         (for [s (active-first state sessions)]
           (session-card dispatch! s))]

        (seq query)
        [:div {:class ["empty-state"]} [:p "No matching favorites."]]

        :else
        [:div {:class ["empty-state"]}
         [:p "No favorites yet."]
         [:p {:class ["empty-state-hint"]} "Tap the star on a session to bookmark it."]])]]))

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
         (icon/icon {:icon-name :plus :size :md})])
      (overflow-menu dispatch! state)]
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
         (for [s (active-first state sessions)]
           (session-card dispatch! s))]

        :else
        [:div {:class ["empty-state"]} [:p "No sessions yet."]])]]))

(defn- home-view [state dispatch!]
  (let [selected-dir (:web/selected-project-dir state)]
    (cond
      (get-in state [:lobby :personal-agent?])
      (personal-agent-home-view state dispatch!)

      (= selected-dir :all)
      (all-sessions-view state dispatch!)

      (= selected-dir :favorites)
      (favorites-view state dispatch!)

      selected-dir
      (project-sessions-view state dispatch!)

      :else
      (let [dirs       (:web/project-dirs state)
            raw-query  (get-in state [:web/search :home])
            query      (str/lower-case (str/trim (or raw-query "")))
            content?   (boolean (get-in state [:web/content-search :home]))
            matches    (get-in state [:web/content-matches :home])
            content-active? (and content? (seq query))
            matched-sessions (when content-active?
                               (->> (get-in state [:lobby :sessions])
                                    (filter #(contains? (or matches #{}) (:session-id %)))))
            dirs       (if (seq query)
                         (filter #(str/includes? (str/lower-case %) query) dirs)
                         dirs)
            loading?   (:web/projects-loading? state)
            rooms      (get-in state [:lobby :rooms])
            connected? (:web/connected? state)
            ;; Session-ids the user has favorited (from disk sessions in the
            ;; lobby); used to light up the star on live/orphan room cards,
            ;; which are built from :rooms and don't carry :favorite? directly.
            fav-ids    (->> (get-in state [:lobby :sessions])
                            (filter :favorite?)
                            (map :session-id)
                            set)
            ;; Active rooms without a known project
            orphans    (filter (fn [r] (:session-id r)) rooms)]
        [:div {:class ["container"] :replicant/key "home"}
         [:div {:class ["topbar"]}
          (menu-button dispatch!)
          [:div {:class ["topbar-title"]} "Xi"]
          (offline-badge state)
          (when connected?
            (for [item (nav-items-for state :home-topbar)]
              [:button {:class ["icon-btn"]
                        :title (:label item)
                        :replicant/key (str "nav-" (:label item))
                        :on {:click (fn [_] (dispatch! (:event item)))}}
               (icon/icon {:icon-name (:icon item) :size :md})]))
          (when connected?
            [:button {:class ["icon-btn"]
                      :on {:click (fn [_] (dispatch! {:type :room/new}))}}
             (icon/icon {:icon-name :plus :size :md})])
          (overflow-menu dispatch! state)]
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

            content-active?
            [:div
             (search-box dispatch! :home "Search projects…" raw-query content?)
             (if (seq matched-sessions)
               [:div {:class ["project-list"]}
                (for [s (active-first state matched-sessions)]
                  (session-card dispatch! s))]
               [:div {:class ["empty-state"]} [:p "No matching sessions."]])]

            :else
            [:div
             (search-box dispatch! :home "Search projects…" raw-query content?)
             [:div {:class ["project-list"]}
              ;; Active orphan rooms first (hide when filtering)
              (when-not (seq query)
                (for [r orphans]
                  (session-card dispatch! {:session-id (:session-id r)
                                          :name (or (:session-name r) "New session")
                                          :active? true :busy? (:busy? r)
                                          :has-dialog? (:has-dialog? r)
                                          :favorite? (contains? fav-ids (:session-id r))})))
              ;; All sessions link (hide when filtering)
              (when-not (seq query)
                [:div {:class ["project-card"]
                       :replicant/key "all-sessions"
                       :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd :all}))}}
                 [:div {:class ["project-card-icon"]}
                  (icon/icon {:icon-name :message-circle :size :sm})]
                 [:div {:class ["project-card-info"]}
                  [:span {:class ["project-card-name"]} "All sessions"]]
                 [:div {:class ["project-card-chevron"]}
                  (icon/icon {:icon-name :chevron-right :size :sm})]])
              ;; Favorites link (hide when filtering)
              (when-not (seq query)
                [:div {:class ["project-card"]
                       :replicant/key "favorites"
                       :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd :favorites}))}}
                 [:div {:class ["project-card-icon"]}
                  (icon/icon {:icon-name :star :size :sm})]
                 [:div {:class ["project-card-info"]}
                  [:span {:class ["project-card-name"]} "Favorites"]]
                 [:div {:class ["project-card-chevron"]}
                  (icon/icon {:icon-name :chevron-right :size :sm})]])
              ;; Project directories
              (if (and (seq query) (empty? dirs))
                [:div {:class ["empty-state"]} [:p "No matching projects."]]
                (for [d dirs]
                  (project-dir-card dispatch! d)))]])]]))))

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
  (let [open?    (boolean (:web/sidebar-open? state))
        pa?      (get-in state [:lobby :personal-agent?])
        projects (when (and open? (not pa?)) (recent-projects state))
        sessions (when open? (recent-sessions state))
        orphans  (when open? (orphan-rooms state sessions))
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
     ;; Keep the card list out of the DOM while the drawer is closed and
     ;; remount it fresh on each open. The drawer is persistently mounted in
     ;; root-view (outside the route case), so while hidden it keeps receiving
     ;; incremental renders as sessions churn (busy toggling + auto-titling) —
     ;; accumulating stale spinner nodes that mis-reconcile into the doubled/
     ;; multiplied spinners seen on open. The home/listing views never show
     ;; this because they are rebuilt fresh on navigation. Keying the content
     ;; by open? makes Replicant discard the whole stale subtree and build it
     ;; anew the moment the drawer opens.
     (sidebar/sidebar-content
      {:attrs {:style {:padding "env(safe-area-inset-top) 0 0 0"}
               :replicant/key (str "sidebar-content-" open?)}}
      (when open?
        (list
         (when (not pa?)
           (sidebar/sidebar-group {:label "Projects"}
             (for [p projects]
               (project-dir-card dispatch! p))
             [:div {:class ["sidebar-nav-buttons"]
                    :replicant/key "sidebar-nav-buttons"}
              (button/button
               {:variant :secondary :size :sm :icon-left :layout-dashboard
                :class "sidebar-nav-button"
                :on-click (fn [_] (dispatch! {:type :route/navigate :page :home}))}
               "All projects")
              (for [item (nav-items-for state :sidebar)]
                (button/button
                 {:variant :secondary :size :sm :icon-left (:icon item)
                  :class "sidebar-nav-button"
                  :attrs {:replicant/key (str "nav-" (:label item))}
                  :on-click (fn [_] (dispatch! (:event item)))}
                 (:label item)))]))
         (sidebar/sidebar-group {:label "Recent"}
           (if (seq cards)
             (for [c cards]
               (session-card dispatch! c))
             [:div {:class ["sidebar-group-label"]} "No recent sessions"])))))
     (sidebar/sidebar-footer {}
       [:div {:style {:display "flex" :align-items "center" :justify-content "space-between"}}
        (theme-toggle/theme-toggle
         {:mode (or (:web/theme-mode state) "auto")
          :size :sm
          :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))})
        (when standalone?
          [:button {:class ["icon-btn" "icon-btn--sm"]
                    :title "Reload"
                    :on {:click (fn [_] (.reload js/location))}}
           (icon/icon {:icon-name :refresh :size :md})])]))))

(defn- git-status-view
  "Roomless working-tree diff page (reached from the project view's overflow
   menu). Reuses the read-only diff renderer — same +/- line coloring and
   per-file syntax highlighting as the chat :diff buffer, minus the selection /
   explain / modify actions (those need a room)."
  [state dispatch!]
  (let [cwd      (:web/git-status-cwd state)
        text     (:web/git-status-text state)
        loading? (:web/git-status-loading? state)]
    [:div {:class ["container"] :replicant/key "git-status"}
     [:div {:class ["topbar"]}
      (nav-group dispatch! (fn [_]
                             (dispatch! {:type :nav/back
                                         :fallback {:page :home :dir cwd}})))
      [:div {:class ["topbar-title"]} "Git status · " (shorten-path cwd)]
      [:button {:class ["icon-btn"]
                :title "Refresh"
                :on {:click (fn [_] (dispatch! {:type :git-status/refresh}))}}
       (icon/icon {:icon-name :refresh :size :md})]
      (overflow-menu dispatch! state)]
     [:div {:class ["diff-tab"]}
      (cond
        (and loading? (nil? text))
        [:div {:class ["empty-state"]} (spinner) [:p "Loading changes…"]]

        (str/blank? text)
        [:div {:class ["empty-state"]} "No changes."]

        :else
        (diff-rows-view dispatch!
                        (diff/diff-rows (diff/parse-diff-text text))
                        nil nil))]]))

(defn- command-palette
  "Global Cmd/Ctrl+K command palette (ui.command). Mounted once in root-view;
   the ui-runtime.js delegate handles open/filter/keyboard-nav. Items dispatch
   app events on select (the runtime clicks the button, firing :on-click, then
   closes the dialog). The Chats group quick-switches to active/recent rooms;
   room-scoped actions only appear when a room is active."
  [state dispatch!]
  (let [room    (state/active-room state)
        ;; Active rooms first, then most-recently-visited sessions; drop the
        ;; chat we're already looking at. Enriched with live status flags.
        recents (->> (recent-sessions state)
                     (active-first state)
                     (remove :current?)
                     (take 8))
        chat-items (mapv (fn [{:keys [session-id name has-dialog?]}]
                           (cmd/command-item
                            {:icon (if has-dialog? :alert-circle :terminal)
                             :on-click (fn [_] (dispatch! {:type :route/navigate
                                                          :page :chat
                                                          :session-id session-id}))}
                            (or name "New session")))
                         recents)]
    (cmd/command-dialog
     {:id "cmdk" :hotkey "mod+k"
      :placeholder "Type a command or search…"
      :attrs {:replicant/key "cmdk"}}
     (when (seq chat-items)
       (apply cmd/command-group {:heading "Chats"} chat-items))
     (apply cmd/command-group {:heading "Navigate"}
       (cmd/command-item {:icon :layout-dashboard
                          :on-click (fn [_] (dispatch! {:type :route/navigate :page :home}))}
         "All projects")
       (for [item (nav-items-for state :palette)]
         (cmd/command-item {:icon (:icon item)
                            :on-click (fn [_] (dispatch! (:event item)))}
           (:label item))))
     (cmd/command-group {:heading "Actions"}
       (cmd/command-item {:icon :plus
                          :on-click (fn [_] (dispatch! {:type :room/new}))}
         "New chat")
       (when room
         (cmd/command-item {:icon :layers
                            :on-click (fn [_] (dispatch! {:type :models/web-list}))}
           "Change model"))
       (when room
         (cmd/command-item {:icon :zap
                            :on-click (fn [_] (dispatch! {:type :skill/web-list}))}
           "Skills"))
       (when room
         (cmd/command-item {:icon :code
                            :on-click (fn [_] (dispatch! {:type :diff/reopen
                                                          :room-id (:id room)
                                                          :method "git" :engine :git}))}
           "Git status"))
       (when room
         (cmd/command-item {:icon :copy
                            :on-click (fn [_]
                                        (let [text (commands/debug-text room)]
                                          (if ios?
                                            (dispatch! {:type :copy/open :text text})
                                            (copy-to-clipboard! text))))}
           "Copy debug info"))
       (cmd/command-item {:icon :refresh
                          :on-click (fn [_] (.reload js/location))}
         "Reload")))))

(defn- auth-overlay
  "Full-screen block while this browser awaits pairing approval (or was
   denied). The code must match what the approving client sees — that
   comparison is the security, not the device label."
  [state]
  (when-let [{:keys [status code]} (:web/auth state)]
    [:div {:class ["auth-overlay"]}
     [:div {:class ["auth-overlay__card"]}
      (if (= :denied status)
        (list [:h2 "Connection denied"]
              [:p "This device was not approved. Reload to request pairing again."])
        (list [:h2 "Approve this device"]
              [:p "New devices must be approved before they can talk to the server."]
              [:div {:class ["auth-overlay__code"]} (str code)]
              [:p "Approve from an already-connected client, or run:"]
              [:code {:class ["auth-overlay__cmd"]} (str "bb serve:approve " code)]))]]))

(defn- auth-request-banner
  "Pairing requests from unknown devices, shown to authed clients."
  [state dispatch!]
  (when-let [reqs (seq (vals (:web/auth-requests state)))]
    [:div {:class ["auth-requests"]}
     (for [{:keys [code client-name platform]} reqs]
       [:div {:class ["auth-requests__item"] :replicant/key code}
        [:span {:class ["auth-requests__label"]}
         (str (or client-name "Unknown device")
              (when platform (str " · " platform))
              " — code " code)]
        (button/button
         {:variant :primary :size :sm
          :on-click (fn [_] (dispatch! {:type :auth/approve :code code}))}
         "Approve")
        (button/button
         {:variant :ghost :size :sm
          :on-click (fn [_] (dispatch! {:type :auth/deny :code code}))}
         "Deny")])]))

(defn root-view
  "Top-level view, route-driven: the session list at /, a room at /chat/:id.
   Wrapped in a floating sidebar layout so every topbar's hamburger reveals
   the recent-sessions drawer over the content."
  [state dispatch! pages]
  (let [open? (boolean (:web/sidebar-open? state))
        page  (get-in state [:web/route :page])]
    (sidebar/sidebar-layout
     {:class "sidebar-layout--floating"
      :attrs (cond-> {:style {:height "100%"}}
               open? (assoc :data-sidebar-open true))}
     (recent-sidebar state dispatch!)
     (sidebar/sidebar-overlay {:on-click (fn [_] (dispatch! {:type :sidebar/close}))})
     (sidebar/sidebar-layout-main
      {:attrs {:style {:min-height "0"}}}
      (if-let [page-view (get pages page)]
        (page-view state dispatch!)
        (case page
          :chat (chat-view state dispatch!)
          :git-status (git-status-view state dispatch!)
          (home-view state dispatch!))))
     (command-palette state dispatch!)
     (auth-request-banner state dispatch!)
     (auth-overlay state))))