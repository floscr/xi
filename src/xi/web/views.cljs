(ns xi.web.views
  "Replicant view layer — pure (state → hiccup) over the same room state the
   TUI renders. Event handlers dispatch events; there is no view-local atom.

   Phase 7a: the online chat view (topbar, timeline of history entries,
   compose input, abort, permission dialogs). Home view + router land in 7b."
  (:require [clojure.string :as str]
            [xi.commands :as commands]
            [xi.error-info :as error-info]
            [xi.core.state :as state]
            [xi.markdown.hiccup :as md]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.embedded :as embedded]
            [xi.highlight.theme-css :as theme]
            [xi.dialog :as dlg]
            [xi.diff :as diff]
            [xi.fuzzy :as fuzzy]
            [xi.palette :as palette]
            [xi.session.sidebar :as sb :refer [format-relative-time session-status
                                               active-first orphan-rooms
                                               sidebar-session-groups sidebar-session-order]]
            [xi.util :as util]
            [xi.web.appearance :as appearance]
            [xi.web.viewer-group :as viewer-group]
            [ui.icon :as icon]
            [ui.form :as form]
            [ui.button :as button]
            [ui.button-group :as button-group]
            [ui.dialog :as dialog]
            [ui.switch :as switch]
            [ui.empty-state :as empty-state]
            [ui.toolbar :as toolbar]
            [ui.lightbox :as lightbox]
            [ui.sidebar :as sidebar]
            [ui.command :as cmd]
            [ui.context-menu :as context-menu]
            [ui.popover :as popover]
            [xi.clj-result :as clj-result]
            [ui.theme-toggle :as theme-toggle]))

;; ── Standalone (homescreen) detection ────────────────────────────────────────

(def standalone?
  "True when running as an installed PWA / homescreen app (no browser chrome)."
  (or (some-> js/navigator .-standalone)  ;; iOS Safari
      (and (exists? js/window.matchMedia)
           (.-matches (.matchMedia js/window "(display-mode: standalone)")))))

;; ── Block context menus ──────────────────────────────────────────────────────

(defonce ^:private last-pointer-type
  ;; pointerType of the most recent pointerdown. The `click` event's own
  ;; pointerType is unreliable — undefined on Firefox, and not "touch" for taps
  ;; on Safari/iOS — so we read the touch/mouse distinction from the pointerdown
  ;; that precedes every click instead. Set by install-pointer-type-tracker!.
  (atom nil))

(defn install-pointer-type-tracker!
  "Record the pointerType of each pointerdown so click handlers can tell a touch
   tap from a mouse click. Idempotent; call once at init."
  []
  (.addEventListener js/document "pointerdown"
                     (fn [^js e] (reset! last-pointer-type (.-pointerType e)))
                     #js {:capture true :passive true}))

(defn tap-opens-context-menu?
  "True when a click should open a content block's context menu — i.e. the last
   pointer interaction was a touch/pen tap, not a mouse. On a mouse pointer the
   menu opens via right-click (contextmenu) instead, so a plain left-click never
   summons it."
  []
  (not= "mouse" @last-pointer-type))

(defn block-context-menu-on
  "Replicant `:on` handlers that summon a content block's context menu via a
   touch tap or a mouse right-click — never a plain left-click. `open!` is
   called with the triggering DOM event (read `.clientX`/`.clientY` for the
   anchor point)."
  [open!]
  {:click       (fn [^js e]
                  (when (tap-opens-context-menu?)
                    (open! e)))
   :contextmenu (fn [^js e]
                  (.preventDefault e)
                  (open! e))})

;; ── Timeline virtualization ──────────────────────────────────────────────────

(def initial-window-size
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
   throws synchronously, bypassing any .catch — so guard on it explicitly.
   Always returns a promise that resolves once the text has been copied."
  [text]
  (if (and (some? js/navigator.clipboard) js/window.isSecureContext)
    (-> (.writeText js/navigator.clipboard text)
        (.catch (fn [_] (fallback-copy! text))))
    (do (fallback-copy! text)
        (js/Promise.resolve))))

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

(defn- needs-copy-dialog?
  "Whether the manual long-press copy dialog is required instead of a direct
   programmatic copy. Only on iOS over an *insecure* origin (plain HTTP), where
   neither navigator.clipboard (not a secure context) nor execCommand works. Over
   HTTPS the async Clipboard API is available, so iOS copies directly like every
   other platform. Evaluated per-click since isSecureContext is fixed per load."
  []
  (and ios? (not js/window.isSecureContext)))

(defn- copy!
  "Copy text from a UI action. On iOS over insecure HTTP, opens the manual
   long-press dialog (its own confirmation). Otherwise copies programmatically
   and flashes a \"Copied\" toast once the copy lands, so the native copy has
   visible feedback."
  [dispatch! text]
  (if (needs-copy-dialog?)
    (dispatch! {:type :copy/open :text text})
    (-> (copy-to-clipboard! text)
        (.then (fn [_] (dispatch! {:type :copy/flash}))))))

(defn- copy-dialog-overlay
  "iOS manual-copy fallback. Shows the text in a pre-selected, read-only
   textarea so the user can copy it via the native long-press menu, since the
   programmatic Clipboard API is unavailable over plain HTTP on iOS. Serving
   over HTTPS (docs/tls-https.md) avoids it."
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

(defn- copy-toast
  "Transient \"Copied\" confirmation shown after a native (programmatic) copy.
   Rendered only while :web/copy-flash is set; auto-dismissed by :copy/flash."
  []
  [:div {:class ["copy-toast"] :replicant/key "copy-toast"}
   (icon/icon {:icon-name :check :size :sm})
   [:span "Copied"]])

;; ── Spinner ──────────────────────────────────────────────────────────────────

(defn spinner [] [:div {:class ["agent-status-spinner"]}])

(defn reload-with-feedback!
  "A hard page reload (especially the big dev build) takes a beat before the
   browser swaps in the new document, so the old page just sits there looking
   frozen after the user clicks Reload. Paint an immediate full-screen
   'Reloading…' overlay, then reload on the next frame so the browser renders
   the overlay first — the user gets instant feedback that stays up until the
   fresh page paints its own 'Connecting…' spinner. State is about to be
   discarded by the reload, so build the node directly instead of via Replicant."
  []
  (let [overlay (.createElement js/document "div")]
    (set! (.-className overlay) "reload-overlay")
    (set! (.-innerHTML overlay)
          (str "<div class=\"reload-overlay__card\">"
               "<div class=\"reload-overlay__spinner\"></div>"
               "<p>Reloading…</p></div>"))
    (.appendChild js/document.body overlay)
    ;; Double rAF guarantees the overlay is painted before we navigate away.
    (js/requestAnimationFrame
     (fn [] (js/requestAnimationFrame #(.reload js/location))))))

(defn card-status-indicator
  "Session status dot for session cards (badge on the chat icon) and palette
  chat rows (trailing slot). Shows the single most important state:
  working (flashing purple) > unread (orange) > live room (green) > nothing.

  A single, ALWAYS-present node whose class toggles. Do NOT replace this with a
  `cond` returning different elements or nil — those swap element identity /
  drop to nil, and as the keyed card list churns and reorders Replicant
  mis-reconciles the slot: it leaves a stale indicator in the DOM (shown after
  the agent stops) and appends a second one on the next state change (the old
  doubled-spinner bug). One stable node means Replicant only ever patches the
  `class` attribute. Hidden via CSS when it carries no state modifier."
  [{:keys [busy? unread? active?]}]
  [:span {:replicant/key "status-indicator"
          :class ["status-dot"
                  (cond busy?   "status-dot--busy"
                        unread? "status-dot--unread"
                        active? "status-dot--live")]}])

(def default-nav-icon
  "Icon for extension nav items that declare none (or one the icon set lacks),
   so every menu row keeps its label aligned with the built-in items."
  :package)

(defn nav-items-for
  "Extension nav items (from ext/compose :nav-items, stored in state at
   init) scoped to one menu surface. Items whose :icon is missing or not in
   `icon/icon-names` get `default-nav-icon`."
  [state menu]
  (->> (:web/nav-items state)
       (filter #(= menu (:menu %)))
       (map #(update % :icon (fn [k]
                               (if (contains? icon/icon-names k)
                                 k
                                 default-nav-icon))))))

;; ── Tool rendering ───────────────────────────────────────────────────────────

(defn- get-arg [args k]
  (or (get args (name k)) (get args k)))

(defn- relativize-path
  "When `path` is an absolute path inside the repo `cwd`, return it relative to
   the repo root (e.g. \"src/xi/web/views.cljs\"); otherwise return it unchanged."
  [path cwd]
  (if (and (string? path) (string? cwd) (seq cwd))
    (let [prefix (str cwd "/")]
      (cond
        (str/starts-with? path prefix) (subs path (count prefix))
        (= path cwd)                   "."
        :else                          path))
    path))

(defn- tool-summary
  "Short one-line argument summary shown in the tool header. File paths are
   shown relative to the repo `cwd` when they live inside it."
  [tool args cwd]
  (case tool
    ("Bash" "bash") (get-arg args :command)
    ("clj")         (get-arg args :code)
    ("Read" "read")  (relativize-path (or (get-arg args :file_path) (get-arg args :path)) cwd)
    ("Write" "write") (relativize-path (or (get-arg args :file_path) (get-arg args :path)) cwd)
    ("Edit" "edit")  (relativize-path (or (get-arg args :file_path) (get-arg args :path)) cwd)
    ("Grep" "grep")  (get-arg args :pattern)
    ("Glob" "find")  (get-arg args :pattern)
    ("ls")           (relativize-path (get-arg args :path) cwd)
    "git_commit"     (get-arg args :message)
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
               ("read" "write" "edit" "read_source") (get-arg args :path)
               "clj_replace" (get-arg args :file)
               nil)]
    (when path (grammars/get-grammar (file-ext path)))))

(def ^:private code-cache-max 400)

(def ^:private hl-cache
  "grammar-object → (js/Map text→[:code …]). Keyed by grammar identity so the
   inlined browser grammars (stable objects) act as the outer key."
  (js/WeakMap.))

(def ^:private plain-code-cache
  "text → [:code …] for the no-grammar path (Bash results etc.)."
  (js/Map.))

(def ^:private clj-code-cache
  "text → [:code …] for clj tool input (see highlight-clj-code)."
  (js/Map.))

(defn- tokens->code
  "Merged highlight tokens → hiccup [:code …], bare URLs linkified."
  [tokens]
  (into [:code]
        (mapcat (fn [{:keys [type value]}]
                  (if-let [cls (theme/token-class type)]
                    [(into [:span {:class cls}] (md/linkify value))]
                    (md/linkify value))))
        tokens))

(def ^:private md-cache-max 300)

(def ^:private md-cache
  "text → rendered markdown hiccup. Every chat bubble renders its markdown on
   every app render (a keystroke in the compose box re-renders the whole
   timeline), so without this each of the ~60 windowed posts re-parses its
   markdown into fresh hiccup and Replicant can't short-circuit — the cause of
   laggy input on long chats."
  (js/Map.))

(defn- highlight-code
  "Tokenize + class-wrap text against a grammar → hiccup [:code ...].
   Bare URLs inside tokens are linkified so they stay clickable.
   Memoized per (grammar, text): returns the *identical* hiccup object on
   repeat calls so Replicant's `unchanged?` short-circuits via cljs =’s
   identical? fast path and skips re-diffing the span subtree."
  [grammar text]
  (let [inner (or (.get hl-cache grammar)
                  (let [m (js/Map.)] (.set hl-cache grammar m) m))]
    (or (.get inner text)
        (let [result (tokens->code (hl/merge-adjacent (hl/tokenize grammar text)))]
          (when (>= (.-size inner) code-cache-max) (.clear inner))
          (.set inner text result)
          result))))

(defn- highlight-clj-code
  "highlight-code for clj source, whose string literals may embed code —
   `(spit \"x.clj\" \"…\")`, `(sh \"bb\" \"-e\" \"…\")`. Memoized per text."
  [text]
  (or (.get clj-code-cache text)
      (when-let [tokens (embedded/tokenize-clj grammars/get-grammar text)]
        (let [result (tokens->code tokens)]
          (when (>= (.-size clj-code-cache) code-cache-max) (.clear clj-code-cache))
          (.set clj-code-cache text result)
          result))))

(defn- plain-code
  "[:code …] for un-highlighted text, memoized so identical text yields the
   identical hiccup object (same Replicant short-circuit as highlight-code)."
  [text]
  (or (.get plain-code-cache text)
      (let [result (into [:code] (md/linkify text))]
        (when (>= (.-size plain-code-cache) code-cache-max) (.clear plain-code-cache))
        (.set plain-code-cache text result)
        result)))

(defn code-focus-segments
  "Split `text` into [muted? segment] pairs around the [start end) char
   `ranges` a permission ask targets (the dialog's :target): text outside
   every range is muted, text inside is not. Ranges are clamped to the text
   (it may be truncated for display) and taken in order; empty pieces are
   dropped."
  [text ranges]
  (let [n (count text)]
    (loop [pos 0 rs (sort ranges) out []]
      (if-let [[s e] (first rs)]
        (let [s (min (max s pos) n)
              e (min (max e s) n)]
          (recur e (rest rs)
                 (cond-> out
                   (< pos s) (conj [true (subs text pos s)])
                   (< s e)   (conj [false (subs text s e)]))))
        (cond-> out (< pos n) (conj [true (subs text pos n)]))))))

(defn- focused-clj-code
  "Highlighted clj `text` with everything outside the ask's `ranges` muted
   (.code-muted). Each segment is highlighted on its own — the ranges are
   whole forms, so every piece tokenizes like it would in context."
  [text ranges]
  (into [:code]
        (map (fn [[muted? s]]
               (into [:span {:class (if muted? "code-muted" "code-focus")}]
                     (rest (or (highlight-clj-code s) (plain-code s))))))
        (code-focus-segments text ranges)))

(defn- render-md
  "Memoized `md/render`: identical text yields the *identical* hiccup object so
   Replicant's `unchanged?` short-circuits via identical? and skips re-diffing
   the post subtree (same trick as highlight-code / plain-code). Keeps typing
   in the composer from re-parsing every message's markdown on each keystroke."
  [text]
  (if (nil? text)
    (md/render text)
    (or (.get md-cache text)
        (let [result (md/render text)]
          (when (>= (.-size md-cache) md-cache-max) (.clear md-cache))
          (.set md-cache text result)
          result))))

(defn- truncate-lines [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) n)
      text
      (str (str/join "\n" (take n lines))
           "\n… (" (- (count lines) n) " more lines)"))))

(defn- edit-diff-code
  "Render an edit tool's unified-diff result with per-line tinting: + lines get
   a subtle green wash, - lines a subtle red one, over the code box. The
   path/context/gap lines stay neutral. Code is still syntax-highlighted.
   `attrs` are extra attributes for the <pre> (the View-diff data attributes)."
  [grammar text attrs]
  (into [:pre (merge {:class ["tool-call-code" "tool-call-diff"]} attrs)]
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

(defn- result-images
  "Extract image blocks from a tool result, tolerating both the MCP shape
   ({:type \"image\" :data .. :mimeType ..}) and the API shape
   ({:type \"image\" :source {:media_type .. :data ..}}). Returns a seq of
   {:data :media-type} maps."
  [result]
  (when (sequential? result)
    (keep (fn [b]
            (when (and (map? b) (= "image" (:type b)))
              (let [src (:source b)
                    data (or (:data b) (:data src))
                    mime (or (:mimeType b) (:media_type src) (:media-type src))]
                (when (and data mime)
                  {:data data :media-type mime}))))
          result)))

(defn- tool-file-path
  "The file a Read/Write/Edit tool block refers to, for the View-file action."
  [tool args]
  (case tool
    ("Read" "read" "Write" "write" "Edit" "edit")
    (or (get-arg args :file_path) (get-arg args :path))
    "clj_replace" (get-arg args :file)
    nil))

(defn- clj-result-view
  "Render a parsed clj result as separate stdout / value / error zones (the
   'Quiet REPL' block): the returned value sits on its own panel — clj data
   structures get syntax colouring, plain command output stays uncoloured;
   errors get a red band + a line:col chip."
  [text is-error]
  (let [{:keys [stdout error loc] :as parsed} (clj-result/parse-clj-result text is-error)
        g (grammars/get-grammar "clj")]
    (list
     (when stdout
       [:div {:class ["tool-call-content" "clj-result-stdout"]}
        [:pre {:class ["tool-call-code"]}
         [:span {:class ["clj-zone-label"]} "stdout"]
         (plain-code (truncate-lines stdout 100))]])
     (when error
       [:div {:class ["tool-call-content" "clj-result-error"]}
        [:pre {:class ["tool-call-code"]}
         (plain-code (truncate-lines error 100))
         (when loc
           [:span {:class ["clj-result-loc-row"]}
            [:span {:class ["clj-result-loc"]} loc]])]])
     (when-let [{value :text :keys [data?]} (clj-result/value-display parsed)]
       (let [shown (truncate-lines value 100)]
         [:div {:class ["tool-call-content" "clj-result-value"]}
          [:pre {:class ["tool-call-code"]}
           (if (and g data?)
             (highlight-code g shown)
             (plain-code shown))]])))))

(defn- confirm-buttons
  "Answer buttons for a :confirm dialog, driven by its normalized :options
   data (xi.dialog) instead of hardcoded per-option markup. Deny-style
   options render first, plain Allow last, extras in between."
  [dialog answer!]
  (for [{:keys [value label]} (sort-by (fn [{:keys [value]}]
                                         (cond (false? value) 0
                                               (true? value)  2
                                               :else          1))
                                       (dlg/confirm-options dialog))]
    [:button {:class ["confirm-btn" (cond (false? value) "confirm-btn--deny"
                                          (true? value)  "confirm-btn--allow"
                                          :else          "confirm-btn--extra")]
              :on {:click (fn [_] (answer! value))}}
     label]))

(defn- dialog-diff
  "A confirm dialog's :diff preview ({:path :text} — the change a guarded
   write/edit is about to make), rendered like the edit tool's result diff."
  [{:keys [diff]}]
  (when-let [{:keys [path text]} diff]
    (edit-diff-code (grammars/get-grammar (file-ext path))
                    (truncate-lines text 100)
                    {:data-diff-path path :data-diff-text text})))

(defn format-elapsed
  "Whole seconds as `12s` / `3m 05s`."
  [secs]
  (let [mins (quot secs 60)]
    (if (>= mins 1)
      (str mins "m " (let [s (mod secs 60)] (if (< s 10) (str "0" s) s)) "s")
      (str secs "s"))))

(def ^:private run-timer-min-secs
  "A running tool shows its timer only once it has run this long, so quick
   calls don't flash a counter."
  2)

(defn- run-timer-text [started-at]
  (let [secs (quot (max 0 (- (js/Date.now) started-at)) 1000)]
    (if (>= secs run-timer-min-secs) (format-elapsed secs) "")))

(defn- run-timer
  "Live elapsed-time label for a still-running tool call (poll-until, long
   builds, stalled commands). The text is repainted straight into the DOM node
   once a second — the tool block itself only re-renders on events, which
   stop arriving exactly when a call stalls."
  [started-at]
  (when started-at
    [:span {:class ["tool-call-timer"]
            :replicant/on-mount
            (fn [{:replicant/keys [^js node]}]
              (aset node "__xiTimer"
                    (js/setInterval #(set! (.-textContent node) (run-timer-text started-at))
                                    1000)))
            :replicant/on-unmount
            (fn [{:replicant/keys [^js node]}]
              (js/clearInterval (aget node "__xiTimer")))}
     (run-timer-text started-at)]))

(defn- tool-post
  "A tool call's <details> block. `:grouped?` — it sits inside a viewer-mode
   group (header row styling); `:collapsed?` — it starts closed (the
   :tool-blocks appearance setting). Both are stamped by chat-view."
  [dispatch! {:keys [tool arguments result is-error status started-at
                     permission resolved-permission
                     grouped? collapsed? cwd]}]
  (let [name      (util/strip-mcp-prefix tool)
        summary   (tool-summary name arguments cwd)
        running?  (= :running status)
        text      (util/extract-text-content result)
        grammar   (when (and text (not is-error)) (tool-grammar name arguments))
        bash?     (contains? #{"Bash" "bash"} name)
        clj?      (= "clj" name)
        clj-code  (when clj? (not-empty (str (get-arg arguments :code))))
        clj-preview (when clj-code (first (str/split-lines clj-code)))
        imgs      (seq (result-images result))
        diff-result? (and (contains? #{"Edit" "edit" "clj_replace"} name)
                          (not is-error))
        label     (if clj?
                    name
                    (str name (when (seq summary)
                                (str " " (if bash?
                                           (str summary)
                                           (first (str/split-lines (str summary))))))))]
    [:div {:class ["post" "post--tool"]}
     [:details {:class (cond-> ["tool-call-block"]
                         clj? (conj "tool-call-block--clj")
                         grouped? (conj "tool-call-block--viewer"))
                :open (not collapsed?)}
      [:summary {:class (cond-> ["tool-call-toggle"] bash? (conj "tool-call-toggle--wrap"))}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class (cond-> ["tool-call-toggle-label"] bash? (conj "tool-call-toggle-label--wrap"))}
        [:span {:class ["tool-call-action"]} name]
        (subs label (count name))]
       (when clj-preview
         [:span {:class ["clj-head-preview"]} clj-preview])
       ;; Pinned right (collapsed or open) so a long preview can't push it away.
       (when running?
         [:span {:class ["tool-call-running"]}
          (run-timer started-at)
          (spinner)])
       ;; Right-side status badge: a filled circle with a white icon that
       ;; captures the outcome — red ✗ on error, purple ✓ when the user
       ;; confirmed the tool, grey ✗ when it was denied. Stays visible even
       ;; when the block is collapsed into a viewer group.
       (cond
         is-error
         [:span {:class ["tool-call-status" "tool-call-status--error"] :title "Error"}
          (icon/icon {:icon-name :x :size :sm})]
         (some? resolved-permission)
         (let [{:keys [value label]} resolved-permission
               deny? (not value)]
           [:span {:class ["tool-call-status"
                           (if deny? "tool-call-status--deny" "tool-call-status--allow")]
                   :title (or label (if deny? "Denied" "Allowed"))}
            (icon/icon {:icon-name (if deny? :x :check) :size :sm})]))]
      (when clj-code
        ;; A pending ask that targets part of this code (the (spit …) /
        ;; (sh …) call it is about) mutes the rest so the eye lands there.
        (let [shown  (truncate-lines clj-code 100)
              target (get-in permission [:dialog :target])
              ranges (when (= :code (:arg target)) (seq (:ranges target)))]
          [:div {:class ["tool-call-content" "tool-call-input"]}
           [:pre {:class ["tool-call-code"]}
            (if ranges
              (focused-clj-code shown ranges)
              (or (highlight-clj-code shown) (plain-code shown)))]]))
      (cond
        ;; When the result carries an image (view_image, screenshots) the text is
        ;; just a "Viewed image: /path" caption — drop it and show only the image.
        (and clj? (seq text))
        (clj-result-view text is-error)

        (and (seq text) (not imgs))
        [:div (cond-> {:class ["tool-call-content"]}
                (tool-file-path name arguments)
                (assoc :data-file-path (tool-file-path name arguments))
                ;; A block that renders a file change carries its raw diff so
                ;; the context menu can open exactly that change (View diff).
                (and diff-result? (tool-file-path name arguments))
                (assoc :data-diff-path (tool-file-path name arguments)
                       :data-diff-text text))
         (let [shown (truncate-lines text 100)]
           (if diff-result?
             (edit-diff-code grammar shown nil)
             [:pre {:class ["tool-call-code"]}
              (if grammar (highlight-code grammar shown) (plain-code shown))]))])
      ;; A permission gate fired for this (still-running) tool call: render the
      ;; ask as a zone inside the same grey box, joined to the code above.
      (when-let [{:keys [dialog answer!]} permission]
        (let [{:keys [message text]} dialog]
          [:div {:class ["tool-call-content"]}
           (dialog-diff dialog)
           [:div {:class ["tool-call-permission"]}
            [:div {:class ["tool-call-permission-msg"]} (or message text)]
            [:div {:class ["tool-call-permission-actions"]}
             (confirm-buttons dialog answer!)]]]))
      (when imgs
        [:div {:class ["tool-call-content" "user-images"]}
         (map-indexed
          (fn [i {:keys [data media-type]}]
            (let [src (str "data:" media-type ";base64," data)]
              [:img {:replicant/key i
                     :class ["user-image" "lightbox-thumb"]
                     :src src
                     :alt "viewed image"
                     :on {:click (fn [^js e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :lightbox/open :src src}))}}]))
          imgs)])]]))

;; ── Error card ───────────────────────────────────────────────────────────────

(defn- reset-clock-time
  "Local wall-clock time of epoch-seconds `secs`, e.g. \"3:00 PM\"."
  [secs]
  (.toLocaleTimeString (js/Date. (* 1000 secs)) js/undefined
                       #js {:hour "numeric" :minute "2-digit"}))

(def ^:private error-card-icons
  {:rate-limit :clock
   :auth       :lock
   :billing    :alert-triangle
   :overloaded :zap
   :network    :circle-x})

(defn- error-card
  "Plain-language card for a recognised agent error (`xi.error-info`): what
   happened, when it is back, the usage windows, and the raw error behind a
   details toggle."
  [{:keys [kind title subtitle resets-at windows raw]}]
  (let [mins (error-info/minutes-until resets-at (.now js/Date))]
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["error-card" (str "error-card--" (name kind))]}
      [:div {:class ["error-card-head"]}
       [:div {:class ["error-card-icon"]}
        (icon/icon {:icon-name (get error-card-icons kind :alert-triangle)})]
       [:div {:class ["error-card-text"]}
        [:div {:class ["error-card-title"]} title]
        [:div {:class ["error-card-sub"]}
         (if resets-at
           (str subtitle " until " (reset-clock-time resets-at)
                (when mins (str " · back in " (error-info/format-minutes mins))))
           subtitle)]]]
      (when (seq windows)
        [:div {:class ["error-card-windows"]}
         [:div {:class ["error-card-meter"]}
          [:div {:class ["error-card-meter-fill"]
                 :style {:width (str (min 100 (:pct (first windows))) "%")}}]]
         [:div {:class ["error-card-meter-labels"]}
          (for [{:keys [label pct]} windows]
            [:span {:replicant/key label} label " " [:b (str pct "%")]])]])
      [:details {:class ["error-card-details"]}
       [:summary {:class ["error-card-details-toggle"]}
        [:span {:class ["tool-call-toggle-icon"]}
         (icon/icon {:icon-name :chevron-right :size :sm})]
        "Technical details"]
       [:pre {:class ["error-card-raw"]} raw]]]]))

;; ── History entry → post ─────────────────────────────────────────────────────

(defn- entry->post [dispatch! entry]
  (case (:kind entry)
    :user
    (let [idx (:history-index entry)]
      (cond
        (:editing? entry)
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
        ;; Skill/command-generated prompt (e.g. a loaded skill body or the
        ;; /commit prompt): collapsed to a one-line user bubble that expands
        ;; on press, so the long generated text doesn't dominate the timeline.
        (:collapsed-label entry)
        [:div {:class ["post" "post--user" "post--user-collapsed"]}
         [:details {:class ["post-body" "user-collapse"]}
          [:summary {:class ["user-collapse-summary"]}
           [:span {:class ["tool-call-toggle-icon"]}
            (icon/icon {:icon-name :chevron-right :size :sm})]
           [:span {:class ["user-collapse-label"]} (:collapsed-label entry)]]
          (when (seq (:text entry))
            [:div {:class ["post-content" "user-collapse-content"]}
             (render-md (:text entry))])]]

        :else
        [:div (cond-> {:class ["post" "post--user" (when idx "post--tappable")]}
                idx (assoc :data-history-index idx)
                idx (assoc :on (block-context-menu-on
                                (fn [^js e]
                                  (dispatch! {:type :bubble/menu-open
                                              :index idx
                                              :text (:text entry)
                                              :images (:images entry)
                                              :x (.-clientX e)
                                              :y (.-clientY e)})))))
         [:div {:class ["post-body"]}
          (if-let [imgs (seq (:images entry))]
            [:div {:class ["user-images"]}
             (map-indexed
              (fn [i {:keys [data media-type name]}]
                (if-not (str/starts-with? (or media-type "") "image/")
                  [:div {:replicant/key i :class ["user-attachment-chip"]}
                   (icon/icon {:icon-name :file-text :size :md})
                   [:span {:class ["user-attachment-name"]} (or name "file")]]
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
            [:div {:class ["post-content"]} (render-md (:text entry))])]]))

    :text
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-body"]}
      (render-md (:text entry))]]

    :thinking
    [:div {:class ["post" "post--assistant" "post--thinking"]}
     [:details {:class (cond-> ["thinking-block"]
                         (:grouped? entry) (conj "thinking-block--viewer"))
                :open (not (:collapsed? entry))}
      [:summary {:class ["thinking-toggle"]}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class ["tool-call-toggle-label"]}
        [:span {:class ["tool-call-action"]} "Thinking"]]]
      (into [:pre {:class ["thinking-text"]}] (md/linkify (:text entry)))]]

    :tool-call
    (tool-post dispatch! entry)

    :status
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} (:text entry)]]

    :error
    (let [msg (or (:message (:error entry)) (pr-str (:error entry)))]
      (when-not (str/includes? (str msg) "null is not an object")
        (if-let [info (error-info/describe (:error entry))]
          (error-card info)
          [:div {:class ["post" "post--assistant"]}
           [:div {:class ["post-content" "error-text"]} (str "[Error] " msg)]])))

    :aborted
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} "Interrupted."]]

    nil))

