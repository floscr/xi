(ns xi.web.views
  "Replicant view layer — pure (state → hiccup) over the same room state the
   TUI renders. Event handlers dispatch events; there is no view-local atom.

   Phase 7a: the online chat view (topbar, timeline of history entries,
   compose input, abort, permission dialogs). Home view + router land in 7b."
  (:require [clojure.string :as str]
            [xi.avatar :as avatar]
            [xi.buffers :as buffers]
            [xi.commands :as commands]
            [xi.error-info :as error-info]
            [xi.core.state :as state]
            [xi.markdown.hiccup :as md]
            [xi.markdown.diff :as md-diff]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.embedded :as embedded]
            [xi.highlight.theme-css :as theme]
            [xi.dialog :as dlg]
            [xi.diff :as diff]
            [xi.fuzzy :as fuzzy]
            [xi.keys :as xkeys]
            [xi.palette :as palette]
            [xi.session.sidebar :as sb :refer [format-relative-time session-status
                                               active-first orphan-rooms
                                               sidebar-session-groups sidebar-session-order]]
            [xi.tui.snippets :as snippets]
            [xi.util :as util]
            [xi.web.appearance :as appearance]
            [xi.web.keymap :as keymap]
            [xi.web.palette-items :as palette-items]
            [xi.web.tool-views :as tool-views]
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

(defn prompt-nav-ctx
  "Prompt navigation over the FULL `history` (not just the rendered window):
   every user entry's absolute history index, so navigation can jump to
   prompts scrolled off the top and expand the window on demand. Carried by
   the :prompt-nav/prev / :next events (xi.web.core/prompt-nav-step)."
  [state history]
  (let [entries      (vec history)
        user-indices (vec (keep-indexed (fn [i e] (when (= :user (:kind e)) i))
                                        entries))]
    {:user-indices user-indices
     :count        (count user-indices)
     :total        (count entries)
     :cur-window   (or (:web/timeline-window state) initial-window-size)}))

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
   over HTTPS (docs/guide/https.md) avoids it."
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
  working (flashing purple) > failed turn (red) > unread (orange) > live room
  (green) > nothing.

  A single, ALWAYS-present node whose class toggles. Do NOT replace this with a
  `cond` returning different elements or nil — those swap element identity /
  drop to nil, and as the keyed card list churns and reorders Replicant
  mis-reconciles the slot: it leaves a stale indicator in the DOM (shown after
  the agent stops) and appends a second one on the next state change (the old
  doubled-spinner bug). One stable node means Replicant only ever patches the
  `class` attribute. Hidden via CSS when it carries no state modifier."
  [{:keys [busy? error? unread? active?]}]
  [:span {:replicant/key "status-indicator"
          :class ["status-dot"
                  (cond busy?   "status-dot--busy"
                        error?  "status-dot--error"
                        unread? "status-dot--unread"
                        active? "status-dot--live")]}])

(defn user-avatar
  "A user's avatar: their image over a circle of initials on a colour from
  their id (xi.avatar). The initials are the fallback — they show for a user
  with no image and under one that fails to load (hidden on error). Public:
  user extensions' web halves draw people with it too."
  [{:keys [id name] url :avatar}]
  [:span {:class ["avatar"]
          :replicant/key (str "avatar-" id)
          :title (or name id)
          :style {:background-color (str "hsl(" (avatar/hue id) " 55% 42%)")}}
   (avatar/initials id name)
   (when url
     [:img {:class ["avatar-img"] :src url :alt "" :loading "lazy"
            ;; the image host learns nothing about which Xi address is in use
            :referrerpolicy "no-referrer"
            :on {:error (fn [e] (set! (.. e -target -style -display) "none"))}}])])

(defn avatar-stack
  "Overlapping avatars of the users in a room (up to 3, then +n); nothing when
  `people` is empty. `title` is the hover text of the whole stack. Public,
  like `user-avatar`."
  [people]
  (when (seq people)
    (let [shown (take 3 people)
          more  (- (count people) (count shown))]
      [:span {:class ["avatar-stack"]
              :title (str/join ", " (map #(or (:name %) (:id %)) people))}
       (for [p shown] (user-avatar p))
       (when (pos? more)
         [:span {:class ["avatar" "avatar--more"]} (str "+" more)])])))

(defn- other-user
  "The avatar data {:id :name :avatar} of `user` when it is somebody other than
  this client's own user; nil for the viewer themselves and for unattributed
  prompts (e.g. resumed from disk)."
  [state user]
  (when (and user (not= user (state/own-user state)))
    (assoc (get-in state [:lobby :profiles user]) :id user)))

(defn- last-of-run?
  "True when history entry `p` is not followed by another prompt from the same
  user: only the last bubble of a run carries its sender's avatar."
  [entries p]
  (let [entry (nth entries p)
        nxt   (nth entries (inc p) nil)]
    (not (and nxt
              (= :user (:kind nxt))
              (= (:user nxt) (:user entry))))))

(def default-nav-icon
  "Icon for extension nav items that declare none (or one the icon set lacks),
   so every menu row keeps its label aligned with the built-in items."
  :package)

(defn- with-default-icon
  "`item` with a :icon missing from `icon/icon-names` set to `default-nav-icon`."
  [item]
  (update item :icon (fn [k]
                       (if (contains? icon/icon-names k)
                         k
                         default-nav-icon))))

(defn nav-items-for
  "Extension nav items (from ext/compose :nav-items, stored in state at
   init) scoped to one menu surface. Items whose :icon is missing or not in
   `icon/icon-names` get `default-nav-icon`."
  [state menu]
  (->> (:web/nav-items state)
       (filter #(= menu (:menu %)))
       (map with-default-icon)))

(defn nav-badge
  "The badge text of a nav item with a `:badge-path`: the positive number at
   that state path (an unread count), else nil. Data-only, so an extension
   can show a count on its sidebar entry without a view of its own."
  [state {:keys [badge-path]}]
  (when (vector? badge-path)
    (let [v (get-in state badge-path)]
      (when (and (number? v) (pos? v)) (str v)))))

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
    ("bb")          (str/join " " (cons (or (not-empty (get-arg args :task)) "tasks")
                                        (map str (get-arg args :args))))
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

(def ^:private md-cache-hard-breaks
  "Same as md-cache, for text rendered with `:hard-breaks?` (user bubbles):
   the same string renders differently per mode, so each mode keeps its own
   text → hiccup map."
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

(defn code-block-segments
  "`code-focus-segments` with each piece further split around `block-ranges`
   (every ask of the call — the dialog's :block, xi.dialog) →
   [muted? in-block? segment] triples. No `ranges` → nothing is muted."
  [text ranges block-ranges]
  (loop [pos 0
         segs (code-focus-segments text (or (seq ranges) [[0 (count text)]]))
         out []]
    (if-let [[muted? s] (first segs)]
      (recur (+ pos (count s)) (rest segs)
             (into out
                   (map (fn [[outside? piece]] [muted? (not outside?) piece]))
                   (code-focus-segments s (map (fn [[a b]] [(- a pos) (- b pos)])
                                               block-ranges))))
      out)))

(defn- focused-clj-code
  "Highlighted clj `text` with everything outside the ask's `ranges` muted
   (.code-muted) and the calls of the whole block (`block-ranges`, may be
   nil) marked .code-block-target — lit up while \"Allow all\" is hovered.
   Each segment is highlighted on its own — the ranges are whole forms, so
   every piece tokenizes like it would in context."
  [text ranges block-ranges]
  (into [:code]
        (map (fn [[muted? in-block? s]]
               (into [:span {:class (cond-> [(if muted? "code-muted" "code-focus")]
                                      in-block? (conj "code-block-target"))}]
                     (rest (or (highlight-clj-code s) (plain-code s))))))
        (code-block-segments text ranges block-ranges)))

(defn- render-md
  "Memoized `md/render`: identical text yields the *identical* hiccup object so
   Replicant's `unchanged?` short-circuits via identical? and skips re-diffing
   the post subtree (same trick as highlight-code / plain-code). Keeps typing
   in the composer from re-parsing every message's markdown on each keystroke.
   `opts` are `md/render` opts; only `:hard-breaks?` is used (user bubbles)."
  ([text] (render-md text nil))
  ([text opts]
   (if (nil? text)
     (md/render text opts)
     (let [cache (if (:hard-breaks? opts) md-cache-hard-breaks md-cache)]
       (or (.get cache text)
           (let [result (md/render text opts)]
             (when (>= (.-size cache) md-cache-max) (.clear cache))
             (.set cache text result)
             result))))))

(defn- truncate-lines [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) n)
      text
      (str (str/join "\n" (take n lines))
           "\n… (" (- (count lines) n) " more lines)"))))

(def ^:private diff-code-cache
  "grammar (nil for none) → (js/Map text→[attrs [:pre …]]) for edit-diff-code."
  (js/Map.))

(defn- edit-diff-code*
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
                    ;; Not highlight-code: the whole block is memoized below,
                    ;; and one cache entry per diff line would flood hl-cache.
                    (if grammar
                      (tokens->code (hl/merge-adjacent (hl/tokenize grammar body)))
                      body)]
                   [:span {:class ["tool-diff-line"]} line]))))
        (str/split-lines text)))

(defn- edit-diff-code
  "Render an edit tool's unified-diff result with per-line tinting: + lines get
   a subtle green wash, - lines a subtle red one, over the code box. The
   path/context/gap lines stay neutral. Code is still syntax-highlighted.
   `attrs` are extra attributes for the <pre> (the View-diff data attributes).
   Memoized per (grammar, text, attrs) as one block, like highlight-code:
   tokenizing line by line through highlight-code made every diff line its own
   cache entry, so a diff-heavy chat overflowed the cache, cleared it on every
   render and re-tokenized all its diffs per keystroke in the composer."
  [grammar text attrs]
  (let [inner (or (.get diff-code-cache grammar)
                  (let [m (js/Map.)] (.set diff-code-cache grammar m) m))
        [cached-attrs cached] (.get inner text)]
    (if (and cached (= attrs cached-attrs))
      cached
      (let [result (edit-diff-code* grammar text attrs)]
        (when (>= (.-size inner) code-cache-max) (.clear inner))
        (.set inner text [attrs result])
        result))))

;; ── Rendered markdown diffs ──────────────────────────────────────────────────

(defn- md-diff-node
  "Hiccup for one diff unit (xi.markdown.diff): its parsed block rendered as
   markdown; an exploded ordered-list item keeps its number."
  [{:keys [block start]}]
  (let [node (md/render-block block nil)]
    (if (and start (= :ol (first node)))
      (into [:ol {:start start}] (rest node))
      node)))

(defn- md-diff-band [status nodes]
  [:div {:class ["md-diff-band" (str "md-diff-band--" (name status))]}
   (into [:div {:class ["markdown"]}] nodes)])

(defn- md-diff-body
  "Rendered markdown diff of `hunks` (xi.markdown.diff hunk shape): unchanged
   blocks as dimmed context, each changed run as its removed blocks (red band)
   over its added ones (green band) with the differing words marked, and a
   dashed rule between hunks. nil when no block changed."
  [hunks]
  (when-let [segments (not-empty (md-diff/diff-segments hunks))]
    (into [:div {:class ["post-content" "md-diff"]}]
          (mapcat (fn [{:keys [kind items del add]}]
                    (case kind
                      :gap [[:div {:class ["md-diff-gap"]}]]
                      :ctx [(md-diff-band :ctx (map md-diff-node items))]
                      :change
                      (let [[d a] (md-diff/mark-words (mapv md-diff-node del)
                                                      (mapv md-diff-node add))]
                        (cond-> []
                          (seq d) (conj (md-diff-band :del d))
                          (seq a) (conj (md-diff-band :add a)))))))
          segments)))

(def ^:private md-diff-cache
  "diff text → md-diff-body of a tool diff (memoized like edit-diff-code, so
   re-renders hand Replicant the identical hiccup)."
  (js/Map.))

(defn- tool-md-diff-body
  "md-diff-body of an edit/write tool's diff text, memoized."
  [text]
  (if (.has md-diff-cache text)
    (.get md-diff-cache text)
    (let [result (md-diff-body (md-diff/tool-diff-hunks text))]
      (when (>= (.-size md-diff-cache) code-cache-max) (.clear md-diff-cache))
      (.set md-diff-cache text result)
      result)))

(defn- md-diff-toggle
  "Rendered ⇄ Code segmented control for a markdown diff. `code?` — the code
   view is showing; clicking the other segment dispatches :md-diff/toggle for
   `k`."
  [dispatch! k code?]
  (let [select (fn [want-code?]
                 (fn [^js e]
                   (.stopPropagation e)
                   (when (not= want-code? code?)
                     (dispatch! {:type :md-diff/toggle :key k}))))]
    (button-group/button-group
     {:variant :boxed :class "md-diff-toggle"}
     (button-group/button-group-item {:icon :file-text :active (not code?)
                                      :attrs {:type "button" :title "Rendered markdown diff"}
                                      :on-click (select false)}
                                     "Rendered")
     (button-group/button-group-item {:icon :code :active code?
                                      :attrs {:type "button" :title "Source diff"}
                                      :on-click (select true)}
                                     "Code"))))

(defn- tool-diff-view
  "An edit/write tool's diff text for `path`: a markdown file gets the
   rendered markdown diff (with a Rendered ⇄ Code toggle keyed by `k`, the
   tool call id; `code?` — the code view is toggled on), anything else — or a
   diff with no rendered change — the line diff. `attrs` go on the outer node."
  [dispatch! {:keys [path text grammar attrs k code?]}]
  (let [rendered (when (and (md-diff/markdown-path? path) k)
                   (tool-md-diff-body text))]
    (if-not rendered
      (edit-diff-code grammar (truncate-lines text 100) attrs)
      [:div (merge {:class ["md-diff-wrap"]} attrs)
       [:div {:class ["md-diff-bar"]}
        [:span {:class ["md-diff-path"]} path]
        (md-diff-toggle dispatch! k code?)]
       (if code?
         (edit-diff-code grammar (truncate-lines text 100) nil)
         rendered)])))

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
  "The file a tool block refers to, for the View-file action: the file
   read/write/edit tools (SDK and xi names), read_source, and the single-file
   clj-surgeon / git tools that take a `file` argument."
  [tool args]
  (case tool
    ("Read" "read" "Write" "write" "Edit" "edit" "read_source")
    (or (get-arg args :file_path) (get-arg args :path))
    ("clj_replace" "clj_outline" "clj_deps" "clj_fix_declares" "clj_mv"
     "clj_fix_parens" "clj_topo" "git_hunk")
    (get-arg args :file)
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

(def ^:private confirm-key-action
  "Confirm answer value → the keyboard action that gives it. Buttons carry it
   as `data-key-action`, so holding Alt badges them with the action's key
   (xi.web.key-hints)."
  {true "permission/allow" false "permission/deny" :always "permission/always"
   :repo "permission/allow-repo" :block "permission/allow-block"})

(defn- confirm-buttons
  "Answer buttons for a :confirm dialog, driven by its normalized :options
   data (xi.dialog) instead of hardcoded per-option markup. Deny-style
   options render first, plain Allow last, extras in between. When the ask
   takes a reason (:deny-reason?), Deny grows a ⋯ twin that calls
   `deny-reason!` (the composer becomes the reason field)."
  [dialog answer! deny-reason!]
  (for [{:keys [value label]} (sort-by (fn [{:keys [value]}]
                                         (cond (false? value) 0
                                               (true? value)  2
                                               :else          1))
                                       (dlg/confirm-options dialog))
        :let [block? (= :block value)
              btn [:button {:class (cond-> ["confirm-btn" (cond (false? value) "confirm-btn--deny"
                                                                 (true? value)  "confirm-btn--allow"
                                                                 :else          "confirm-btn--extra")]
                                     block? (conj "confirm-btn--block"))
                            :data-key-action (confirm-key-action value)
                            :title (when block? "Allow every request of this block (hover to see them)")
                            :on {:click (fn [_] (answer! value))}}
                   (if-let [n (and block? (get-in dialog [:block :count]))]
                     (str label " (" n ")")
                     label)]]]
    (if (and (false? value) deny-reason! (:deny-reason? dialog))
      [:span {:class ["confirm-split"]}
       btn
       [:button {:class ["confirm-btn" "confirm-btn--deny" "confirm-btn--more"]
                 :title "Deny with reason…"
                 :aria-label "Deny with reason"
                 :on {:click (fn [_] (deny-reason!))}}
        "⋯"]]
      btn)))

(defn- diff-preview
  "A change preview ({:path :text} — the diff a guarded write/edit asked to
   make; its ask's :diff, kept on the tool-call entry by :ui/dialog-open),
   rendered like the edit tool's result diff. `k` / `code?` as for
   tool-diff-view."
  [dispatch! {:keys [path text] :as diff} k code?]
  (when diff
    (tool-diff-view dispatch! {:path path :text text :k k :code? code?
                               :grammar (grammars/get-grammar (file-ext path))
                               :attrs {:data-diff-path path :data-diff-text text}})))

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
   stop arriving exactly when a call stalls. Keyed by `started-at`: a
   permission allow restamps it (xi.dialog/restamp-gated-call), and the key
   change remounts the span so a fresh interval picks up the new start."
  [started-at]
  (when started-at
    [:span {:class ["tool-call-timer"]
            :replicant/key [::run-timer started-at]
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
   :tool-blocks appearance setting); `:room-ext` — the room's extension
   slices, for extension tool views. All stamped by chat-view."
  [dispatch! {:keys [id tool arguments result is-error status started-at diff
                     permission resolved-permission
                     grouped? collapsed? cwd room-ext md-diff-code?]}]
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
        ;; a user extension's view of its own tool's result (xi.web.tool-views)
        ext-view  (when (and (not running?) (seq text) (not imgs))
                    (tool-views/render {:tool name :arguments arguments
                                        :text text :is-error is-error}
                                       room-ext))
        diff-result? (and (contains? #{"Edit" "edit" "clj_replace"} name)
                          (not is-error))
        ;; The change a guarded write/edit asked to make: shown while its ask
        ;; is pending, after a deny, and when the turn was interrupted under
        ;; it — a finished call's own result speaks for itself.
        preview   (when (not= :done status)
                    (or diff (get-in permission [:dialog :diff])))
        denied?   (and (some? resolved-permission) (not (:value resolved-permission)))
        ;; Anything to expand? A call that is still running with no output
        ;; yet (a long `bb check`) has nothing behind the chevron, so the
        ;; chevron is hidden until the body gets its first piece.
        has-content? (boolean (or clj-code preview (seq text) ext-view permission imgs))
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
       [:span {:class (cond-> ["tool-call-toggle-icon"]
                        (not has-content?) (conj "tool-call-toggle-icon--empty"))}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class (cond-> ["tool-call-toggle-label"] bash? (conj "tool-call-toggle-label--wrap"))}
        [:span {:class ["tool-call-action"]} name]
        (subs label (count name))]
       (when clj-preview
         [:span {:class ["clj-head-preview"]} clj-preview])
       ;; Pinned right (collapsed or open) so a long preview can't push it away.
       (when running?
         [:span {:class ["tool-call-running"]}
          ;; No clock while the call waits on its permission ask — it isn't
          ;; running yet. The allow restamps :started-at, so the timer
          ;; returns counting only actual run time.
          (when-not permission (run-timer started-at))
          (spinner)])
       ;; Right-side status badge: a filled circle with a white icon that
       ;; captures the outcome — grey ✗ when the user denied the call (its
       ;; result is an error too, but the denial is the story), red ✗ on
       ;; error, purple ✓ when the user confirmed the tool. Stays visible even
       ;; when the block is collapsed into a viewer group.
       (cond
         denied?
         (let [{:keys [label reason]} resolved-permission]
           [:span {:class ["tool-call-status" "tool-call-status--deny"]
                   :title (str (or label "Denied") (when reason (str ": " reason)))}
            (icon/icon {:icon-name :x :size :sm})])
         is-error
         [:span {:class ["tool-call-status" "tool-call-status--error"] :title "Error"}
          (icon/icon {:icon-name :x :size :sm})]
         (some? resolved-permission)
         (let [{:keys [label reason]} resolved-permission]
           [:span {:class ["tool-call-status" "tool-call-status--allow"]
                   :title (str (or label "Allowed") (when reason (str ": " reason)))}
            (icon/icon {:icon-name :check :size :sm})]))]
      (when clj-code
        ;; A pending ask that targets part of this code (the (spit …) /
        ;; (sh …) call it is about) mutes the rest so the eye lands there.
        ;; An ask that is one of several (:block) also marks every call the
        ;; block's asks target, lit up while "Allow all" is hovered.
        (let [shown  (truncate-lines clj-code 100)
              target (get-in permission [:dialog :target])
              ranges (when (= :code (:arg target)) (seq (:ranges target)))
              block  (get-in permission [:dialog :block])
              block-ranges (when (= :code (:arg block)) (seq (:ranges block)))]
          [:div {:class ["tool-call-content" "tool-call-input"]}
           [:pre {:class ["tool-call-code"]}
            (if (or ranges block-ranges)
              (focused-clj-code shown ranges block-ranges)
              (or (highlight-clj-code shown) (plain-code shown)))]]))
      (when preview
        [:div {:class ["tool-call-content"]} (diff-preview dispatch! preview id md-diff-code?)])
      (cond
        ;; When the result carries an image (view_image, screenshots) the text is
        ;; just a "Viewed image: /path" caption — drop it and show only the image.
        (and clj? (seq text))
        (clj-result-view text is-error)

        ext-view
        [:div {:class ["tool-call-content" "tool-call-content--ext"]} ext-view]

        (and (seq text) (not imgs))
        [:div (cond-> {:class ["tool-call-content"]}
                (tool-file-path name arguments)
                (assoc :data-file-path (tool-file-path name arguments))
                ;; A block that renders a file change carries its raw diff so
                ;; the context menu can open exactly that change (View diff).
                (and diff-result? (tool-file-path name arguments))
                (assoc :data-diff-path (tool-file-path name arguments)
                       :data-diff-text text))
         (if diff-result?
           (tool-diff-view dispatch! {:path (tool-file-path name arguments) :text text
                                      :grammar grammar :k id :code? md-diff-code?})
           (let [shown (truncate-lines text 100)]
             [:pre {:class ["tool-call-code"]}
              (if grammar (highlight-code grammar shown) (plain-code shown))]))])
      ;; A permission gate fired for this (still-running) tool call: render the
      ;; ask as a zone inside the same grey box, joined to the preview above.
      (when-let [{:keys [dialog answer! deny-reason!]} permission]
        (let [{:keys [message text]} dialog]
          [:div {:class ["tool-call-content"]}
           [:div {:class ["tool-call-permission"]}
            [:div {:class ["tool-call-permission-msg"]} (or message text)]
            [:div {:class ["tool-call-permission-actions"]}
             (confirm-buttons dialog answer! deny-reason!)]]]))
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
   details toggle. A rate-limit card carries a Continue button that re-prompts
   the chat (`room-id` is stamped on the entry by the timeline)."
  [dispatch! {:keys [kind title subtitle resets-at windows raw edn? room-id]}]
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
      (when (and room-id (= :rate-limit kind))
        [:div {:class ["error-card-actions"]}
         (button/button
          {:variant :primary :size :sm :class "error-card-continue"
           :on-click (fn [_] (dispatch! {:type :input/submit
                                         :room-id room-id
                                         :text "continue"}))}
          "Continue")])
      [:details {:class ["error-card-details"]}
       [:summary {:class ["error-card-details-toggle"]}
        [:span {:class ["tool-call-toggle-icon"]}
         (icon/icon {:icon-name :chevron-right :size :sm})]
        "Technical details"]
       [:pre {:class ["error-card-raw"]}
        (if edn? (or (highlight-clj-code raw) (plain-code raw)) raw)]]]]))

;; ── History entry → post ─────────────────────────────────────────────────────

(defn- post-badge
  "The sender's avatar on the bottom-right corner of a :user bubble from
   another user on a shared server (their name is its tooltip). nil for the
   viewer's own prompts, unattributed ones (e.g. resumed from disk) and all
   but the last bubble of a run by one sender. `:sender` is the user's avatar
   data, stamped on the entry by the timeline (see other-user and
   last-of-run?). Goes inside the bubble, which is its positioning context."
  [{:keys [sender]}]
  (when sender
    [:span {:class ["post-badge"]} (user-avatar sender)]))

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
        [:div {:class ["post" "post--user" "post--user-collapsed"
                       (when (:sender entry) "post--badged")]}
         [:details {:class ["post-body" "user-collapse"]}
          [:summary {:class ["user-collapse-summary"]}
           [:span {:class ["tool-call-toggle-icon"]}
            (icon/icon {:icon-name :chevron-right :size :sm})]
           [:span {:class ["user-collapse-label"]} (:collapsed-label entry)]]
          (when (seq (:text entry))
            [:div {:class ["post-content" "user-collapse-content"]}
             (render-md (:text entry) {:hard-breaks? true})])
          (post-badge entry)]]

        :else
        [:div (cond-> {:class ["post" "post--user" (when idx "post--tappable")
                               (when (:sender entry) "post--badged")]}
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
          ;; Prompts are typed text, not authored markdown: a newline in the
          ;; composer is a line break, so render soft breaks as <br>.
          (when (seq (:text entry))
            [:div {:class ["post-content"]}
             (render-md (:text entry) {:hard-breaks? true})])
          (post-badge entry)]]))

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
          (error-card dispatch! (assoc info :room-id (:room-id entry)))
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

;; ── File drop zone ───────────────────────────────────────────────────────────