(defn- with-post-key
  "Attach a stable :replicant/key so Replicant reconciles timeline posts by
   identity, not position. Without it, dropping an entry from the middle of the
   sequence (e.g. hidden :thinking blocks) or sliding the render window crashes
   the DOM diff with `removeChild ... not a Node`. Every entry->post return is a
   `[:div {attrs} …]` vector; guard the rare non-map-attrs shape anyway."
  [k node]
  (cond
    (not (vector? node))  node
    (map? (second node))  (assoc-in node [1 :replicant/key] k)
    :else                 (into [(first node) {:replicant/key k}] (rest node))))

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

(defn- read-raw-file
  "Read any File as base64, preserving its real mime type and filename; promise
   of {:data base64 :media-type mime :name filename} or nil on failure. Used for
   every non-image attachment (PDF, zip, text, …) — the bytes are stored
   verbatim and reach the agent by on-disk path."
  [^js file]
  (js/Promise.
   (fn [resolve _]
     (let [reader (js/FileReader.)]
       (set! (.-onload reader)
             (fn [_]
               (let [[_ mime _ b64] (re-matches #"^data:([^;,]*)(;base64)?,(.*)$"
                                                (.-result reader))]
                 (resolve {:data b64
                           :media-type (or (not-empty (.-type file))
                                           (not-empty mime)
                                           "application/octet-stream")
                           :name (.-name file)}))))
       (set! (.-onerror reader) (fn [_] (resolve nil)))
       (.readAsDataURL reader file)))))

(defn- add-files!
  "Stage a seq of Files as compose attachments of the chat `draft-key`: images
   are downscaled; every other file type (PDF, zip, text, …) is read as-is and
   referenced by path server-side."
  [dispatch! draft-key files]
  (when (seq files)
    (-> (js/Promise.all
         (to-array
          (map (fn [^js f]
                 (if (str/starts-with? (or (.-type f) "") "image/")
                   (resize-image-file f)
                   (read-raw-file f)))
               files)))
        (.then (fn [results]
                 (when-let [valid (seq (remove nil? (array-seq results)))]
                   (dispatch! {:type :compose/add-images :draft-key draft-key
                               :images (vec valid)}))))
        (.catch (fn [err] (js/console.error "[xi-web] attachment read failed:" err))))))