(defn- files-drag?
  "True when a drag event carries files from outside the page — internal drags
   (selected text, links) keep their default behaviour."
  [^js e]
  (when-let [types (some-> e .-dataTransfer .-types)]
    (boolean (some #{"Files"} (array-seq types)))))

(defn- file-drop-attrs
  "Event handlers that make the chat view a drop target: dragging files over it
   raises the overlay (:web/file-drag?), dropping stages them on the composer of
   `draft-key` like the attach button or a paste."
  [dispatch! draft-key file-drag?]
  {:dragenter (fn [^js e]
                (when (and (files-drag? e) (not file-drag?))
                  (dispatch! {:type :web/file-drag :on? true})))
   ;; Without preventDefault on dragover the browser refuses the drop and opens
   ;; the file in the tab instead.
   :dragover (fn [^js e]
               (when (files-drag? e)
                 (.preventDefault e)
                 (set! (.. e -dataTransfer -dropEffect) "copy")))
   :drop (fn [^js e]
           (when (files-drag? e)
             (.preventDefault e)
             (dispatch! {:type :web/file-drag :on? false})
             (add-files! dispatch! draft-key (array-seq (.. e -dataTransfer -files)))))})

(defn- file-drop-overlay
  "Covers the chat view while files are dragged over it. Always rendered (shown
   by a modifier class) so the container's child list never toggles. Once
   raised it is the only hit target (its children ignore the pointer), so its
   dragleave means the drag left the view. No mouse events fire during a
   drag, so a mousemove/click means it ended unseen (cancelled) — lower it
   then too rather than leave it blocking the view."
  [dispatch! active?]
  (let [lower! (fn [_] (dispatch! {:type :web/file-drag :on? false}))]
    [:div {:class ["file-drop-overlay" (when active? "file-drop-overlay--active")]
           :on {:dragleave lower! :mousemove lower! :click lower!}}
     [:div {:class ["file-drop-overlay-label"]}
      (icon/icon {:icon-name :file-text :size :md})
      [:span "Drop files to attach"]]]))

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
        (let [selected? (= i selected-index)]
          [:button {:class ["slash-item"
                            (when selected? "slash-item--selected")]
                    ;; Keep the keyboard-selected row visible when the list
                    ;; overflows the dropdown's max-height. "nearest" is a
                    ;; no-op when it is already in view (mouse hovers).
                    :replicant/on-render
                    (fn [{:replicant/keys [^js node]}]
                      (when selected?
                        (.scrollIntoView node #js {:block "nearest"})))
                    :on {:click (fn [_]
                                  (dispatch-command! dispatch! room-id name)
                                  (when-let [^js el (compose-textarea-el)]
                                    (set! (.-value el) ""))
                                  (dispatch! {:type :compose/clear-draft :draft-key draft-key}))
                         :mouseenter (fn [_]
                                       (dispatch! {:type :cmd/select :index i}))}}
           [:span {:class ["slash-item-name"]} (str "/" name)]
           [:span {:class ["slash-item-desc"]} description]]))
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
    (fn [idx {:keys [text images sender]}]
      [:div {:class ["queue-item"] :replicant/key idx}
       (when sender (user-avatar sender))
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
                  :on {:click (fn [_] (dispatch! {:type :palette/open-projects :action :insert}))}}
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
                            (case (keymap/event->chord e)
                              ("up" "alt+k" "ctrl+p")
                              (do (.preventDefault e)
                                  (dispatch! {:type :cmd/select
                                              :index (mod (dec sel) (count cmd-matches))}))
                              ("down" "alt+j" "ctrl+n")
                              (do (.preventDefault e)
                                  (dispatch! {:type :cmd/select
                                              :index (mod (inc sel) (count cmd-matches))}))
                              ("enter" "tab")
                              (do (.preventDefault e)
                                  (let [cmd-name (:name (nth cmd-matches sel))]
                                    (dispatch-command! dispatch! room-id cmd-name)
                                    (when-let [^js el (compose-textarea-el)]
                                      (set! (.-value el) ""))
                                    (dispatch! {:type :compose/clear-draft
                                                :draft-key draft-key})))
                              "escape"
                              (do (.preventDefault e)
                                  (when-let [^js el (compose-textarea-el)]
                                    (set! (.-value el) ""))
                                  (dispatch! {:type :compose/clear-draft
                                              :draft-key draft-key}))
                              nil))
                          (cond
                            ;; Tab expands a snippet trigger before the caret
                            ;; (c → continue), like the TUI. Anywhere else Tab
                            ;; keeps its default focus move.
                            (and (= "Tab" (.-key e)) (not (.-shiftKey e))
                                 (= (.. e -target -selectionStart)
                                    (.. e -target -selectionEnd)))
                            (let [^js el (.-target e)]
                              (when-let [{:keys [text caret]}
                                         (snippets/expand-at (.-value el) (.-selectionStart el))]
                                (.preventDefault e)
                                (set! (.-value el) text)
                                (.setSelectionRange el caret caret)
                                (dispatch! {:type :compose/set-draft
                                            :draft-key draft-key :text text})))

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
                        ;; the one abort button tagged: it shows in both branches
                        :data-key-action "agent/abort"
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

(defn- deny-reason-compose
  "The composer turned into the reason field of a *deny with reason* (the ⋯
   beside a permission ask's Deny). Replaces .compose-box while
   :web/deny-reason targets an ask still pending in this room; the normal
   draft is untouched underneath. Enter denies with the typed reason — the
   gate hands it to the model — Shift+Enter breaks the line, Esc goes back to
   the composer without answering."
  [dispatch! {:keys [text]} {dmsg :message dtext :text}]
  (let [submit! #(dispatch! {:type :deny-reason/submit})]
    [:div {:class ["compose-box" "deny-compose"]}
     [:div {:class ["compose-frame" "skill-compose-frame"]}
      [:div {:class ["skill-compose-head" "deny-compose-head"]}
       (icon/icon {:icon-name :shield :size :sm})
       [:span {:class ["skill-compose-name"]} "Deny with reason"]
       [:span {:class ["skill-compose-desc"]} (or dmsg dtext)]
       [:button {:class ["icon-btn" "skill-compose-close"] :type "button"
                 :title "Back to the composer (the request stays pending)"
                 :on {:click (fn [_] (dispatch! {:type :deny-reason/cancel}))}}
        (icon/icon {:icon-name :x :size :sm})]]
      [:div {:class ["compose-input-row"]}
       [:div {:class ["compose-input-wrapper"]}
        (form/form-textarea-auto
         {:placeholder "Tell the agent why — or what to do instead…"
          :value (or text "")
          :max-rows 6
          :attrs {:replicant/on-mount
                  (fn [{:replicant/keys [^js node]}]
                    (.focus node #js {:preventScroll true}))
                  :on {:input (fn [^js e]
                                (dispatch! {:type :deny-reason/set-text
                                            :text (.. e -target -value)}))
                       ;; iOS Return (no Enter keydown) — see compose-box.
                       :beforeinput (fn [^js e]
                                      (when (= "insertLineBreak" (.-inputType e))
                                        (.preventDefault e)
                                        (submit!)))
                       :keydown
                       (fn [^js e]
                         (cond
                           (= "Escape" (.-key e))
                           (do (.preventDefault e)
                               (dispatch! {:type :deny-reason/cancel}))

                           ;; Manual newline so beforeinput doesn't submit.
                           (and (= "Enter" (.-key e)) (.-shiftKey e))
                           (let [^js el (.-target e)
                                 start  (.-selectionStart el)
                                 v      (.-value el)
                                 nv     (str (subs v 0 start) "\n" (subs v (.-selectionEnd el)))]
                             (.preventDefault e)
                             (set! (.-value el) nv)
                             (set! (.-selectionStart el) (inc start))
                             (set! (.-selectionEnd el) (inc start))
                             (dispatch! {:type :deny-reason/set-text :text nv}))

                           (= "Enter" (.-key e))
                           (do (.preventDefault e) (submit!))))}}})]
       [:div {:class ["compose-actions"]}
        [:button {:class ["icon-btn" "deny-compose-send"] :type "button"
                  :title "Deny with this reason"
                  :on {:click (fn [_] (submit!))}}
         (icon/icon {:icon-name :arrow-up :size :md})]]]
      [:div {:class ["skill-compose-footer"]}
       [:span {:class ["skill-compose-hint"]} "Enter to deny · Esc to go back"]]]]))

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
  [key {:keys [message type value label reason]}]
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
       [:span label]]
      (when reason
        [:div {:class ["dialog-deny-reason"]} reason])]]))

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
        (diff-preview dispatch! (:diff live-dialog)
                      id (contains? (:web/md-diff-code state) id))
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
             (confirm-buttons live-dialog answer!
                              #(dispatch! {:type :deny-reason/start
                                           :room-id room-id :dialog-id id})))])]])))

;; ── Diff view ────────────────────────────────────────────────────────────────

(defn- diff-file-grammar
  "Highlight grammar for a filename, or nil."
  [filename]
  (when filename
    (when-let [ext (file-ext filename)]
      (grammars/get-grammar ext))))

(def ^:private diff-text-cache
  "diff line map → highlighted [:code …]. Keyed by the (stable, memoized) line
   object, so a big diff never competes with chat code blocks for hl-cache's
   400 slots — it used to clear that cache on every render and re-tokenize the
   whole diff each time."
  (js/WeakMap.))

(defn- diff-text-code [grammar line]
  (or (.get diff-text-cache line)
      (let [result (tokens->code (hl/merge-adjacent (hl/tokenize grammar (or (:text line) ""))))]
        (.set diff-text-cache line result)
        result)))

(def ^:private diff-rows-memo
  "[text rows] of the last diff parsed by diff-rows-for-text."
  (atom nil))

(defn- diff-rows-for-text
  "diff/diff-rows of the unified diff `text`, memoized on the last text so
   re-renders (every keystroke, stream chunk) hand diff-rows-view the
   *identical* rows and its per-file caches hit."
  [text]
  (let [[t rows] @diff-rows-memo]
    (if (and rows (= t text))
      rows
      (let [rows (diff/diff-rows (diff/parse-diff-text text))]
        (reset! diff-rows-memo [text rows])
        rows))))

(defn- diff-line-view
  "Render a single selectable diff line with syntax highlighting. Tapping a
   code line dispatches :diff/select-line with its selection index. `highlight?`
   adds an emphasis class (agent-spotlighted lines); `reviewed?` marks lines the
   user has already added to a review (both used by the review canvas)."
  ([dispatch! grammar selected? line sel-idx row-key]
   (diff-line-view dispatch! grammar selected? line sel-idx row-key false false))
  ([dispatch! grammar selected? line sel-idx row-key highlight?]
   (diff-line-view dispatch! grammar selected? line sel-idx row-key highlight? false))
  ([dispatch! grammar selected? {:keys [type text old-line new-line] :as line} sel-idx row-key highlight? reviewed?]
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
        (diff-text-code grammar line)
        (or text ""))]])))

(def ^:private diff-groups-cache
  "rows vector → its per-file groups (each starting with a :file row)."
  (js/WeakMap.))

(defn- diff-file-groups [rows]
  (when (seq rows)
    (or (.get diff-groups-cache rows)
        (let [groups (reduce (fn [groups r]
                               (if (= :file (:row r))
                                 (conj groups [r])
                                 (update groups (dec (count groups)) conj r)))
                             [] rows)]
          (.set diff-groups-cache rows groups)
          groups))))

(def ^:private diff-md-cache
  "file group → its rendered markdown diff (diff-group-md-body), or false."
  (js/WeakMap.))

(defn- diff-group-md-body
  "md-diff-body of a file group's hunks (rebuilt from its :hunk / :line
   rows), memoized per group; nil when no rendered block changed."
  [group]
  (let [cached (.get diff-md-cache group)]
    (if (some? cached)
      (when cached cached)
      (let [hunks (->> (rest group)
                       (partition-by #(= :hunk (:row %)))
                       (remove #(= :hunk (:row (first %))))
                       (mapv #(into [] (comp (filter (fn [r] (= :line (:row r))))
                                             (map :line)
                                             (remove (fn [l] (= :meta (:type l)))))
                                    %)))
            body  (md-diff-body hunks)]
        (.set diff-md-cache group (or body false))
        body))))

(def ^:private diff-body-cache
  "file group → [dispatch! range shown body-hiccup]. A file's body only depends on
   the selection when the range touches it, so unrelated files keep returning
   the identical hiccup and Replicant skips diffing their thousands of nodes."
  (js/WeakMap.))

(defn- diff-group-range
  "`range` if it overlaps the selectable lines of `group`, else nil."
  [group range]
  (when range
    (let [lo (some :sel-idx group)
          hi (some :sel-idx (rseq group))]
      (when (and lo (<= (first range) hi) (<= lo (second range)))
        range))))

(def ^:private diff-row-budget
  "Body rows (code lines + hunk headers) a limited diff view renders up front,
   shared across files in order. A 50k-line diff is ~650k DOM nodes, which
   froze the browser; the rest sits behind each file's \"Show more\" button."
  1500)