(defn- handle-compose-paste! [dispatch! draft-key ^js e]
  (let [items (.. e -clipboardData -items)
        files (->> (range (.-length items))
                   (keep (fn [i]
                           (let [^js item (aget items i)]
                             (when (str/starts-with? (.-type item) "image/")
                               (.getAsFile item))))))]
    (if (seq files)
      (do (.preventDefault e)
          (add-files! dispatch! draft-key files))
      ;; Long / code-like text pastes get wrapped in a bare ``` fence at the
      ;; cursor, with newlines added so the fences sit on their own lines.
      (let [text (some-> (.-clipboardData e) (.getData "text"))]
        (when (and (seq text) (util/paste-should-fence? text))
          (.preventDefault e)
          (let [^js ta (.-target e)
                value (.-value ta)
                start (.-selectionStart ta)
                end (.-selectionEnd ta)
                before (subs value 0 start)
                after (subs value end)
                insertion (util/fence-paste
                           text
                           {:at-line-start? (or (empty? before)
                                                (str/ends-with? before "\n"))
                            :at-line-end? (or (empty? after)
                                              (str/starts-with? after "\n"))})
                new-value (str before insertion after)
                caret (+ (count before) (count insertion))]
            (set! (.-value ta) new-value)
            (.setSelectionRange ta caret caret)
            (dispatch! {:type :compose/set-draft
                        :draft-key draft-key
                        :text new-value})))))))

(defn- sync-compose-draft!
  "The composer textarea carries its value as child text, which only sets the
   *default* value: once the user has typed, the DOM value is dirty and a
   re-render with another chat's draft no longer shows. The element is reused
   across chats, so on a chat switch (tracked in a data attribute — never on
   plain typing, which would race the async render) write the new chat's draft
   into the DOM explicitly."
  [^js node draft-key draft]
  (let [k (str draft-key)]
    (when-not (= k (.getAttribute node "data-draft-key"))
      (.setAttribute node "data-draft-key" k)
      (set! (.-value node) (or draft "")))))

(defn- compose-image-strip [dispatch! draft-key images]
  (when (seq images)
    [:div {:class ["compose-images"]}
     (map-indexed
      (fn [idx {:keys [data media-type name]}]
        (if-not (str/starts-with? (or media-type "") "image/")
          [:div {:replicant/key idx :class ["compose-attachment-chip"]}
           (icon/icon {:icon-name :file-text :size :md})
           [:span {:class ["compose-attachment-name"]} (or name "file")]
           [:button {:class ["compose-attachment-remove"]
                     :on {:click (fn [_] (dispatch! {:type :compose/remove-image
                                                     :draft-key draft-key :idx idx}))}}
            (icon/icon {:icon-name :x :size :sm})]]
          [:div {:replicant/key idx :class ["compose-image-thumb"]}
           (let [src (str "data:" media-type ";base64," data)]
             [:img {:src src :alt "attachment" :class ["lightbox-thumb"]
                    :on {:click (fn [_] (dispatch! {:type :lightbox/open :src src}))}}])
           [:button {:class ["compose-image-remove"]
                     :on {:click (fn [_] (dispatch! {:type :compose/remove-image
                                                     :draft-key draft-key :idx idx}))}}
            (icon/icon {:icon-name :x :size :sm})]]))
      images)]))

;; ── Command suggestions ───────────────────────────────────────────────────────

(def ^:private web-commands
  "Commands shown in the web suggestion popup and palette Commands section —
   the shared curated list (see xi.palette/palette-commands) plus a few
   web-only entries. `/commits` opens the session-commits bar (a client-side
   palette sub-page, not a server command), so it lives here rather than in
   the shared list the TUI reads."
  (conj (vec palette/palette-commands)
        {:name "commits"
         :description "List commits made this session"
         :while-busy? true}
        {:name "files"
         :description "Browse project files"
         :while-busy? true}
        {:name "skills"
         :description "Browse and load skills"
         :while-busy? true}))

(def ^:private web-command-names
  (into #{} (map :name) web-commands))

(defn draft-key
  "Per-chat identity key for state that should be scoped to a single chat
   (drafts, scroll position, …). The virtual room's id while composing a brand
   new chat, else the room's session id once joined, else the route's session
   id, else `:new`.

   The virtual-room id must win over the active room: opening a new chat leaves
   the client attached to the *previous* room (it only leaves on the first
   prompt, see xi.web.core/submit-pending), so `active-room` still points at the
   old chat. Keying off it would file the new chat's draft under the old chat's
   id, leaking it into that chat the next time it's shown."
  [state]
  (or (get-in state [:web/pending-room :id])
      (get-in (state/active-room state) [:session :id])
      (get-in state [:web/route :session-id])
      :new))

(defn known-command?
  "True if `name` is a recognized web slash command. Used to decide whether a
   submission is worth recording into the recently-executed list."
  [name]
  (contains? web-command-names name))

(def ^:private while-busy-command-names
  (into #{} (comp (filter :while-busy?) (mapcat #(cons (:name %) (:aliases %)))) web-commands))

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

(defn- match-commands
  "Filter commands (and their subcommands) by prefix query (text after the /).
   An exact name/alias hit sorts first, so Enter on \"/d\" runs /deny rather
   than whichever d-command happens to be listed earlier."
  [query]
  (let [q      (str/lower-case (or query ""))
        exact? (fn [{:keys [name aliases]}] (boolean (or (= name q) (some #{q} aliases))))]
    (->> (palette/expand-commands web-commands)
         (filter #(or (str/starts-with? (:name %) q) (exact? %)))
         (sort-by (complement exact?))
         vec)))

(defn dispatch-command!
  "Fire a slash command, decoupled from the compose draft. Accepts
   \"/diff staged\", \"diff staged\", or \"diff\".

   Backend commands go through :web/command (not :command/run directly): that
   handler delivers them immediately when a room is joined and the socket is
   up, else stashes them as a pending spinner bubble tied to THIS session and
   fires them on (re)join — so an offline command can never be forwarded blind
   into the wrong room."
  [dispatch! room-id slash]
  (let [{:keys [name args]}
        (commands/parse-input (if (str/starts-with? slash "/") slash (str "/" slash)))]
    ;; Web-only commands (commits/files/skills, and /model without args) open a
    ;; palette sub-page directly instead of flowing through :web/command, so the
    ;; :web/command recorder tap never sees them. Record them here so they still
    ;; land in the palette's Recent list like backend commands do.
    (when (or (#{"commits" "files" "skills"} name)
              (and (= name "model") (not args)))
      (dispatch! {:type :web/record-command :name name}))
    (case name
      ;; Web-only: open the session-commits palette bar instead of running a
      ;; (non-existent) server command.
      "commits" (dispatch! {:type :palette/open-commits})
      ;; Web-only: open the file browser palette page.
      "files"   (dispatch! {:type :palette/open-files})
      ;; Web-only: /skills opens the skills palette page (the TUI's picker
      ;; menu the server command would push doesn't render on web).
      "skills"  (dispatch! {:type :palette/open-skills})
      ;; /model without args opens the in-palette model picker (the TUI menu
      ;; the server command would push doesn't render on web); with args it
      ;; runs the backend command, which sets the model directly.
      "model"   (if args
                  (dispatch! {:type :web/command :room-id room-id
                              :name name :args args})
                  (dispatch! {:type :palette/open-models}))
      (dispatch! (cond-> {:type :web/command :room-id room-id :name name}
                   args (assoc :args args))))))

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
                                (dispatch-command! dispatch! room-id name)
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
        (dispatch! {:type :compose/clear-images :draft-key draft-key}))
      (if room-id
        (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                     (seq images) (assoc :images (vec images))))
        ;; No active room yet (cached session view) — stash the message and
        ;; let the pending-submit tap fire it once :room/joined arrives.
        (dispatch! (cond-> {:type :submit/pending :session-id session-id :text text}
                     (seq images) (assoc :images (vec images))))))))

(defn- prompt-nav-controls
  "Jump-to-previous-prompt control shown left of the Projects button. Collapsed
   it is a single up-arrow; when the timeline is scrolled up off the bottom a
   down-arrow that snaps back to the newest content appears beside it. Once
   opened it expands into a button group showing the current position / total
   and up (older) / down (newer) navigation — on the newest prompt the down
   arrow scrolls to the bottom instead of being a no-op.
   `nav-ctx` carries the full-history user-prompt indices so navigation reaches
   prompts that aren't in the rendered timeline window yet."
  [dispatch! nav-idx nav-ctx scrolled-up?]
  (let [total   (:count nav-ctx)
        prev!   (fn [_] (dispatch! (assoc nav-ctx :type :prompt-nav/prev)))
        bottom! (fn [_] (dispatch! {:type :timeline/scroll-to-bottom}))
        up-btn  [:button {:class ["quick-cmd" "prompt-nav-btn"]
                          :title (if nav-idx "Previous prompt" "Jump to previous prompt")
                          :disabled (boolean (and nav-idx (<= nav-idx 0)))
                          :on {:click prev!}}
                 (icon/icon {:icon-name :arrow-up :size :sm})]]
    (if (nil? nav-idx)
      (if scrolled-up?
        [:div {:class ["prompt-nav-group"]}
         up-btn
         [:button {:class ["quick-cmd" "prompt-nav-btn"]
                   :title "Scroll to bottom"
                   :on {:click bottom!}}
          (icon/icon {:icon-name :arrow-down :size :sm})]]
        up-btn)
      (let [last? (>= nav-idx (dec total))
            next! (fn [_]
                    (if last?
                      (bottom! nil)
                      (dispatch! (assoc nav-ctx :type :prompt-nav/next))))]
        [:div {:class ["prompt-nav-group"]}
         up-btn
         [:button {:class ["quick-cmd" "prompt-nav-count"]
                   :title "Close prompt navigation"
                   :on {:click (fn [_] (dispatch! {:type :prompt-nav/close}))}}
          (str (inc nav-idx) "/" total)]
         [:button {:class ["quick-cmd" "prompt-nav-btn"]
                   :title (if last? "Scroll to bottom" "Next prompt")
                   :on {:click next!}}
          (icon/icon {:icon-name :arrow-down :size :sm})]]))))

(defn- queue-popover
  "Floating list of prompts queued while the agent is busy, anchored above the
   queue-count button that toggles it. Each entry can be removed before the
   current turn ends and the queue is sent as one combined prompt."
  [dispatch! room-id queued]
  [:div {:class ["queue-popover"]}
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

(defn- floating-actions
  "Action row rendered inside .compose-frame. On mobile it floats over the
   timeline just above the composer as frosted icon-only pill groups; on
   desktop it lays out as a flat labeled bar at the top of the composer card
   (see the ≥769px CSS — .qc-label spans are hidden on mobile). Left:
   prompt-nav, Projects, Snippets and a Commands button that opens the palette
   commands page. Right: while messages are queued, a button showing just the
   count; tapping it toggles the queue popover (rendered by compose-box)."
  [dispatch! room-id pa? prompt-nav nav-ctx scrolled-up? queued]
  (let [qcount (count queued)]
    (when (or (not pa?) (pos? qcount))
      [:div {:class ["float-actions"]}
     (when-not pa?
       [:div {:class ["fgroup"]}
        (when (pos? (or (:count nav-ctx) 0))
          (prompt-nav-controls dispatch! prompt-nav nav-ctx scrolled-up?))
        [:button {:class ["quick-cmd"] :title "Projects" :aria-label "Projects"
                  :on {:click (fn [_] (dispatch! {:type :palette/open-projects}))}}
         (icon/icon {:icon-name :folder :size :sm})
         [:span {:class ["qc-label"]} "Projects"]]
        [:button {:class ["quick-cmd"] :title "Snippets" :aria-label "Snippets"
                  :on {:click (fn [_] (dispatch! {:type :palette/open-snippets}))}}
         (icon/icon {:icon-name :file-text :size :sm})
         [:span {:class ["qc-label"]} "Snippets"]]
        [:button {:class ["quick-cmd"] :title "Commands" :aria-label "Commands"
                  :on {:click (fn [_] (dispatch! {:type :palette/open-commands}))}}
         (icon/icon {:icon-name :terminal :size :sm})
         [:span {:class ["qc-label"]} "Commands"]]])
     (when (pos? qcount)
       [:div {:class ["queue-float"]}
        [:div {:class ["fgroup"]}
         [:button {:class ["quick-cmd" "queue-num"]
                   :title "Queued messages" :aria-label "Queued messages"
                   :on {:click (fn [_] (dispatch! {:type :queue/toggle-popover}))}}
          (str qcount)]]])])))

(defn- offline-indicator
  "Non-interactive wifi-off glyph shown to the left of the send button while the
   client is disconnected. The framework icon set has no wifi/offline glyph, so
   this is an inline SVG styled to match .icon."
  []
  [:span {:class ["compose-offline-indicator"] :title "Offline"}
   [:svg {:class ["icon"] :xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
          :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"
          :aria-hidden "true"}
    [:path {:d "M12 20h.01"}]
    [:path {:d "M8.5 16.429a5 5 0 0 1 7 0"}]
    [:path {:d "M5 12.859a10 10 0 0 1 5.17-2.69"}]
    [:path {:d "M19 12.859a10 10 0 0 0-2.007-1.523"}]
    [:path {:d "M2 8.82a15 15 0 0 1 4.177-2.643"}]
    [:path {:d "M22 8.82a15 15 0 0 0-11.288-3.764"}]
    [:path {:d "m2 2 20 20"}]]])

(declare skill-form-compose)

(defn- compose-box [dispatch! room busy? images draft-key draft session-id cmd-selected pa? queue-open? prompt-nav nav-ctx scrolled-up? offline?]
  (let [room-id  (:id room)
        cmd-query (when (and (not pa?) (string? draft) (str/starts-with? draft "/"))
                    (subs draft 1))
        cmd-matches (when (some? cmd-query) (match-commands cmd-query))
        cmd-open?   (seq cmd-matches)
        has-input?  (seq (str/trim (or draft "")))
        queued      (get-in room [:agent :queued])
        new?        (nil? session-id)]
    [:div {:class ["compose-box"]}
     ;; The popover anchors on .compose-box, NOT inside .compose-frame: on
     ;; desktop the frame's backdrop-filter makes it the containing block for
     ;; absolutely positioned descendants, so a popover inside it would be
     ;; clipped away by the frame's overflow:hidden.
     (when (and queue-open? (seq queued) (not cmd-open?))
       (queue-popover dispatch! room-id queued))
     (compose-image-strip dispatch! draft-key images)
     [:div {:class ["compose-frame"]}
      (when-not cmd-open?
        (floating-actions dispatch! room-id pa? prompt-nav nav-ctx scrolled-up?
                          queued))
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
               :multiple true
               :style {:display "none"}
               :on {:change (fn [^js e]
                              (add-files! dispatch! draft-key (array-seq (.. e -target -files)))
                              (set! (.. e -target -value) ""))}}]
      [:div {:class ["compose-input-wrapper"]}
       (form/form-textarea-auto
        {:placeholder (if busy? "Working…" "Message…")
         :value (or draft "")
         :max-rows 6
         :attrs {:replicant/on-render
                 (fn [{:replicant/keys [^js node]}]
                   (sync-compose-draft! node draft-key draft))
                 :replicant/on-mount
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
                      :paste (fn [^js e] (handle-compose-paste! dispatch! draft-key e))
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
                              (dispatch-command! dispatch! room-id cmd-name)
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
                                    (dispatch-command! dispatch! room-id cmd-name)
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
                          (cond
                            ;; Shift+Enter inserts a newline, like the TUI. We
                            ;; insert it manually and preventDefault so the
                            ;; beforeinput "insertLineBreak" handler above does
                            ;; not fire and submit the message instead (without
                            ;; the preventDefault the default line-break would
                            ;; proceed and trigger that handler).
                            (and (= "Enter" (.-key e)) (.-shiftKey e))
                            (let [^js el (.-target e)
                                  start  (.-selectionStart el)
                                  end    (.-selectionEnd el)
                                  v      (.-value el)
                                  nv     (str (subs v 0 start) "\n" (subs v end))]
                              (.preventDefault e)
                              (set! (.-value el) nv)
                              (set! (.-selectionStart el) (inc start))
                              (set! (.-selectionEnd el) (inc start))
                              (dispatch! {:type :compose/set-draft
                                          :draft-key draft-key :text nv}))

                            (= "Enter" (.-key e))
                            (do (.preventDefault e)
                                (let [v (.. e -target -value)]
                                  (when (or (not busy?) (submittable-while-busy? v))
                                    (submit-compose! dispatch! room-id session-id
                                                     images draft-key v)))))))}}})]
      (let [spinner-abort
            ;; The busy spinner doubles as an abort button, sitting right next
            ;; to the circle-x inside .compose-actions (4px gap — no stray
            ;; whitespace between them).
            (when busy?
              [:button {:class ["icon-btn" "compose-spinner-abort"]
                        :aria-label "Abort"
                        :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
               (spinner)])]
        (if (and busy? (not (command-while-busy? draft)))
          ;; Busy: queue-send button (when there's something to queue) next to
          ;; the abort button. Queued-message access lives in the floating
          ;; queue-count group above the composer.
          [:div {:class ["compose-actions"]}
           (when offline? (offline-indicator))
           spinner-abort
           (when (or has-input? (seq images))
             [:button {:class ["icon-btn"]
                       :on {:click (fn [_] (submit-compose! dispatch! room-id session-id
                                                            images draft-key draft))}}
              (icon/icon {:icon-name :arrow-up :size :md})])
           [:button {:class ["icon-btn"]
                     :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
            (icon/icon {:icon-name :circle-x :size :md})]]
          [:div {:class ["compose-actions"]}
           (when offline? (offline-indicator))
           spinner-abort
           [:button {:class ["icon-btn"]
                     :on {:click (fn [_] (submit-compose! dispatch! room-id session-id
                                                          images draft-key draft))}}
            (icon/icon {:icon-name :arrow-up :size :md})]]))]]]))

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

(defn- form-dialog-body
  "One textarea per :form dialog field (see xi.dialog/form-fields). Transient
   values live in app state under :web/dialog-form keyed by field name;
   Submit answers with the values map, Cancel answers nil."
  [dispatch! state dialog answer!]
  (let [values  (:web/dialog-form state)
        fields  (dlg/form-fields dialog)
        ;; Effective value: the transient form value once the user has touched
        ;; the field, otherwise the field's prefilled :value.
        eff     (fn [{:keys [name value]}]
                  (if (contains? values name) (str (get values name)) (str value)))
        done!   (fn [value]
                  (dispatch! {:type :web/dialog-form-reset})
                  (answer! value))]
    [:div {:class ["dialog-form"]}
     (for [{:keys [name label] :as field} fields]
       [:div {:replicant/key name :class ["dialog-form-field"]}
        [:div {:class ["dialog-form-label"]} label]
        (form/form-textarea-auto
         {:value     (eff field)
          :max-rows  6
          :attrs     {:value (eff field)}
          :on-change (fn [^js e]
                       (dispatch! {:type :web/dialog-form-set
                                   :patch {name (.. e -target -value)}}))})])
     [:div {:class ["confirm-actions"]}
      (button/button
       {:variant :ghost :size :sm
        :on-click (fn [_] (done! nil))}
       "Cancel")
      (button/button
       {:variant :primary :size :sm
        :on-click (fn [_]
                    (done! (into {}
                                 (map (fn [{:keys [name] :as field}]
                                        [name (eff field)]))
                                 fields)))}
       "Submit")]]))

(defn dialog-decision-label
  "Human label for the choice the user made on a now-resolved dialog."
  [type options value]
  (case type
    :confirm    (dlg/resolved-label {:options options} value)
    :select     (or (some #(when (= (:value %) value) (:label %)) options)
                    (str value))
    :alert      "Dismissed"
    :cwd-select (str value)
    :form       (if value "Submitted" "Cancelled")
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
  [dispatch! state room history suppress-id]
  (when-let [{:keys [id type message text options] :as live-dialog}
             (first (remove #(= suppress-id (:id %)) (get-in room [:ui :dialogs])))]
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
        (dialog-diff live-dialog)
        (case type
          :cwd-select (cwd-select-body dispatch! state options answer!)
          :form       (form-dialog-body dispatch! state live-dialog answer!)
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
             (confirm-buttons live-dialog answer!))])]])))

;; ── Diff view ────────────────────────────────────────────────────────────────

(defn- diff-file-grammar
  "Highlight grammar for a filename, or nil."
  [filename]
  (when filename
    (when-let [ext (file-ext filename)]
      (grammars/get-grammar ext))))

(defn- diff-line-view
  "Render a single selectable diff line with syntax highlighting. Tapping a
   code line dispatches :diff/select-line with its selection index. `highlight?`
   adds an emphasis class (agent-spotlighted lines); `reviewed?` marks lines the
   user has already added to a review (both used by the review canvas)."
  ([dispatch! grammar selected? line sel-idx row-key]
   (diff-line-view dispatch! grammar selected? line sel-idx row-key false false))
  ([dispatch! grammar selected? line sel-idx row-key highlight?]
   (diff-line-view dispatch! grammar selected? line sel-idx row-key highlight? false))
  ([dispatch! grammar selected? {:keys [type text old-line new-line]} sel-idx row-key highlight? reviewed?]
  (let [cls (cond-> ["diff-line"]
              (= type :add)     (conj "diff-line--add")
              (= type :delete)  (conj "diff-line--del")
              (= type :meta)    (conj "diff-line--meta")
              sel-idx           (conj "diff-line--selectable")
              reviewed?         (conj "diff-line--reviewed")
              highlight?        (conj "diff-line--highlight")
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
        (or text ""))]])))

(defn diff-rows-view
  "Render flattened diff rows as a scrollable view with selection highlight.
   Rows are grouped by file: each file gets a sticky header and a horizontally
   scrollable body so long lines don't push the whole view. The selection
   toolbar, when present, is anchored to the bottom of the selected range.
   Optional opts: :highlight — a set of :sel-idx to spotlight (review canvas);
   :reviewed — a set of :sel-idx marked as already-reviewed (review canvas);
   :line-suffix — (fn [sel-idx]) → hiccup|nil, rendered right after that line
   (review canvas inline comment threads);
   :collapsed — a set of filenames whose bodies are collapsed. When this opt is
   present (even as an empty set) file headers become clickable and dispatch
   :diff/toggle-file to fold/unfold their body."
  ([dispatch! rows range toolbar] (diff-rows-view dispatch! rows range toolbar nil))
  ([dispatch! rows range toolbar {:keys [highlight reviewed line-suffix collapsed]}]
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
                body-rows (rest group)
                collapse?  (some? collapsed)
                folded?    (boolean (and collapsed (contains? collapsed filename)))]
            [:div {:class ["diff-file"
                           (when folded? "diff-file--collapsed")]
                   :replicant/key filename}
             [:div (cond-> {:class ["diff-file-header"
                                    (when collapse? "diff-file-header--clickable")]}
                     collapse?
                     (assoc :on {:click (fn [_] (dispatch! {:type :diff/toggle-file
                                                            :filename filename}))}))
              (when collapse?
                [:span {:class ["diff-file-caret"]}
                 (icon/icon {:icon-name (if folded? :chevron-right :chevron-down)
                             :size :sm})])
              [:span {:class ["diff-file-name"]} filename]
              (when status-label
                [:span {:class ["diff-file-status"
                                (str "diff-file-status--" (name status))]}
                 status-label])]
             (when-not folded?
               [:div {:class ["diff-file-body"]}
              (map-indexed
               (fn [ri {:keys [row header line sel-idx] :as r}]
                 (let [k (str fi "-" ri)]
                   (case row
                     :hunk
                     [:div {:class ["diff-hunk-header"] :replicant/key (str "h" k)} header]
                     :line
                     (let [selected? (boolean (and range sel-idx
                                                   (<= (first range) sel-idx (second range))))
                           hl?       (boolean (and highlight sel-idx (highlight sel-idx)))
                           rev?      (boolean (and reviewed sel-idx (reviewed sel-idx)))
                           suffix    (when line-suffix (line-suffix sel-idx))]
                       (if suffix
                         (list (diff-line-view dispatch! (grammar-for (:filename r))
                                               selected? line sel-idx (str "l" k) hl? rev?)
                               suffix)
                         (diff-line-view dispatch! (grammar-for (:filename r))
                                         selected? line sel-idx (str "l" k) hl? rev?)))
                     nil)))
               body-rows)])]))
        file-groups)
       (empty-state/empty-state {} "No changes."))
     toolbar])))

(defn position-sel-toolbar!
  "Anchor the selection toolbar to the bottom edge of the last selected diff
   line, within the scrollable diff view. Runs on mount and on every update so
   the toolbar follows the range as the selection changes. Public so the
   canvas-review extension can reuse the same anchored selection popover."
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

(defn position-modify-panel!
  "Keep the modify panel pinned above the keyboard instead of anchored to the
   diff range, so focusing the textarea doesn't cause the weird iOS scroll.
   Repositions on mount/update and tracks the VisualViewport so it follows the
   keyboard as it animates open/closed and as the page scrolls; the listeners
   are torn down on unmount. Public so the canvas-review extension can reuse
   the same keyboard-pinned compose panel."
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
   {:value "session-git"     :label "Session uncommitted" :title "Session Changes (since last commit)"}
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

(defn- diff-method-bar
  "Selects above the diff to switch the source and the renderer. Each fires
   :diff/reopen, which re-runs /diff on the server (measuring the difftastic
   column width from the browser viewport first, so it fills the page).

   A session can hold arbitrarily many commits, so single-commit diffs are not
   enumerated in the source list. Instead the commit currently in view appears
   as a dynamic option, and a trailing \"Pick a commit…\" entry opens the
   commits command bar to choose another. The engine toggle reuses the active
   method, so flipping git/difftastic keeps the same commit in view."
  [dispatch! room-id diff-buffer engine collapse-opts]
  (let [commit  (:commit diff-buffer)
        engine  (or engine :git)
        method  (if commit
                  (str "commit:" (:sha commit))
                  (diff-method-for-title (:title diff-buffer)))
        options (cond-> diff-methods
                  commit  (conj {:value (str "commit:" (:sha commit))
                                 :label (str "Commit " (:short commit))})
                  :always (conj {:value "__pick-commit__" :label "Pick a commit…"}))
        {:keys [filenames all-collapsed?]} collapse-opts]
    [:div {:class ["diff-method-bar"]}
     (form/form-select
      {:options   options
       :value     method
       :on-change (fn [v]
                    (if (= v "__pick-commit__")
                      (dispatch! {:type :palette/open-commits})
                      (dispatch! {:type :diff/reopen :room-id room-id
                                  :method v :engine engine})))})
     (form/form-select
      {:options   diff-engines
       :value     (name engine)
       :on-change (fn [v]
                    (dispatch! {:type :diff/reopen :room-id room-id
                                :method method :engine (keyword v)}))})
     (when (seq filenames)
       (let [label (if all-collapsed? "Expand all files" "Collapse all files")]
         (button/button
          {:variant :ghost :size :sm
           :class "diff-collapse-all"
           :icon (if all-collapsed? :chevron-right :chevron-down)
           :attrs {:aria-label label :title label}
           :on-click (fn [_] (dispatch! {:type :diff/toggle-all
                                         :filenames filenames}))})))]))

(defn- commit-info-header
  "Message + metadata for a single-commit diff, shown above the diff body. The
   raw `git show` preamble is stripped by the unified-diff parser, so this
   renders the subject, short sha, author, date and optional body from the
   structured commit metadata carried on the buffer."
  [{:keys [short subject body author date rel-time]}]
  [:div {:class ["diff-commit-info"]}
   [:div {:class ["diff-commit-subject"]} subject]
   [:div {:class ["diff-commit-meta"]}
    (->> [[:span {:class ["diff-commit-sha"]} short]
          (when author author)
          (or date rel-time)]
         (remove nil?)
         (interpose " · "))]
   (when body
     [:pre {:class ["diff-commit-body"]} body])])

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
  [dispatch! room-id diff-buffer sel modify? collapsed]
  (let [engine (or (:engine diff-buffer) :git)
        git?   (not= engine :difft)
        rows   (when git? (diff/diff-rows (diff/parse-diff-text (:text diff-buffer))))
        filenames (when git?
                    (into [] (comp (filter #(= :file (:row %))) (map :filename)) rows))
        collapsed (or collapsed #{})]
    [:div {:class ["diff-tab"]}
     (diff-method-bar dispatch! room-id diff-buffer engine
                      (when (seq filenames)
                        {:filenames filenames
                         :all-collapsed? (every? collapsed filenames)}))
     (when-let [c (:commit diff-buffer)]
       (commit-info-header c))
     (if (= engine :difft)
       (into [:pre {:class ["diff-difft"]}] (difft-spans (:text diff-buffer)))
       (let [range (diff/selection-range sel)]
         (diff-rows-view dispatch! rows range
                         (when range
                           (diff-action-bar dispatch! room-id rows range modify?))
                         {:collapsed collapsed})))]))

(def ^:private markdown-exts
  "Extensions rendered as formatted markdown (HTML markup) instead of
   syntax-highlighted source in the file viewer."
  #{"md" "markdown" "mdown" "markdn" "mkd" "mdx"})

(defn- file-tab-view
  "Read-only file viewer rendered as the active tab. Markdown files render as
   formatted HTML; other files are syntax-highlighted from their extension when
   a grammar is available, else shown as plain text."
  [{:keys [path text]}]
  (let [ext     (file-ext path)
        grammar (grammars/get-grammar ext)]
    [:div {:class ["file-tab"]}
     [:div {:class ["file-tab-header"]}
      [:span {:class ["file-tab-path"]} path]]
     (if (contains? markdown-exts ext)
       [:div {:class ["file-tab-md"]}
        [:div {:class ["post-content"]} (md/render text)]]
       [:pre {:class ["file-tab-code"]}
        (if grammar (highlight-code grammar text) text)])]))

(defn- prompt-part-preview
  "First ~200 chars of a part's text, with a single trailing ellipsis when it
   was clipped — the collapsed overview line for a system-prompt part."
  [text]
  (let [t (str/triml (or text ""))]
    (if (<= (count t) 200) t (str (str/trimr (subs t 0 200)) "…"))))

(defn- prompt-tab-view
  "System-prompt buffer rendered as the active tab (the web equivalent of the
   TUI's /prompt buffer). Each contributing part is a collapsible card: collapsed
   shows a one-line preview, clicking the header expands it to the full,
   markdown-rendered text. The toolbar toggles every part at once. `expanded` is
   the `:web/prompt-expanded` set of expanded part indices."
  [dispatch! room expanded]
  (let [parts   (get-in room [:agent :system-parts])
        claude? (= :anthropic (get-in room [:agent :provider]))
        n       (count parts)
        all?    (and (pos? n) (= expanded (set (range n))))]
    [:div {:class ["file-tab" "prompt-tab"]}
     [:div {:class ["file-tab-header"]}
      [:span {:class ["file-tab-path"]} "System Prompt"]
      (when (pos? n)
        [:button {:class ["tab-pill-item"]
                  :on {:click (fn [_] (dispatch! {:type :prompt/toggle-all :n n}))}}
         (if all? "Collapse all" "Expand all")])]
     [:div {:class ["prompt-tab-body"]}
      (when claude?
        [:div {:class ["prompt-note"]}
         "The Claude Code preset is injected by the Agent SDK ahead of everything "
         "below and is not shown here; the parts below are appended after it."])
      (if (seq parts)
        (map-indexed
         (fn [idx {:keys [source text repo?]}]
           (let [open? (contains? expanded idx)
                 lines (inc (count (re-seq #"\n" (or text ""))))]
             [:div {:class ["prompt-part" (when open? "prompt-part--open")
                            (when repo? "prompt-part--repo")]
                    :replicant/key (str "pp-" idx)}
              [:button {:class ["prompt-part-header"]
                        :on {:click (fn [_] (dispatch! {:type :prompt/part-toggle
                                                        :idx idx}))}}
               [:span {:class ["prompt-part-caret"]}
                (icon/icon {:icon-name (if open? :chevron-down :chevron-right)
                            :size :sm})]
               [:span {:class ["prompt-part-source"]} (str source)]
               (when repo?
                 [:span {:class ["prompt-part-badge"]} "repo"])
               [:span {:class ["prompt-part-lines"]} (str lines " lines")]]
              (if open?
                [:div {:class ["prompt-part-body" "post-content"]} (render-md text)]
                [:div {:class ["prompt-part-preview"]} (prompt-part-preview text)])]))
         parts)
        [:div {:class ["prompt-note"]} "(no system prompt)"])]]))

;; ── Tab bar ──────────────────────────────────────────────────────────────────

(defn- tab-bar
  "Segmented pill for switching between chat and buffer views. Lives inline in
   the topbar next to the overflow menu; only rendered when a buffer exists.
   `canvas?` adds a Canvas pill that navigates to the room's canvas-review
   page (a separate route, not a buffer switch) — shown once a review has
   been built for this session."
  [dispatch! room-id active-buffer buffers canvas?]
  (let [;; A virtual new chat has no room id yet; its buffers live on the
        ;; pending room (see :pending/buffer-switch).
        switch! (fn [buffer-id]
                  (dispatch! (if room-id
                               {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id}
                               {:type :pending/buffer-switch :buffer-id buffer-id})))]
  [:div {:class ["tab-pill"]}
   [:button {:class ["tab-pill-item" (when (= active-buffer :chat) "tab-pill-item--active")]
             :on {:click (fn [_] (switch! :chat))}}
    "Chat"]
   (when (:diff buffers)
     [:button {:class ["tab-pill-item" (when (= active-buffer :diff) "tab-pill-item--active")]
               :on {:click (fn [_] (switch! :diff))}}
      "Diff"])
   (when (:file buffers)
     [:button {:class ["tab-pill-item" (when (= active-buffer :file) "tab-pill-item--active")]
               :on {:click (fn [_] (switch! :file))}}
      "File"])
   (when (:prompt buffers)
     [:button {:class ["tab-pill-item" (when (= active-buffer :prompt) "tab-pill-item--active")]
               :on {:click (fn [_] (switch! :prompt))}}
      "Prompt"])
   (when canvas?
     [:button {:class ["tab-pill-item"]
               :on {:click (fn [_] (dispatch! {:type :canvas-review/open-page
                                               :room-id room-id}))}}
      "Canvas"])]))

;; ── Chat view ────────────────────────────────────────────────────────────────

(defn- offline-badge [state]
  (when (false? (:web/connected? state))
    [:span {:class ["offline-label"]} "Offline"]))

(defn shorten-path
  "~/Code/Projects/xi → xi, ~/Code/Work/Hyma/studio → studio"
  [path]
  (when path
    (let [parts (str/split path #"/")]
      (last parts))))


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

(defn- message-circle-icon
  "Inline Lucide `message-circle` (chat bubble) SVG at the icon component's :sm
   size — the shared icon set has no chat icon (icon/icon returns nil for it)."
  []
  [:svg {:class ["icon" "icon-sm"]
         :xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "2"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"}]])

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
   (let [open?     (:web/overflow-menu? state)
         room      (state/active-room state)
         ext-items (filter #(or (nil? (:mode %)) (= (:mode %) (:mode git-ctx)))
                           (nav-items-for state :overflow))]
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
         (when room
           [:button {:class ["overflow-menu-item"]
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :overflow/close})
                                   (dispatch! {:type :palette/open-models}))}}
            (icon/icon {:icon-name :layers :size :sm})
            [:span "Change model"]])
         (when room
           [:button {:class ["overflow-menu-item"]
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :overflow/close})
                                   (dispatch! {:type :palette/open-skills}))}}
            (icon/icon {:icon-name :zap :size :sm})
            [:span "Skills"]])
         [:button {:class ["overflow-menu-item"]
                   :on {:click (fn [e]
                                 (.stopPropagation e)
                                 (dispatch! {:type :overflow/close})
                                 (dispatch! {:type :appearance/open}))}}
          (icon/icon {:icon-name :settings :size :sm})
          [:span "Appearance"]]
         ;; Extension nav items get their own section: :mode-less items always
         ;; show; :mode-scoped ones only in the matching git-ctx mode, with the
         ;; ctx keys merged into their event (so e.g. a :project item receives
         ;; the :cwd).
         (when (seq ext-items)
           (list
            [:div {:class ["overflow-menu-divider"]
                   :replicant/key "overflow-ext-divider"}]
            (for [item ext-items]
              [:button {:class ["overflow-menu-item"]
                        :replicant/key (str "nav-" (:label item))
                        :on {:click (fn [e]
                                      (.stopPropagation e)
                                      (dispatch! {:type :overflow/close})
                                      (dispatch! (merge (:event item)
                                                        (select-keys git-ctx [:cwd :room-id :number]))))}}
               (icon/icon {:icon-name (:icon item) :size :sm})
               [:span (:label item)]])))
         [:div {:class ["overflow-menu-divider"]}]
         [:button {:class ["overflow-menu-item"]
                   :on {:click (fn [e]
                                 (.stopPropagation e)
                                 (dispatch! {:type :overflow/close})
                                 (copy! dispatch! (commands/debug-text room)))}}
          (icon/icon {:icon-name :copy :size :sm})
          [:span "Copy debug info"]]
         [:button {:class ["overflow-menu-item"]
                   :on {:click (fn [e]
                                 (.stopPropagation e)
                                 (dispatch! {:type :overflow/close})
                                 (reload-with-feedback!))}}
          (icon/icon {:icon-name :refresh :size :sm})
          [:span "Reload"]]]))])))

(defn- optimistic-post
  "An optimistic user bubble rendered at the tail of the timeline the instant a
   prompt is sent, before the server echoes the real :user entry back (instant
   feedback on slow/mobile links). Suppressed once the matching entry lands in
   history so it never duplicates the authoritative message."
  [dispatch! state room sid history]
  (when-let [{:keys [room-id session-id text images]} (:web/optimistic state)]
    (let [;; room-id is the strong identity: a stashed prompt fired after the
          ;; user switched chats carries the room it was AIMED at (:room-id),
          ;; while :session-id may have drifted to the now-viewed room's
          ;; session (optimistic-tap stamps the route's sid). Matching those
          ;; independently would leak the bubble into the room the user
          ;; switched to. So when a room-id is known it must match; only fall
          ;; back to session-id when the room hasn't joined yet (room-id nil).
          for-this? (cond
                      room-id    (= room-id (:id room))
                      session-id (and sid (= session-id sid))
                      ;; A virtual new chat whose room hasn't joined yet: no
                      ;; session on either side and no room adopted. Show the
                      ;; bubble instantly while :room/join round-trips.
                      :else      (and (nil? sid) (nil? (:id room))))
          last-user (->> history (filter #(= :user (:kind %))) last)
          confirmed? (and last-user
                          (= (not-empty (some-> text str/trim))
                             (not-empty (some-> (:text last-user) str/trim)))
                          (= (count images) (count (:images last-user))))]
      (when (and for-this? (not confirmed?))
        (entry->post dispatch! {:kind :user :text text :images images})))))

(defn- pending-command-post
  "Spinner bubble for a backend slash command queued while offline / before the
   room joined (see the :web/command handler). Renders the command text with a
   spinner so the user sees it is pending for THIS session; pending-command-tap
   fires it on (re)join and clears it. Matched to the viewed room the same way
   optimistic-post is (strong room-id identity, session-id fallback, then the
   not-yet-joined virtual chat)."
  [state room sid]
  (when-let [{:keys [room-id session-id name args]} (:web/pending-command state)]
    (let [for-this? (cond
                      room-id    (= room-id (:id room))
                      session-id (and sid (= session-id sid))
                      :else      (and (nil? sid) (nil? (:id room))))]
      (when for-this?
        [:div {:class ["post" "post--user" "post--pending-command"]}
         [:div {:class ["post-body"]}
          [:div {:class ["post-content"]}
           [:span {:class ["pending-command-text"]}
            (str "/" name (when args (str " " args)))]
           (spinner)]]]))))

(defn- chat-back-route
  "Where the chat-view back arrow should land: the listing the session belongs
   to, regardless of how the chat was reached (drill-down or a sidebar jump).
   Personal-agent mode has no projects → the root home. Otherwise the project's
   session listing (/projects/:cwd) when the cwd is known, falling back to the
   projects root."
  [state]
  (if (get-in state [:lobby :agent-id])
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

(defn- launch-logo
  "The Xi mark: a blocky X and i sharing one gradient."
  []
  [:svg {:class ["launch-logo"] :viewBox "0 0 64 40" :aria-hidden "true"}
   [:defs
    [:linearGradient {:id "launch-logo-gradient" :gradientUnits "userSpaceOnUse"
                      :x1 "0" :y1 "0" :x2 "64" :y2 "40"}
     [:stop {:offset "0" :stop-color "#e83fb4"}]
     [:stop {:offset "0.55" :stop-color "#8b4cf0"}]
     [:stop {:offset "1" :stop-color "#3b8fd6"}]]]
   [:g {:fill "url(#launch-logo-gradient)"}
    ;; X: two crossing diagonals
    [:polygon {:points "0,0 9,0 36,40 27,40"}]
    [:polygon {:points "27,0 36,0 9,40 0,40"}]
    ;; i: dot + stem
    [:rect {:x "46" :y "0" :width "9" :height "9"}]
    [:rect {:x "46" :y "15" :width "9" :height "25"}]]])

(defn- launch-header
  "Welcome card at the top of every chat's timeline: Xi logo, the
   current model / cwd / AGENTS.md files and a footer hint. It is ordinary
   timeline content, so it scrolls away with the conversation and can be
   scrolled back to. Works for a virtual (not-yet-joined) room too: the
   caller fills model (server default from :lobby/state) and agents-files
   (fetched via :cwd/agents-files) from the pending room, so the card matches
   the real room's once the first prompt creates it."
  [{:keys [model cwd agents-files pa? dispatch!]}]
  [:div {:class ["launch-header"]}
   [:div {:class ["launch-hero"]}
    (launch-logo)
    [:div {:class ["launch-tagline"]}
     (if pa? "personal agent" "coding agent")]]
   (when (or model cwd (seq agents-files))
     [:div {:class ["launch-section"]}
      [:div {:class ["launch-section-title"]} "Session"]
      [:dl {:class ["launch-facts"]}
       (when model
         (list
          [:dt {:replicant/key "model-k"} "Model"]
          [:dd {:replicant/key "model-v"}
           [:span {:class ["launch-value" "launch-value--clickable"]
                   :on {:click (fn [_] (dispatch! {:type :palette/open-models}))}}
            model]]))
       (when cwd
         (list
          [:dt {:replicant/key "cwd-k"} "cwd"]
          [:dd {:replicant/key "cwd-v"}
           [:span {:class ["launch-value"] :title cwd} cwd]]))
       (when-let [files (seq agents-files)]
         (list
          [:dt {:replicant/key "agents-k"} "Loaded"]
          [:dd {:replicant/key "agents-v"}
           [:span {:class ["launch-value"]}
            (str (count files) " AGENTS.md file" (when (> (count files) 1) "s"))]]))]])
   [:div {:class ["launch-footer"]}
    "Type /help for the full list of commands."]])

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
   /tree edit) and resubmits the edited text. Retry forks the same way and
   resubmits the message unchanged. Delete forks the same way but discards the
   message. Edit and Retry both carry the message's image attachments so the
   fork resends them with the text. Copy uses the iOS long-press fallback when
   the async Clipboard API is unavailable."
  [dispatch! room-id {:keys [index text images x y]}]
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
                                          :index index :text (or text "")
                                          :images images}))}}
       (icon/icon {:icon-name :edit :size :sm})
       [:span "Edit"]]
      [:button {:class ["bubble-menu-item"]
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (close!)
                              (copy! dispatch! text))}}
       (icon/icon {:icon-name :copy :size :sm})
       [:span "Copy"]]
      [:button {:class ["bubble-menu-item"]
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (close!)
                              (dispatch! {:type :bubble/retry
                                          :index index :text (or text "")
                                          :images images}))}}
       (icon/icon {:icon-name :refresh :size :sm})
       [:span "Retry"]]
      [:button {:class ["bubble-menu-item" "bubble-menu-item--danger"]
                :on {:click (fn [e]
                              (.stopPropagation e)
                              (close!)
                              (dispatch! {:type :tree/navigate
                                          :room-id room-id :index index}))}}
       (icon/icon {:icon-name :trash :size :sm})
       [:span "Delete"]]]]))

(defn- code-copy-menu
  "Floating menu shown when a rendered code block (`pre`) or inline `code` is
   tapped: Copy, plus View file when the block belongs to a Read/Write/Edit
   tool call (opens the file in the room's :file buffer tab via :file/open),
   and View diff when the block is a file change (an edit result or a
   permission-dialog preview): opens exactly that block's diff in the :diff tab,
   converted by xi.diff/tool-diff->unified — no git, no server round-trip.
   Mirrors bubble-menu's positioning/backdrop; the tap is detected by a
   delegated listener in xi.web.core. Uses the iOS long-press fallback when
   the async Clipboard API is unavailable."
  [dispatch! room-id {:keys [text path diff-path diff-text x y]}]
  (let [close! (fn [] (dispatch! {:type :code/menu-close}))]
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
                              (copy! dispatch! text))}}
       (icon/icon {:icon-name :copy :size :sm})
       [:span "Copy"]]
      (when path
        [:button {:class ["bubble-menu-item"]
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (close!)
                                (dispatch! {:type :file/open :path path}))}}
         (icon/icon {:icon-name :file-text :size :sm})
         [:span "View file"]])
      (when-let [unified (some->> diff-text (diff/tool-diff->unified diff-path))]
        [:button {:class ["bubble-menu-item"]
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (close!)
                                (dispatch! {:type :ui/diff-open :room-id room-id
                                            :title (str "Diff: " (last (str/split diff-path #"/")))
                                            :text unified :engine :git}))}}
         (icon/icon {:icon-name :code :size :sm})
         [:span "View diff"]])]]))

(defn- subagent-duration [{:keys [started ended]}]
  (when started
    (format-elapsed (quot (- (or ended (.now js/Date)) started) 1000))))

(def ^:private subagent-status-label
  {:running "running" :done "done" :error "error" :stopped "stopped"})

;; Children default COLLAPSED (opt-in :expanded?): the panel renders one head
;; per agent (status · label · duration) during the run, and only streams a
;; child's full history once the user expands it. Expanding-by-default made the
;; panel re-render every entry of every sub-agent on every streaming delta,
;; which stalled the web client with several concurrent agents (PR reviews).
(defn- subagent-child [dispatch! room-id {:keys [id label task status history result expanded? session-id] :as child}]
  (let [open? (boolean expanded?)]
    [:div {:class ["subagent-card" (str "subagent-card--" (name (or status :running)))]
           :replicant/key id}
     [:div {:class ["subagent-card-head"]
            :on {:click (fn [_] (dispatch! {:type :subagent/toggle-child
                                            :room-id room-id :sub-id id}))}}
      [:span {:class ["subagent-card-chevron" (when open? "is-open")]}
       (icon/icon {:icon-name :chevron-right :size :sm})]
      [:span {:class ["subagent-status" (str "subagent-status--" (name (or status :running)))]}
       (when (= :running status) (spinner))
       (get subagent-status-label status (name (or status :running)))]
      [:span {:class ["subagent-card-label"]} (or label task "sub-agent")]
      (when-let [d (subagent-duration child)]
        [:span {:class ["subagent-card-dur"]} d])
      (when-not (= :running status)
        [:button {:class ["subagent-open"]
                  :title "Open as chat"
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (if session-id
                                  (dispatch! {:type :route/navigate :page :chat
                                              :session-id session-id})
                                  (dispatch! {:type :subagent/promote
                                              :room-id room-id :sub-id id})))}}
         (icon/icon {:icon-name :message-circle :size :sm})])
      (when-not (= :running status)
        [:button {:class ["subagent-dismiss"]
                  :title "Dismiss"
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (dispatch! {:type :subagent/dismiss
                                            :room-id room-id :sub-id id}))}}
         (icon/icon {:icon-name :x :size :sm})])
      (when (= :running status)
        [:button {:class ["subagent-stop"]
                  :title "Stop sub-agent"
                  :on {:click (fn [e]
                                (.stopPropagation e)
                                (dispatch! {:type :subagent/abort
                                            :room-id room-id :sub-id id}))}}
         (icon/icon {:icon-name :circle-x :size :sm})])]
     (when open?
       [:div {:class ["subagent-card-body"]}
        (when (seq task)
          [:div {:class ["subagent-task"]} task])
        (if (seq history)
          (map-indexed
           (fn [i e] (when-let [post (entry->post dispatch! e)]
                       [:div {:replicant/key i :class ["subagent-entry"]} post]))
           history)
          [:div {:class ["subagent-empty"]} "No output yet."])])]))

(defn- subagents-panel [dispatch! room]
  (let [room-id (:id room)
        {:keys [agents collapsed?]} (get-in room [:ext :subagents])]
    (when (seq agents)
      (let [running (count (filter #(= :running (:status %)) agents))
            open?   (not collapsed?)]
        [:div {:class ["subagents-panel"]}
         [:div {:class ["subagents-head"]
                :on {:click (fn [_] (dispatch! {:type :subagent/toggle-collapse
                                                :room-id room-id}))}}
          [:span {:class ["subagents-chevron" (when open? "is-open")]}
           (icon/icon {:icon-name :chevron-right :size :sm})]
          [:span {:class ["subagents-title"]} "Sub-agents"]
          [:span {:class ["subagents-count"]} (count agents)]
          (when (pos? running)
            [:span {:class ["subagents-running"]} (spinner) (str running " running")])
          (when (< running (count agents))
            [:button {:class ["subagents-clear"]
                      :title "Dismiss finished sub-agents"
                      :on {:click (fn [e]
                                    (.stopPropagation e)
                                    (dispatch! {:type :subagent/dismiss
                                                :room-id room-id}))}}
             (icon/icon {:icon-name :x :size :sm})])]
         (when open?
           [:div {:class ["subagents-list"]}
            (map (fn [c] (subagent-child dispatch! room-id c)) agents)])]))))

(defn- quick-replies-row
  "One-tap reply chips detected for the last assistant response
   (xi.quick-replies). Additive UI — the response text is unchanged. Tapping a
   chip sends its predefined message as a normal prompt (same path as the
   composer). Hidden while a turn is in flight."
  [dispatch! room busy?]
  (let [chips (get-in room [:quick-replies :chips])]
    (when (and (seq chips) (not busy?))
      [:div {:class ["quick-replies"]}
       (map-indexed
        (fn [i {:keys [label send]}]
          [:div {:replicant/key (str "qr-" i) :class ["quick-reply"]}
           (button/button
            {:variant :ghost :size :sm :class "quick-reply-btn"
             :on-click (fn [_] (dispatch! {:type :input/submit
                                           :room-id (:id room)
                                           :text send}))}
            label)])
        chips)])))

(defn- block-info
  "Describe a tool / thinking history `entry` for the super-collapsed summary
   row (see xi.web.viewer-group). Nil for other kinds."
  [entry cwd]
  (case (:kind entry)
    :tool-call (let [name (util/strip-mcp-prefix (:tool entry))]
                 {:kind     :tool-call
                  :name     name
                  :detail   (some-> (tool-summary name (:arguments entry) cwd)
                                    str str/split-lines first)
                  :running? (= :running (:status entry))
                  :error?   (boolean (:is-error entry))})
    :thinking  {:kind :thinking :name "Thinking"}
    nil))

(defn- super-group
  "One summary row standing in for a run of collapsed viewer rows: step count,
   the newest block (name + argument), a spinner while anything is running and
   an error badge if a tool failed. A <details>, so clicking it reveals the
   usual header rows in place; Replicant only writes changed attrs, so the
   user's open/closed choice survives re-renders as the run grows."
  [run-key run]
  (let [{n :count :keys [error-count running? latest]}
        (viewer-group/summarize (map :info run))]
    [:details {:class ["viewer-tool-group" "viewer-tool-group--super"]
               :replicant/key (str "vg-" run-key)}
     [:summary {:class ["viewer-group-summary"]}
      [:span {:class ["tool-call-toggle-icon"]}
       (icon/icon {:icon-name :chevron-right :size :sm})]
      [:span {:class ["viewer-group-count"]} (viewer-group/count-label n)]
      (when latest
        [:span {:class ["viewer-group-latest"]}
         [:span {:class ["tool-call-action"]} (:name latest)]
         (when (seq (:detail latest))
           (str " " (:detail latest)))])
      (when running?
        [:span {:class ["tool-call-running"]} (spinner)])
      (when (pos? error-count)
        [:span {:class ["tool-call-status" "tool-call-status--error"]
                :title (str error-count (if (= 1 error-count) " error" " errors"))}
         (icon/icon {:icon-name :x :size :sm})])]
     [:div {:class ["viewer-group-rows"]} (map :node run)]]))

(defn- group-viewer-items
  "Viewer mode: collapse runs of consecutive collapsible tool and thinking
   posts into a single grouped container that shows just their headers.
   `items` is an ordered seq of {:key :node :group? :collapsed? :info}. Runs of
   :group? true fold into one `.viewer-tool-group`; everything else (text,
   dialogs, non-collapsible tools) passes through unchanged, breaking the run.
   With `super?`, a run whose blocks are all :collapsed? folds further into one
   `super-group` summary row."
  [super? items]
  (mapcat
   (fn [run]
     (if (:group? (first run))
       (let [run-key (:key (first run))]
         [(if (and super? (viewer-group/super-collapsible? run))
            (super-group run-key run)
            [:div {:class ["viewer-tool-group"]
                   :replicant/key (str "vg-" run-key)}
             (map :node run)])])
       (map :node run)))
   (partition-by :group? items)))

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
        ;; Prefer the server's history once it lands, but an empty room
        ;; history ([] — truthy) must NOT shadow the cache. On the resume
        ;; path the room is installed with an empty history for a beat before
        ;; :session/resumed streams the messages in; `(or [] cached)` would
        ;; return [] and blank the timeline, flashing a spinner between the
        ;; cached paint and the newest chat. Fall through to the cache until
        ;; the authoritative history actually arrives.
        room-history   (:history room)
        authoritative? (boolean (seq room-history))
        history (if authoritative? room-history (:history cached))
        busy?   (get-in room [:agent :busy?])
        new?    (nil? sid)
        ;; A virtual new chat has no room yet: fall back to the server's
        ;; default model (rides on :lobby/state) like the room it becomes.
        model   (or (get-in room [:agent :model]) (:model cached)
                    (get-in state [:web/pending-room :model])
                    (when new? (get-in state [:lobby :model])))
        ;; The server's history for an existing session is still in flight
        ;; (nothing optimistic/pending to show in the meantime).
        resuming? (and (not new?)
                       (not authoritative?)
                       (not (:web/optimistic state))
                       (not (:web/pending-submit state))
                       (not (:web/pending-command state)))
        ;; Only fall back to a blocking spinner when there's no cache to
        ;; paint. With a cache we render it immediately and show a subtle
        ;; "updating" hint in the topbar instead of a spinner flash.
        loading? (and resuming? (empty? history))
        ready?  (not loading?)
        pa?     (get-in state [:lobby :agent-id])
        dkey    (draft-key state)
        ;; A virtual new chat has no room: its buffers (e.g. an opened file)
        ;; live on the pending room.
        ui         (if room (:ui room) (when new? (get-in state [:web/pending-room :ui])))
        buffers    (:buffers ui)
        active-buf (get ui :active-buffer :chat)
        canvas?    (boolean (seq (get-in room [:ext :canvas-review :diff])))
        has-tabs?  (boolean (or (:diff buffers) (:file buffers) (:prompt buffers) canvas?))
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
      [:div {:class ["topbar-title"]}
       ;; Cached history is already painted; the server's copy is still in
       ;; flight. Show a quiet inline hint instead of blanking to a spinner.
       (when (and resuming? (seq history))
         [:span {:class ["topbar-updating"]}
          (spinner) [:span "Updating…"]])]
      (offline-badge state)
      (when has-tabs?
        (tab-bar dispatch! (:id room) active-buf buffers canvas?))
      (overflow-menu dispatch! state (when room {:mode :room :room-id (:id room)}))]
     (case active-buf
       :diff
       (diff-tab-view dispatch! (:id room) (:diff buffers)
                      (:web/diff-sel state)
                      (:web/diff-modify? state)
                      (:web/diff-collapsed state))

       :file
       (file-tab-view (:file buffers))

       :prompt
       (prompt-tab-view dispatch! room (or (:web/prompt-expanded state) #{}))

       ;; default: :chat
       (list
        [:div {:class ["timeline"
                       (when-not pa? "timeline--float-footer")]}
         [:div {:class ["timeline-content"]}
          (if ready?
            (let [entries (vec history)
                  total   (count entries)
                  win     (or (:web/timeline-window state) initial-window-size)
                  natural (max 0 (- total win))
                  ;; While the user is scrolled up, the top edge is frozen (set
                  ;; in :web/set-scrolled-up) so streaming appends below can't
                  ;; slide the window and drop nodes above the viewport (which
                  ;; jumps the scroll on Safari/iOS). min with natural keeps
                  ;; "Show earlier" able to reveal further back.
                  start   (if-let [fs (:web/frozen-window-start state)]
                            (min fs natural)
                            natural)
                  ;; Permission-gate correlation: the gate fires while its
                  ;; tool-call is already in history as a :running entry. Other
                  ;; tool calls can be running too (parallel calls), so the
                  ;; dialog's :call picks the gated one (dlg/permission-tool-index).
                  ;; Attach the first pending :confirm dialog to that tool block
                  ;; so the ask renders as a zone inside the same grey box.
                  resolved-list (get-in state [:web/resolved-dialogs (:id room)])
                  resolved-by-tool (into {} (keep (fn [e] (when-let [t (:tool-id e)] [t e]))
                                                  resolved-list))
                  pending-dialog (first (get-in room [:ui :dialogs]))
                  ;; Scan only the rendered window [start, total): if the
                  ;; running tool were scrolled off we must NOT suppress the
                  ;; standalone dialog, or its answer buttons would vanish.
                  perm-tool-idx (when (= :confirm (:type pending-dialog))
                                  (dlg/permission-tool-index pending-dialog entries start))
                  perm-answer!
                  (when perm-tool-idx
                    (let [{:keys [id type message text options]} pending-dialog
                          room-id (:id room)
                          tool-id (:id (nth entries perm-tool-idx))]
                      (fn [value]
                        (dispatch! {:type :web/dialog-resolved
                                    :room-id room-id :dialog-id id
                                    :entry {:key id :anchor (count history)
                                            :tool-id tool-id
                                            :message (or message text) :type type
                                            :value value
                                            :label (dialog-decision-label type options value)}})
                        (dispatch! {:type :ui/dialog-response
                                    :room-id room-id :dialog-id id :value value})
                        (dispatch! {:type :ui/dialog-close
                                    :room-id room-id :dialog-id id}))))]
              (list
               ;; Part of the scrollable timeline, not an empty-state: shown
               ;; whenever the window reaches the first entry, so it stays
               ;; above the conversation and can be scrolled back to.
               (when (zero? start)
                 (with-post-key "tl-launch-header"
                                (launch-header
                                 {:model model
                                  :cwd (or (:cwd room) (get-in state [:web/pending-room :cwd])
                                           (when sid
                                             (some (fn [s] (when (= sid (:session-id s)) (:cwd s)))
                                                   (get-in state [:lobby :sessions]))))
                                  :agents-files (or (get-in room [:agent :agents-files])
                                                    (when new?
                                                      (get-in state [:web/pending-room :agents-files])))
                                  :pa? pa?
                                  :dispatch! dispatch!})))
               (when (pos? start)
                 [:div {:class ["load-earlier"] :replicant/key "tl-load-earlier"}
                  (button/button
                   {:variant :ghost :size :sm
                    :on-click (fn [_] (dispatch! {:type :timeline/set-window
                                                  :window (+ win window-step)}))}
                   (str "Show " (min window-step start) " earlier messages"
                        " (" start " hidden)"))])
               (let [editing   (:web/editing-bubble state)
                     ;; Appearance settings (xi.web.appearance): viewer mode
                     ;; folds tool/thinking posts into grouped header rows
                     ;; (group-viewer-items); :tool-blocks / :thinking-blocks
                     ;; decide whether each block starts open or collapsed.
                     app       (appearance/effective-in state)
                     viewer?   (:viewer-mode? app)
                     ;; Answered dialogs live in a web-only log, each anchored
                     ;; to the history length at answer time so its static
                     ;; bubble stays in chronological place as the turn resumes.
                     ;; Ones tagged :tool-id render inside their tool block
                     ;; instead (see resolved-by-tool), so drop them here.
                     by-anchor (group-by :anchor resolved-list)
                     ;; Resolved-dialog bubbles are never groupable (:group?
                     ;; false), so they break any run of collapsed tools.
                     ritems    (fn [p] (map (fn [e] {:key (str "rdlg-" (:key e))
                                                     :group? false
                                                     :node (resolved-dialog-post (:key e) e)})
                                            (remove :tool-id (get by-anchor p))))]
                 (group-viewer-items
                  (and viewer? (:super-collapsed? app))
                  (concat
                   ;; Resolved bubbles anchored above the visible window: pin at top.
                   (mapcat ritems (sort (filter #(< % start) (keys by-anchor))))
                   (mapcat
                    (fn [p]
                      (concat
                       (ritems p)
                       (when (< p total)
                         (let [entry (nth entries p)
                               ;; A tool post joins a viewer group unless it has
                               ;; a *pending* permission ask (needs the inline
                               ;; Allow/Deny buttons, so it stays expanded and
                               ;; breaks the run). Already-answered tools join the
                               ;; group and show a decision icon in their header.
                               ;; Thinking blocks fold into the same group as a
                               ;; collapsed "Thinking" row.
                               groupable? (and viewer?
                                               (#{:tool-call :thinking} (:kind entry))
                                               (not= p perm-tool-idx))
                               ;; Start collapsed per the block-kind setting; a
                               ;; tool awaiting Allow/Deny always stays open.
                               collapsed? (and (not= p perm-tool-idx)
                                               (appearance/block-collapsed? app (:kind entry)))
                               post  (entry->post
                                      dispatch!
                                      (cond-> (assoc entry :history-index p
                                                     :cwd (or (:cwd room) (get-in state [:web/pending-room :cwd])))
                                        groupable?
                                        (assoc :grouped? true)
                                        collapsed?
                                        (assoc :collapsed? true)
                                        (and (= :user (:kind entry)) (= p (:index editing)))
                                        (assoc :editing? true :edit-text (:text editing))
                                        (= p perm-tool-idx)
                                        (assoc :permission {:dialog pending-dialog
                                                            :answer! perm-answer!})
                                        (resolved-by-tool (:id entry))
                                        (assoc :resolved-permission
                                               (resolved-by-tool (:id entry)))))]
                           (when post [{:key (str "h-" p)
                                        :group? groupable?
                                        :collapsed? collapsed?
                                        :info (when groupable?
                                                (block-info entry (or (:cwd room) (get-in state [:web/pending-room :cwd]))))
                                        :node (with-post-key (str "h-" p) post)}])))))
                    (range start (inc total))))))
               ;; These tail bubbles appear/disappear as a turn progresses
               ;; (optimistic → real message, pending command clears, dialog
               ;; answered). They share the parent's child list with the keyed
               ;; h-N posts above, so they must be keyed too — a mix of keyed
               ;; and unkeyed siblings makes Replicant reconcile the tail
               ;; positionally and, when the optimistic bubble is replaced by a
               ;; real h-N post on send, throw `removeChild ... not a Node`.
               (with-post-key "tl-optimistic"
                              (optimistic-post dispatch! state room sid history))
               (with-post-key "tl-pending-command"
                              (pending-command-post state room sid))
               (with-post-key "tl-dialog"
                              (dialog-post dispatch! state room history
                                           (when perm-tool-idx (:id pending-dialog))))))
            (with-post-key "tl-empty"
                           (empty-state/empty-state {} (spinner) [:p "Connecting…"])))
          ;; subagents-panel and quick-replies-row are direct children of
          ;; .timeline-content, sharing the child list with the keyed h-N post
          ;; seq above. They toggle nil↔element across a turn (subagents spawn/
          ;; finish; chips hide while busy). A parent must not mix keyed and
          ;; unkeyed children — when one of these toggles while the post seq
          ;; also changes length, Replicant reconciles the tail positionally and
          ;; throws `removeChild ... not a Node`, wedging the client. Key them so
          ;; the whole timeline-content child list reconciles by identity.
          (with-post-key "tl-subagents"
                         (when room (subagents-panel dispatch! room)))
          ;; Quick-reply chips render inline at the bottom of the feed, right
          ;; after the last response they regard — normal flow content, so they
          ;; can never overlap the message the way the floating footer did.
          (with-post-key "tl-quick-replies"
                         (quick-replies-row dispatch! room busy?))]]
        (copy-dialog-overlay dispatch! (:web/copy-text state))
        (when-let [menu (:web/bubble-menu state)]
          (bubble-menu dispatch! (:id room) menu))
        (when-let [menu (:web/code-menu state)]
          (code-copy-menu dispatch! (:id room) menu))
        (lightbox/lightbox {:src (:web/lightbox state)
                            :on-close (fn [] (dispatch! {:type :lightbox/close}))})
        [:div {:class ["compose-dock"]}
         (when (:web/copy-flash state) (copy-toast))
         (if-let [form (:web/skill-form state)]
           (skill-form-compose dispatch! form)
           (compose-box dispatch! room busy? (get-in state [:web/compose-images dkey])
                        dkey (get-in state [:web/drafts dkey]) sid
                        (:web/cmd-selected state)
                        (get-in state [:lobby :agent-id])
                        (:web/queue-popover? state)
                        (:web/prompt-nav state)
                        nav-ctx
                        (:web/scrolled-up? state)
                        (false? (:web/connected? state))))]))]))

;; ── Home view ────────────────────────────────────────────────────────────────

(defn- with-projects
  "Tag session cards to display their owning project in the description line.
   Used by cross-project listings (All sessions, Favorites, the sidebar) so a
   session shows which project it belongs to; the per-project view omits it."
  [cards]
  (map #(assoc % :show-project? true) cards))

(defn- session-menu-items
  "ui.context-menu entries for a session card: bookmark toggle, hide/show in
   Recent (only where the caller opts in via :dismissable?), and Delete. Hiding
   is gated on idle — a busy card or one awaiting a dialog response can't be
   dismissed. Delete is always offered: the server keeps a busy room alive and
   just suppresses its card (see room_manager/session-delete)."
  [dispatch! {:keys [session-id favorite? dismissed? dismissable? busy? has-dialog?]}]
  (let [idle? (not (or busy? has-dialog?))]
    (cond-> [{:label    (if favorite? "Remove bookmark" "Bookmark")
              :icon     :star
              :on-click #(dispatch! {:type :favorites/toggle :session-id session-id})}]
      (and dismissable? idle?)
      (conj {:label    (if dismissed? "Show in recent" "Hide from recent")
             :icon     (if dismissed? :eye :eye-off)
             :on-click #(dispatch! {:type :dismissed/toggle :session-id session-id})})
      true
      (conj {:type :separator}
            {:label    "Delete"
             :icon     :trash
             :variant  :danger
             :confirm  "Delete this session permanently?"
             :on-click #(dispatch! {:type :session/delete :session-id session-id})}))))

(defn- open-card-menu!
  "Open the enclosing card's context menu from its ⋮ button, anchored under the
   button. Goes through the framework's long-press entry point, which fires the
   same contextmenu event a right-click / long-press would."
  [^js e]
  (.stopPropagation e)
  (let [btn (.-currentTarget e)
        r   (.getBoundingClientRect btn)]
    (when-let [open! (aget js/window "__uiLongPress")]
      (when-let [trigger (.closest btn ".context-menu-trigger")]
        (open! trigger (.-left r) (.-bottom r))))))

(defn- session-card
  "Session row. Secondary actions (bookmark, hide from Recent, delete) live in
   a ui.context-menu on the card: right-click, long-press on touch (the
   framework's gesture runtime), or the ⋮ button."
  [dispatch! {:keys [session-id name cwd timestamp current? active? busy? has-dialog? unread? show-project?]
              :as card-data}]
  (let [card
  [:div {:class ["project-card"
                 (when has-dialog? "project-card--dialog")
                 (when current? "project-card--current")]
         :replicant/key (or session-id (str "card-" name))
         ;; Decode the cached history on pointerdown so the click that
         ;; follows (≈100ms later on touch) finds it ready.
         :on {:pointerdown (fn [_] (when session-id
                                     (dispatch! {:type :cache/prefetch
                                                 :session-id session-id})))
              :click (fn [_] (dispatch! {:type :route/navigate
                                         :page :chat :session-id session-id}))}}
   ;; The chat icon is faded unless the session has a live room; its badge is
   ;; the session's status dot (working > unread > live, see
   ;; card-status-indicator).
   [:div {:class ["project-card-icon" "project-card-icon--badged"
                  (when-not (or active? has-dialog?) "project-card-icon--idle")]
          :title (cond busy?   "Working…"
                       unread? "Unread responses"
                       active? "Live on the server"
                       :else   nil)}
    (cond
      has-dialog? (icon/icon {:icon-name :alert-circle :size :sm})
      :else       (message-circle-icon))
    (card-status-indicator {:busy? busy? :unread? unread? :active? active?})]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} (or name "New session")]
    [:span {:class ["project-card-path"]}
     (->> [(when (and show-project? cwd) (shorten-path cwd))
           ;; "just now" is noise; only a real age (3m ago) is useful.
           (let [rel (format-relative-time timestamp)]
             (when-not (= rel "just now") rel))
           (when has-dialog? "needs response")]
          (remove str/blank?)
          (str/join " · "))]]
   ;; ⋮ opens the same context menu as right-click / long-press.
   (when session-id
     [:button {:class ["project-card-action" "session-more-btn"]
               :title "More actions"
               :on {:click open-card-menu!}}
      (more-vertical-icon)])]]
    (if session-id
      ;; The trigger wraps the card (rather than being it) so the card keeps
      ;; its own click handler — the trigger's :attrs would replace it.
      (context-menu/context-menu-trigger
       {:items (session-menu-items dispatch! card-data)
        :class "project-card-trigger"
        :attrs {:replicant/key session-id}}
       card)
      card)))

(defn- project-dir-card
  "Card for a project directory in the home view. `dirty?` draws an orange
   status dot on the folder icon when the project's git tree has changes."
  [dispatch! path dirty?]
  [:div {:class ["project-card" "project-card--dir"]
         :replicant/key (str "dir-" path)
         :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd path}))}}
   [:div {:class ["project-card-icon" (when dirty? "project-card-icon--dirty")]}
    (icon/icon {:icon-name :folder :size :sm})
    (when dirty? [:span {:class ["project-dirty-dot"]}])]
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
        (empty-state/empty-state {} (spinner) [:p "Loading sessions…"])

        (and (empty? sessions) (empty? orphans))
        (if (seq query)
          (empty-state/empty-state {} [:p "No matching sessions."])
          (empty-state/empty-state {}
           [:p "No sessions yet."]
           [:button {:class ["btn" "btn--primary"]
                     :on {:click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}}
            "Start new session"]))

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
        ;; The lobby broadcast only carries a capped recent list; the full
        ;; list is fetched on demand (:sessions/all) when this view opens.
        ;; Fall back to the capped list for an instant paint while it loads.
        sessions   (or (:web/all-sessions state)
                       (get-in state [:lobby :sessions]))
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
         (for [o (with-projects orphans)]
           (session-card dispatch! o))
         (for [s (with-projects (active-first state sessions))]
           (session-card dispatch! s))]

        (seq query)
        (empty-state/empty-state {} [:p "No matching sessions."])

        :else
        (empty-state/empty-state {} [:p "No sessions yet."]))]]))

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
         (for [s (with-projects (active-first state sessions))]
           (session-card dispatch! s))]

        (seq query)
        (empty-state/empty-state {} [:p "No matching favorites."])

        :else
        (empty-state/empty-state {}
         [:p "No favorites yet."]
         [:p {:class ["empty-state-hint"]} "Tap the star on a session to bookmark it."]))]]))

(defn- personal-agent-home-view
  "Home view for personal-agent mode: a flat session list with no project
   navigation (the personal agent has no projects). Supports name/content
   search and a link to the favorites list."
  [state dispatch!]
  (let [raw-query   (get-in state [:web/search :personal-agent])
        query       (str/lower-case (str/trim (or raw-query "")))
        content?    (boolean (get-in state [:web/content-search :personal-agent]))
        matches     (get-in state [:web/content-matches :personal-agent])
        all-sessions (get-in state [:lobby :sessions])
        connected?  (:web/connected? state)
        orphans     (filter-sessions (orphan-rooms state all-sessions) query content? matches)
        sessions    (filter-sessions all-sessions query content? matches)]
    [:div {:class ["container"] :replicant/key "home"}
     [:div {:class ["topbar"]}
      (menu-button dispatch!)
      [:div {:class ["topbar-title"]} "Xi"]
      (offline-badge state)
      (when connected?
        [:button {:class ["icon-btn"]
                  :title "Favorites"
                  :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd :favorites}))}}
         (icon/icon {:icon-name :star :size :md})])
      (when connected?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :room/new}))}}
         (icon/icon {:icon-name :plus :size :md})])
      (overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (cond
        (not connected?)
        (empty-state/empty-state {}
         (spinner)
         [:p "Connecting to server…"])

        :else
        [:div
         (search-box dispatch! :personal-agent "Search sessions\u2026" raw-query content?)
         (cond
           (or (seq sessions) (seq orphans))
           [:div {:class ["project-list"]}
            (for [o orphans]
              (session-card dispatch! o))
            (for [s (active-first state sessions)]
              (session-card dispatch! s))]

           (seq query)
           (empty-state/empty-state {} [:p "No matching sessions."])

           :else
           (empty-state/empty-state {} [:p "No sessions yet."]))])]]))

(defn- home-view [state dispatch!]
  (let [selected-dir (:web/selected-project-dir state)]
    (cond
      (and (get-in state [:lobby :agent-id])
           (= selected-dir :favorites))
      (favorites-view state dispatch!)

      (get-in state [:lobby :agent-id])
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
            (empty-state/empty-state {}
             (spinner)
             [:p "Connecting to server…"])

            loading?
            (empty-state/empty-state {}
             (spinner)
             [:p "Loading projects…"])

            content-active?
            [:div
             (search-box dispatch! :home "Search projects…" raw-query content?)
             (if (seq matched-sessions)
               [:div {:class ["project-list"]}
                (for [s (with-projects (active-first state matched-sessions))]
                  (session-card dispatch! s))]
               (empty-state/empty-state {} [:p "No matching sessions."]))]

            :else
            [:div
             (search-box dispatch! :home "Search projects…" raw-query content?)
             [:div {:class ["project-list"]}
              ;; Active orphan rooms first (hide when filtering)
              (when-not (seq query)
                (for [r orphans]
                  (session-card dispatch! {:session-id (:session-id r)
                                          :name (or (:session-name r) "New session")
                                          :cwd (:cwd r)
                                          :show-project? true
                                          :active? true :busy? (:busy? r)
                                          :has-dialog? (:has-dialog? r)
                                          :favorite? (contains? fav-ids (:session-id r))})))
              ;; All sessions link (hide when filtering)
              (when-not (seq query)
                [:div {:class ["project-card"]
                       :replicant/key "all-sessions"
                       :on {:click (fn [_] (dispatch! {:type :projects/select-dir :cwd :all}))}}
                 [:div {:class ["project-card-icon"]}
                  (message-circle-icon)]
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
                (empty-state/empty-state {} [:p "No matching projects."])
                (let [dirty (:web/project-dirty state)]
                  (for [d dirs]
                    (project-dir-card dispatch! d (contains? dirty d)))))]])]]))))

;; ── Root ─────────────────────────────────────────────────────────────────────

(defn- recent-projects
  "Distinct project directories ordered by most-recently used, derived from
   live rooms (active now) followed by saved sessions sorted by last visited.
   Personal-agent sessions carry no cwd, so the list collapses to empty there."
  [state]
  (let [room-cwds (->> (get-in state [:lobby :rooms]) (keep :cwd))
        sess-cwds (->> (get-in state [:lobby :sessions])
                       (filter :cwd)
                       (sort-by palette/session-time #(compare %2 %1))
                       (map :cwd))]
    (->> (concat room-cwds sess-cwds)
         distinct
         (take 5))))

(defn- claude-usage-meter
  "One usage window in the usage popover: label · bar · percent, the bar
   colored by severity, with a muted reset note below."
  [label pct severity note]
  [:div {:class ["claude-usage-meter" (str "claude-usage--" severity)]}
   [:div {:class ["claude-usage-meter-row"]}
    [:span {:class ["claude-usage-label"]} label]
    [:div {:class ["claude-usage-track"]}
     [:div {:class ["claude-usage-fill"]
            :style {:width (str pct "%")}}]]
    [:span {:class ["claude-usage-pct"]} (str pct "%")]]
   (when note
     [:div {:class ["claude-usage-note"]} note])])

(defn- claude-usage-ring
  "Radial progress ring for a percent; its color follows the severity class
   on an ancestor (see .claude-usage--*)."
  [pct]
  (let [circumference 94.25] ; 2πr for r=15
    [:svg {:class ["claude-usage-ring"] :viewBox "0 0 36 36" :aria-hidden "true"}
     [:circle {:class ["claude-usage-ring-track"] :cx "18" :cy "18" :r "15"}]
     [:circle {:class ["claude-usage-ring-fill"] :cx "18" :cy "18" :r "15"
               :stroke-dasharray (str (* circumference pct 0.01) " " circumference)
               :transform "rotate(-90 18 18)"}]]))

(defn- claude-usage-popover
  "Claude subscription usage (rides on the lobby broadcast) for the sidebar
   footer: a ring + session percent that opens a popover with the session
   (5-hour) and weekly windows as labelled bars and their reset times. The
   server only reports a severity for the session window, so the weekly bar's
   is derived from its percent."
  [state]
  (when-let [{:keys [session weekly severity session-resets-at weekly-resets-at]}
             (get-in state [:lobby :claude-usage])]
    (let [fmt-time (fn [iso opts]
                     (when iso
                       (try (.toLocaleTimeString (js/Date. iso) js/undefined opts)
                            (catch :default _ nil))))
          session-reset (fmt-time session-resets-at #js {:hour "2-digit" :minute "2-digit"})
          weekly-reset  (fmt-time weekly-resets-at #js {:weekday "short" :hour "2-digit" :minute "2-digit"})
          resets-in (when session-resets-at
                      (let [ms (.getTime (js/Date. session-resets-at))]
                        (when-not (js/isNaN ms)
                          (let [m (js/Math.max 0 (js/Math.round (/ (- ms (js/Date.now)) 60000)))
                                h (js/Math.floor (/ m 60))
                                rm (mod m 60)]
                            (cond (zero? m) "now"
                                  (zero? h) (str "in " m " min")
                                  (zero? rm) (str "in " h " h")
                                  :else (str "in " h " h " rm " min"))))))
          weekly-severity (cond (nil? weekly) "normal"
                                (>= weekly 100) "exceeded"
                                (>= weekly 90) "critical"
                                (>= weekly 75) "warning"
                                :else "normal")]
      (list
       [:button (merge {:class ["sidebar-usage-trigger" (str "claude-usage--" severity)]
                        :title (str "Claude usage: session " session "%"
                                    (when weekly (str " · week " weekly "%")))
                        :replicant/key "sidebar-usage-trigger"}
                       (popover/trigger-attrs "sidebar-usage"))
        (claude-usage-ring session)
        [:span {:class ["sidebar-usage-pct"]} (str session "%")]]
       (popover/popover-content
        {:id    "sidebar-usage"
         :side  :top
         :align :start
         :class "claude-usage"
         :attrs {:replicant/key "sidebar-usage"}}
        [:div {:class ["claude-usage-title"]} "Claude usage"]
        (claude-usage-meter "Session" session severity
                            (when session-reset
                              (str "Resets " session-reset
                                   (when resets-in (str " · " resets-in)))))
        (when weekly
          (claude-usage-meter "Week" weekly weekly-severity
                              (when weekly-reset (str "Resets " weekly-reset)))))))))

(defn- more-horizontal-icon
  "Inline Lucide `ellipsis` SVG — the shared icon set has none (see
   `more-vertical-icon`)."
  []
  [:svg {:class ["icon" "icon-sm"]
         :xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "2"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:circle {:cx "5" :cy "12" :r "1"}]
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "19" :cy "12" :r "1"}]])

(defn- sidebar-more-menu
  "The sidebar footer's ⋯ button and its popover: labelled bulk session
   actions instead of a row of bare icons. `items` are
   {:label :icon :on-click :badge :danger?} maps or :separator; each click
   closes the popover."
  [items]
  (let [close! (fn [^js e]
                 (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover)))]
    (list
     [:button (merge {:class ["sidebar-footer-btn"]
                      :title "More actions"
                      :replicant/key "sidebar-more-trigger"}
                     (popover/trigger-attrs "sidebar-more-menu"))
      (more-horizontal-icon)]
     (popover/popover-content
      {:id    "sidebar-more-menu"
       :side  :top
       :align :end
       :class "sidebar-more-menu"
       :attrs {:replicant/key "sidebar-more-menu"}}
      (for [[i item] (map-indexed vector items)]
        (if (= :separator item)
          [:div {:class ["sidebar-more-sep"] :replicant/key (str "sep-" i)}]
          (let [{:keys [label icon on-click badge danger? title]} item]
            [:button {:class         (cond-> ["sidebar-more-item"]
                                       danger? (conj "sidebar-more-item--danger"))
                      :title         title
                      :replicant/key label
                      :on            {:click (fn [e] (close! e) (on-click))}}
             (icon/icon {:icon-name icon :size :sm})
             [:span label]
             (when badge [:span {:class ["sidebar-more-badge"]} badge])])))))))

(defn- sidebar-section
  "Collapsible drawer group: a framework sidebar-group whose label is a quiet
   small-caps toggle row (label · chevron). The label carries no icon,
   so it sits flush with the item icons and the item names read as nested
   inside it. Collapsed group ids live in :web/sidebar-collapsed (see
   :sidebar/toggle-group); a collapsed group keeps its header but doesn't build
   its cards at all."
  [dispatch! collapsed {:keys [id label]} & children]
  (let [open? (not (contains? collapsed id))]
    (sidebar/sidebar-group
     ;; The framework conj's :class as ONE token, so collapsed-ness rides a
     ;; data attribute rather than a second class.
     {:class "sidebar-section"
      :attrs {:replicant/key  (str "section-" (name id))
              :data-collapsed (when-not open? "true")}
      :label [:button {:class ["sidebar-section-toggle"]
                       :aria-expanded (str open?)
                       :on {:click (fn [_] (dispatch! {:type :sidebar/toggle-group :group id}))}}
              [:span {:class ["sidebar-section-label"]} label]
              [:span {:class ["sidebar-section-chevron"]} (icon/icon {:icon-name :chevron-down :size :sm})]]}
     (when open?
       [:div {:class ["sidebar-section-items"]} children]))))

(def ^:private mac?
  (boolean (re-find #"Mac|iPhone|iPad" (or (some-> js/navigator .-platform) ""))))

(defn- sidebar-search
  "Search field atop the drawer sidebar. A trigger, not an input: it opens the
   command palette (the same one as mod+k), which already fuzzy-searches every
   session, project and command."
  [dispatch!]
  [:button {:class ["sidebar-search-trigger"]
            :type "button"
            :replicant/key "sidebar-search"
            :on {:click (fn [_] (dispatch! {:type :palette/open}))}}
   (icon/icon {:icon-name :search :size :sm})
   [:span {:class ["sidebar-search-trigger-label"]} "Search"]
   [:kbd {:class ["sidebar-search-trigger-kbd"]} (if mac? "⌘K" "Ctrl K")]])

(def ^:private recent-sidebar-cache
  ;; Memo for the docked/drawer sidebar. On wide screens the sidebar is always
  ;; rendered, so without this its whole projects+sessions subtree would be
  ;; rebuilt (and re-diffed) on every app render — including every composer
  ;; keystroke — making typing janky. We cache the built hiccup keyed on the
  ;; state slices the sidebar actually reads; while those are unchanged
  ;; (typing only touches composer state) we hand back the *identical* hiccup,
  ;; which Replicant's `unchanged?` skips by reference, so the subtree is
  ;; neither rebuilt nor reconciled. (dispatch! is a stable singleton, so it's
  ;; safe to leave out of the key.)
  (atom nil))

(defn- session-cleanups
  "Cleanup actions that currently apply, given the sidebar session groups —
   shared by the sidebar ⋯ menu and the command palette. Each is
   {:label :icon :event} (+ :badge); \"Prune all\" runs every one of them."
  [pa? {:keys [recent hidden earlier]}]
  (let [unread (count (filter :unread? (concat recent hidden earlier)))]
    (cond-> []
      (pos? unread)
      (conj {:label "Mark all as read"
             :icon  :check
             :badge unread
             :event {:type :session/mark-all-read}})
      (or (seq recent) (seq earlier))
      (conj {:label "Hide all from Recent"
             :icon  :eye-off
             :event {:type :session/dismiss-all}})
      (not pa?)
      (conj {:label "Close idle rooms"
             :icon  :trash
             :event {:type :rooms/prune}}))))

(defn- recent-sidebar*
  "The drawer panel: framework sidebar listing recently-used projects above
   recent sessions, both sorted by last visited. Slid in/out by the floating
   layout's data-sidebar-open attribute. Reuses session-card/project-dir-card
   so indicators render the same way as the home listings (navigation
   auto-closes the drawer)."
  [state dispatch!]
  (let [open?    (boolean (:web/sidebar-open? state))
        ;; On wide screens the drawer is docked (always visible, no overlay),
        ;; so render its content regardless of the open/closed drawer state.
        wide?    (boolean (:web/wide? state))
        render?  (or open? wide?)
        pa?      (get-in state [:lobby :agent-id])
        projects (when (and render? (not pa?)) (recent-projects state))
        ;; Session cards split into Recent / Hidden / Earlier (busy pinned to
        ;; the top). Shared with ALT+j/k keyboard nav so both agree on order.
        ;; "Hidden" = dismissed this run (reversible, still fully resumable).
        {:keys [recent hidden earlier]} (when render? (sidebar-session-groups state))
        cards    (concat recent hidden earlier)
        collapsed (or (:web/sidebar-collapsed state) #{})
        section  (partial sidebar-section dispatch! collapsed)]
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
      ;; Only the top padding is overridden (safe-area inset for the iOS
      ;; drawer); the inline padding comes from CSS so the cards stay inset.
      {:attrs {:style {:padding-top "max(var(--size-2), env(safe-area-inset-top))"}
               :replicant/key (str "sidebar-content-" render?)}}
      (when render?
        (list
         (sidebar-search dispatch!)
         ;; Recent projects, closed out by the "All projects" overview row.
         (when (not pa?)
           (section {:id :projects :label "Projects"}
             (let [dirty (:web/project-dirty state)]
               (for [p projects]
                 (project-dir-card dispatch! p (contains? dirty p))))
             (sidebar/sidebar-menu-item
              {:icon-name :layout-dashboard
               :class     "sidebar-row"
               :active    (= :home (get-in state [:web/route :page]))
               :attrs     {:replicant/key "all-projects"}
               :on-click  (fn [_] (dispatch! {:type :route/navigate :page :home}))}
              "All projects")))
         (when (seq recent)
           (section {:id :recent :label "Recent"}
             (for [c (with-projects recent)]
               (session-card dispatch! (assoc c :dismissable? true)))))
         ;; Hidden group sits between Recent and Earlier. Its cards keep the
         ;; toggle (now an eye → "Show in recent") so the user can restore them.
         (when (seq hidden)
           (section {:id :hidden :label "Hidden"}
             (for [c (with-projects hidden)]
               (session-card dispatch! (assoc c :dismissable? true)))))
         (when (seq earlier)
           (section {:id :earlier :label "Earlier"}
             (for [c (with-projects earlier)]
               (session-card dispatch! c))))
         ;; Entries contributed by extensions (:nav-items with :menu :sidebar,
         ;; e.g. Image Graphs) — last, below the core session groups.
         (let [ext-items (when (not pa?) (nav-items-for state :sidebar))]
           (when (seq ext-items)
             (section {:id :extensions :label "Extensions"}
               (for [item ext-items]
                 (sidebar/sidebar-menu-item
                  {:icon-name (:icon item)
                   :class     "sidebar-row"
                   :attrs     {:replicant/key (str "nav-" (:label item))}
                   :on-click  (fn [_] (dispatch! (:event item)))}
                  (:label item)))))))))
     ;; NOTE: keep this footer's state reads reflected in `recent-sidebar`'s
     ;; memo key below, or the docked sidebar can go stale.
     (let [cleanups (session-cleanups pa? {:recent recent :hidden hidden :earlier earlier})
           cleanup-items (mapv (fn [{:keys [event] :as item}]
                                 (assoc item :on-click #(dispatch! event)))
                               cleanups)
           prune-all (when (> (count cleanups) 1)
                       {:label    "Prune all"
                        :icon     :zap
                        :title    (str/join " · " (map :label cleanups))
                        :on-click #(run! (comp dispatch! :event) cleanups)})
           items (->> (cond-> cleanup-items
                        standalone? (conj :separator
                                          {:label    "Reload"
                                           :icon     :refresh
                                           :on-click reload-with-feedback!})
                        prune-all   (conj :separator prune-all))
                      (drop-while #{:separator}))]
       (sidebar/sidebar-footer {}
         [:div {:class ["sidebar-footer-bar"]}
          [:div {:class ["sidebar-footer-start"]}
           (claude-usage-popover state)]
          (theme-toggle/theme-toggle
           {:mode (or (:web/theme-mode state) "auto")
            :size :sm
            :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))})
          [:div {:class ["sidebar-footer-actions"]}
           [:button {:class ["sidebar-footer-btn"]
                     :title "Appearance settings"
                     :on {:click (fn [_] (dispatch! {:type :appearance/open}))}}
            (icon/icon {:icon-name :settings :size :sm})]
           (when (seq items)
             (sidebar-more-menu items))]])))))

(defn- recent-sidebar
  "Memoized wrapper around `recent-sidebar*`. Returns the identical cached
   hiccup while the sidebar's input slices are unchanged (see cache docstring)."
  [state dispatch!]
  (let [sig    [(:web/sidebar-open? state)
                (:web/wide? state)
                (:lobby state)
                (:web/response-counts state)
                (:web/watched state)
                (get-in state [:web/route :session-id])
                (get-in state [:web/route :page])
                (:web/sidebar-collapsed state)
                (:web/project-dirty state)
                (:web/theme-mode state)
                (:web/nav-items state)]
        cached @recent-sidebar-cache]
    (if (and cached (= (:sig cached) sig))
      (:html cached)
      (let [html (recent-sidebar* state dispatch!)]
        (reset! recent-sidebar-cache {:sig sig :html html})
        html))))

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
        (empty-state/empty-state {} (spinner) [:p "Loading changes…"])

        (str/blank? text)
        (empty-state/empty-state {} "No changes.")

        :else
        (diff-rows-view dispatch!
                        (diff/diff-rows (diff/parse-diff-text text))
                        nil nil))]]))

(defn- palette-chat-item
  "cmd/command-item variant with a trailing status slot, so palette chat rows
   surface the same status dot as session cards (working > unread > live —
   see card-status-indicator). Mirrors the
   ui.command DOM contract (.command-item) so ui-runtime.js keyboard
   navigation works.

   Two modes, driven by ui-runtime.js's filter:
   - default (recents): the empty data-command-value makes the row show ONLY
     at the empty query — an empty value matches nothing once anything is
     typed, so the whole Chats group auto-hides and its rows drop out of
     keyboard nav.
   - :search? — the inverse: data-command-search-only hides the row at the
     empty query, and a real data-command-value (name + project path) makes
     it appear as a search result while typing. Used by the Sessions group
     so every session (incl. Earlier) is reachable by search."
  ([card dispatch!] (palette-chat-item card dispatch! nil))
  ([{:keys [session-id name cwd has-dialog? busy? unread? active?]} dispatch!
    {:keys [search?]}]
   (let [label (or name "New session")]
     [:button (cond-> {:class ["command-item"] :role "option" :type "button"
                       :data-command-value
                       (if search? (str label " " (some-> cwd shorten-path)) "")
                       :on {:click (fn [_] (dispatch! {:type :route/navigate
                                                       :page :chat
                                                       :session-id session-id}))}}
                search? (assoc :data-command-search-only "true"))
      (icon/icon {:icon-name (if has-dialog? :alert-circle :terminal)
                  :size :sm :class "command-item-icon"})
      [:span {:class ["command-item-label"]} label]
      [:div {:class ["command-item-status"]}
       (card-status-indicator {:busy? busy? :unread? unread? :active? active?})]])))

(defn- palette-project-actions
  "Command items for a project's second-level page (Tab-drilled from a project
   row). Mirrors the project three-dots overflow menu — new chat, git status,
   search — plus any :project-scoped extension nav items, with open sessions
   last."
  [state dispatch! cwd]
  (concat
   [(cmd/command-item
     {:icon :plus
      :on-click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}
     "New chat")
    (cmd/command-item
     {:icon :code
      :on-click (fn [_] (dispatch! {:type :git-status/open :cwd cwd}))}
     "Git status")
    (cmd/command-item
     {:icon :search
      :on-click (fn [_] (dispatch! {:type :palette/open-search :cwd cwd}))}
     "Search session text")]
   (for [item (nav-items-for state :overflow)
         :when (contains? #{nil :project} (:mode item))]
     (cmd/command-item
      {:icon (:icon item)
       :on-click (fn [_] (dispatch! (merge (:event item) {:cwd cwd})))}
      (:label item)))
   [(cmd/command-item
     {:icon :folder-open
      :on-click (fn [_] (dispatch! {:type :projects/select-dir :cwd cwd}))}
     "Open sessions")]))

(defn- palette-search-page
  "Full-text session search as a palette sub-page (drilled from a project's
   \"Search session text\"). The palette input is the query — the dialog's
   input listener dispatches :palette/search-input, which debounces a
   server-side search over saved-session names + transcripts scoped to the
   project cwd. Spinner while a reply is in flight; result rows carry the
   matched snippet and open the session. Rows set data-command-value to the
   live query so ui-runtime's filter never hides them (the match lives in the
   transcript text, not the row label)."
  [state dispatch!]
  (let [{:keys [query results]} (:web/palette-search state)]
    (cond
      (str/blank? (str/trim (or query "")))
      [:div {:class ["command-empty"]} "Type to search session text…"]

      (nil? results)
      [:div {:class ["command-loading"]} (spinner)]

      (empty? results)
      [:div {:class ["command-empty"]} "No matching sessions"]

      :else
      (apply cmd/command-group {:heading "Matching sessions"}
        (for [{:keys [session-id name snippet]} results]
          (cmd/command-item
           {:icon :message-circle
            :value query
            :description snippet
            :on-click (fn [_] (dispatch! {:type :route/navigate
                                          :page :chat
                                          :session-id session-id}))}
           (or name "(untitled)")))))))

(defn- palette-model-page
  "Model list as a palette sub-page (drilled from Change model / /model). Shows
   a spinner while :web/model-list loads, then a command-item per model with
   the active one checked. Selecting sends /model and closes the palette."
  [state dispatch!]
  (let [room    (state/active-room state)
        models  (:web/model-list state)
        ;; In a not-yet-created chat the choice lives on the pending room;
        ;; prefer it so the checkmark tracks the pick before the room exists.
        current (or (get-in state [:web/pending-room :model])
                    (get-in room [:agent :model]))]
    (if (nil? models)
      [:div {:class ["command-loading"]} (spinner)]
      (apply cmd/command-group {:heading "Model"}
        (for [m models]
          (cmd/command-item
           {:icon (if (= m current) :check :layers)
            :value m
            :on-click (fn [_] (dispatch! {:type :models/select
                                          :model m :room-id (:id room)}))}
           m))))))

(defn- palette-skill-item [dispatch! {:keys [name description]}]
  (cmd/command-item
   {:icon :zap
    :value (str name " " description)
    :description description
    :on-click (fn [_] (dispatch! {:type :skill/select :name name}))}
   name))

(defn- palette-skill-page
  "On-demand skills as a palette sub-page (drilled from Skills). Spinner while
   :web/skill-list loads, then a command-item per skill. Recently-used skills
   first (like the commands page), then the rest alphabetically. Selecting
   loads the skill and closes the palette."
  [state dispatch!]
  (let [skills (:web/skill-list state)]
    (cond
      (nil? skills)  [:div {:class ["command-loading"]} (spinner)]
      (empty? skills) [:div {:class ["command-empty"]} "No skills found"]
      :else
      (let [recents  (:web/recent-skills state)
            by-name  (into {} (map (juxt :name identity)) skills)
            recent   (keep by-name recents)
            the-rest (remove (comp (set recents) :name) skills)]
        [:div
         (when (seq recent)
           (apply cmd/command-group {:heading "Recent"}
             (map #(palette-skill-item dispatch! %) recent)))
         (apply cmd/command-group {:heading (if (seq recent) "All skills" "Skills")}
           (map #(palette-skill-item dispatch! %) the-rest))]))))

(defn- palette-project-insert-page
  "Project paths as a palette sub-page (drilled from the Projects compose
   button). Spinner while :web/project-dirs loads, then a command-item per
   project. Selecting inserts the path into the current compose draft."
  [state dispatch!]
  (let [dirs (:web/project-dirs state)
        dkey (draft-key state)]
    (if (empty? dirs)
      [:div {:class ["command-loading"]} (spinner)]
      (apply cmd/command-group {:heading "Insert project path"}
        (for [path dirs]
          (cmd/command-item
           {:icon :folder
            :value (str (shorten-path path) " " path)
            :on-click (fn [_] (dispatch! {:type :projects/picker-insert
                                          :path path :draft-key dkey}))}
           (shorten-path path)))))))

(defn- snippet-command-item
  [dispatch! dkey {:keys [label text]}]
  (cmd/command-item
   {:icon :code
    :value (str label " " text)
    :description text
    :on-click (fn [_] (dispatch! {:type :snippets/picker-insert
                                  :text text :draft-key dkey}))}
   label))

(defn- palette-command-item
  [dispatch! room-id {:keys [name description]}]
  (cmd/command-item
   {:icon :terminal
    :value (str name " " description)
    :description description
    :on-click (fn [_] (dispatch-command! dispatch! room-id name))}
   name))

(defn- palette-commands-page
  "Slash commands as a palette sub-page (drilled from the floating Commands
   button). Recently-executed commands first, then the full curated list."
  [state dispatch!]
  (let [room-id  (:id (state/active-room state))
        recents  (:web/recent-commands state)
        by-name  (into {} (map (juxt :name identity)) web-commands)
        recent   (keep by-name recents)
        the-rest (remove (comp (set recents) :name) web-commands)]
    [:div
     (when (seq recent)
       (apply cmd/command-group {:heading "Recent"}
         (map #(palette-command-item dispatch! room-id %) recent)))
     (apply cmd/command-group {:heading (if (seq recent) "All commands" "Commands")}
       (map #(palette-command-item dispatch! room-id %) the-rest))]))

(defn- palette-snippets-page
  "Snippets as a palette sub-page (drilled from the Snippets compose button).
   Spinner while :web/snippet-list loads, then a command-item per snippet
   grouped into Project (cwd-specific, from the snippets extension) and global.
   Selecting inserts the snippet's text into the current compose draft."
  [state dispatch!]
  (let [{:keys [global project] :as loaded} (:web/snippet-list state)
        dkey (draft-key state)]
    (cond
      (nil? loaded)
      [:div {:class ["command-loading"]} (spinner)]

      (and (empty? global) (empty? project))
      [:div {:class ["command-empty"]} "No snippets found"]

      :else
      [:div
       (when (seq project)
         (apply cmd/command-group {:heading "Project snippets"}
           (map #(snippet-command-item dispatch! dkey %) project)))
       (when (seq global)
         (apply cmd/command-group {:heading "Snippets"}
           (map #(snippet-command-item dispatch! dkey %) global)))])))

(defn- palette-commits-page
  "Commits made during this session (git base..HEAD) as a palette sub-page —
   official git_commit-tool commits and plain shell `git commit`s alike, since
   both are ordinary commits in the range. Spinner while :web/commit-list
   loads; selecting a commit opens its diff in the room's diff viewer."
  [state dispatch!]
  (let [room    (state/active-room state)
        commits (:web/commit-list state)]
    (cond
      (nil? commits)   [:div {:class ["command-loading"]} (spinner)]
      (empty? commits) [:div {:class ["command-empty"]} "No commits this session"]
      :else
      (apply cmd/command-group {:heading "Session commits"}
        (for [{:keys [sha short subject rel-time]} commits]
          (cmd/command-item
           {:icon :code
            :value (str short " " subject)
            :description (str short " · " rel-time)
            :on-click (fn [_] (dispatch! {:type :commits/open-diff
                                          :sha sha :room-id (:id room)}))}
           subject))))))

(defn- palette-files-page
  "File browser as a palette sub-page: drill into directories (\"..\" steps up),
   click a file to open it in the :file tab. Absolute paths throughout; the
   server lists each directory. Spinner while :web/file-list loads."
  [state dispatch!]
  (let [{:keys [path parent entries error]} (:web/file-list state)]
    (cond
      (nil? (:web/file-list state)) [:div {:class ["command-loading"]} (spinner)]
      error [:div {:class ["command-empty"]} error]
      :else
      (apply cmd/command-group {:heading (or path "Files")}
        (concat
         (when parent
           [(cmd/command-item
             {:icon :arrow-left
              :value ".."
              :on-click (fn [_] (dispatch! {:type :files/cd :path parent}))}
             "..")])
         (if (empty? entries)
           [(cmd/command-item {:icon :folder :value ""} "(empty directory)")]
           (for [{:keys [name dir?]} entries
                 :let [child (str path "/" name)]]
             (cmd/command-item
              {:icon (if dir? :folder :file-text)
               :value name
               :on-click (fn [_]
                           (if dir?
                             (dispatch! {:type :files/cd :path child})
                             (dispatch! {:type :files/open :path child})))}
              (if dir? (str name "/") name)))))))))

(defn- palette-file-finder-page
  "Instant fuzzy file finder as a palette sub-page (Ctrl/Cmd+P). The palette
   input is the query — the dialog's input listener dispatches
   :file-finder/input, which fuzzy-ranks the preloaded flat file list
   client-side (no per-keystroke server round-trip). Rows carry :value = the
   live query so ui-runtime's own substring filter never re-hides a fuzzy
   match; selecting opens the file in the :file tab (relative path, resolved
   server-side against the room cwd)."
  [state dispatch!]
  (let [{:keys [files error]} (:web/file-tree state)
        query (or (:web/file-finder-query state) "")]
    (cond
      (nil? (:web/file-tree state)) [:div {:class ["command-loading"]} (spinner)]
      error [:div {:class ["command-empty"]} error]
      (empty? files) [:div {:class ["command-empty"]} "No files"]
      :else
      (let [ranked (fuzzy/rank query files {:limit 50})]
        (if (empty? ranked)
          [:div {:class ["command-empty"]} "No matching files"]
          (apply cmd/command-group {:heading "Files"}
            (for [rel ranked]
              (cmd/command-item
               {:icon :file-text
                :value query
                :on-click (fn [_] (dispatch! {:type :files/open :path rel}))}
               rel))))))))

(defn- palette-keydown
  "Extra keyboard layer over ui-runtime.js (which owns arrow-nav, live filter
   and Enter): Tab drills the active project row into its action sub-page;
   Escape / Shift+Tab / Backspace-on-empty backs out of a sub-page. Reads the
   runtime's `.command-item--active` element and its `data-palette-drill` cwd."
  [dispatch! palette-page]
  (fn [^js e]
    (let [dialog (.-currentTarget e)
          key    (.-key e)]
      (cond
        (and (nil? palette-page) (= key "Tab") (not (.-shiftKey e)))
        (when-let [active (.querySelector dialog ".command-item--active")]
          (when-let [cwd (.. active -dataset -paletteDrill)]
            (.preventDefault e)
            (dispatch! {:type :palette/drill :cwd cwd
                        :label (.. active -dataset -paletteLabel)})))

        (and (some? palette-page)
             (or (= key "Escape")
                 (and (= key "Tab") (.-shiftKey e))
                 (and (= key "Backspace")
                      (when-let [input (.querySelector dialog ".command-input")]
                        (= "" (.-value input))))))
        (do (.preventDefault e)
            (dispatch! {:type :palette/back}))))))

(defn- command-palette
  "Global Cmd/Ctrl+K command palette (ui.command). Mounted once in root-view;
   the ui-runtime.js delegate handles open/filter/keyboard-nav. Items dispatch
   app events on select (the runtime clicks the button, firing :on-click, then
   closes the dialog). The Chats group quick-switches to active/recent rooms;
   room-scoped actions only appear when a room is active.

   Two levels: at the top level, Tab on a project row drills into a project
   action page (:web/palette-page); Escape/Shift+Tab/Backspace backs out."
  [state dispatch!]
  (let [open?        (boolean (:web/palette-open? state))
        palette-page (:web/palette-page state)
        search-page? (= :search (:kind palette-page))
        finder-page? (= :file-finder (:kind palette-page))
        dialog-attrs {:id "cmdk" :hotkey "mod+k"
                      :placeholder (cond
                                     search-page? "Search session text…"
                                     finder-page? "Find file…"
                                     palette-page "Filter actions…"
                                     :else        "Type a command or search…")
                      :attrs {:replicant/key "cmdk"
                              ;; Flip :web/palette-open? when the native dialog
                              ;; gains its `open` attribute (opened by
                              ;; ui-runtime's mod+k). Until then the heavy
                              ;; command items below aren't built, so typing in
                              ;; the compose box doesn't re-diff ~120 hidden
                              ;; nodes every keystroke.
                              :replicant/on-mount
                              (fn [{:replicant/keys [^js node]}]
                                (let [obs (js/MutationObserver.
                                           (fn [_ _]
                                             (when (.-open node)
                                               (dispatch! {:type :palette/opened}))))]
                                  (.observe obs node
                                            #js {:attributes true
                                                 :attributeFilter #js ["open"]})))
                              :on {:keydown (palette-keydown dispatch! palette-page)
                                   ;; On the search sub-page the palette input
                                   ;; is the query — feed keystrokes into the
                                   ;; debounced server search (delegated: input
                                   ;; events bubble from .command-input).
                                   :input (fn [^js e]
                                            (let [v (.. e -target -value)]
                                              (cond
                                                search-page?
                                                (dispatch! {:type :palette/search-input :query v})
                                                finder-page?
                                                (dispatch! {:type :file-finder/input :query v}))))
                                   :close (fn [_] (dispatch! {:type :palette/closed}))}}}]
    (cond
      ;; Closed: render just the dialog shell (observer stays attached via the
      ;; stable :replicant/key). No children means near-zero per-keystroke cost.
      (not open?)
      (cmd/command-dialog dialog-attrs)

      palette-page
      ;; On a sub-page the leading search icon becomes a clickable back arrow
      ;; (via the framework's :leading slot) instead of a separate back row.
       (cmd/command-dialog
       (assoc dialog-attrs
              :leading
              [:button {:class ["command-search-back"] :type "button"
                        :aria-label "Back"
                        :on {:click (fn [_] (dispatch! {:type :palette/back}))}}
               (icon/icon {:icon-name :arrow-left :size :sm})])
       (case (:kind palette-page)
         :model          (palette-model-page state dispatch!)
         :skill          (palette-skill-page state dispatch!)
         :commits        (palette-commits-page state dispatch!)
         :files          (palette-files-page state dispatch!)
         :file-finder    (palette-file-finder-page state dispatch!)
         :project-insert (palette-project-insert-page state dispatch!)
         :snippets       (palette-snippets-page state dispatch!)
         :commands       (palette-commands-page state dispatch!)
         :search         (palette-search-page state dispatch!)
         (apply cmd/command-group {:heading (str "Project · " (:label palette-page))}
           (palette-project-actions state dispatch! (:cwd palette-page)))))

      :else
      (let [room         (state/active-room state)
            ;; Active rooms first, then most-recently-visited sessions; drop the
            ;; chat we're already looking at. Enriched with live status flags.
            recents      (->> (palette/recent-sessions state)
                              (active-first state)
                              (remove :current?)
                              (take 8))
            chat-items   (mapv #(palette-chat-item % dispatch!) recents)
            project-dirs (:web/project-dirs state)
            pa?          (get-in state [:lobby :agent-id])
            {:keys [recent hidden earlier] :as groups} (sidebar-session-groups state)
            ;; Same cleanups as the sidebar ⋯ menu, incl. its "Prune all".
            cleanups     (session-cleanups pa? groups)
            cur-sid      (get-in state [:web/route :session-id])
            cur-session  (when cur-sid
                           (some #(when (= cur-sid (:session-id %)) %)
                                 (get-in state [:lobby :sessions])))
            cur-fav?     (boolean (:favorite? cur-session))
            cur-hidden?  (boolean (:dismissed? cur-session))
            ;; Search-only tier: every session (Recent + Hidden + Earlier),
            ;; invisible at the empty query (data-command-search-only) and
            ;; matched by name/project while typing — so older sessions are
            ;; reachable without leaving the palette.
            all-sessions (->> (concat recent hidden earlier)
                              (remove #(= cur-sid (:session-id %))))]
     (cmd/command-dialog dialog-attrs
     (when (seq chat-items)
       (apply cmd/command-group {:heading "Chats"} chat-items))
     (when (seq all-sessions)
       (apply cmd/command-group {:heading "Sessions"}
         (map #(palette-chat-item % dispatch! {:search? true}) all-sessions)))
     (apply cmd/command-group {:heading "Navigate"}
       (cond-> [(cmd/command-item {:icon :layout-dashboard
                                   :on-click (fn [_] (dispatch! {:type :route/navigate :page :home}))}
                  "All projects")]
         ;; Fuzzy file finder needs a room cwd to scope the listing.
         room (conj (cmd/command-item
                     {:icon :file-text
                      :on-click (fn [_] (dispatch! {:type :palette/open-file-finder}))}
                     "Find file…"))
         :always (into (for [item (nav-items-for state :palette)]
                         (cmd/command-item {:icon (:icon item)
                                            :on-click (fn [_] (dispatch! (:event item)))}
                           (:label item))))))
     (when (seq project-dirs)
       (apply cmd/command-group {:heading "Projects"}
         (for [d project-dirs]
           (cmd/command-item
            {:icon :folder
             :shortcut "⇥"
             :value (str "project " (shorten-path d) " " d)
             :attrs {:data-palette-drill d
                     :data-palette-label (shorten-path d)}
             ;; Mouse click drills into the project action sub-page (same as
             ;; keyboard Tab); :reopen? keeps the panel open past the runtime's
             ;; force-close. "Open sessions" inside the sub-page navigates.
             :on-click (fn [_] (dispatch! {:type :palette/drill :cwd d
                                           :label (shorten-path d) :reopen? true}))}
            (shorten-path d)))))
     ;; Actions come from the shared xi.palette spec (same labels/icons/order as
     ;; the TUI Ctrl+/ palette); the web maps each :key to its own handler.
     (let [action-onclick
           (fn [key]
             (case key
               :new-chat     (fn [_] (dispatch! {:type :room/new}))
               :change-model (fn [_] (dispatch! {:type :palette/open-models}))
               :skills       (fn [_] (dispatch! {:type :palette/open-skills}))
               :git-status   (fn [_] (dispatch! {:type :diff/reopen
                                                 :room-id (:id room)
                                                 :method "git" :engine :git}))
               :copy-debug   (fn [_]
                               (copy! dispatch! (commands/debug-text room)))
               :reload       (fn [_] (reload-with-feedback!))))]
       (let [actions (for [{:keys [key label icon]} (palette/actions (boolean room))]
                       (cmd/command-item {:icon icon :on-click (action-onclick key)} label))
             ;; The pushover user extension seeds every room's [:ext :pushover]
             ;; state, so its presence means the server has it loaded — only
             ;; then is the toggle useful (Ctrl+Shift+P on the TUI). The mode
             ;; comes back as a :user-ext/sync of that slice.
             push?   (and room (contains? (get-in state [:rooms (:id room) :ext])
                                          :pushover))
             push-mode (or (get-in state [:rooms (:id room) :ext :pushover :mode]) :auto)]
         (apply cmd/command-group {:heading "Actions"}
           (cond-> (vec actions)
             push?
             (conj (cmd/command-item
                    {:icon :bell
                     :on-click (fn [_] (dispatch! {:type  :user-ext/forward
                                                   :event {:type :ext.pushover/toggle
                                                           :room-id (:id room)}}))}
                    (str "Push notifications: "
                         (case push-mode
                           :on  "on (always)"
                           :off "off"
                           "auto (only when away)"))))
             :always
             (into (let [mode  (or (:web/theme-mode state) "auto")
                         ;; In auto mode the effective theme follows the OS
                         ;; preference, so resolve it to know what "toggle"
                         ;; should switch to.
                         dark? (case mode
                                 "dark"  true
                                 "light" false
                                 (.-matches (js/window.matchMedia
                                             "(prefers-color-scheme: dark)")))]
                     (cond-> [(cmd/command-item
                               {:icon (if dark? :sun :moon)
                                :value "theme toggle light dark"
                                :on-click (fn [_]
                                            (dispatch! {:type :theme/set-mode
                                                        :mode (if dark? "light" "dark")}))}
                               (if dark? "Switch to light theme" "Switch to dark theme"))]
                       (not= mode "auto")
                       (conj (cmd/command-item
                              {:icon :monitor
                               :value "theme auto system"
                               :on-click (fn [_]
                                           (dispatch! {:type :theme/set-mode
                                                       :mode "auto"}))}
                              "Use system theme"))
                       :always
                       (conj (cmd/command-item
                              {:icon :settings
                               :value "appearance settings viewer mode tool thinking blocks"
                               :on-click (fn [_] (dispatch! {:type :appearance/open}))}
                              "Appearance settings")))))))))
     (when cur-sid
       (cmd/command-group {:heading "Current session"}
         (cmd/command-item
          {:icon :star
           :on-click (fn [_] (dispatch! {:type :favorites/toggle :session-id cur-sid}))}
          (if cur-fav? "Remove bookmark" "Bookmark session"))
         (cmd/command-item
          {:icon (if cur-hidden? :eye :eye-off)
           :on-click (fn [_] (dispatch! {:type :dismissed/toggle :session-id cur-sid}))}
          (if cur-hidden? "Show in Recent" "Hide from Recent"))
         (cmd/command-item
          {:icon :trash
           :on-click (fn [_]
                       (dispatch! {:type :session/delete :session-id cur-sid})
                       (dispatch! {:type :route/navigate :page :home}))}
          "Delete session")))
     (when (seq cleanups)
       (apply cmd/command-group {:heading "Cleanup"}
         (cond-> (mapv (fn [{:keys [label icon event]}]
                         (cmd/command-item
                          {:icon icon :on-click (fn [_] (dispatch! event))}
                          label))
                       cleanups)
           (> (count cleanups) 1)
           (conj (cmd/command-item
                  {:icon  :zap
                   :value (str "prune all " (str/join " " (map :label cleanups)))
                   :on-click (fn [_] (run! (comp dispatch! :event) cleanups))}
                  "Prune all")))))
     (when room
       (apply cmd/command-group {:heading "Commands"}
         (for [{:keys [name description]} (palette/expand-commands web-commands)]
           (cmd/command-item
            {:icon :terminal
             :value (str name " " description)
             ;; /model drills into an in-palette model picker instead of
             ;; running the command (which would close the palette).
             :on-click (if (= name "model")
                         (fn [_] (dispatch! {:type :palette/open-models}))
                         (fn [_] (dispatch-command! dispatch! (:id room) name)))}
            name)))))))))

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

;; ── Skill input form ─────────────────────────────────────────────────────────

(defn- skill-form-stage-images!
  "Read + downscale picked image files, then stage them on the skill form."
  [dispatch! files]
  (when (seq files)
    (-> (js/Promise.all (to-array (map resize-image-file files)))
        (.then (fn [results]
                 (when-let [valid (seq (remove nil? (array-seq results)))]
                   (dispatch! {:type :skill-form/add-images :images (vec valid)}))))
        (.catch (fn [err] (js/console.error "[xi-web] skill-form image read failed:" err))))))

(defn- handle-skill-form-paste!
  "Stage clipboard images pasted anywhere inside the skill form (Ctrl+V)."
  [dispatch! ^js e]
  (let [items (.. e -clipboardData -items)
        files (->> (range (.-length items))
                   (keep (fn [i]
                           (let [^js item (aget items i)]
                             (when (str/starts-with? (.-type item) "image/")
                               (.getAsFile item))))))]
    (when (seq files)
      (.preventDefault e)
      (skill-form-stage-images! dispatch! files))))

(defn- skill-form-image-field
  "Thumbnails of staged images plus an add button wrapping a hidden file input."
  [dispatch! images]
  [:div {:class ["skill-form-images"]}
   (map-indexed
    (fn [idx {:keys [data media-type]}]
      [:div {:replicant/key idx :class ["skill-form-thumb"]}
       [:img {:src (str "data:" media-type ";base64," data)}]
       [:button {:class ["skill-form-thumb-remove"] :type "button"
                 :on {:click (fn [_] (dispatch! {:type :skill-form/remove-image :idx idx}))}}
        (icon/icon {:icon-name :x :size :sm})]])
    images)
   ;; A label wrapping a hidden file input isn't keyboard-focusable, so the
   ;; form would tab straight from the last textarea to the send button.
   ;; tabindex puts it in the tab order; Enter/Space opens the file picker.
   [:label {:class ["skill-form-add-image"]
            :tabindex "0"
            :role "button"
            :on {:keydown (fn [^js e]
                            (when (or (= "Enter" (.-key e)) (= " " (.-key e)))
                              (.preventDefault e)
                              (some-> (.querySelector (.-currentTarget e) "input[type=file]")
                                      (.click))))}}
    (icon/icon {:icon-name :image :size :sm})
    [:span "Add image"]
    [:input {:type "file" :accept "image/*" :multiple true
             :style {:display "none"}
             :on {:change (fn [^js e]
                            (skill-form-stage-images!
                             dispatch! (array-seq (.. e -target -files)))
                            (set! (.. e -target -value) ""))}}]]])

(defn- skill-form-compose
  "The composer reshaped into a skill input form (replaces .compose-box in the
   compose dock while :web/skill-form is set). One separated section per
   dynamic <input /> placeholder: text placeholders get an auto-growing
   textarea, image-upload an image picker. The first text field is autofocused.
   Esc pressed twice cancels (the first press arms and shows a hint); Cmd/Ctrl+
   Enter or the send button submits — values are substituted into the skill
   body and posted like a normal prompt."
  [dispatch! form]
  (let [{:keys [name description inputs values images body armed?]} form
        first-text (some (fn [{n :name t :type}] (when (not= t :image) n))
                         inputs)]
    [:div {:class ["compose-box" "skill-compose"]}
     [:div {:class ["compose-frame" "skill-compose-frame"]
            :on {:paste (fn [^js e] (handle-skill-form-paste! dispatch! e))
                 :keydown
                 (fn [^js e]
                   (cond
                     (= "Escape" (.-key e))
                     (do (.preventDefault e)
                         (dispatch! {:type :skill-form/escape}))

                     (and (= "Enter" (.-key e))
                          (or (.-metaKey e) (.-ctrlKey e)))
                     (do (.preventDefault e)
                         (when body (dispatch! {:type :skill-form/submit})))))}}
      [:div {:class ["skill-compose-head"]}
       (icon/icon {:icon-name :zap :size :sm})
       [:span {:class ["skill-compose-name"]} name]
       (when (not-empty description)
         [:span {:class ["skill-compose-desc"]} description])
       [:button {:class ["icon-btn" "skill-compose-close"] :type "button"
                 :title "Cancel skill"
                 :on {:click (fn [_] (dispatch! {:type :skill-form/cancel}))}}
        (icon/icon {:icon-name :x :size :sm})]]
      (for [{in-name :name in-type :type} inputs]
        [:div {:replicant/key in-name :class ["skill-compose-field"]}
         [:div {:class ["skill-form-label"]} (dlg/humanize-name in-name)]
         (if (= in-type :image)
           (skill-form-image-field dispatch! images)
           [:textarea {:class ["skill-form-input"]
                       :placeholder "…"
                       :rows 1
                       :value (get values in-name "")
                       :replicant/on-mount
                       (fn [{:replicant/keys [^js node]}]
                         (when (= in-name first-text)
                           (.focus node #js {:preventScroll true})))
                       :on {:input (fn [^js e]
                                     (dispatch! {:type :skill-form/set-value
                                                 :name in-name
                                                 :value (.. e -target -value)}))}}])])
      ;; Skills without an <image-upload /> placeholder still accept image
      ;; attachments (pasted or picked) — they ride along with the prompt like
      ;; normal composer attachments.
      (when-not (some #(= :image (:type %)) inputs)
        [:div {:class ["skill-compose-field"]}
         [:div {:class ["skill-form-label"]} "Images"]
         (skill-form-image-field dispatch! images)])
      [:div {:class ["skill-compose-footer"]}
       [:span {:class ["skill-compose-hint" (when armed? "skill-compose-hint--armed")]}
        (if armed?
          "Press Esc again to cancel"
          "Esc Esc to cancel")]
       [:button {:class ["icon-btn" "skill-compose-send"] :type "button"
                 :disabled (nil? body)
                 :title "Start skill"
                 :on {:click (fn [_] (dispatch! {:type :skill-form/submit}))}}
        (if body
          (icon/icon {:icon-name :arrow-up :size :md})
          (spinner))]]]]))

(defn- appearance-row
  "One settings row: label (+ optional hint) on the left, its control on the
   right."
  [label hint control]
  [:div {:class ["appearance-row"]}
   [:div {:class ["appearance-row-text"]}
    [:div {:class ["appearance-row-label"]} label]
    (when hint [:div {:class ["form-hint"]} hint])]
   control])

(defn- open-collapsed-choice
  "Open / Collapsed segmented control for the block-kind setting `key`."
  [dispatch! key value]
  (button-group/button-group {:variant :boxed}
    (button-group/button-group-item
     {:active (= :open value)
      :on-click (fn [_] (dispatch! {:type :appearance/set :key key :value :open}))}
     "Open")
    (button-group/button-group-item
     {:active (= :collapsed value)
      :on-click (fn [_] (dispatch! {:type :appearance/set :key key :value :collapsed}))}
     "Collapsed")))

(defn- appearance-dialog
  "The Appearance settings dialog (sidebar gear / overflow menu / palette).
   Edits this browser's overrides (:web/appearance, see xi.web.appearance) on
   top of xi.config/appearance and the built-in defaults; the timeline
   re-renders live as each control changes. Reset drops the overrides so the
   configured values apply again. Escape closes it (keymap binding)."
  [state dispatch!]
  (when (:web/appearance-open? state)
    (let [overrides (:web/appearance state)
          app       (appearance/effective-in state)
          close!    (fn [] (dispatch! {:type :appearance/close}))]
      (dialog/dialog-overlay
       {:on-close close!
        :class "appearance-overlay"
        :attrs {:replicant/key "appearance-dialog"}}
       (dialog/dialog-panel {:class "appearance-dialog"}
         (dialog/dialog-header {}
           [:h3 "Appearance"]
           [:button {:class ["icon-btn" "icon-btn--sm"]
                     :title "Close"
                     :on {:click (fn [_] (close!))}}
            (icon/icon {:icon-name :x :size :md})])
         (dialog/dialog-body {:class "appearance-body"}
           (appearance-row
            "Theme" nil
            (theme-toggle/theme-toggle
             {:mode (or (:web/theme-mode state) "auto")
              :size :sm
              :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))}))
           (appearance-row
            "Viewer mode"
            "Fold runs of tool and thinking rows into one box"
            (switch/switch-toggle
             {:checked (:viewer-mode? app)
              :on-change (fn [^js e]
                           (dispatch! {:type :appearance/set
                                       :key :viewer-mode?
                                       :value (boolean (.. e -target -checked))}))}))
           (appearance-row
            "Super collapsed"
            "Fold collapsed groups into one summary row (needs viewer mode)"
            (switch/switch-toggle
             {:checked (:super-collapsed? app)
              :disabled (not (:viewer-mode? app))
              :on-change (fn [^js e]
                           (dispatch! {:type :appearance/set
                                       :key :super-collapsed?
                                       :value (boolean (.. e -target -checked))}))}))
           (appearance-row
            "Tool blocks"
            "Read, edit, clj… calls and their results"
            (open-collapsed-choice dispatch! :tool-blocks (:tool-blocks app)))
           (appearance-row
            "Thinking blocks" nil
            (open-collapsed-choice dispatch! :thinking-blocks (:thinking-blocks app))))
         (dialog/dialog-footer {}
           (button/button
            {:variant :ghost :size :sm
             :disabled (not (appearance/overridden? overrides))
             :on-click (fn [_] (dispatch! {:type :appearance/reset}))}
            "Reset to defaults")
           (button/button
            {:variant :primary :size :sm
             :on-click (fn [_] (close!))}
            "Done")))))))

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
     (appearance-dialog state dispatch!)
     (auth-request-banner state dispatch!)
     (auth-overlay state))))