(defn- diff-visible-rows
  "Per-file cap on rendered body rows: a vector parallel to `groups` holding a
   row count, or nil for files rendered in full (folded, or in `expanded`).
   Files draw from `budget` in order, so the first files of a huge diff show
   their top and the rest start closed."
  [groups collapsed expanded budget]
  (loop [gs (seq groups) left budget out []]
    (if-let [g (first gs)]
      (let [fname (:filename (first g))
            n     (dec (count g))]
        (if (or (contains? collapsed fname) (contains? expanded fname) (<= n left))
          (recur (next gs)
                 (if (or (contains? collapsed fname) (contains? expanded fname)) left (- left n))
                 (conj out nil))
          (recur (next gs) 0 (conj out left))))
      out)))

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
   :diff/toggle-file to fold/unfold their body;
   :expanded — a set of filenames shown in full. When this opt is present
   (even as an empty set) the view is size-limited (diff-row-budget): files
   past the budget are cut off behind a button dispatching :diff/show-all;
   :md-code — the :web/md-diff-code set. When this opt is present (even as an
   empty set) markdown files render as a rendered markdown diff, with a
   Rendered ⇄ Code toggle in their header (key [:diff filename])."
  ([dispatch! rows range toolbar] (diff-rows-view dispatch! rows range toolbar nil))
  ([dispatch! rows range toolbar {:keys [highlight reviewed line-suffix collapsed expanded md-code]}]
  (let [grammar-cache (atom {})
        grammar-for (fn [f] (or (@grammar-cache f)
                                (let [g (diff-file-grammar f)]
                                  (swap! grammar-cache assoc f g) g)))
        file-groups (diff-file-groups rows)
        ;; Highlight / reviewed / line-suffix are per-render closures from the
        ;; review canvas, so only the plain diff viewer caches file bodies.
        cache-body? (not (or highlight reviewed line-suffix))
        visible     (if (some? expanded)
                      (diff-visible-rows file-groups collapsed expanded diff-row-budget)
                      (vec (repeat (count file-groups) nil)))]
    [:div {:class ["diff-view"]}
     (if (seq file-groups)
       (map-indexed
        (fn [fi group]
          (let [{:keys [filename status]} (first group)
                shown (nth visible fi)
                status-label (case status
                               :added "added" :deleted "deleted"
                               :renamed "renamed" :binary "binary" nil)
                body-rows (rest group)
                collapse?  (some? collapsed)
                folded?    (boolean (and collapsed (contains? collapsed filename)))
                md-key     [:diff filename]
                md-body    (when (and md-code (md-diff/markdown-path? filename))
                             (diff-group-md-body group))
                md-code?   (contains? md-code md-key)]
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
                 status-label])
              (when md-body
                (md-diff-toggle dispatch! md-key md-code?))]
             (when (and md-body (not md-code?) (not folded?))
               [:div {:class ["diff-file-md"]} md-body])
             (when-not (or folded? (and md-body (not md-code?)))
               (let [file-range (diff-group-range group range)
                     [c-dispatch c-range c-shown c-body] (when cache-body? (.get diff-body-cache group))]
                 (if (and c-body (identical? c-dispatch dispatch!) (= c-range file-range)
                          (= c-shown shown))
                   c-body
                   (let [body
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
               (if shown (take shown body-rows) body-rows))
              (when shown
                (let [hidden (count (filter #(= :line (:row %)) (drop shown body-rows)))]
                  (when (pos? hidden)
                    [:div {:class ["diff-show-more"] :replicant/key (str "more" fi)}
                     (button/button
                      {:variant :ghost :size :sm
                       :on-click (fn [_] (dispatch! {:type :diff/show-all :filename filename}))}
                      (str "Show " hidden " more line" (when (not= 1 hidden) "s")))])))]]
                     (when cache-body?
                       (.set diff-body-cache group #js [dispatch! file-range shown body]))
                     body))))]))
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
        method  (cond
                  commit                 (str "commit:" (:sha commit))
                  (:source diff-buffer)  (:source diff-buffer)
                  :else                  (diff-method-for-title (:title diff-buffer)))
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
   (red = removed, green = added). Markdown files in a git diff render as a
   rendered markdown diff unless toggled to code (`md-code`, the
   :web/md-diff-code set)."
  [dispatch! room-id diff-buffer sel modify? collapsed expanded md-code]
  (let [engine (or (:engine diff-buffer) :git)
        git?   (not= engine :difft)
        rows   (when git? (diff-rows-for-text (:text diff-buffer)))
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
                         {:collapsed collapsed :expanded (or expanded #{})
                          :md-code (or md-code #{})})))]))

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
     (if (contains? md-diff/markdown-exts ext)
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

(defn buffer-icon
  "The icon of a buffer kind (xi.buffers) wherever buffers are listed: the
   buffer menu, the sidebar rows, the palette."
  [kind]
  (case kind
    :diff   :code
    :file   :file-text
    :prompt :terminal
    :list))

(defn- text-tab-view
  "Any other text buffer (the event log, the shortcut list — buffers the TUI
   opens that mirror into the room) as a plain read-only view."
  [id {:keys [text] :as buf}]
  [:div {:class ["file-tab"]}
   [:div {:class ["file-tab-header"]}
    [:span {:class ["file-tab-path"]} (buffers/label id buf)]]
   [:pre {:class ["file-tab-code"]} (or text "")]])

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


(defn menu-button
  "Framework hamburger toggle that opens the recent-sessions drawer. Lives on
   every topbar; toggles the :web/sidebar-open? app state."
  [dispatch!]
  (sidebar/sidebar-mobile-toggle
   {:on-click (fn [_] (dispatch! {:type :sidebar/toggle}))
    :attrs    {:data-key-action "sidebar/toggle"}}))

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

(defn- buffer-menu
  "The topbar's view switch, shown once the room has a buffer (xi.buffers) or
   a canvas: one frosted pill naming the view in front (Chat, or the open
   buffer's title) with a chevron, opening a popover that lists the chat,
   every buffer in opening order (× closes one — for everyone in the room,
   the list is shared), the Canvas page once a review exists, and \"Close
   all buffers\". Switching is this client's own (:ui/buffer-switch stays
   local); a virtual new chat keeps its buffers on the pending room
   (:pending/buffer-switch)."
  [dispatch! room-id active-buffer buffers canvas? people-of]
  (let [switch! (fn [buffer-id]
                  (dispatch! {:type :diff/clear-selection})
                  (dispatch! (if room-id
                               {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id}
                               {:type :pending/buffer-switch :buffer-id buffer-id})))
        close!  (fn [buffer-id]
                  (dispatch! (if room-id
                               (if buffer-id
                                 {:type :ui/buffer-close :room-id room-id :buffer-id buffer-id}
                                 {:type :ui/buffers-close-all :room-id room-id})
                               {:type :pending/buffer-close :buffer-id buffer-id})))
        hide!   (fn [^js e]
                  (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover)))
        active  (get buffers active-buffer)
        kind    (when active (buffers/kind active-buffer active))
        label   (if active (buffers/label active-buffer active) "Chat")
        menu-id "buffer-menu"
        item    (fn [{:keys [id icon title active? people on-click on-close key]}]
                  [:button {:class ["sidebar-more-item" "buffer-menu-item"
                                    (when active? "buffer-menu-item--active")]
                            :replicant/key (or key id)
                            :title title
                            :on {:click (fn [e] (hide! e) (on-click))}}
                   (icon/icon {:icon-name icon :size :sm})
                   [:span {:class ["buffer-menu-title"]} title]
                   ;; buffer presence: who else is on this view
                   (when (seq people)
                     [:span {:class ["buffer-menu-people"]} (avatar-stack people)])
                   (when on-close
                     [:span {:class ["buffer-menu-close"]
                             :role "button" :title "Close buffer"
                             :on {:click (fn [^js e]
                                           (.stopPropagation e)
                                           (on-close))}}
                      (icon/icon {:icon-name :x :size :sm})])])]
    (list
     [:button (merge {:class ["tab-pill" "buffer-menu-trigger"]
                      :title "Switch view"
                      :replicant/key "buffer-menu-trigger"}
                     (popover/trigger-attrs menu-id))
      (when kind (icon/icon {:icon-name (buffer-icon kind) :size :sm}))
      [:span {:class ["buffer-menu-label"]} label]
      (icon/icon {:icon-name :chevron-down :size :sm})]
     (popover/popover-content
      {:id    menu-id
       :side  :bottom
       :align :center
       :class "sidebar-more-menu buffer-menu"
       :attrs {:replicant/key "buffer-menu"}}
      (item {:id "chat" :icon :terminal :title "Chat"
             :active? (not active) :people (people-of :chat)
             :on-click #(switch! :chat)})
      (for [[id buf] (buffers/ordered buffers)
            :let [title (buffers/label id buf)]]
        (item {:id (str id) :icon (buffer-icon (buffers/kind id buf)) :title title
               :active? (= id active-buffer)
               :people (people-of id)
               :on-click #(switch! id)
               :on-close #(close! id)}))
      (when canvas?
        (item {:id "canvas" :icon :layout-dashboard :title "Canvas"
               :on-click #(dispatch! {:type :canvas-review/open-page :room-id room-id})}))
      (when (seq buffers)
        (list
         [:div {:class ["overflow-menu-divider"] :replicant/key "buffer-menu-divider"}]
         (item {:id "close-all" :icon :x
                :title (str "Close all buffers (" (count buffers) ")")
                :on-click #(close! nil)})))))))

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
                   :data-key-action "chat/new"
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

(defn- presence-line
  "Who else is in this room: the other users attached right now (room
   :members, kept current by :room/presence). Nothing when we are alone — the
   common single-user case stays as quiet as before."
  [state room]
  (let [me       (state/own-user state)
        profiles (get-in state [:lobby :profiles])
        others   (->> (state/room-users room)
                      (remove #{me})
                      (mapv (fn [id] (assoc (get profiles id) :id id))))]
    (when (seq others)
      [:span {:class ["topbar-presence"]
              :title (str "Also here: " (str/join ", " (map #(or (:name %) (:id %)) others)))}
       (avatar-stack others)
       [:span (str/join ", " (map #(or (:name %) (:id %)) others))]])))

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
   tapped: Copy, plus View file when the block belongs to a tool call that
   names a file (see tool-file-path; opens it in the room's :file buffer tab
   via :file/open),
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

(def ^:private timeline-item-cache
  "History entry → [ctx items] of its last timeline render (see
   memo-timeline-items). Weak, so entries that leave the history are dropped."
  (js/WeakMap.))

(defn- memo-timeline-items
  "chat-view's timeline items for history `entry`: `(build)`, reused while
   `ctx` (everything besides the entry the post reads) is unchanged. Typing
   re-renders the whole app; the identical post hiccup lets Replicant skip each
   unchanged post instead of rebuilding and deep-comparing all of them, which
   on a long chat scrolled far back was most of every keystroke. A nil `ctx`
   always rebuilds."
  [entry ctx build]
  (if (nil? ctx)
    (build)
    (let [[c items :as hit] (.get timeline-item-cache entry)]
      (if (and hit (= c ctx))
        items
        (let [items (build)]
          (.set timeline-item-cache entry [ctx items])
          items)))))

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
        ;; A joined room whose session was never persisted (no provider/CLI
        ;; id: a new chat nobody has prompted yet, reopened by its URL or the
        ;; sidebar) has no history to wait for — a disk resume always carries
        ;; one of the ids. Without this it spun on "Connecting…" forever.
        fresh?  (and (some? room)
                     (not (get-in room [:session :cli-session-id]))
                     (not (get-in room [:session :provider-session-id])))
        ;; The server's history for an existing session is still in flight
        ;; (nothing optimistic/pending to show in the meantime).
        resuming? (and (not new?)
                       (not authoritative?)
                       (not fresh?)
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
        active-buffer (get buffers active-buf)
        buffer-kind (when active-buffer (buffers/kind active-buf active-buffer))
        canvas?    (boolean (seq (get-in room [:ext :canvas-review :diff])))
        has-tabs?  (boolean (or (seq buffers) canvas?))
        ;; Prompt navigation over the FULL history (not just the rendered
        ;; window): collect every user entry's absolute history index so we can
        ;; jump to prompts scrolled off the top, expanding the window on demand.
        nav-ctx (prompt-nav-ctx state history)
        ;; Files dropped anywhere on the view stage on the composer — only
        ;; while it shows (the chat buffer, no skill form or deny-reason box
        ;; in its place), so a drop never lands on an invisible draft.
        deny-reason (:web/deny-reason state)
        drop-zone? (and (nil? buffer-kind)
                        (not (string? (:text active-buffer)))
                        (not (:web/skill-form state))
                        (not (and room (= (:room-id deny-reason) (:id room))
                                  (some #(= (:dialog-id deny-reason) (:id %))
                                        (get-in room [:ui :dialogs])))))
        file-drag? (and drop-zone? (:web/file-drag? state))]
    [:div (cond-> {:class ["container" "chat-drop-zone"] :replicant/key "chat"}
            drop-zone? (assoc :on (file-drop-attrs dispatch! dkey file-drag?)))
     (file-drop-overlay dispatch! file-drag?)
     [:div {:class ["topbar" "topbar--chat"]}
      (menu-button dispatch!)
      [:div {:class ["topbar-title"]}
       ;; Cached history is already painted; the server's copy is still in
       ;; flight. Show a quiet inline hint instead of blanking to a spinner.
       (when (and resuming? (seq history))
         [:span {:class ["topbar-updating"]}
          (spinner) [:span "Updating…"]])
       (presence-line state room)]
      (offline-badge state)
      (when has-tabs?
        (buffer-menu dispatch! (:id room) active-buf buffers canvas?
                     ;; buffer presence: the other users on each view
                     (let [viewers (buffers/viewers room)]
                       (fn [id] (sb/room-people state (get viewers id))))))
      (overflow-menu dispatch! state (when room {:mode :room :room-id (:id room)}))]
     ;; The view follows the open buffer's kind (xi.buffers); no buffer (or an
     ;; id the room no longer holds) is the chat.
     (case buffer-kind
       :diff
       (diff-tab-view dispatch! (:id room) active-buffer
                      (:web/diff-sel state)
                      (:web/diff-modify? state)
                      (:web/diff-collapsed state)
                      (:web/diff-expanded state)
                      (:web/md-diff-code state))

       :file
       (file-tab-view active-buffer)

       :prompt
       (prompt-tab-view dispatch! room (or (:web/prompt-expanded state) #{}))

       (if (and active-buffer (string? (:text active-buffer)))
         (text-tab-view active-buf active-buffer)
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
                               cwd      (or (:cwd room) (get-in state [:web/pending-room :cwd]))
                               ;; other users' prompts get their avatar on
                               ;; the last bubble of a run
                               sender   (when (and (= :user (:kind entry))
                                               (last-of-run? entries p))
                                          (other-user state (:user entry)))
                               tool?    (= :tool-call (:kind entry))
                               md-code? (contains? (:web/md-diff-code state) (:id entry))
                               editing? (and (= :user (:kind entry)) (= p (:index editing)))
                               resolved (resolved-by-tool (:id entry))]
                           (memo-timeline-items
                            entry
                            ;; Everything below reads besides the entry; nil (the
                            ;; post with the pending ask carries fresh answer fns)
                            ;; always rebuilds.
                            (when-not (= p perm-tool-idx)
                              [p dispatch! cwd (:id room) sender groupable? collapsed?
                               (when tool?
                                 (tool-views/inputs (util/strip-mcp-prefix (:tool entry)) (:ext room)))
                               md-code? (when editing? [(:text editing)]) resolved])
                            (fn []
                              (when-let [post (entry->post
                                               dispatch!
                                               (cond-> (assoc entry :history-index p
                                                              :cwd cwd
                                                              :room-id (:id room)
                                                              :sender sender)
                                                 groupable?
                                                 (assoc :grouped? true)
                                                 collapsed?
                                                 (assoc :collapsed? true)
                                                 ;; extension tool views read their room slice
                                                 tool?
                                                 (assoc :room-ext (:ext room))
                                                 ;; markdown diff toggled to its code view
                                                 md-code?
                                                 (assoc :md-diff-code? true)
                                                 editing?
                                                 (assoc :editing? true :edit-text (:text editing))
                                                 (= p perm-tool-idx)
                                                 (assoc :permission {:dialog pending-dialog
                                                                     :answer! perm-answer!
                                                                     :deny-reason!
                                                                     #(dispatch! {:type :deny-reason/start
                                                                                  :room-id (:id room)
                                                                                  :dialog-id (:id pending-dialog)})})
                                                 resolved
                                                 (assoc :resolved-permission resolved)))]
                                [{:key (str "h-" p)
                                  :group? groupable?
                                  :collapsed? collapsed?
                                  :info (when groupable? (block-info entry cwd))
                                  :node (with-post-key (str "h-" p) post)}])))))))
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
         ;; Deny with reason (⋯ beside Deny) holds the dock only while that
         ;; ask is still pending here — answered elsewhere, the composer is back.
         (let [deny-reason (:web/deny-reason state)
               deny-dialog (when (and room (= (:room-id deny-reason) (:id room)))
                             (some #(when (= (:dialog-id deny-reason) (:id %)) %)
                                   (get-in room [:ui :dialogs])))]
           (cond
             deny-dialog
             (deny-reason-compose dispatch! deny-reason deny-dialog)

             (:web/skill-form state)
             (skill-form-compose dispatch! (:web/skill-form state))

             :else
             (compose-box dispatch!
                          ;; queued prompts from other users show their avatar
                          (update-in room [:agent :queued]
                                     (fn [queued]
                                       (mapv #(assoc % :sender (other-user state (:user %)))
                                             queued)))
                          busy? (get-in state [:web/compose-images dkey])
                          dkey (get-in state [:web/drafts dkey]) sid
                          (:web/cmd-selected state)
                          (get-in state [:lobby :agent-id])
                          (:web/queue-popover? state)
                          (:web/prompt-nav state)
                          nav-ctx
                          (:web/scrolled-up? state)
                          (false? (:web/connected? state)))))])))]))

;; ── Home view ────────────────────────────────────────────────────────────────

(defn- with-projects
  "Tag session cards to display their owning project in the description line.
   Used by cross-project listings (All sessions, the sidebar) so a
   session shows which project it belongs to; the per-project view omits it."
  [cards]
  (map #(assoc % :show-project? true) cards))

(defn- ext-session-menu-item
  "ui.context-menu entry for an extension's `:session-menu-items` declaration
   (see xi.ext.core), for the session card `card`: its :event with the card's
   :session-id merged in, labelled :label-on while the card's :flag key is set."
  [dispatch! {:keys [session-id] :as card}
   {:keys [label label-on flag icon event]}]
  {:label    (if (and label-on (get card flag)) label-on label)
   :icon     icon
   :on-click #(dispatch! (assoc event :session-id session-id))})

(defn- session-menu-items
  "ui.context-menu entries for a session card: the extensions' items
   (see `:session-menu-items`), pin/unpin (a pinned session never ages out of
   Recent and survives bulk cleanups), hide/show in Recent (only
   where the caller opts in via :dismissable?), copy session ID, and Delete.
   Hiding is gated on idle — a busy card or one awaiting a dialog response
   can't be dismissed. Delete is always offered: the server keeps a busy room
   alive and just suppresses its card (see room_manager/session-delete)."
  [dispatch! ext-items {:keys [session-id dismissed? pinned? dismissable? busy? has-dialog?] :as card}]
  (let [idle? (not (or busy? has-dialog?))]
    (cond-> (mapv #(ext-session-menu-item dispatch! card %) ext-items)
      true
      (conj {:label    (if pinned? "Unpin session" "Pin session")
             :icon     :bookmark
             :on-click #(dispatch! {:type :pinned/toggle :session-id session-id})})
      (and dismissable? idle?)
      (conj {:label    (if dismissed? "Show in recent" "Hide from recent")
             :icon     (if dismissed? :eye :eye-off)
             :on-click #(dispatch! {:type :dismissed/toggle :session-id session-id})})
      true
      (conj {:label    "Copy session ID"
             :icon     :copy
             :on-click #(copy! dispatch! session-id)})
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

(defn- session-buffer-rows
  "The buffers open for a session (xi.buffers; `:buffers` on the card, from
   the lobby — live room or parked), listed under its card: one row per
   buffer, kind icon and title, × to close it (for everyone; the list is
   shared). A row navigates to the session and opens that buffer
   (:route/navigate with :buffer-id). Shown for the session in view, and for
   when its \"N buffers\" line was clicked (:web/sidebar-buffers-open, kept per
   browser)."
  [dispatch! state {:keys [session-id buffers current? viewers]}]
  (let [room   (state/active-room state)
        active (when (and current? (= session-id (get-in room [:session :id])))
                 (get-in room [:ui :active-buffer]))]
    [:div {:class ["session-buffers"] :replicant/key (str "buffers-" session-id)}
     (for [{:keys [id kind title]} buffers
           :let [people (sb/room-people state (get viewers id))]]
       [:button {:class ["session-buffer-row" (when (= id active) "session-buffer-row--active")]
                 :replicant/key (str id)
                 :title title
                 :on {:click (fn [^js e]
                               (.stopPropagation e)
                               (dispatch! {:type :route/navigate :page :chat
                                           :session-id session-id :buffer-id id}))}}
        (icon/icon {:icon-name (buffer-icon kind) :size :sm})
        [:span {:class ["session-buffer-title"]} title]
        ;; buffer presence: who else is on it
        (when (seq people)
          [:span {:class ["session-buffer-people"]} (avatar-stack people)])
        [:span {:class ["session-buffer-close"]
                :role "button" :title "Close buffer"
                :on {:click (fn [^js e]
                              (.stopPropagation e)
                              (dispatch! {:type :session/buffer-close
                                          :session-id session-id :buffer-id id}))}}
         (icon/icon {:icon-name :x :size :sm})]])]))

(defn- session-card
  "Session row. Secondary actions (the extensions', hide from
   Recent, delete) live in a ui.context-menu on the card: right-click,
   long-press on touch (the framework's gesture runtime), or the ⋮ button.
   A session with open buffers says so in its subline (\"3 buffers\", a
   click unfolds them) and lists them under the card (session-buffer-rows)."
  [dispatch! state {:keys [session-id name cwd timestamp current? active? busy? has-dialog? error? unread? show-project? people buffers pinned?]
              :as card-data}]
  (let [n-buffers (count buffers)
        ;; unfolded while this browser toggled it open (never automatically)
        buffers-open? (and (pos? n-buffers)
                           (contains? (:web/sidebar-buffers-open state) session-id))
        card
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
                       error?  "Last turn failed"
                       unread? "Unread responses"
                       active? "Live on the server"
                       :else   nil)}
    (cond
      has-dialog? (icon/icon {:icon-name :alert-circle :size :sm})
      :else       (message-circle-icon))
    (card-status-indicator {:busy? busy? :error? error? :unread? unread? :active? active?})]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]}
     (when pinned?
       (icon/icon {:icon-name :bookmark :class "project-card-pin" :attrs {:title "Pinned"}}))
     (or name "New session")]
    [:span {:class ["project-card-path"]}
     (->> [(when (and show-project? cwd) (shorten-path cwd))
           ;; "just now" is noise; only a real age (3m ago) is useful.
           (let [rel (format-relative-time timestamp)]
             (when-not (= rel "just now") rel))
           (when has-dialog? "needs response")]
          (remove str/blank?)
          (str/join " · "))
     (when (pos? n-buffers)
       (list
        " · "
        [:span {:class ["project-card-buffers" (when buffers-open? "project-card-buffers--open")]
                :role "button"
                :title (if buffers-open? "Hide the buffers" "Show the buffers")
                :on {:click (fn [^js e]
                              (.stopPropagation e)
                              (dispatch! {:type :sidebar/buffers-toggle :session-id session-id}))}}
         (str n-buffers (if (= 1 n-buffers) " buffer" " buffers"))]))]]
   ;; One slot at the card's edge: who is in the room right now (multi-user
   ;; servers only), swapped for the ⋮ on hover so neither pushes the other.
   [:div {:class ["project-card-trail"]}
    (avatar-stack people)
    ;; ⋮ opens the same context menu as right-click / long-press.
    (when session-id
      [:button {:class ["project-card-action" "session-more-btn"]
                :title "More actions"
                :on {:click open-card-menu!}}
       (more-vertical-icon)])]]]
    (list
     (if session-id
       ;; The trigger wraps the card (rather than being it) so the card keeps
       ;; its own click handler — the trigger's :attrs would replace it.
       (context-menu/context-menu-trigger
        {:items (session-menu-items dispatch! (:web/session-menu-items state) card-data)
         :class "project-card-trigger"
         ;; data-session-id: :sidebar/animate-leave finds the row to play
         ;; its leave transition on when the session is deleted
         :attrs {:replicant/key session-id
                 :data-session-id session-id
                 :data-flip (str "s:" session-id)}}
        card)
       card)
     (when buffers-open?
       (session-buffer-rows dispatch! state card-data)))))

(defn- draft-chat-card
  "Sidebar row for a parked draft chat (see router/stash-draft-chat): the first
   line of its unsent prompt, the project it would run in. Click resumes it;
   the trailing x discards it with its text."
  [dispatch! state {:keys [id cwd]}]
  (let [text (some->> (str/split-lines (get-in state [:web/drafts id] ""))
                      (some #(when-not (str/blank? %) (str/trim %))))]
    [:div {:class ["project-card"]
           :replicant/key (str "draft-" id)
           :data-flip (str "draft:" id)
           :title text
           :on {:click (fn [_] (dispatch! {:type :draft-chat/open :id id}))}}
     [:div {:class ["project-card-icon" "project-card-icon--idle"]}
      (icon/icon {:icon-name :edit :size :sm})]
     [:div {:class ["project-card-info"]}
      [:span {:class ["project-card-name"]} text]
      (when cwd
        [:span {:class ["project-card-path"]} (shorten-path cwd)])]
     [:button {:class ["project-card-action"]
               :title "Discard draft"
               :on {:click (fn [^js e]
                             (.stopPropagation e)
                             (dispatch! {:type :draft-chat/discard :id id}))}}
      (icon/icon {:icon-name :x :size :sm})]]))

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
      (menu-button dispatch!)
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
           (session-card dispatch! state o))
         (for [s (active-first state sessions)]
           (session-card dispatch! state s))])]]))



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
      (menu-button dispatch!)
      [:div {:class ["topbar-title"]} "All sessions"]
      (when connected?
        [:button {:class ["icon-btn"]
                  :data-key-action "chat/new"
                  :on {:click (fn [_] (dispatch! {:type :room/new}))}}
         (icon/icon {:icon-name :plus :size :md})])
      (overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (search-box dispatch! :all-sessions "Search sessions\u2026" raw-query content?)
      (cond
        (or (seq sessions) (seq orphans))
        [:div {:class ["project-list"]}
         (for [o (with-projects orphans)]
           (session-card dispatch! state o))
         (for [s (with-projects (active-first state sessions))]
           (session-card dispatch! state s))]

        (seq query)
        (empty-state/empty-state {} [:p "No matching sessions."])

        :else
        (empty-state/empty-state {} [:p "No sessions yet."]))]]))

(defn- personal-agent-home-view
  "Home view for personal-agent mode: a flat session list with no project
   navigation (the personal agent has no projects). Supports name/content
   search."
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
                  :data-key-action "chat/new"
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
              (session-card dispatch! state o))
            (for [s (active-first state sessions)]
              (session-card dispatch! state s))]

           (seq query)
           (empty-state/empty-state {} [:p "No matching sessions."])

           :else
           (empty-state/empty-state {} [:p "No sessions yet."]))])]]))

(defn- home-view [state dispatch!]
  (let [selected-dir (:web/selected-project-dir state)]
    (cond
      (get-in state [:lobby :agent-id])
      (personal-agent-home-view state dispatch!)

      (= selected-dir :all)
      (all-sessions-view state dispatch!)

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
                      :data-key-action "chat/new"
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
                  (session-card dispatch! state s))]
               (empty-state/empty-state {} [:p "No matching sessions."]))]

            :else
            [:div
             (search-box dispatch! :home "Search projects…" raw-query content?)
             [:div {:class ["project-list"]}
              ;; Active orphan rooms first (hide when filtering)
              (when-not (seq query)
                (for [r orphans]
                  (session-card dispatch! state {:session-id (:session-id r)
                                          :name (or (:session-name r) "New session")
                                          :cwd (:cwd r)
                                          :show-project? true
                                          :active? true :busy? (:busy? r)
                                          :has-dialog? (:has-dialog? r)
                                          :error? (:error? r)
                                          :people (sb/room-people state (:users r))})))
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
   on an ancestor (see .claude-usage--*). A nil percent draws the bare track."
  [pct]
  (let [circumference 94.25] ; 2πr for r=15
    [:svg {:class ["claude-usage-ring"] :viewBox "0 0 36 36" :aria-hidden "true"}
     [:circle {:class ["claude-usage-ring-track"] :cx "18" :cy "18" :r "15"}]
     (when pct
       [:circle {:class ["claude-usage-ring-fill"] :cx "18" :cy "18" :r "15"
                 :stroke-dasharray (str (* circumference pct 0.01) " " circumference)
                 :transform "rotate(-90 18 18)"}])]))

(defn- usage-window-outdated?
  "True once a usage window's reset time has passed: the window rolled over,
   so the cached percent no longer describes it."
  [resets-at]
  (when resets-at
    (let [ms (.getTime (js/Date. resets-at))]
      (and (not (js/isNaN ms)) (<= ms (js/Date.now))))))

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
                                  :else (str "in " h " h " rm " min"))))))]
      (let [outdated? (usage-window-outdated? session-resets-at)
            severity  (if outdated? "unknown" severity)
            weekly    (when-not (usage-window-outdated? weekly-resets-at) weekly)
            weekly-severity (cond (nil? weekly) "normal"
                                  (>= weekly 100) "exceeded"
                                  (>= weekly 90) "critical"
                                  (>= weekly 75) "warning"
                                  :else "normal")]
        (list
         [:button (merge {:class ["sidebar-usage-trigger" (str "claude-usage--" severity)]
                          :title (if outdated?
                                   "Claude usage: unavailable"
                                   (str "Claude usage: session " session "%"
                                        (when weekly (str " · week " weekly "%"))))
                          :replicant/key "sidebar-usage-trigger"}
                         (popover/trigger-attrs "sidebar-usage"))
          (claude-usage-ring (when-not outdated? session))
          [:span {:class ["sidebar-usage-pct"]} (if outdated? "?" (str session "%"))]]
         (popover/popover-content
          {:id    "sidebar-usage"
           :side  :top
           :align :start
           :class "claude-usage"
           :attrs {:replicant/key "sidebar-usage"}}
          [:div {:class ["claude-usage-title"]} "Claude usage"]
          (if outdated?
            [:div {:class ["claude-usage-note"]}
             "Unavailable — the last reading is out of date and could not be refreshed."]
            (list
             (claude-usage-meter "Session" session severity
                                 (when session-reset
                                   (str "Resets " session-reset
                                        (when resets-in (str " · " resets-in)))))
             (when weekly
               (claude-usage-meter "Week" weekly weekly-severity
                                   (when weekly-reset (str "Resets " weekly-reset))))))))))))

(defn- switch-user!
  "Act as user `id` from this browser: claim it (localStorage xi-user, sent in
   :auth/hello) and reconnect. A device the server assigns a user to
   (`xi clients user`) keeps that user. Unauthenticated by design; see
   xi.server.ws."
  [id]
  (try (.setItem js/localStorage "xi-user" id) (catch :default _ nil))
  (.reload js/location))

(defn- user-switcher
  "The sidebar footer's user avatar and the popover to switch user. Only when
   the server has more than one user to be (config :users plus root)."
  [state]
  (let [ids (get-in state [:lobby :user-ids])]
    (when (> (count ids) 1)
      (let [me       (state/own-user state)
            profiles (get-in state [:lobby :profiles])
            person   (fn [id] (assoc (get profiles id) :id id))
            label    (fn [p] (or (:name p) (:id p)))
            close!   (fn [^js e]
                       (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover)))]
        (list
         [:button (merge {:class ["sidebar-footer-btn" "sidebar-user-trigger"]
                          :title (str "Signed in as " (label (person me)))
                          :replicant/key "sidebar-user-trigger"}
                         (popover/trigger-attrs "sidebar-user-menu"))
          (user-avatar (person me))]
         (popover/popover-content
          {:id    "sidebar-user-menu"
           :side  :top
           :align :start
           :class "sidebar-more-menu"
           :attrs {:replicant/key "sidebar-user-menu"}}
          (for [id ids
                :let [p (person id)]]
            [:button {:class         ["sidebar-more-item" "sidebar-user-item"]
                      :replicant/key (str "user-" id)
                      :on            {:click (fn [e]
                                               (close! e)
                                               (when (not= id me) (switch-user! id)))}}
             (user-avatar p)
             [:span (label p)]
             (when (= id me)
               (icon/icon {:icon-name :check :size :sm}))])))))))

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
      :attrs {:replicant/key  (str "section-" (subs (str id) 1))
              :data-collapsed (when-not open? "true")}
      :label [:button {:class ["sidebar-section-toggle"]
                       :data-flip (str "g:" id)
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

(defn session-cleanups
  "Cleanup actions that currently apply, given the sidebar session groups —
   shared by the sidebar ⋯ menu, the command palette and the ALT+Shift+P shortcut. Each is
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
        ;; Groups extensions declare (:sidebar-groups).
        ext-groups (when render?
                     (sb/extension-groups state (:web/sidebar-groups state)))
        cards    (concat recent hidden earlier)
        drafts   (:web/draft-chats state)
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
               :attrs     {:replicant/key "all-projects" :data-flip "all-projects"}
               :on-click  (fn [_] (dispatch! {:type :route/navigate :page :home}))}
              "All projects")))
         ;; New chats left with a prompt typed into them (client-local, not a
         ;; session yet) so switching away doesn't lose the text.
         (when (seq drafts)
           (section {:id :drafts :label "Drafts"}
             (for [d (rseq drafts)]
               (draft-chat-card dispatch! state d))))
         ;; Extension groups (:sidebar-groups). Their sessions also show in
         ;; their time group below.
         (for [{:keys [id label cards total more]} ext-groups]
           (section {:id id :label label}
             (for [c (with-projects cards)]
               (session-card dispatch! state c))
             (when (and more (> total (count cards)))
               (sidebar/sidebar-menu-item
                {:icon-name (:icon more)
                 :class     "sidebar-row"
                 :attrs     {:replicant/key (str "more-" (subs (str id) 1))
                             :data-flip (str "more-" (subs (str id) 1))}
                 :on-click  (fn [_] (dispatch! (:event more)))}
                (:label more)))))
         (when (seq recent)
           (section {:id :recent :label "Recent"}
             (for [c (with-projects recent)]
               (session-card dispatch! state (assoc c :dismissable? true)))))
         ;; Hidden group sits between Recent and Earlier. Its cards keep the
         ;; toggle (now an eye → "Show in recent") so the user can restore them.
         (when (seq hidden)
           (section {:id :hidden :label "Hidden"}
             (for [c (with-projects hidden)]
               (session-card dispatch! state (assoc c :dismissable? true)))))
         (when (seq earlier)
           (section {:id :earlier :label "Earlier"}
             (for [c (with-projects earlier)]
               (session-card dispatch! state c))))
         ;; Entries contributed by extensions (:nav-items with :menu :sidebar,
         ;; e.g. Image Graphs) — last, below the core session groups.
         (let [ext-items (when (not pa?) (nav-items-for state :sidebar))]
           (when (seq ext-items)
             (section {:id :extensions :label "Extensions"}
               (for [item ext-items]
                 (sidebar/sidebar-menu-item
                  {:icon-name (:icon item)
                   :class     "sidebar-row"
                   ;; :badge-path — a state path whose positive number shows as
                   ;; a badge (an unread count); see nav-badge
                   :badge     (nav-badge state item)
                   :attrs     {:replicant/key (str "nav-" (:label item))
                               :data-flip (str "nav-" (:label item))}
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
           (user-switcher state)
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
                (:web/draft-chats state)
                (:web/project-dirty state)
                (:web/theme-mode state)
                (:web/nav-items state)
                ;; the badges of :badge-path nav items (nav-badge)
                (mapv #(nav-badge state %) (:web/nav-items state))
                (:web/sidebar-groups state)
                (:web/session-menu-items state)
                (state/own-user state)
                ;; the per-session buffer rows (session-buffer-rows): which
                ;; cards are unfolded and which buffer the viewed room shows
                (:web/sidebar-buffers-open state)
                (get-in (state/active-room state) [:ui :active-buffer])]
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
      (menu-button dispatch!)
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
                        (diff-rows-for-text text)
                        nil nil {:expanded (or (:web/diff-expanded state) #{})
                                 :md-code  (or (:web/md-diff-code state) #{})}))]]))

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
  ([{:keys [session-id name cwd has-dialog? busy? error? unread? active?]} dispatch!
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
       (card-status-indicator {:busy? busy? :error? error? :unread? unread? :active? active?})]])))

(defn- project-actions
  "A project's palette actions as data ({:icon :label :on-click}) — the rows of
   its second-level page (Enter/Tab on a project row). Mirrors the project
   three-dots overflow menu — new chat, git status, search, find file — plus any
   :project-scoped extension nav items, with open sessions last."
  [state dispatch! cwd]
  (concat
   [{:icon :plus :label "New chat"
     :on-click (fn [_] (dispatch! {:type :projects/new-session :cwd cwd}))}
    {:icon :code :label "Git status"
     :on-click (fn [_] (dispatch! {:type :git-status/open :cwd cwd}))}
    {:icon :search :label "Search session text"
     :on-click (fn [_] (dispatch! {:type :palette/open-search :cwd cwd}))}
    {:icon :file-text :label "Find file"
     :on-click (fn [_] (dispatch! {:type :palette/open-file-finder :cwd cwd}))}]
   (for [item (nav-items-for state :overflow)
         :when (contains? #{nil :project} (:mode item))]
     {:icon (:icon item) :label (:label item)
      :on-click (fn [_] (dispatch! (merge (:event item) {:cwd cwd})))})
   ;; :list — the framework icon set has no :folder-open; an unknown name
   ;; renders no icon at all.
   [{:icon :list :label "Open sessions"
     :on-click (fn [_] (dispatch! {:type :projects/select-dir :cwd cwd}))}]))

(defn- palette-project-actions
  "Command items for a project's second-level page (Tab-drilled from a project
   row): one row per `project-actions` entry."
  [state dispatch! cwd]
  (for [{:keys [icon label on-click]} (project-actions state dispatch! cwd)]
    (cmd/command-item {:icon icon :on-click on-click} label)))

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
  (let [{:keys [query results]} (:web/palette-search state)
        blank? (str/blank? (str/trim (or query "")))]
    (cond
      (nil? results)
      [:div {:class ["command-loading"]} (spinner)]

      (empty? results)
      [:div {:class ["command-empty"]}
       (if blank? "No sessions yet" "No matching sessions")]

      ;; Nothing typed: the newest sessions; typing narrows to text matches.
      :else
      (apply cmd/command-group {:heading (if blank? "Recent sessions" "Matching sessions")}
        (for [{:keys [session-id name cwd snippet]} results]
          (cmd/command-item
           {:icon :message-circle
            :value query
            :description (or snippet (some-> cwd shorten-path))
            :on-click (fn [_] (dispatch! {:type :route/navigate
                                          :page :chat
                                          :session-id session-id}))}
           (or name "(untitled)")))))))

(defn- palette-model-page
  "Model list as a palette sub-page (drilled from Change model / /model).
   Paints the cached :web/model-list (xi.web.models) — a spinner only before
   any list is known — as a command-item per model with the active one
   checked, under :web/model-error when a pick turned out unavailable.
   Selecting sends /model and closes the palette."
  [state dispatch!]
  (let [room    (state/active-room state)
        models  (:web/model-list state)
        error   (:web/model-error state)
        ;; In a not-yet-created chat the choice lives on the pending room;
        ;; prefer it so the checkmark tracks the pick before the room exists.
        current (or (get-in state [:web/pending-room :model])
                    (get-in room [:agent :model]))]
    (cond
      (nil? models)   [:div {:class ["command-loading"]} (spinner)]
      (empty? models) [:div {:class ["command-empty"]} "No models available"]
      :else
      [:div {:replicant/key :model-page}
       (when error [:div {:class ["command-error"]} error])
       (apply cmd/command-group {:heading "Model"}
         (for [m models]
           (cmd/command-item
            {:icon (if (= m current) :check :layers)
             :value m
             :on-click (fn [_] (dispatch! {:type :models/select
                                           :model m :room-id (:id room)}))}
            m)))])))

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

(defn- keycaps
  "A key's display string (\"Alt+Shift+P\", \"] f\") as keyboard keycaps: one
   <kbd> per key, the chords of a sequence spaced apart. Goes inside the
   framework's `.command-shortcut` <kbd> (nested <kbd>s = a key combination)."
  [display]
  (into [:span {:class ["keycaps"]}]
        (for [chord (str/split display #" ")]
          (into [:span {:class ["keycap-chord"]}]
                (for [k (str/split chord #"\+(?=.)")]
                  [:kbd {:class ["keycap"]} k])))))

(defn- palette-projects-page
  "Projects as a palette sub-page. One component for both pickers; the page's
   :action says what a row does — :drill (the :projects/pick key) opens the
   project's action sub-page like Enter on a top-level project row, :insert
   (the compose Projects button) puts its path into the draft. Tab on a row
   opens the project's file finder with the same action (palette-keydown
   reads the row's `data-palette-drill` cwd). Spinner while :web/project-dirs
   loads."
  [state dispatch! {:keys [action]}]
  (let [dirs   (:web/project-dirs state)
        dkey   (draft-key state)
        select (fn [d short]
                 (case action
                   :insert {:type :projects/picker-insert :path d :draft-key dkey}
                   {:type :palette/drill :cwd d :label short :reopen? true}))]
    (if (empty? dirs)
      [:div {:class ["command-loading"]} (spinner)]
      (apply cmd/command-group {:heading (if (= action :insert) "Insert project path" "Projects")}
        (for [d dirs
              :let [short (shorten-path d)]]
          (cmd/command-item
           {:icon :folder
            :shortcut (keycaps "⇥")
            :value (str "project " short " " d)
            :attrs {:data-palette-drill d
                    :data-palette-label short}
            :on-click (fn [_] (dispatch! (select d short)))}
           short))))))

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

(defn- buffer-palette-groups
  "The palette's buffer rows (the Ctrl+K groups and the Alt+B switcher page) as
   a vector of `{:heading :items}`: \"Buffers\" — the room in view first, the
   chat then its buffers in opening order, a pick switches to it — then
   \"Other sessions\" (a pick opens that chat on the buffer), then \"Buffer
   actions\" (\"Close all\" for the room in view). The view in front is left
   out (switching to it is a no-op); empty groups are left out, so nothing
   when no buffer is open anywhere."
  [state dispatch!]
  (let [room      (state/active-room state)
        room-id   (:id room)
        cur-sid   (get-in state [:web/route :session-id])
        own       (buffers/ordered (get-in room [:ui :buffers]))
        active    (get-in room [:ui :active-buffer] :chat)
        name-of   (fn [sid]
                    (or (some #(when (= sid (:session-id %)) (or (:name %) (:session-name %)))
                              (concat (get-in state [:lobby :sessions])
                                      (get-in state [:lobby :rooms])))
                        "New session"))
        others    (for [[sid bufs] (get-in state [:lobby :buffers])
                        :when (not= sid cur-sid)
                        b bufs]
                    (assoc b :session-id sid :session-name (name-of sid)))
        switch!   (fn [id]
                    (dispatch! {:type :diff/clear-selection})
                    (dispatch! {:type :ui/buffer-switch :room-id room-id :buffer-id id}))]
    (->> [{:heading "Buffers"
           :items (concat
                   (when (and room (seq own) (not= active :chat))
                     [(cmd/command-item
                       {:icon :terminal
                        :value "buffer chat"
                        :on-click (fn [_] (switch! :chat))}
                       "Chat")])
                   (for [[id buf] own
                         :when (not= id active)
                         :let [title (buffers/label id buf)]]
                     (cmd/command-item
                      {:icon (buffer-icon (buffers/kind id buf))
                       :value (str "buffer " title)
                       :on-click (fn [_] (switch! id))}
                      title)))}
          {:heading "Other sessions"
           :items (for [{:keys [id kind title session-id session-name]} others]
                    (cmd/command-item
                     {:icon (buffer-icon kind)
                      :value (str "buffer " title " " session-name)
                      :on-click (fn [_] (dispatch! {:type :route/navigate :page :chat
                                                    :session-id session-id :buffer-id id}))}
                     (str title " · " session-name)))}
          {:heading "Buffer actions"
           :items (when (seq own)
                    [(cmd/command-item
                      {:icon :x
                       :value "close all buffers"
                       :on-click (fn [_] (dispatch! {:type :ui/buffers-close-all :room-id room-id}))}
                      (str "Close all buffers (" (count own) ")"))])}]
         (keep (fn [{:keys [items] :as g}]
                 (when (seq items) (assoc g :items (vec items)))))
         vec)))

(defn- palette-buffers-page
  "The buffer switcher (Alt+B): the palette on its Buffers rows alone, so
   typing filters them and Enter switches."
  [state dispatch!]
  (if-let [groups (seq (buffer-palette-groups state dispatch!))]
    (for [{:keys [heading items]} groups]
      (apply cmd/command-group {:heading heading} items))
    [:div {:class ["command-empty"]} "No open buffers"]))

(defn- palette-file-finder-page
  "Instant fuzzy file finder as a palette sub-page (Ctrl/Cmd+P). The palette
   input is the query — the dialog's input listener dispatches
   :file-finder/input, which fuzzy-ranks the preloaded flat file list
   client-side (no per-keystroke server round-trip). Rows carry :value = the
   live query so ui-runtime's own substring filter never re-hides a fuzzy
   match. The page's :action says what picking a row does — :open views the
   file in the :file tab (relative path, resolved server-side against the
   listed tree's cwd — the project's when drilled from a project row, else
   the room's); :insert (Tab from the insert project picker) puts the file's
   absolute path into the draft."
  [state dispatch! {:keys [action]}]
  (let [{:keys [cwd files error]} (:web/file-tree state)
        query (or (:web/file-finder-query state) "")
        dkey  (draft-key state)
        select (fn [rel]
                 (case action
                   :insert {:type :projects/picker-insert :path (str cwd "/" rel) :draft-key dkey}
                   {:type :files/open :path rel :cwd cwd}))]
    (cond
      (nil? (:web/file-tree state)) [:div {:class ["command-loading"]} (spinner)]
      error [:div {:class ["command-empty"]} error]
      (empty? files) [:div {:class ["command-empty"]} "No files"]
      :else
      (let [ranked (fuzzy/rank query files {:limit 50})]
        (if (empty? ranked)
          [:div {:class ["command-empty"]} "No matching files"]
          (apply cmd/command-group {:heading (if (= action :insert) "Insert file path" "Files")}
            (for [rel ranked]
              (cmd/command-item
               {:icon :file-text
                :value query
                :on-click (fn [_] (dispatch! (select rel)))}
               rel))))))))

(defn- pin-palette-items!
  "Keep the `.command-item--pinned` row (\"Search all sessions\") selectable under
   any filter query. ui-runtime.js hides non-matching items via [hidden] and
   keyboard nav / Enter only consider non-hidden items, so after each filter
   pass (its capture-phase input listener runs before the dialog's own) un-hide
   the row and its group, sink the group last in nav order, and make the row
   the active item when nothing else matches. The runtime's \"No results found\"
   state is left alone."
  [^js dialog]
  (when-let [item (.querySelector dialog ".command-item--pinned")]
    (when (.-hidden item)
      (set! (.-hidden item) false)
      (when-let [grp (.closest item ".command-group")]
        (set! (.-hidden grp) false)
        (set! (.. grp -style -order) "9999"))
      (when-not (.querySelector dialog ".command-item--active")
        (.add (.-classList item) "command-item--active")))))

(defn- palette-keydown
  "Extra keyboard layer over ui-runtime.js (which owns arrow-nav, live filter
   and Enter): Tab drills the active project row into its action sub-page;
   on the project picker page Tab opens the active project's file finder
   instead (Enter there already drills); on the search page Tab flips its
   names-only/full-text toggle (Ctrl/Cmd+F belongs to the browser's find);
   Shift+Tab / Backspace-on-empty backs out of a sub-page (Escape closes the
   palette, as at the top level). Reads the
   runtime's `.command-item--active` element and its `data-palette-drill` cwd."
  [dispatch! palette-page]
  (fn [^js e]
    (let [dialog (.-currentTarget e)
          key    (.-key e)
          tab?   (and (= key "Tab") (not (.-shiftKey e)))
          active (fn [] (.querySelector dialog ".command-item--active"))]
      (cond
        (and (nil? palette-page) tab?)
        (when-let [^js row (active)]
          (when-let [cwd (.. row -dataset -paletteDrill)]
            (.preventDefault e)
            (dispatch! {:type :palette/drill :cwd cwd
                        :label (.. row -dataset -paletteLabel)})))

        (and (= :projects (:kind palette-page)) tab?)
        (when-let [cwd (some-> ^js (active) (.. -dataset -paletteDrill))]
          (.preventDefault e)
          (dispatch! {:type :palette/open-file-finder :cwd cwd :in-dialog? true
                      :action (:action palette-page)}))

        (and (= :search (:kind palette-page)) tab?)
        (do (.preventDefault e)
            (dispatch! {:type :palette/toggle-content-search}))

        (and (some? palette-page)
             (or (and (= key "Tab") (.-shiftKey e))
                 (and (= key "Backspace")
                      (when-let [input (.querySelector dialog ".command-input")]
                        (= "" (.-value input))))))
        (do (.preventDefault e)
            (dispatch! {:type :palette/back}))))))

(def ^:private palette-quick-keys
  "Alt+<key> quick-select keys for the command palette, in row order. Skips the
   keys Alt already does here: list nav (j k n p) and xi's global/permission
   bindings (u x a d)."
  "sfghlqweryiotzcvbm")

(defn- with-shortcut
  "cmd/command-item `opts` plus a keycap badge of the key that does the same
   as the row: its keyboard `action` (an xi.keys id), else a keymap action
   whose `:event` is the row's `event` (xi.web.keymap/event-shortcut).
   Unchanged when no key is bound."
  [opts state {:keys [action event]}]
  (if-let [s (or (some->> action (keymap/shortcut state))
                 (some->> event (keymap/event-shortcut state)))]
    (assoc opts :shortcut (keycaps s))
    opts))

(defn- palette-search-toggle
  "Search-mode toggle at the right edge of the \"Search all sessions\" page's
   input: full-text over names + transcripts (the default) vs. names only,
   with its Tab shortcut as a keycap (Tab is free on this page — drilling is
   top-level only — and no browser reserves it). Passed through the dialog's
   :leading slot together with the back button (the only insertion point
   inside .command-search); the row is a flex container, so style.css floats
   the toggle right via `order`. Mousedown is swallowed so the input keeps
   focus."
  [state dispatch!]
  (let [names-only? (boolean (get-in state [:web/palette-search :names-only?]))]
    [:button {:class ["command-search-toggle"]
              :type "button" :tabindex "-1"
              :title (if names-only?
                       "Searching names only — click or Tab to search session text too"
                       "Searching names + session text — click or Tab for names only")
              :on {:mousedown (fn [^js e] (.preventDefault e))
                   :click (fn [_] (dispatch! {:type :palette/toggle-content-search}))}}
     (icon/icon {:icon-name :file-text :size :sm})
     [:span {:class ["command-search-toggle-label"]}
      (if names-only? "Names" "Full-text")]
     (keycaps "⇥")]))

(defn- command-palette
  "Global Cmd/Ctrl+K command palette (ui.command). Mounted once in root-view;
   the ui-runtime.js delegate handles open/filter/keyboard-nav. Items dispatch
   app events on select (the runtime clicks the button, firing :on-click, then
   closes the dialog). The Chats group quick-switches to active/recent rooms;
   room-scoped actions only appear when a room is active.

   Two levels: at the top level, Tab on a project row drills into a project
   action page (:web/palette-page); Shift+Tab/Backspace-on-empty backs out."
  [state dispatch!]
  (let [open?        (boolean (:web/palette-open? state))
        palette-page (:web/palette-page state)
        search-page? (= :search (:kind palette-page))
        finder-page? (= :file-finder (:kind palette-page))
        buffers-page? (= :buffers (:kind palette-page))
        projects-page? (= :projects (:kind palette-page))
        names-only?  (boolean (get-in state [:web/palette-search :names-only?]))
        dialog-attrs {:id "cmdk" :hotkey "mod+k"
                      ;; Hold Alt → key badges on the first rows, Alt+key picks one.
                      :quick-nav palette-quick-keys
                      :placeholder (cond
                                     search-page? (if names-only?
                                                    "Search session names…"
                                                    "Search session text…")
                                     finder-page? "Find file…"
                                     buffers-page? "Switch buffer…"
                                     projects-page? "Find project…"
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
                                                 :attributeFilter #js ["open"]})
                                  ;; The runtime re-filters whenever the list
                                  ;; re-renders (no input event), re-hiding the
                                  ;; pinned row — re-pin once it has settled.
                                  (.observe (js/MutationObserver.
                                             (fn [_ _]
                                               (js/setTimeout #(pin-palette-items! node) 0)))
                                            node
                                            #js {:childList true :subtree true})))
                              :on {:keydown (palette-keydown dispatch! palette-page)
                                   ;; On the search sub-page the palette input
                                   ;; is the query — feed keystrokes into the
                                   ;; debounced server search (delegated: input
                                   ;; events bubble from .command-input).
                                   :input (fn [^js e]
                                            (let [v (.. e -target -value)]
                                              (cond
                                                (nil? palette-page)
                                                (pin-palette-items! (.-currentTarget e))
                                                search-page?
                                                (dispatch! {:type :palette/search-input :query v})
                                                finder-page?
                                                (dispatch! {:type :file-finder/input :query v}))))
                                   ;; `close` is a queued task: a drill's close +
                                   ;; reopen (Enter/click on a project row) can
                                   ;; land it after the dialog is open again
                                   ;; (task vs. animation-frame order differs by
                                   ;; browser) — a stale event must not blank the
                                   ;; open palette.
                                   :close (fn [^js e]
                                            (when-not (.-open (.-target e))
                                              (dispatch! {:type :palette/closed})))}}}]
    (cond
      ;; Closed: render just the dialog shell (observer stays attached via the
      ;; stable :replicant/key). No children means near-zero per-keystroke cost.
      (not open?)
      (cmd/command-dialog dialog-attrs)

      palette-page
      ;; On a sub-page the leading search icon becomes a clickable back arrow
      ;; (via the framework's :leading slot) instead of a separate back row;
      ;; the search page also carries its names-only/full-text toggle there
      ;; (floated to the input's right edge, see .command-search-toggle).
       (cmd/command-dialog
       (assoc dialog-attrs
              :leading
              (list
               [:button {:class ["command-search-back"] :type "button"
                         :aria-label "Back"
                         :on {:click (fn [_] (dispatch! {:type :palette/back}))}}
                (icon/icon {:icon-name :arrow-left :size :sm})]
               (when search-page?
                 (palette-search-toggle state dispatch!))))
       (case (:kind palette-page)
         :model          (palette-model-page state dispatch!)
         :skill          (palette-skill-page state dispatch!)
         :commits        (palette-commits-page state dispatch!)
         :files          (palette-files-page state dispatch!)
         :file-finder    (palette-file-finder-page state dispatch! palette-page)
         :buffers        (palette-buffers-page state dispatch!)
         :projects       (palette-projects-page state dispatch! palette-page)
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
            cur-hidden?  (boolean (:dismissed? cur-session))
            cur-pinned?  (boolean (:pinned? cur-session))
            ;; Search-only tier: every session (Recent + Hidden + Earlier),
            ;; invisible at the empty query (data-command-search-only) and
            ;; matched by name/project while typing — so older sessions are
            ;; reachable without leaving the palette.
            all-sessions (->> (concat recent hidden earlier)
                              (remove #(= cur-sid (:session-id %))))]
     (cmd/command-dialog dialog-attrs
     (when (seq chat-items)
       (apply cmd/command-group {:heading "Chats"} chat-items))
     ;; Every open buffer (xi.buffers) — the same rows as the Alt+B switcher.
     (for [{:keys [heading items]} (buffer-palette-groups state dispatch!)]
       (apply cmd/command-group {:heading heading} items))
     (when (seq all-sessions)
       (apply cmd/command-group {:heading "Sessions"}
         (map #(palette-chat-item % dispatch! {:search? true}) all-sessions)))
     (apply cmd/command-group {:heading "Navigate"}
       (cond-> [(let [event {:type :route/navigate :page :home}]
                  (cmd/command-item (with-shortcut {:icon :layout-dashboard
                                                    :on-click (fn [_] (dispatch! event))}
                                      state {:event event})
                    "All projects"))]
         ;; Fuzzy file finder needs a room cwd to scope the listing.
         room (conj (let [event {:type :palette/open-file-finder}]
                      (cmd/command-item
                       (with-shortcut {:icon :file-text
                                       :on-click (fn [_] (dispatch! event))}
                         state {:event event})
                       "Find file…")))
         :always (into (for [item (concat (nav-items-for state :palette)
                                          (map with-default-icon (palette-items/items state)))]
                         (cmd/command-item (with-shortcut {:icon (:icon item)
                                                           :on-click (fn [_] (dispatch! (:event item)))}
                                             state item)
                           (:label item))))))
     (when (seq project-dirs)
       (apply cmd/command-group {:heading "Projects"}
         (map
          (fn [d]
            (let [short (shorten-path d)]
              (cmd/command-item
               {:icon :folder
                :shortcut (keycaps "⇥")
                :value (str "project " short " " d)
                :attrs {:data-palette-drill d
                        :data-palette-label short}
                ;; Enter / mouse click drills into the project action sub-page
                ;; (same as keyboard Tab); :reopen? keeps the panel open past
                ;; the runtime's force-close. "Open sessions" inside the
                ;; sub-page navigates.
                :on-click (fn [_] (dispatch! {:type :palette/drill :cwd d
                                              :label short :reopen? true}))}
               short)))
          project-dirs)))
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
               :keys         (fn [_] (dispatch! {:type :keys/show}))
               :reload       (fn [_] (reload-with-feedback!))))]
       (let [actions (for [{:keys [key label icon action]} (palette/actions (boolean room)
                                                               (boolean (:web/pending-room state)))]
                       ;; the key that runs the same thing (xi.keys), as a badge
                       (cmd/command-item (with-shortcut {:icon icon :on-click (action-onclick key)}
                                           state {:action action})
                         label))
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
         ;; The extensions' session menu items (e.g. Add to favorites)
         (for [item (:web/session-menu-items state)
               :let [{:keys [label icon on-click]}
                     (ext-session-menu-item dispatch! (assoc cur-session :session-id cur-sid) item)]]
           (cmd/command-item {:icon icon :on-click (fn [_] (on-click))} label))
         (cmd/command-item
          {:icon :bookmark
           :on-click (fn [_] (dispatch! {:type :pinned/toggle :session-id cur-sid}))}
          (if cur-pinned? "Unpin session" "Pin session"))
         (cmd/command-item
          {:icon (if cur-hidden? :eye :eye-off)
           :on-click (fn [_] (dispatch! {:type :dismissed/toggle :session-id cur-sid}))}
          (if cur-hidden? "Show in Recent" "Hide from Recent"))
         (cmd/command-item
          {:icon :trash
           :on-click (fn [_] (dispatch! {:type :session/delete :session-id cur-sid}))}
          "Delete session")))
     (when (seq cleanups)
       (apply cmd/command-group {:heading "Cleanup"}
         (cond-> (mapv (fn [{:keys [label icon event]}]
                         (cmd/command-item
                          (with-shortcut {:icon icon :on-click (fn [_] (dispatch! event))}
                            state {:event event})
                          label))
                       cleanups)
           (> (count cleanups) 1)
           ;; Same as ALT+Shift+P (xi.web.core/prune-all!).
           (conj (cmd/command-item
                  (with-shortcut
                    {:icon  :zap
                     :value (str "prune all " (str/join " " (map :label cleanups)))
                     :on-click (fn [_] (run! (comp dispatch! :event) cleanups))}
                    state {:action :sessions/prune})
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
            name))))
     ;; Pinned last row: full-text search over every saved session (nil cwd =
     ;; no project scope), the same sub-page as a project's "Search session
     ;; text". The --pinned classes keep it visible under any filter query
     ;; (see style.css).
     (cmd/command-group {:class ["command-group--pinned"]}
       (cmd/command-item
        {:icon :search
         :class ["command-item--pinned"]
         ;; Carry the text typed so far over into the search page. Looked up
         ;; by id, not [open]: the runtime closes the dialog before clicking
         ;; the selected item (its input keeps the value until reopened).
         :on-click (fn [_]
                     (let [^js input (.querySelector js/document "#cmdk .command-input")]
                       (dispatch! {:type :palette/open-search :cwd nil
                                   :query (some-> input .-value)})))}
        "Search all sessions")))))))

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
   Esc pressed twice cancels (the first press arms and shows a hint); Enter
   (Shift+Enter for a newline) or the send button submits — values are
   substituted into the skill body and posted like a normal prompt."
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

                     ;; Enter submits, Shift+Enter inserts a newline (the
                     ;; textarea default). Not during IME composition.
                     (and (= "Enter" (.-key e))
                          (not (.-shiftKey e))
                          (not (.-isComposing e)))
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
          "Enter to start · Shift+Enter for newline · Esc Esc to cancel")]
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

(def ^:private fixed-shortcuts
  "Keys the web client handles outside the keymap (the palette's own hotkey,
   the composer's editing keys) — listed for completeness, not rebindable.
   `[keys label]`, alternatives in `keys` separated by \" / \"."
  [["Ctrl+K / Cmd+K" "Command palette"]
   ["Alt" "Palette: hold for key badges, Alt + badge key picks the row"]
   ["Tab" "Message box: expand the snippet word before the cursor"]
   ["Shift+Enter" "Message box: new line"]
   ["Ctrl+Enter" "On one of your messages: go back to before it and edit it"]])

(defn- keys-row
  "One dialog row: label, a dotted leader, the keys as keycaps. `kbds` is a
   seq of `{:display}`, alternatives joined by a `/`."
  [{:keys [label kbds class title key]}]
  [:li {:class (into ["keys-row"] class)
        :replicant/key key
        :title title}
   [:span {:class ["keys-row-label"]} label]
   [:span {:class ["keys-row-dots"]}]
   (into [:span {:class ["keys-row-keys"]}]
         (interpose [:span {:class ["keys-alt"]} "/"]
                    (for [{:keys [display]} kbds]
                      (keycaps display))))])

(defn- keys-dialog
  "The Keyboard shortcuts dialog (`?`, Alt+/, palette): an index of every key
   of the effective keymap (xi.web.keymap/listing) as keycaps, one section per
   layer, the layers active right now first. Rows the user changed in config.edn
   carry a bar on the left. Laid out like the calendar's agenda list
   (cal-agenda-*): uppercase group headers, hairline dividers, full-width hover
   rows. Escape closes it (:dialog/close action)."
  [state dispatch!]
  (when (:web/keys-open? state)
    (let [close!   (fn [] (dispatch! {:type :keys/close}))
          sections (keymap/listing state)
          custom?  (boolean (some #(some :custom? (:rows %)) sections))
          n-keys   (reduce + (for [{:keys [rows]} sections
                                   {ks :keys :keys [action]} rows
                                   :when action]
                               (count ks)))]
      (dialog/dialog-overlay
       {:on-close close!
        :class "keys-overlay"
        :attrs {:replicant/key "keys-dialog"}}
       (dialog/dialog-panel {:class "keys-dialog"}
         (dialog/dialog-header {}
           [:h3 "Keyboard shortcuts"
            [:span {:class ["keys-count"]} (str n-keys " bindings")]]
           [:button {:class ["icon-btn" "icon-btn--sm"]
                     :title "Close"
                     :on {:click (fn [_] (close!))}}
            (icon/icon {:icon-name :x :size :md})])
         (dialog/dialog-body {:class "keys-body"}
           (into [:div {:class ["keys-sections"]}]
                 (concat
                  (for [{:keys [layer label active? rows]} sections]
                    [:section {:class ["keys-section" (when-not active? "keys-section--inactive")]
                               :replicant/key layer}
                     [:h4 {:class ["keys-section-title"]}
                      label
                      (when-not active?
                        [:span {:class ["keys-section-note"]} "not active here"])]
                     (into [:ul {:class ["keys-list"]}]
                           (for [{ks :keys :keys [label custom? action]} rows]
                             (keys-row {:label label
                                        :key (str/join " " ks)
                                        :title (when custom? "changed in config.edn")
                                        :class [(when custom? "keys-row--custom")
                                                (when-not action "keys-row--unbound")]
                                        :kbds (for [k ks]
                                                {:display (xkeys/format-key k)})})))])
                  [[:section {:class ["keys-section" "keys-section--fixed"]}
                    [:h4 {:class ["keys-section-title"]} "Always"]
                    (into [:ul {:class ["keys-list"]}]
                          (for [[k label] fixed-shortcuts]
                            (keys-row {:label label
                                       :key k
                                       :kbds (for [d (str/split k #" / ")]
                                               {:display d})})))]])))
         (dialog/dialog-footer {}
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
     (keys-dialog state dispatch!)
     (auth-request-banner state dispatch!)
     (auth-overlay state))))