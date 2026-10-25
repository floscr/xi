(ns xi.web.views
  "Replicant view layer: pure (state → hiccup) over the same room state the
   TUI renders. Event handlers dispatch events; there is no view-local atom."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.avatar :as avatar]
            [xi.buffers :as buffers]
            [xi.commands :as commands]
            [xi.error-info :as error-info]
            [xi.ext.subagent.handlers :as sa]
            [xi.ext.subagent.threads :as sa-threads]
            [xi.web.subagents :as sav]
            [xi.core.state :as state]
            [xi.markdown.hiccup :as md]
            [xi.markdown.diff :as md-diff]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.embedded :as embedded]
            [xi.highlight.theme-css :as theme]
            [xi.dialog :as dlg]
            [xi.diff :as diff]
            [xi.web.diff-state :as diff-state]
            [xi.fuzzy :as fuzzy]
            [xi.keys :as xkeys]
            [xi.layout :as layout]
            [xi.web.layout :as wlayout]
            [xi.palette :as palette]
            [xi.providers.routing :as routing]
            [xi.session.sidebar :as sb :refer [format-relative-time session-status
                                               active-first orphan-rooms
                                               sidebar-session-groups sidebar-session-order]]
            [xi.session-links :as session-links]
            [xi.tui.snippets :as snippets]
            [xi.url :as url]
            [xi.util :as util]
            [xi.web.appearance :as appearance]
            [xi.web.block-menu :as block-menu]
            [xi.web.buffer-views :as buffer-views]
            [xi.web.overlays :as overlays]
            [xi.web.clipboard :as clipboard]
            [xi.web.dashboard :as dashboard]
            [xi.web.heading-anchor :as heading-anchor]
            [xi.web.theme :as ctheme]
            [xi.web.keymap :as keymap]
            [xi.web.rename :as rename]
            [xi.web.router :as router]
            [xi.web.palette-items :as palette-items]
            [xi.web.terminal :as terminal]
            [xi.web.tool-views :as tool-views]
            [xi.web.timeline-posts :as timeline-posts]
            [xi.blocks :as blocks]
            [xi.web.viewer-group :as viewer-group]
            [xi.web.workspaces :as workspaces]
            [xi.workspace :as workspace]
            [ui.icon :as icon]
            [ui.alert :as alert]
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
            [ui.panels :as panels]
            [ui.popover :as popover]
            [ui.select :as select]
            [ui.color-picker :as color-picker]
            [ui.chip :as chip]
            [ui.accordion :as accordion]
            [ui.tabs :as tabs]
            [xi.clj-result :as clj-result]
            [ui.theme-toggle :as theme-toggle]))

;; ── Standalone (homescreen) detection ────────────────────────────────────────

(def standalone?
  (or (some-> js/navigator .-standalone)  ;; iOS Safari
      (and (exists? js/window.matchMedia)
           (.-matches (.matchMedia js/window "(display-mode: standalone)")))))

;; ── Block context menus ──────────────────────────────────────────────────────

;; pointerType of the most recent pointerdown: the click event's own
;; pointerType is unreliable across browsers.
(defonce ^:private last-pointer-type (atom nil))

(defn install-pointer-type-tracker!
  []
  (.addEventListener js/document "pointerdown"
                     (fn [^js e] (reset! last-pointer-type (.-pointerType e)))
                     #js {:capture true :passive true}))

(defn tap-opens-context-menu?
  "True when a click should open a content block's context menu: a touch/pen
   tap. A mouse opens it via right-click instead."
  []
  (not= "mouse" @last-pointer-type))

(defn block-context-menu-on
  "Replicant `:on` handlers opening a content block's context menu on a touch
   tap or a mouse right-click. `open!` receives the DOM event."
  [open!]
  {:click       (fn [^js e]
                  (when (tap-opens-context-menu?)
                    (open! e)))
   :contextmenu (fn [^js e]
                  (.preventDefault e)
                  (open! e))})

;; ── Timeline virtualization ──────────────────────────────────────────────────

(def initial-window-size
  60)

(defn prompt-nav-ctx
  "Prompt navigation context over the full `history`: every user entry's
   absolute index, carried by the :prompt-nav/prev / :next events."
  [state history]
  (let [entries      (vec history)
        user-indices (vec (keep-indexed (fn [i e] (when (= :user (:kind e)) i))
                                        entries))]
    {:user-indices user-indices
     :count        (count user-indices)
     :total        (count entries)
     :cur-window   (or (:web/timeline-window state) initial-window-size)}))

(def ^:private window-step
  40)

;; ── Clipboard ─────────────────────────────────────────────────────────────────

(def ios?
  (let [ua       (or (some-> js/navigator .-userAgent) "")
        platform (or (some-> js/navigator .-platform) "")
        touch    (or (some-> js/navigator .-maxTouchPoints) 0)]
    (boolean
     (or (re-find #"iPad|iPhone|iPod" ua)
         (and (re-find #"Mac" platform) (> touch 1))))))

(defn- needs-copy-dialog?
  []
  (and ios? (not js/window.isSecureContext)))

(defn copy!
  "Copy `text`, or open the manual-copy dialog where the clipboard API is
   unavailable (iOS over plain HTTP)."
  [dispatch! text]
  (if (needs-copy-dialog?)
    (dispatch! {:type :copy/open :text text})
    (-> (clipboard/copy! text)
        (.then (fn [_] (dispatch! {:type :copy/flash}))))))

(defn- share-link!
  "Hand `url` to the device's share sheet (Web Share API). Closing the sheet
   rejects with AbortError: not an error."
  [title url]
  (-> (.share js/navigator #js {:title title :url url})
      (.catch (fn [_] nil))))

(defn session-link-items
  "Menu entries ({:label :icon :on-click}) handing out a chat's address
   (router/session-url): copy it, and where the device has a share sheet
   (navigator.share: phones, Safari) share it."
  [dispatch! session-id title]
  (let [url (router/session-url js/location.origin session-id)]
    (cond-> [{:label    "Copy link"
              :icon     :link
              :on-click #(copy! dispatch! url)}]
      (some? (.-share js/navigator))
      (conj {:label    "Share link…"
             :icon     :upload
             :on-click #(share-link! title url)}))))

(defn- copy-dialog-overlay
  "iOS manual-copy fallback: the text in a pre-selected read-only textarea for
   the native long-press menu. HTTPS (docs/guide/https.md) avoids it."
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
  []
  [:div {:class ["copy-toast"] :replicant/key "copy-toast"}
   (icon/icon {:icon-name :check :size :sm})
   [:span "Copied"]])

;; ── Spinner ──────────────────────────────────────────────────────────────────

(defn spinner [] [:div {:class ["agent-status-spinner"]}])

(defn- history-skeleton
  "Stands in for a session's history while it loads with nothing cached
   (chat-history :loading?). Bottom-anchored like the timeline, so the
   messages land where the ghosts were; the note fades in after a few seconds
   (CSS), at once while offline."
  [offline?]
  [:div {:class ["history-skeleton"] :role "status" :aria-label "Loading conversation"}
   [:p {:class ["history-skeleton-note" (when offline? "history-skeleton-note--offline")]}
    (if offline? "Waiting for the connection…" "Still fetching from the server…")]
   [:i {:class ["history-skeleton-user"]}]
   [:i {:class ["history-skeleton-tool"]}]
   [:div {:class ["history-skeleton-lines"]} [:i] [:i] [:i]]
   [:i {:class ["history-skeleton-user" "history-skeleton-user--short"]}]
   [:div {:class ["history-skeleton-lines"]} [:i] [:i]]])

(defn reload-with-feedback!
  "Paint a full-screen 'Reloading…' overlay (built directly, state is about
   to be discarded), then reload on the next frame so it is visible."
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
  "Session status dot for session cards and palette chat rows: working >
   failed turn > unread > live room > nothing. One always-present node whose
   class toggles; returning different elements or nil mis-reconciles the slot
   as the keyed card list reorders (the old doubled-spinner bug)."
  [{:keys [busy? error? unread? active?]}]
  [:span {:replicant/key "status-indicator"
          :class ["status-dot"
                  (cond busy?   "status-dot--busy"
                        error?  "status-dot--error"
                        unread? "status-dot--unread"
                        active? "status-dot--live")]}])

(defn user-avatar
  "A user's avatar: their image over a circle of initials coloured from their
   id (xi.avatar); the initials show when the image is missing or fails.
   Public for user extensions' web halves."
  [{:keys [id name] url :avatar}]
  [:span {:class ["avatar"]
          :replicant/key (str "avatar-" id)
          :title (or name id)
          :style {:background-color (str "hsl(" (avatar/hue id) " 55% 42%)")}}
   (avatar/initials id name)
   (when url
     [:img {:class ["avatar-img"] :src url :alt "" :loading "lazy"
            :referrerpolicy "no-referrer"
            :on {:error (fn [e] (set! (.. e -target -style -display) "none"))}}])])

(defn avatar-stack
  "Overlapping avatars of `people` (up to 3, then +n). Public, like `user-avatar`."
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
  "Avatar data {:id :name :avatar} of `user` when it is not this client's own
   user; nil for the viewer and unattributed prompts."
  [state user]
  (when (and user (not= user (state/own-user state)))
    (assoc (get-in state [:lobby :profiles user]) :id user)))

(defn- last-of-run?
  "True when entry `p` is not followed by another prompt from the same user."
  [entries p]
  (let [entry (nth entries p)
        nxt   (nth entries (inc p) nil)]
    (not (and nxt
              (= :user (:kind nxt))
              (= (:user nxt) (:user entry))))))

(def default-nav-icon
  "Icon for extension nav items that declare none, keeping menu labels aligned."
  :package)

(defn- with-default-icon
  [item]
  (update item :icon (fn [k]
                       (if (contains? icon/icon-names k)
                         k
                         default-nav-icon))))

(defn nav-items-for
  [state menu]
  (->> (:web/nav-items state)
       (filter #(= menu (:menu %)))
       (map with-default-icon)))

(defn nav-badge
  [state {:keys [badge-path]}]
  (when (vector? badge-path)
    (let [v (get-in state badge-path)]
      (when (and (number? v) (pos? v)) (str v)))))

;; ── Tool rendering ───────────────────────────────────────────────────────────

(defn- get-arg [args k]
  (or (get args (name k)) (get args k)))

(defn- relativize-path
  [path cwd]
  (if (and (string? path) (string? cwd) (seq cwd))
    (let [prefix (str cwd "/")]
      (cond
        (str/starts-with? path prefix) (subs path (count prefix))
        (= path cwd)                   "."
        :else                          path))
    path))

(defn- tool-summary
  [tool args cwd]
  (case tool
    ("Bash" "bash") (get-arg args :command)
    ("clj")         (get-arg args :code)
    ("run_task")    (str/join " " (concat (some-> (get-arg args :runner) not-empty vector)
                                          [(or (not-empty (get-arg args :task)) "(list tasks)")]
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

(defn- tool-detail
  "What a tool header shows next to the name under the :tool-args appearance
   setting (`mode`): the per-tool `tool-summary`, or every argument as
   `k=v` (xi.util/tool-args-line)."
  [mode tool args cwd]
  (if (= :all mode)
    (util/tool-args-line args)
    (tool-summary tool args cwd)))

(defn- file-ext [path]
  (when (and (string? path) (str/includes? path "."))
    (-> path (str/split #"\.") last str/lower-case)))

(defn- tool-grammar
  [tool args]
  (let [path (case tool
               ("Read" "Write" "Edit") (get-arg args :file_path)
               ("read" "write" "edit" "read_source") (get-arg args :path)
               "clj_replace" (get-arg args :file)
               nil)]
    (when path (grammars/get-grammar (file-ext path)))))

(def ^:private code-cache-max 400)

(def ^:private hl-cache
  (js/WeakMap.))

(def ^:private plain-code-cache
  (js/Map.))

(def ^:private clj-code-cache
  (js/Map.))

(defn- tokens->code
  [tokens]
  (into [:code]
        (mapcat (fn [{:keys [type value]}]
                  (if-let [cls (theme/token-class type)]
                    [(into [:span {:class cls}] (md/linkify value))]
                    (md/linkify value))))
        tokens))

(def ^:private md-cache-max 300)

(def ^:private md-cache
  "text → rendered markdown hiccup. Every post re-renders on every app render,
   so the memo hands Replicant the identical hiccup to short-circuit on."
  (js/Map.))

(def ^:private md-cache-hard-breaks
  (js/Map.))

(defn- highlight-code
  "Tokenize + class-wrap text against a grammar → [:code …], URLs linkified.
   Memoized per (grammar, text) so repeat calls return the identical hiccup."
  [grammar text]
  (let [inner (or (.get hl-cache grammar)
                  (let [m (js/Map.)] (.set hl-cache grammar m) m))]
    (or (.get inner text)
        (let [result (tokens->code (hl/merge-adjacent (hl/tokenize grammar text)))]
          (when (>= (.-size inner) code-cache-max) (.clear inner))
          (.set inner text result)
          result))))

(defn- highlight-clj-code
  "highlight-code for clj source whose string literals may embed code
   (xi.highlight.embedded). Memoized per text."
  [text]
  (or (.get clj-code-cache text)
      (when-let [tokens (embedded/tokenize-clj grammars/get-grammar text)]
        (let [result (tokens->code tokens)]
          (when (>= (.-size clj-code-cache) code-cache-max) (.clear clj-code-cache))
          (.set clj-code-cache text result)
          result))))

(defn- plain-code
  [text]
  (or (.get plain-code-cache text)
      (let [result (into [:code] (md/linkify text))]
        (when (>= (.-size plain-code-cache) code-cache-max) (.clear plain-code-cache))
        (.set plain-code-cache text result)
        result)))

(defn code-focus-segments
  "Split `text` into [muted? segment] pairs around the [start end) char
   `ranges` a permission ask targets: text outside every range is muted.
   Ranges are clamped to the text; empty pieces are dropped."
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
   (the dialog's :block) → [muted? in-block? segment] triples."
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
   and the block's calls (`block-ranges`) marked .code-block-target. Each
   segment is a whole form, so it highlights on its own."
  [text ranges block-ranges]
  (into [:code]
        (map (fn [[muted? in-block? s]]
               (into [:span {:class (cond-> [(if muted? "code-muted" "code-focus")]
                                      in-block? (conj "code-block-target"))}]
                     (rest (or (highlight-clj-code s) (plain-code s))))))
        (code-block-segments text ranges block-ranges)))

(defn- render-md
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
                    (if grammar
                      (tokens->code (hl/merge-adjacent (hl/tokenize grammar body)))
                      body)]
                   [:span {:class ["tool-diff-line"]} line]))))
        (str/split-lines text)))

(defn- edit-diff-code
  "An edit tool's unified diff with per-line tinting and syntax highlighting.
   `attrs` go on the <pre>. Memoized per (grammar, text, attrs) as one block
   rather than per line, which overflowed hl-cache."
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
  "Rendered markdown diff of `hunks` (xi.markdown.diff): dimmed context, red
   band of removed blocks over a green band of added ones with differing
   words marked. nil when no block changed."
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
  (js/Map.))

(defn- tool-md-diff-body
  [text]
  (if (.has md-diff-cache text)
    (.get md-diff-cache text)
    (let [result (md-diff-body (md-diff/tool-diff-hunks text))]
      (when (>= (.-size md-diff-cache) code-cache-max) (.clear md-diff-cache))
      (.set md-diff-cache text result)
      result)))

(defn- md-diff-toggle
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

(defn- file-row-attrs
  "Attributes making a tool block's row (its header, a diff bar) the trigger
   of the file menu for `path`: `.file-path` + data-file-path, opened on
   right-click or touch long-press (data-long-press, the ui runtime) by the
   delegated listener in xi.web.core (file-path-menu); a tap keeps the row's
   own action (toggle). `:dir?` marks a directory (data-file-dir): its menu
   finds a file in it instead of viewing it."
  ([attrs path] (file-row-attrs attrs path nil))
  ([attrs path {:keys [dir?]}]
   (cond-> (-> attrs
               (update :class conj "file-path")
               (assoc :data-file-path path :data-long-press "true"))
     dir? (assoc :data-file-dir "true"))))

(defn- tool-diff-view
  "An edit/write tool's diff for `path`: the rendered markdown diff with a
   Rendered ⇄ Code toggle (keyed by the call id `k`) for markdown files,
   else the line diff (xi.diff/tool-diff-body: without the tool's own path
   line). `attrs` go on the outer node."
  [dispatch! {:keys [path text grammar attrs k code?]}]
  (let [rendered (when (and (md-diff/markdown-path? path) k)
                   (tool-md-diff-body text))
        lines    (truncate-lines (diff/tool-diff-body text) 100)]
    (if-not rendered
      (edit-diff-code grammar lines attrs)
      [:div (merge {:class ["md-diff-wrap"]} attrs)
       [:div (file-row-attrs {:class ["md-diff-bar"]} path)
        [:span {:class ["md-diff-path"]} path]
        (md-diff-toggle dispatch! k code?)]
       (if code?
         (edit-diff-code grammar lines nil)
         rendered)])))

(defn- result-images
  "Image blocks of a tool result as {:data :media-type} maps, accepting both
   the MCP and the API block shapes."
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
  [tool args]
  (case tool
    ("Read" "read" "Write" "write" "Edit" "edit" "read_source")
    (or (get-arg args :file_path) (get-arg args :path))
    ("clj_replace" "clj_outline" "clj_deps" "clj_fix_declares" "clj_mv"
     "clj_fix_parens" "clj_topo" "git_hunk")
    (get-arg args :file)
    nil))

(defn- clj-result-view
  "A parsed clj result as separate stdout / value / error zones; clj data
   values are syntax coloured."
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
  "Confirm answer value → its keyboard action, carried as `data-key-action`
   for the Alt key badges (xi.web.key-hints)."
  {true "permission/allow" false "permission/deny" :always "permission/always"
   :repo "permission/allow-repo" :block "permission/allow-block"})

(defn- close-popover!
  [^js e]
  (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover)))

(defn- confirm-menu-item
  [{:keys [label desc]} {:keys [class data-key-action title on-click key]}]
  [:button {:class (into ["confirm-menu-item"] class)
            :data-key-action data-key-action
            :title title
            :replicant/key key
            :on {:click on-click}}
   [:span {:class ["confirm-menu-label"]} label]
   (when desc [:span {:class ["confirm-menu-desc"]} desc])])

(defn- confirm-split
  "A confirm answer as a split button: the main button answers `value`; the
   caret opens `menu` as a popover keyed by `id`."
  [{:keys [id class label value data-key-action caret-label answer!]} menu]
  (let [menu      (seq (remove nil? menu))
        btn-class (into ["confirm-btn"] class)]
    [:span {:class (cond-> ["confirm-split"] menu (conj "confirm-split--menu"))}
     [:button {:class btn-class
               :data-key-action data-key-action
               :on {:click (fn [_] (answer! value))}}
      label]
     (when menu
       (list
        [:button (merge {:class (conj btn-class "confirm-btn--caret")
                         :aria-label caret-label
                         :title caret-label
                         :replicant/key (str id "-caret")}
                        (popover/trigger-attrs id))
         (icon/icon {:icon-name :chevron-down :size :sm})]
        (popover/popover-content
         {:id    id
          :side  :bottom
          :align :end
          :class "confirm-menu"
          :attrs {:replicant/key id :data-key-hints-open true}}
         menu)))]))

(def ^:private confirm-menu-rank
  "Order of the grants in Allow's menu: the one-off block grant first,
   persisting rules after it, the meta action last."
  {:block 0 :always 1 :repo 2 :recommend 4})

(defn- confirm-buttons
  "Answer buttons for a :confirm dialog from its normalized :options
   (xi.dialog): a Deny split button (caret: deny with reason via
   `deny-reason!`) and an Allow one whose caret lists the other grants. A
   dialog without plain Allow renders its grants as standalone buttons."
  [dialog answer! deny-reason!]
  (let [options (dlg/confirm-options dialog)
        deny    (first (filter #(false? (:value %)) options))
        allow   (first (filter #(true? (:value %)) options))
        extras  (sort-by #(get confirm-menu-rank (:value %) 3)
                         (remove #(boolean? (:value %)) options))
        did     (str "confirm-" (:id dialog))
        block-n (get-in dialog [:block :count])
        extra-label (fn [{:keys [value label]}]
                      (if (and (= :block value) block-n) (str label " (" block-n ")") label))
        block-title "Allow every request of this block (hover to see them)"]
    (list
     (when deny
       (confirm-split {:id (str did "-deny")
                       :class ["confirm-btn--deny"]
                       :label (:label deny)
                       :value false
                       :data-key-action (confirm-key-action false)
                       :caret-label "Deny with reason"
                       :answer! answer!}
                      [(when (and deny-reason! (:deny-reason? dialog))
                         (confirm-menu-item {:label "Deny with reason…"
                                             :desc  "Tell the agent why, in the composer"}
                                            {:key "deny-reason"
                                             :on-click (fn [e] (close-popover! e) (deny-reason!))}))]))
     (if allow
       (confirm-split {:id (str did "-allow")
                       :class ["confirm-btn--allow"]
                       :label (:label allow)
                       :value true
                       :data-key-action (confirm-key-action true)
                       :caret-label "More ways to allow"
                       :answer! answer!}
                      (for [{:keys [value] :as opt} extras
                            :let [block? (= :block value)]]
                        (list
                         (when (and (= :recommend value) (not= opt (first extras)))
                           [:div {:class ["confirm-menu-sep"] :replicant/key "sep"}])
                         (confirm-menu-item (assoc opt :label (extra-label opt))
                                            {:key (str value)
                                             :class (when block? ["confirm-btn--block"])
                                             :data-key-action (confirm-key-action value)
                                             :title (when block? block-title)
                                             :on-click (fn [e] (close-popover! e) (answer! value))}))))
       (for [{:keys [value] :as opt} extras
             :let [block? (= :block value)]]
         [:button {:class (cond-> ["confirm-btn" "confirm-btn--extra"] block? (conj "confirm-btn--block"))
                   :data-key-action (confirm-key-action value)
                   :title (when block? block-title)
                   :on {:click (fn [_] (answer! value))}}
          (extra-label opt)])))))

(defn- explain-button
  "The Explain action of a tool block's answer row: asks a sub-agent about
   the call (xi.ext.subagent.handlers/explain-call). `child` is that
   sub-agent, if started: spins while running, disabled once done."
  [dispatch! room-id call-id child]
  (let [status   (:status child)
        running? (= :running status)
        done?    (= :done status)]
    [:button {:class (cond-> ["confirm-btn" "confirm-btn--explain"]
                       running? (conj "confirm-btn--explain-running"))
              :disabled (or running? done?)
              :title "Ask a sub-agent what this call does and why"
              :on {:click (fn [_] (dispatch! {:type :subagent/explain-call
                                              :room-id room-id :call-id call-id}))}}
     (if running?
       [:span {:class ["agent-status-spinner--sm"]}]
       [:span {:class ["confirm-explain-mark"] :aria-hidden "true"} "?"])
     (cond running? "Explaining…"
           done?    "Explained"
           :else    "Explain")]))

(defn- explanation-block
  "The Explain sub-agent's text under a tool block, streamed while it runs.
   Hidden while it is still silent and the ask is `pending?` (the button's
   spinner shows the wait)."
  [{:keys [status history result]} pending?]
  (let [running? (= :running status)
        text     (or result (sa/final-text history))]
    (when (or (seq text) (not running?) (not pending?))
      [:div {:class ["tool-explain" (str "tool-explain--" (name (or status :running)))]}
       [:div {:class ["tool-explain-head"]}
        (icon/icon {:icon-name :info :size :sm})
        [:span "Explanation"]
        (when running? (spinner))]
       [:div {:class ["tool-explain-body" "post-content"]}
        (cond
          (seq text) (render-md text)
          running?   [:div {:class ["tool-explain-empty"]} "Explaining…"]
          :else      [:div {:class ["tool-explain-empty"]}
                      (case status
                        :stopped "The explanation was stopped."
                        "The explanation failed.")])]])))

(defn- diff-preview
  "The diff a guarded write/edit asked to make ({:path :text}), rendered like
   the edit tool's result diff. `k` / `code?` as for tool-diff-view."
  [dispatch! {:keys [path text] :as diff} k code?]
  (when diff
    (tool-diff-view dispatch! {:path path :text text :k k :code? code?
                               :grammar (grammars/get-grammar (file-ext path))
                               :attrs {:data-diff-path path :data-diff-text text}})))

(defn format-elapsed
  [secs]
  (let [mins (quot secs 60)]
    (if (>= mins 1)
      (str mins "m " (let [s (mod secs 60)] (if (< s 10) (str "0" s) s)) "s")
      (str secs "s"))))

(def ^:private run-timer-min-secs
  2)

(defn- run-timer-text [started-at]
  (let [secs (quot (max 0 (- (js/Date.now) started-at)) 1000)]
    (if (>= secs run-timer-min-secs) (format-elapsed secs) "")))

(defn- run-timer
  "Live elapsed-time label for a running tool call, repainted into the DOM
   node once a second (events stop arriving exactly when a call stalls).
   Keyed by `started-at` so a permission allow's restamp remounts it."
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

(defn- details-open?
  "A timeline <details>'s :open: the user's remembered `open-choice` (see
   remember-open), else the appearance default."
  [open-choice collapsed?]
  (if (some? open-choice) open-choice (not collapsed?)))

(defn- remember-open
  "A <summary> click handler that owns its <details>'s toggle: it records the
   choice under `k` in the session's :web/block-open (core's
   :timeline/block-toggle) and the re-render applies it, so the choice
   survives session switches."
  [dispatch! session-key k]
  (fn [^js e]
    (.preventDefault e)
    (dispatch! {:type :timeline/block-toggle :session-key session-key :key k
                :open? (not (.. e -currentTarget -parentNode -open))})))

;; ── Image viewer (:web/lightbox) ──
;; One image is ui.lightbox as is. Several get the same markup plus swipe,
;; ‹ › buttons and a counter, so ui-runtime's pinch / pan / double-tap zoom
;; keeps working on the image shown; a swipe only starts while it's unzoomed.

(defn- image-srcs
  "The data: URLs of the images among `atts` (attachment maps), in order."
  [atts]
  (into [] (keep (fn [{:keys [data media-type]}]
                   (when (str/starts-with? (or media-type "") "image/")
                     (str "data:" media-type ";base64," data))))
        atts))

(def ^:private swipe-axis-px 10)
(def ^:private swipe-commit-px 60)

(defn- swipe-handlers
  "The viewer overlay's pointer handlers: a sideways one-finger drag moves the
   image with the finger and, past swipe-commit-px, steps to its neighbour.
   The drag is kept on the overlay node so a re-render mid-drag keeps it; the
   runtime's own pointer-up clears the transform, which is the snap-back."
  [dispatch! has-prev? has-next?]
  (let [image (fn [^js overlay] (.querySelector overlay ".lightbox-image"))]
    {:pointerdown
     (fn [^js e]
       (let [overlay (.-currentTarget e)
             img     (image overlay)]
         ;; A second finger (a pinch) cancels the swipe.
         (aset overlay "__xiSwipe"
               (when (and (.-isPrimary e) img
                          (not (.hasAttribute img "data-zoomed"))
                          (not (.closest (.-target e) "button")))
                 {:id (.-pointerId e) :x0 (.-clientX e) :y0 (.-clientY e)}))))
     :pointermove
     (fn [^js e]
       (let [overlay (.-currentTarget e)
             {:keys [id x0 y0 axis] :as drag} (aget overlay "__xiSwipe")]
         (when (and drag (= id (.-pointerId e)))
           (let [dx   (- (.-clientX e) x0)
                 dy   (- (.-clientY e) y0)
                 axis (or axis
                          (when (> (js/Math.hypot dx dy) swipe-axis-px)
                            (if (> (js/Math.abs dx) (js/Math.abs dy)) :x :y)))]
             (aset overlay "__xiSwipe" (assoc drag :axis axis :dx dx))
             (when-let [^js img (and (= axis :x) (image overlay))]
               ;; Past either end the image only gives a little.
               (let [end? (if (pos? dx) (not has-prev?) (not has-next?))]
                 (set! (.. img -style -transition) "none")
                 (set! (.. img -style -transform)
                       (str "translateX(" (if end? (* dx 0.3) dx) "px)"))))))))
     :pointerup
     (fn [^js e]
       (let [overlay (.-currentTarget e)
             {:keys [id axis dx]} (aget overlay "__xiSwipe")]
         (aset overlay "__xiSwipe" nil)
         (when (and (= id (.-pointerId e)) (= axis :x)
                    (> (js/Math.abs dx) swipe-commit-px))
           (cond
             (and (neg? dx) has-next?) (dispatch! {:type :lightbox/step :delta 1})
             (and (pos? dx) has-prev?) (dispatch! {:type :lightbox/step :delta -1})))))
     :pointercancel
     (fn [^js e] (aset (.-currentTarget e) "__xiSwipe" nil))}))

(defn- image-viewer [dispatch! {:keys [srcs index dir]}]
  (let [close #(dispatch! {:type :lightbox/close})
        n     (count srcs)]
    (if (< n 2)
      (lightbox/lightbox {:src (first srcs) :on-close close})
      (let [has-prev? (pos? index)
            has-next? (< index (dec n))
            button    (fn [cls label icon-name on-click]
                        [:button {:class cls :type "button" :aria-label label
                                  :on {:click (fn [^js e]
                                                (.stopPropagation e)
                                                (on-click))}}
                         (icon/icon {:icon-name icon-name :size :md})])]
        [:div {:class ["lightbox-overlay" "lightbox-gallery"]
               :on (assoc (swipe-handlers dispatch! has-prev? has-next?)
                          :click (fn [_] (close)))}
         ;; Keyed by index: each image is a fresh node with its own zoom.
         [:img {:replicant/key index
                :class ["lightbox-image"
                        (case dir
                          :next "lightbox-image--from-next"
                          :prev "lightbox-image--from-prev"
                          nil)]
                :src (nth srcs index)
                :alt (str "Image " (inc index) " of " n)
                :on {:click (fn [^js e] (.stopPropagation e))}}]
         [:div {:class ["lightbox-counter"]} (str (inc index) " / " n)]
         (when has-prev?
           (button ["lightbox-nav" "lightbox-nav--prev"] "Previous image" :chevron-left
                   #(dispatch! {:type :lightbox/step :delta -1})))
         (when has-next?
           (button ["lightbox-nav" "lightbox-nav--next"] "Next image" :chevron-right
                   #(dispatch! {:type :lightbox/step :delta 1})))
         (button ["lightbox-close"] "Close" :x close)]))))

(defn- tool-post
  "A tool call's <details> block. `:grouped?` (inside a viewer group),
   `:collapsed?`, `:open-choice` + `:toggle!` (remember-open), `:tool-args`
   (the appearance setting) and `:room-ext` (for extension tool views) are
   stamped by chat-view."
  [dispatch! {:keys [id tool arguments result is-error status started-at diff
                     permission resolved-permission explanation room-id
                     grouped? collapsed? open-choice toggle! cwd room-ext
                     md-diff-code? tool-args]}]
  (let [name      (util/strip-mcp-prefix tool)
        summary   (tool-detail tool-args name arguments cwd)
        running?  (= :running status)
        text      (util/strip-file-hash (util/extract-text-content result))
        grammar   (when (and text (not is-error)) (tool-grammar name arguments))
        bash?     (contains? #{"Bash" "bash"} name)
        clj?      (= "clj" name)
        clj-code  (when clj? (not-empty (str (get-arg arguments :code))))
        clj-preview (when clj-code (first (str/split-lines clj-code)))
        imgs      (seq (result-images result))
        ext-view  (when (and (not running?) (seq text) (not imgs))
                    (tool-views/render {:tool name :arguments arguments
                                        :text text :is-error is-error}
                                       room-ext dispatch!))
        diff-result? (and (contains? #{"Edit" "edit" "clj_replace"} name)
                          (not is-error))
        ;; The asked-for change shows until the call is done; a finished
        ;; call's own result speaks for itself.
        preview   (when (not= :done status)
                    (or diff (get-in permission [:dialog :diff])))
        denied?   (and (some? resolved-permission) (not (:value resolved-permission)))
        has-content? (boolean (or clj-code preview (seq text) ext-view permission imgs))
        file-path (when (not= :all tool-args) (tool-file-path name arguments))
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
                :open (and has-content? (details-open? open-choice collapsed?))}
      ;; Nothing to expand yet (a running call without output): the header
      ;; stays shut until content arrives.
      ;; A file tool's whole header row opens the file menu; under the :all
      ;; appearance the row shows every argument instead.
      [:summary (cond-> {:class (cond-> ["tool-call-toggle"]
                                  bash? (conj "tool-call-toggle--wrap")
                                  (not has-content?) (conj "tool-call-toggle--static"))}
                  (not has-content?)
                  (assoc :on {:click (fn [^js e] (.preventDefault e))})
                  (and has-content? toggle!)
                  (assoc :on {:click toggle!})
                  file-path
                  (file-row-attrs file-path))
       [:span {:class (cond-> ["tool-call-toggle-icon"]
                        running? (conj "tool-call-toggle-icon--running")
                        (and (not running?) (not has-content?)) (conj "tool-call-toggle-icon--empty"))}
        (if running?
          (spinner)
          (icon/icon {:icon-name :chevron-right :size :sm}))]
       [:span {:class (cond-> ["tool-call-toggle-label"] bash? (conj "tool-call-toggle-label--wrap"))}
        [:span {:class ["tool-call-action"]} name]
        (subs label (count name))]
       (when clj-preview
         [:span {:class ["clj-head-preview"]} clj-preview])
       ;; No clock while the call waits on its ask; the allow restamps :started-at.
       (when running?
         [:span {:class ["tool-call-running"]}
          (when-not permission (run-timer started-at))])
       ;; Outcome badge: denied > error > allowed.
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
        ;; A pending ask's :target mutes the code outside it; its :block marks
        ;; every call the block's asks target.
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
        (and clj? (seq text))
        (clj-result-view text is-error)

        ext-view
        [:div {:class ["tool-call-content" "tool-call-content--ext"]} ext-view]

        (and (seq text) (not imgs))
        [:div (cond-> {:class ["tool-call-content"]}
                (tool-file-path name arguments)
                (assoc :data-file-path (tool-file-path name arguments))
                ;; The raw diff rides along for the context menu's View diff.
                (and diff-result? (tool-file-path name arguments))
                (assoc :data-diff-path (tool-file-path name arguments)
                       :data-diff-text text))
         (if diff-result?
           (tool-diff-view dispatch! {:path (tool-file-path name arguments) :text text
                                      :grammar grammar :k id :code? md-diff-code?})
           (let [shown (truncate-lines text 100)]
             [:pre {:class ["tool-call-code"]}
              (if grammar (highlight-code grammar shown) (plain-code shown))]))])
      ;; A pending permission ask renders as a zone inside the same box.
      (when-let [{:keys [dialog answer! deny-reason!]} permission]
        (let [{:keys [message text]} dialog]
          [:div {:class ["tool-call-content"]}
           [:div {:class ["tool-call-permission"]}
            [:div {:class ["tool-call-permission-msg"]} (or message text)]
            [:div {:class ["tool-call-permission-actions"]}
             (explain-button dispatch! room-id id explanation)
             (confirm-buttons dialog answer! deny-reason!)]]]))
      (when imgs
        [:div {:class ["tool-call-content" "user-images" "tool-call-images"]}
         (let [srcs (image-srcs imgs)]
           (map-indexed
            (fn [i src]
              [:img {:replicant/key i
                     :class ["user-image" "lightbox-thumb"]
                     :src src
                     :alt "viewed image"
                     :on {:click (fn [^js e]
                                   (.stopPropagation e)
                                   (dispatch! {:type :lightbox/open :src src :srcs srcs}))}}])
            srcs))])]
     ;; Outside the <details> so a collapsed block keeps it in view.
     (when explanation
       (explanation-block explanation (some? permission)))]))

;; ── Error card ───────────────────────────────────────────────────────────────

(defn- reset-clock-time
  [secs]
  (.toLocaleTimeString (js/Date. (* 1000 secs)) js/undefined
                       #js {:hour "numeric" :minute "2-digit"}))

(def ^:private error-card-icons
  {:rate-limit :clock
   :auth       :lock
   :billing    :alert-triangle
   :overloaded :zap
   :network    :circle-x})

(def ^:private model-switchable-errors
  "Error kinds another model can get around: their card offers Switch model."
  #{:rate-limit :billing :overloaded})

(defn- error-retry-target
  "The user message an :error entry at `p` failed to answer: the nearest
   earlier entry, skipping other errors, when it is a :user one → the
   :bubble/retry payload {:index :text :images}; nil otherwise."
  [entries p]
  (when (= :error (:kind (nth entries p nil)))
    (when-let [i (->> (range (dec p) -1 -1)
                      (remove #(= :error (:kind (nth entries %))))
                      first)]
      (let [e (nth entries i)]
        (when (= :user (:kind e))
          {:index i :text (or (:text e) "") :images (:images e)})))))

(defn- error-card
  "Plain-language card for a recognised agent error (xi.error-info), raw
   error behind a details toggle. Answering a user message (`retry`, see
   error-retry-target) it offers Retry; otherwise a rate-limit card offers
   Continue. Rate-limit, billing and overload cards add Switch model."
  [dispatch! {:keys [kind title subtitle resets-at windows raw edn? room-id retry]}]
  (let [mins      (error-info/minutes-until resets-at (.now js/Date))
        continue? (and (nil? retry) (= :rate-limit kind))
        lead?     (or (some? retry) continue?)
        switch?   (contains? model-switchable-errors kind)]
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
      (when (and room-id (or lead? switch?))
        [:div {:class ["error-card-actions"]}
         (when retry
           (button/button
            {:variant :primary :size :sm :class "error-card-primary"
             :on-click (fn [_] (dispatch! (assoc retry :type :bubble/retry)))}
            "Retry"))
         (when continue?
           (button/button
            {:variant :primary :size :sm :class "error-card-primary"
             :on-click (fn [_] (dispatch! {:type :input/submit
                                           :room-id room-id
                                           :text "continue"}))}
            "Continue"))
         (when switch?
           (button/button
            {:variant (if lead? :ghost :primary) :size :sm
             :class (if lead? "error-card-secondary" "error-card-primary")
             :on-click (fn [_] (dispatch! {:type :palette/open-models}))}
            "Switch model"))])
      [:details {:class ["error-card-details"]}
       [:summary {:class ["error-card-details-toggle"]}
        [:span {:class ["tool-call-toggle-icon"]}
         (icon/icon {:icon-name :chevron-right :size :sm})]
        "Technical details"]
       [:pre {:class ["error-card-raw"]}
        (if edn? (or (highlight-clj-code raw) (plain-code raw)) raw)]]]]))

;; ── History entry → post ─────────────────────────────────────────────────────

(defn- post-badge
  "The sender's avatar on a :user bubble from another user; `:sender` is
   stamped by the timeline on the last bubble of a run (see other-user and
   last-of-run?)."
  [{:keys [sender]}]
  (when sender
    [:span {:class ["post-badge"]} (user-avatar sender)]))

(defn- status-post
  "A :status history entry as a ui.alert. Plain entries are a slim neutral
   banner of :text; entries with a :title (shape in the xi.commands ns doc) show
   :tone, :icon, :detail and :rows, :text being the TUI's rendering. An
   :action adds a button running its slash command, or opening its :url; a
   :goto one opening a session's chat."
  [dispatch! {:keys [text title detail rows tone icon action goto room-id]}]
  (let [rows   (when (seq rows)
                 (into [:span {:class ["status-alert-rows"]}]
                       (mapcat (fn [{:keys [label text tone]}]
                                 (let [cls #(cond-> [%] tone (conj (str "status-alert-row--" (name tone))))]
                                   [[:span {:class (cls "status-alert-row-label")} label]
                                    [:span {:class (cls "status-alert-row-text")} text]])))
                       rows))
        button (when (and action room-id)
                 (if-let [url (:url action)]
                   (button/button
                    {:variant :secondary :size :sm :class "status-alert-action"
                     :href url :attrs {:target "_blank" :rel "noopener"}}
                    (:label action))
                   (button/button
                    {:variant :secondary :size :sm :class "status-alert-action"
                     :on-click (fn [_] (dispatch! (cond-> {:type :web/command :room-id room-id
                                                           :name (:command action)}
                                                    (:args action) (assoc :args (:args action)))))}
                    (:label action))))
        goto   (when goto
                 (button/button
                  {:variant :secondary :size :sm :class "status-alert-action"
                   :on-click (fn [_] (dispatch! {:type :route/navigate :page :chat
                                                 :session-id (:session-id goto)}))}
                  (:label goto)))
        buttons (when (or goto button)
                  [:span {:class ["status-alert-actions"]} goto button])]
    [:div {:class (cond-> ["post" "post--assistant" "post--status"]
                    (not title) (conj "post--status-slim"))}
     (apply alert/alert {:variant tone :title title
                         ;; no :tone → neutral banner with the info icon; with
                         ;; a tone the alert's variant picks its own icon
                         :icon-name (or icon (when-not tone :info))
                         :class "status-alert" :attrs {:role "status"}}
            (remove nil? (if title [detail rows buttons] [text buttons])))]))

(defn- block-action-card
  "A message an agent block's Send / Submit posted for the user
   (xi.blocks/action-message): which block, and its state, picked lines
   marked, instead of the raw tag the agent reads."
  [{:keys [id kind action body]}]
  [:div {:class ["block-action-card"]}
   [:div {:class ["block-action-head"]}
    (icon/icon {:icon-name :layout-dashboard :size :sm})
    [:span "From block " [:code id]]
    [:span {:class ["block-action-kind"]} (str kind " · " action)]]
   [:ul {:class ["block-action-lines"]}
    (for [[i line] (map-indexed vector (str/split-lines body))]
      [:li {:replicant/key i
            :class (cond
                     (re-find #"^\s*[\(\[]x[\)\]]" line) "block-action-picked"
                     (re-find #"^\s*[\(\[] [\)\]]" line) "block-action-unpicked")}
       line])]])

(defn- bubble-menu-opener
  "DOM-event handler opening the user bubble's menu (bubble-menu) for the
   history entry at `idx`, at the pointer."
  [dispatch! idx entry]
  (fn [^js e]
    (dispatch! {:type :bubble/menu-open
                :index idx
                :text (:text entry)
                :images (:images entry)
                :x (.-clientX e)
                :y (.-clientY e)})))

;; ── Image attachments ────────────────────────────────────────────────────────

(def ^:private MAX_IMAGE_DIMENSION
  1568)

(defn- resize-image-file
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
  "Read any File as base64; promise of {:data :media-type :name} or nil on
   failure. Non-image attachments reach the agent by on-disk path."
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

(defn- read-attachments!
  "Read Files into attachment maps (images resized, the rest raw) and call
   `on-ready` with the vector of readable ones; nothing when none read."
  [files on-ready]
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
                   (on-ready (vec valid)))))
        (.catch (fn [err] (js/console.error "[xi-web] attachment read failed:" err))))))

(defn- add-files!
  [dispatch! draft-key files]
  (read-attachments! files
                     (fn [images]
                       (dispatch! {:type :compose/add-images :draft-key draft-key
                                   :images images}))))

(defn- pasted-image-files [^js e]
  (let [items (.. e -clipboardData -items)]
    (->> (range (.-length items))
         (keep (fn [i]
                 (let [^js item (aget items i)]
                   (when (str/starts-with? (.-type item) "image/")
                     (.getAsFile item))))))))

(defn- handle-prompt-paste!
  "Paste into a prompt textarea (the composer, the bubble editor): image
   files go to `on-files`; long / code-like text gets fenced
   (xi.util/fence-paste), and `on-text` gets the textarea's new value when
   the browser could not insert it itself."
  [^js e {:keys [on-files on-text]}]
  (let [files (pasted-image-files e)]
    (if (seq files)
      (do (.preventDefault e)
          (on-files files))
      ;; Inserted via execCommand so it lands on the browser's undo stack
      ;; (assigning .value wipes it) and fires the :input that saves the draft.
      ;; Re-setting the selection first ends Chrome's typing group, so the paste
      ;; is its own undo step instead of merging with the text typed before it.
      (let [text (some-> (.-clipboardData e) (.getData "text"))]
        (when (and (seq text) (util/paste-should-fence? text))
          (.preventDefault e)
          (let [^js ta (.-target e)
                value (.-value ta)
                before (subs value 0 (.-selectionStart ta))
                after (subs value (.-selectionEnd ta))
                insertion (util/fence-paste
                           text
                           {:at-line-start? (or (empty? before)
                                                (str/ends-with? before "\n"))
                            :at-line-end? (or (empty? after)
                                              (str/starts-with? after "\n"))})]
            (.setSelectionRange ta (.-selectionStart ta) (.-selectionEnd ta))
            (when-not (.execCommand js/document "insertText" false insertion)
              (.setRangeText ta insertion (count before) (- (count value) (count after)) "end")
              (on-text (.-value ta)))))))))

(defn- handle-compose-paste! [dispatch! draft-key ^js e]
  (handle-prompt-paste!
   e
   {:on-files (fn [files] (add-files! dispatch! draft-key files))
    :on-text (fn [text] (dispatch! {:type :compose/set-draft
                                    :draft-key draft-key
                                    :text text}))}))

(defn- attachment-strip
  "Staged attachments as a row of thumbs and file chips, each with an ×
   that calls `(on-remove idx)`. `class` adds a modifier to .compose-images."
  [dispatch! images {:keys [on-remove class]}]
  (when (seq images)
    [:div {:class ["compose-images" class]}
     (map-indexed
      (fn [idx {:keys [data media-type name]}]
        (if-not (str/starts-with? (or media-type "") "image/")
          [:div {:replicant/key idx :class ["compose-attachment-chip"]}
           (icon/icon {:icon-name :file-text :size :md})
           [:span {:class ["compose-attachment-name"]} (or name "file")]
           [:button {:class ["compose-attachment-remove"] :type "button"
                     :on {:click (fn [_] (on-remove idx))}}
            (icon/icon {:icon-name :x :size :sm})]]
          [:div {:replicant/key idx :class ["compose-image-thumb"]}
           (let [src (str "data:" media-type ";base64," data)]
             [:img {:src src :alt "attachment" :class ["lightbox-thumb"]
                    :on {:click (fn [_] (dispatch! {:type :lightbox/open :src src
                                                    :srcs (image-srcs images)}))}}])
           [:button {:class ["compose-image-remove"] :type "button"
                     :on {:click (fn [_] (on-remove idx))}}
            (icon/icon {:icon-name :x :size :sm})]]))
      images)]))

(defn- compose-image-strip [dispatch! draft-key images]
  (attachment-strip dispatch! images
                    {:on-remove (fn [idx]
                                  (dispatch! {:type :compose/remove-image
                                              :draft-key draft-key :idx idx}))}))

(defn- viewable-file? [path]
  (let [ext (file-ext path)]
    (or (contains? md-diff/markdown-exts ext) (some? (grammars/get-grammar ext)))))

(defn- attachment-meta
  "`PDF · 2.4 MB` for a file attachment: its extension (else the media
   subtype) and the decoded size of its base64 `data`."
  [{:keys [data media-type name]}]
  (let [ext   (or (second (re-find #"\.([A-Za-z0-9]{1,8})$" (or name "")))
                  (some-> media-type (str/split #"/") second))
        bytes (when (string? data)
                (- (* 3 (quot (count data) 4))
                   (count (re-find #"=+$" data))))
        size  (when bytes
                (cond
                  (< bytes 1024)    (str bytes " B")
                  (< bytes 1048576) (str (js/Math.round (/ bytes 1024)) " KB")
                  :else             (str (.toFixed (/ bytes 1048576) 1) " MB")))]
    (str/join " · " (remove str/blank? [(some-> ext str/upper-case) size]))))

(defn- user-attachment
  "One attachment of a prompt: an image (opens the lightbox on `srcs`, the
   prompt's images) or a file card."
  [dispatch! srcs i {:keys [data media-type name path] :as att}]
  (if (str/starts-with? (or media-type "") "image/")
    (let [src (str "data:" media-type ";base64," data)]
      [:img {:replicant/key i
             :class ["user-image" "lightbox-thumb"]
             :src src
             :alt (or name "attached image")
             :on {:click (fn [^js e]
                           (.stopPropagation e)
                           (dispatch! {:type :lightbox/open :src src :srcs srcs}))}}])
    [:div (cond-> {:replicant/key i
                   :class ["user-attachment-card"
                           (when (= media-type "application/pdf") "user-attachment-card--pdf")]}
            (and (or (string? data) path)
                 (or (str/starts-with? (or media-type "") "text/")
                     (viewable-file? (or name ""))))
            (-> (update :class conj "user-attachment-card--viewable")
                (assoc :title "View file"
                       :on {:click (fn [^js e]
                                     (.stopPropagation e)
                                     (dispatch! {:type :attachment/open
                                                 :name (or name "file") :data data :path path}))})))
     [:span {:class ["user-attachment-icon"]}
      (icon/icon {:icon-name :file-text :size :md})]
     [:span {:class ["user-attachment-text"]}
      [:span {:class ["user-attachment-name"]} (or name "file")]
      [:span {:class ["user-attachment-meta"]} (attachment-meta att)]]]))

(defn- user-attachments
  "A prompt's attachments right-aligned above its bubble: the images side by
   side in one row, the file cards stacked below. With `on-remove` (the bubble
   editor) each one carries an × calling `(on-remove i)`."
  ([dispatch! entry] (user-attachments dispatch! entry nil))
  ([dispatch! entry on-remove]
   (if-let [atts (seq (:images entry))]
     (let [srcs (image-srcs atts)
           node (fn [i att]
                  (let [n (user-attachment dispatch! srcs i att)]
                    (if on-remove
                      [:div {:replicant/key i :class ["user-attachment-edit"]}
                       n
                       [:button {:class ["user-attachment-remove"] :type "button"
                                 :aria-label "Remove attachment"
                                 :on {:click (fn [^js e]
                                               (.stopPropagation e)
                                               (on-remove i))}}
                        (icon/icon {:icon-name :x :size :sm})]]
                      n)))
           image? #(str/starts-with? (or (:media-type (second %)) "") "image/")
           {images true files false} (group-by image? (map-indexed vector atts))]
       [:div {:class ["user-attachments"]}
        (when images
          [:div {:replicant/key "images" :class ["user-attachment-images"]}
           (map #(apply node %) images)])
        (map #(apply node %) files)])
     (when-let [n (:image-count entry)]
       (when (pos? n)
         [:div {:class ["status-text"]} (str "📎 " n " image" (when (> n 1) "s"))])))))

(defn- bubble-editor
  "The inline editor of a sent prompt (:web/editing-bubble), in place: the
   bubble keeps its look and size, the text becomes a textarea sized to it
   (data-value feeds the CSS replica that grows it), the attachments above
   each get an × that drops them, a paste adds images, and Cancel/Save sit
   below the bubble. Save forks the chat at the message (xi.web.core
   bubble-edit-save). The children are keyed so the textarea survives the
   attachments appearing."
  [dispatch! {:keys [edit-text edit-images]}]
  [:div {:class ["post" "post--user" "post--editing"]}
   (some-> (user-attachments dispatch! {:images edit-images}
                             (fn [idx]
                               (dispatch! {:type :bubble/edit-remove-image :idx idx})))
           (assoc-in [1 :replicant/key] "attachments"))
   [:div {:replicant/key "body" :class ["post-body"]}
    [:span {:class ["bubble-edit-tag"]} "Editing"]
    [:div {:class ["post-content" "bubble-edit-grow"] :data-value edit-text}
    [:textarea {:replicant/key "text"
                :class ["bubble-edit-textarea"]
                :rows 1
                :value edit-text
                :replicant/on-mount
                (fn [{:replicant/keys [^js node]}]
                  ;; Scroll into view ourselves once the keyboard has animated in.
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
                     :paste (fn [^js e]
                              (handle-prompt-paste!
                               e
                               {:on-files (fn [files]
                                            (read-attachments!
                                             files
                                             (fn [images]
                                               (dispatch! {:type :bubble/edit-add-images
                                                           :images images}))))
                                :on-text (fn [text]
                                           (dispatch! {:type :bubble/edit-change
                                                       :text text}))}))
                     :keydown (fn [^js e]
                                (when (= "Escape" (.-key e))
                                  (dispatch! {:type :bubble/edit-cancel})))}}]]]
   [:div {:replicant/key "actions" :class ["bubble-edit-actions"]}
    (button/button
     {:variant :ghost :size :sm
      :on-click (fn [_] (dispatch! {:type :bubble/edit-cancel}))}
     "Cancel")
    (button/button
     {:variant :primary :size :sm
      :on-click (fn [_] (dispatch! {:type :bubble/edit-save}))}
     "Save")]])

(defn- entry->post [dispatch! entry]
  (case (:kind entry)
    :user
    (let [idx (:history-index entry)]
      (cond
        (:editing? entry)
        (bubble-editor dispatch! entry)
        ;; Skill/command-generated prompts collapse to a one-line bubble. A tap
        ;; toggles it, so the bubble menu opens on right-click or a touch
        ;; long-press (data-long-press, the ui runtime) only.
        (:collapsed-label entry)
        [:div (cond-> {:class ["post" "post--user" "post--user-collapsed"
                               (when (:sender entry) "post--badged")]}
                idx (assoc :data-history-index idx
                           :data-long-press "true"
                           :on (let [open! (bubble-menu-opener dispatch! idx entry)]
                                 {:contextmenu (fn [^js e]
                                                 (.preventDefault e)
                                                 (open! e))})))
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
                                (bubble-menu-opener dispatch! idx entry))))
         (user-attachments dispatch! entry)
         ;; An attachments-only prompt has no bubble; its badge hangs off the
         ;; attachments instead.
         (if (seq (:text entry))
           [:div {:class ["post-body"]}
            [:div {:class ["post-content"]}
             (if-let [action (blocks/parse-action-message (:text entry))]
               (block-action-card action)
               (render-md (:text entry) {:hard-breaks? true}))]
            (post-badge entry)]
           (when (:sender entry)
             [:div {:class ["user-attachments-badge"]} (post-badge entry)]))]))

    :text
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-body"]}
      (render-md (:text entry))]]

    :thinking
    [:div {:class ["post" "post--assistant" "post--thinking"]}
     [:details {:class (cond-> ["thinking-block"]
                         (:grouped? entry) (conj "thinking-block--viewer"))
                :open (details-open? (:open-choice entry) (:collapsed? entry))}
      [:summary (cond-> {:class ["thinking-toggle"]}
                  (:toggle! entry) (assoc :on {:click (:toggle! entry)}))
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class ["tool-call-toggle-label"]}
        [:span {:class ["tool-call-action"]} "Thinking"]]]
      (into [:pre {:class ["thinking-text"]}] (md/linkify (str/trimr (:text entry))))]]

    :tool-call
    (tool-post dispatch! entry)

    :status
    (status-post dispatch! entry)

    :error
    (let [msg (or (:message (:error entry)) (pr-str (:error entry)))]
      (when-not (str/includes? (str msg) "null is not an object")
        (if-let [info (error-info/describe (:error entry))]
          (error-card dispatch! (assoc info :room-id (:room-id entry)
                                            :retry (:retry entry)))
          [:div {:class ["post" "post--assistant"]}
           [:div {:class ["post-content" "error-text"]} (str "[Error] " msg)]])))

    :aborted
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} "Interrupted."]]

    nil))

(defn- with-post-key
  "Attach a stable :replicant/key so timeline posts reconcile by identity;
   positional reconciling crashes when the window slides or an entry drops."
  [k node]
  (cond
    (not (vector? node))  node
    (map? (second node))  (assoc-in node [1 :replicant/key] k)
    :else                 (into [(first node) {:replicant/key k}] (rest node))))

(def ^:private askable-kinds
  "History entry kinds whose posts carry a thread (with-ask-ref)."
  #{:user :text :tool-call :thinking :error})

(defn- with-ask-ref
  "Mark a post as a block one can ask about: entry `index` of the room's
   history, or with `sub-id` of that sub-agent's (no index: the sub-agent
   itself). The floating Ask button and the touch menu (xi.web.threads)
   read these attributes."
  [node {:keys [index sub-id]}]
  (let [attrs (cond-> {}
                (number? index) (assoc :data-ask-index index)
                sub-id          (assoc :data-ask-sub sub-id))]
    (cond
      (not (vector? node))  node
      (map? (second node))  (update node 1 merge attrs)
      :else                 (into [(first node) attrs] (rest node)))))

;; ── Compose ──────────────────────────────────────────────────────────────────

(defn- compose-textarea-el []
  (.querySelector js/document ".compose-input-wrapper textarea"))

(defn- sync-compose-draft!
  "On a chat switch (tracked in a data attribute), write the new chat's draft
   into the reused textarea: child text only sets the default value, which a
   dirty DOM value no longer follows."
  [^js node draft-key draft]
  (let [k (str draft-key)]
    (when-not (= k (.getAttribute node "data-draft-key"))
      (.setAttribute node "data-draft-key" k)
      (set! (.-value node) (or draft "")))))

;; ── File drop zone ───────────────────────────────────────────────────────────

(defn- files-drag?
  [^js e]
  (when-let [types (some-> e .-dataTransfer .-types)]
    (boolean (some #{"Files"} (array-seq types)))))

(defn- file-drop-attrs
  "Event handlers making the chat view a drop target: dragging files raises
   the overlay (:web/file-drag?), dropping stages them on `draft-key`."
  [dispatch! draft-key file-drag?]
  {:dragenter (fn [^js e]
                (when (and (files-drag? e) (not file-drag?))
                  (dispatch! {:type :web/file-drag :on? true})))
   ;; Without preventDefault the browser refuses the drop.
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
  "Covers the chat view while files are dragged over it. Always rendered so
   the child list never toggles; a mousemove/click means the drag ended
   unseen, so lower it then too."
  [dispatch! active?]
  (let [lower! (fn [_] (dispatch! {:type :web/file-drag :on? false}))]
    [:div {:class ["file-drop-overlay" (when active? "file-drop-overlay--active")]
           :on {:dragleave lower! :mousemove lower! :click lower!}}
     [:div {:class ["file-drop-overlay-label"]}
      (icon/icon {:icon-name :file-text :size :md})
      [:span "Drop files to attach"]]]))

;; ── Command suggestions ───────────────────────────────────────────────────────

(def ^:private web-page-commands
  "Web palette sub-pages run as commands (dispatch-command!). They shadow a
   published command of the same name (the diff extension's terminal
   /commits menu)."
  (mapv #(assoc % :while-busy? true :source {:kind :web})
        [{:name "commits" :description "List commits made this session"}
         {:name "log"     :description "Browse the git log"}
         {:name "files"   :description "Browse project files"}
         {:name "skills"  :description "Browse and load skills"}]))

(def ^:private web-commands-of
  (memoize
   (fn [published]
     (let [pages (into #{} (map :name) web-page-commands)]
       (palette/order-commands
        (into (filterv #(and (commands/on-surface? :web %) (not (pages (:name %))))
                       (or published (commands/specs commands/built-in-commands)))
              web-page-commands))))))

(defn web-commands
  "Commands in the web suggestion popup and palettes, featured first: the
   server's (xi.commands/listed; the built-ins until they arrive) plus the
   web's palette sub-pages."
  [state]
  (web-commands-of (:commands state)))

(defn draft-key
  "Per-chat key for chat-scoped state (drafts, …): the virtual room's id
   while composing a new chat (it must win, the client is still attached to
   the previous room), else the active room's session id, else the route's,
   else `:new`."
  [state]
  (or (get-in state [:web/pending-room :id])
      (get-in (state/active-room state) [:session :id])
      (get-in state [:web/route :session-id])
      :new))

(defn- command-names
  "Every name and alias of `cmds`."
  [cmds]
  (into #{} (mapcat #(cons (:name %) (:aliases %))) cmds))

(defn known-command?
  [cmds name]
  (contains? (command-names cmds) name))

(defn command-usage-name
  "The name a run of `name` with `args` is recorded under in
   :web/command-usage: \"yolo sane\" when the first arg is a subcommand of
   `name` in `cmds`, else `name`."
  [cmds name args]
  (let [sub     (first (str/split (str/trim (or args "")) #"\s+"))
        subcmds (some #(when (= name (:name %)) (:subcommands %)) cmds)]
    (if (some #(= sub (:name %)) subcmds)
      (str name " " sub)
      name)))

(defn command-while-busy?
  "True when `text` is a slash command of `cmds` flagged :while-busy?
   (matched on the leading token, so subcommands qualify)."
  [cmds text]
  (let [t (str/trim (or text ""))]
    (and (str/starts-with? t "/")
         (contains? (command-names (filter :while-busy? cmds))
                    (-> t (subs 1) (str/split #"\s+") first)))))

(defn submittable-while-busy?
  "True when `text` may be submitted while the agent is busy: a plain prompt
   (queued) or a :while-busy? command of `cmds`."
  [cmds text]
  (let [t (str/trim (or text ""))]
    (or (not (str/starts-with? t "/"))
        (command-while-busy? cmds t))))

(defn match-commands
  "Commands (and subcommands) matching the prefix `query`, as families (a
   command with its subcommands): the family whose name or alias is exactly
   `query` first, the others by use. Within a family, entries go by their own
   use. Use = `counts` (:web/command-counts, name → runs), ties broken by
   `usage` (:web/command-usage, most recent first); names as in
   command-usage-name. `skills` ({:name :description}) join as entries with
   :skill, tracked as \"skill:<name>\" (xi.web.core/skill-select). Unused
   entries keep declaration order, commands before skills. `commands`
   defaults to the web's list before the server sent one (`web-commands`)."
  [query {:keys [usage counts skills commands]}]
  (let [q        (str/lower-case (or query ""))
        family   (fn [name] (first (str/split name #" ")))
        rank     (zipmap usage (range))
        fam-rank (reduce-kv (fn [m n i] (update m (family n) (fnil min i) i)) {} rank)
        fam-count (reduce-kv (fn [m n c] (update m (family n) (fnil + 0) c)) {} counts)
        never    (count usage)
        exact?   (fn [{:keys [name aliases]}] (boolean (or (= name q) (some #{q} aliases))))
        stat-key (fn [{:keys [name skill]}] (if skill (str "skill:" skill) name))
        all      (concat (palette/expand-commands (or commands (web-commands nil)))
                         (map (fn [{:keys [name description]}]
                                {:name name :skill name
                                 :description (str "Skill" (when (seq description)
                                                             (str " · " description)))})
                              skills))
        exact-family (some #(when (exact? %) (family (stat-key %))) all)]
    (->> all
         (filter #(or (str/starts-with? (:name %) q) (exact? %)))
         (sort-by (fn [e]
                    (let [k   (stat-key e)
                          fam (family k)]
                      [(if (= exact-family fam) 0 1)
                       (- (get fam-count fam 0))
                       (get fam-rank fam never)
                       (- (get counts k 0))
                       (get rank k never)])))
         vec)))

(defn dispatch-command!
  "Fire a slash command (\"/diff staged\", \"diff staged\" or \"diff\").
   Web-only commands open their palette sub-page here (and are recorded
   here, since the :web/command tap never sees them); backend commands go
   through :web/command, which stashes them until a room is joined."
  [dispatch! room-id slash]
  (let [{:keys [name args]}
        (commands/parse-input (if (str/starts-with? slash "/") slash (str "/" slash)))]
    (when (or (#{"commits" "log" "files" "skills"} name)
              (and (= name "model") (not args)))
      (dispatch! {:type :web/record-command :name name}))
    (case name
      "commits" (dispatch! {:type :palette/open-commits})
      "log"     (dispatch! {:type :palette/open-commits :scope :log})
      "files"   (dispatch! {:type :palette/open-files})
      "skills"  (dispatch! {:type :palette/open-skills})
      "model"   (if args
                  (dispatch! {:type :web/command :room-id room-id
                              :name name :args args})
                  (dispatch! {:type :palette/open-models}))
      (dispatch! (cond-> {:type :web/command :room-id room-id :name name}
                   args (assoc :args args))))))

(defn slash-ctx
  "match-commands' options from state: the commands, usage, counts and the
   recently used skills (described once the skill list is loaded, gone ones
   dropped); :skill-names are all skills a typed \"/<skill> args\" may run
   (a command of the same name wins)."
  [state]
  (let [listed  (:web/skill-list state)
        by-name (into {} (map (juxt :name identity)) listed)
        recents (:web/recent-skills state)
        cmds    (web-commands state)]
    {:commands    cmds
     :usage       (:web/command-usage state)
     :counts      (:web/command-counts state)
     :skills      (if listed
                    (vec (keep by-name recents))
                    (mapv (fn [n] {:name n}) recents))
     :skill-names (into #{} (remove (command-names cmds))
                        (concat recents (map :name listed)))}))

(defn- clear-compose! [dispatch! draft-key]
  (when-let [^js el (compose-textarea-el)]
    (set! (.-value el) ""))
  (dispatch! {:type :compose/clear-draft :draft-key draft-key}))

(defn- pick-command!
  "A command picked in the suggestion popup or the palette: one that
   `:takes-args?` is completed into the composer (\"/subagent \") for its
   argument, any other runs at once (and the typed \"/…\" is cleared). A
   skill runs through :skill/select, which opens its form when it has inputs."
  [dispatch! room-id draft-key {:keys [name takes-args? skill]}]
  (cond
    skill
    (do (dispatch! {:type :skill/select :name skill})
        (clear-compose! dispatch! draft-key))

    takes-args?
    (dispatch! {:type :compose/prefill :draft-key draft-key :text (str "/" name " ")})

    :else
    (do (dispatch-command! dispatch! room-id name)
        (clear-compose! dispatch! draft-key))))

(defn- skill-invocation
  "[skill args] when `text` is \"/<skill> args\" for one of `skill-names`
   (submit-compose! runs it client-side)."
  [text skill-names]
  (let [{:keys [name args]} (when (str/starts-with? text "/") (commands/parse-input text))]
    (when (and name (contains? skill-names name))
      [name args])))

(defn- command-suggestions
  [dispatch! room-id draft-key commands selected-index]
  (when (seq commands)
    [:div {:class ["slash-dropdown"]}
     (map-indexed
      (fn [i {:keys [name description] :as cmd}]
        (let [selected? (= i selected-index)]
          [:button {:class ["slash-item"
                            (when selected? "slash-item--selected")]
                    :replicant/on-render
                    (fn [{:replicant/keys [^js node]}]
                      (when selected?
                        (.scrollIntoView node #js {:block "nearest"})))
                    :on {:click (fn [_] (pick-command! dispatch! room-id draft-key cmd))
                         :mouseenter (fn [_]
                                       (dispatch! {:type :cmd/select :index i}))}}
           [:span {:class ["slash-item-name"]} (str "/" name)]
           [:span {:class ["slash-item-desc"]} description]]))
      commands)]))

(defn- submit-compose!
  "Send the composer's text and attachments. `:steer?` (Alt+Enter) asks for
   the message to go into the running turn instead of the queue."
  [dispatch! room-id session-id images draft-key draft skill-names & [{:keys [steer?]}]]
  (let [text (str/trim (or draft ""))
        [skill args] (when (empty? images) (skill-invocation text skill-names))]
    (when (or (seq text) (seq images))
      (clear-compose! dispatch! draft-key)
      (when (seq images)
        (dispatch! {:type :compose/clear-images :draft-key draft-key}))
      (cond
        skill
        (dispatch! {:type :skill/select :name skill :args args})

        room-id
        (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                     (seq images) (assoc :images (vec images))
                     steer?       (assoc :steer? true)))

        :else
        (dispatch! (cond-> {:type :submit/pending :session-id session-id :text text}
                     (seq images) (assoc :images (vec images))))))))

(defn- prompt-nav-controls
  "Prompt navigation control: a single up-arrow when collapsed (plus a
   scroll-to-bottom arrow while scrolled up); opened, a position / total
   group with older / newer arrows. `nav-ctx` is prompt-nav-ctx."
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


(defn- steer-icon
  "Steering a message into the running turn: the send button's face while
   Alt is held, the queue popover's steer button. Phosphor's hand-pointing
   (MIT), inline: the framework's icon set has no hand. `size` :md or :sm."
  ([] (steer-icon :md))
  ([size]
   [:svg {:class ["icon" (when (= :sm size) "icon-sm")] :xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 256 256" :fill "currentColor" :aria-hidden "true"}
    [:path {:d "M196,88a27.86,27.86,0,0,0-13.35,3.39A28,28,0,0,0,144,74.7V44a28,28,0,0,0-56,0v80l-3.82-6.13A28,28,0,0,0,35.73,146l4.67,8.23C74.81,214.89,89.05,240,136,240a88.1,88.1,0,0,0,88-88V116A28,28,0,0,0,196,88Zm12,64a72.08,72.08,0,0,1-72,72c-37.63,0-47.84-18-81.68-77.68l-4.69-8.27,0-.05A12,12,0,0,1,54,121.61a11.88,11.88,0,0,1,6-1.6,12,12,0,0,1,10.41,6,1.76,1.76,0,0,0,.14.23l18.67,30A8,8,0,0,0,104,152V44a12,12,0,0,1,24,0v68a8,8,0,0,0,16,0V100a12,12,0,0,1,24,0v20a8,8,0,0,0,16,0v-4a12,12,0,0,1,24,0Z"}]]))

(defn- queue-popover
  "The queued messages, each with: steer it into the running turn (only
   while the turn can take one, xi.agent/steerable?), run it in a sub-agent,
   remove it."
  [dispatch! room-id queued steerable?]
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
       (when steerable?
         [:button {:class ["icon-btn" "icon-btn--sm" "queue-item-action" "queue-item-steer"]
                   :title "Send into the running turn now" :aria-label "Send into the running turn now"
                   :on {:click (fn [_]
                                 (dispatch! {:type :prompt/queue-steer
                                             :room-id room-id :index idx}))}}
          (steer-icon :sm)])
       [:button {:class ["icon-btn" "icon-btn--sm" "queue-item-action"]
                 :title "Run in a sub-agent now" :aria-label "Run in a sub-agent now"
                 :on {:click (fn [_]
                               (dispatch! {:type :subagent/run-queued
                                           :room-id room-id :index idx}))}}
        (icon/icon {:icon-name :play :size :sm})]
       [:button {:class ["icon-btn" "icon-btn--sm" "queue-item-action"]
                 :title "Remove from queue" :aria-label "Remove from queue"
                 :on {:click (fn [_]
                               (dispatch! {:type :prompt/queue-remove
                                           :room-id room-id :index idx}))}}
        (icon/icon {:icon-name :x :size :sm})]])
    queued)])

(defn- floating-actions
  "Action row inside .compose-frame (icon pills on mobile, a labeled bar on
   desktop): prompt-nav, Projects, Snippets, Commands, then at the right
   `agents` (the Agents button, views/agents-button) and the queue count
   that toggles the queue popover."
  [dispatch! room-id pa? prompt-nav nav-ctx scrolled-up? queued agents]
  (let [qcount (count queued)]
    (when (or (not pa?) (pos? qcount) (seq agents))
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
     (when (or (pos? qcount) (seq agents))
       [:div {:class ["queue-float"]}
        [:div {:class ["fgroup"]}
         agents
         (when (pos? qcount)
           [:button {:class ["quick-cmd" "queue-num"]
                     :title "Queued messages" :aria-label "Queued messages"
                     :replicant/key "queue-num"
                     :on {:click (fn [_] (dispatch! {:type :queue/toggle-popover}))}}
            (str qcount)])]])])))

(defn- offline-indicator
  "Wifi-off glyph beside the send button while disconnected (inline SVG; the
   framework icon set has none)."
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

(defn- usage-window-outdated?
  [resets-at]
  (when resets-at
    (let [ms (.getTime (js/Date. resets-at))]
      (and (not (js/isNaN ms)) (<= ms (js/Date.now))))))


(defn- flame-icon
  "Tabler's flame, outline or `filled?`, which the clj-ui-framework icon set lacks."
  [filled? classes]
  [:svg (cond-> {:class (into ["icon" "icon-sm"] classes)
                 :xmlns "http://www.w3.org/2000/svg"
                 :viewBox "0 0 24 24"
                 :aria-hidden "true"}
          filled?       (assoc :fill "currentColor")
          (not filled?) (assoc :fill "none" :stroke "currentColor" :stroke-width "2"
                               :stroke-linecap "round" :stroke-linejoin "round"))
   [:path {:d (if filled?
                "M10 2c0 -.88 1.056 -1.331 1.692 -.722c1.958 1.876 3.096 5.995 1.75 9.12l-.08 .174l.012 .003c.625 .133 1.203 -.43 2.303 -2.173l.14 -.224a1 1 0 0 1 1.582 -.153c1.334 1.435 2.601 4.377 2.601 6.27c0 4.265 -3.591 7.705 -8 7.705s-8 -3.44 -8 -7.706c0 -2.252 1.022 -4.716 2.632 -6.301l.605 -.589c.241 -.236 .434 -.43 .618 -.624c1.43 -1.512 2.145 -2.924 2.145 -4.78"
                "M12 10.941c2.333 -3.308 .167 -7.823 -1 -8.941c0 3.395 -2.235 5.299 -3.667 6.706c-1.43 1.408 -2.333 3.294 -2.333 5.588c0 3.704 3.134 6.706 7 6.706c3.866 0 7 -3.002 7 -6.706c0 -1.712 -1.232 -4.403 -2.333 -5.588c-2.084 3.353 -3.257 3.353 -4.667 2.235")}]])

(defn- yolo-flame
  "The room's yolo level (xi.ext.yolo) → [flame title], nil when off: the
   flame outlined for yolo, filled for relentless."
  [room]
  (case (get-in room [:ext :yolo :level])
    :sane       [(flame-icon false ["yolo-chip-flame"])
                 "Yolo mode: tool calls run without asking, except risky ones."]
    :relentless [(flame-icon true ["yolo-chip-flame" "yolo-chip-flame--relentless"])
                 "Relentless yolo mode: every tool call runs without asking."]
    nil))

(defn- compose-status-pct
  "A window's percent, amber from 75% on."
  [pct]
  [:span {:class [(when (>= pct 75) "compose-status-hot")]} (str pct "%")])

(defn- compose-status
  "The plain-text line under the composer's input row on wide desktops (CSS
   hides it below): the chat's model, opening the model picker, the yolo
   flame while yolo mode is on, turning it off, and the Claude subscription
   usage from the lobby broadcast, opening /usage."
  [dispatch! room model claude-usage]
  (let [{:keys [session weekly session-resets-at weekly-resets-at]} claude-usage
        session (when-not (usage-window-outdated? session-resets-at) session)
        weekly  (when-not (usage-window-outdated? weekly-resets-at) weekly)
        yolo    (yolo-flame room)]
    (when (or model claude-usage yolo)
      [:div {:class ["compose-status"]}
       (when model
         [:button {:class ["compose-status-item"]
                   :title (str model " — change model")
                   :on {:click (fn [_] (dispatch! {:type :palette/open-models}))}}
          (str/lower-case (rename/model-label model))])
       (when-let [[flame title] yolo]
         (let [title (str title " Click to turn it off.")]
           [:button {:class ["compose-status-item" "compose-status-yolo"]
                     :title title
                     :aria-label title
                     :on {:click (fn [_] (dispatch-command! dispatch! (:id room) "yolo off"))}}
            flame]))
       (when claude-usage
         [:button {:class ["compose-status-item" "compose-status-usage"]
                   :title "Claude usage — all usage"
                   :on {:click (fn [_] (dispatch! {:type :route/navigate :page :usage}))}}
          (if session (compose-status-pct session) "?")
          [:span {:class ["compose-status-dim"]} " session"]
          (when weekly
            (list [:span {:class ["compose-status-dim"]} " · "]
                  (compose-status-pct weekly)
                  [:span {:class ["compose-status-dim"]} " week"]))])])))

(defn- compose-box [dispatch! room busy? images draft-key draft session-id cmd-selected pa? queue-open? prompt-nav nav-ctx scrolled-up? offline? cmd-ctx status agents]
  (let [room-id  (:id room)
        cmd-query (when (and (not pa?) (string? draft) (str/starts-with? draft "/"))
                    (subs draft 1))
        cmd-matches (when (some? cmd-query) (match-commands cmd-query cmd-ctx))
        skill-names (:skill-names cmd-ctx)
        cmd-open?   (seq cmd-matches)
        has-input?  (seq (str/trim (or draft "")))
        queued      (get-in room [:agent :queued])
        new?        (nil? session-id)
        steerable?  (agent/steerable? room)]
    [:div {:class ["compose-box"]}
     ;; Anchored on .compose-box: the frame's backdrop-filter would clip it.
     (when (and queue-open? (seq queued) (not cmd-open?))
       (queue-popover dispatch! room-id queued steerable?))
     (compose-image-strip dispatch! draft-key images)
     [:div {:class ["compose-frame"]}
      (when-not cmd-open?
        (floating-actions dispatch! room-id pa? prompt-nav nav-ctx scrolled-up?
                          queued agents))
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
                   (when new?
                     (.focus node #js {:preventScroll true})))
                 :on {:input (fn [^js e]
                               (dispatch! {:type :compose/set-draft
                                           :draft-key draft-key
                                           :text (.. e -target -value)}))
                      :paste (fn [^js e] (handle-compose-paste! dispatch! draft-key e))
                      ;; iOS Safari's Return key fires no Enter keydown, only a
                      ;; beforeinput "insertLineBreak".
                      :beforeinput
                      (fn [^js e]
                        (when (= "insertLineBreak" (.-inputType e))
                          (.preventDefault e)
                          (if cmd-open?
                            (let [sel (min (or cmd-selected 0) (dec (count cmd-matches)))]
                              (pick-command! dispatch! room-id draft-key (nth cmd-matches sel)))
                            (let [v (.. e -target -value)]
                              (when (or (not busy?) (submittable-while-busy? (:commands cmd-ctx) v)
                                        (skill-invocation (str/trim v) skill-names))
                                (submit-compose! dispatch! room-id session-id
                                                 images draft-key v skill-names))))))
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
                                  (pick-command! dispatch! room-id draft-key (nth cmd-matches sel)))
                              "escape"
                              (do (.preventDefault e)
                                  (when-let [^js el (compose-textarea-el)]
                                    (set! (.-value el) ""))
                                  (dispatch! {:type :compose/clear-draft
                                              :draft-key draft-key}))
                              nil))
                          (cond
                            ;; Tab expands a snippet trigger before the caret, like the TUI.
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

                            ;; Shift+Enter: manual newline so the beforeinput
                            ;; handler above doesn't submit.
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

                            ;; Alt+Enter steers: the message goes into the
                            ;; running turn instead of the queue.
                            (= "Enter" (.-key e))
                            (do (.preventDefault e)
                                (let [v (.. e -target -value)]
                                  (when (or (not busy?) (submittable-while-busy? (:commands cmd-ctx) v)
                                            (skill-invocation (str/trim v) skill-names))
                                    (submit-compose! dispatch! room-id session-id
                                                     images draft-key v skill-names
                                                     {:steer? (and steerable? (.-altKey e))})))))))}}})]
      (let [spinner-abort
            (when busy?
              [:button {:class ["icon-btn" "compose-spinner-abort"]
                        :aria-label "Abort"
                        :data-key-action "agent/abort"
                        :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
               (spinner)])]
        (if (and busy? (not (command-while-busy? (:commands cmd-ctx) draft)))
          [:div {:class ["compose-actions"]}
           (when offline? (offline-indicator))
           spinner-abort
           ;; While the turn can be steered, holding Alt (data-alt-held,
           ;; xi.web.key-hints) turns the send arrow into the steer icon, and
           ;; an Alt+click steers like Alt+Enter.
           (when (or has-input? (seq images) steerable?)
             [:button {:class ["icon-btn" (when steerable? "compose-send--steer")
                               (when-not (or has-input? (seq images)) "compose-send--alt-only")]
                       :title (when steerable? "Send · Alt: into the running turn")
                       :on {:click (fn [^js e]
                                     (submit-compose! dispatch! room-id session-id
                                                      images draft-key draft skill-names
                                                      {:steer? (and steerable? (.-altKey e))}))}}
              [:span {:class ["compose-send-icon"]} (icon/icon {:icon-name :arrow-up :size :md})]
              (when steerable? [:span {:class ["compose-steer-icon"]} (steer-icon)])])
           [:button {:class ["icon-btn"]
                     :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
            (icon/icon {:icon-name :circle-x :size :md})]]
          [:div {:class ["compose-actions"]}
           (when offline? (offline-indicator))
           spinner-abort
           [:button {:class ["icon-btn"]
                     :on {:click (fn [_] (submit-compose! dispatch! room-id session-id
                                                          images draft-key draft skill-names))}}
            (icon/icon {:icon-name :arrow-up :size :md})]]))]
      status]]))

(defn- deny-reason-compose
  "The composer turned into the reason field of a deny-with-reason while
   :web/deny-reason targets a pending ask; the normal draft is untouched
   underneath. Enter denies, Esc goes back without answering."
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
  "Radio group (+ custom path input) for the missing-cwd dialog; transient
   selection lives in :web/dialog-form."
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
  "One textarea per :form dialog field (xi.dialog/form-fields); transient
   values live in :web/dialog-form. Submit answers the values map, Cancel nil."
  [dispatch! state dialog answer!]
  (let [values  (:web/dialog-form state)
        fields  (dlg/form-fields dialog)
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
  [type options value]
  (case type
    :confirm    (dlg/resolved-label {:options options} value)
    :select     (or (some #(when (= (:value %) value) (:label %)) options)
                    (if (nil? value) "Cancelled" (str value)))
    :alert      "Dismissed"
    :cwd-select (str value)
    :form       (if value "Submitted" "Cancelled")
    (str value)))

(defn- resolved-dialog-post
  "Static bubble for an answered dialog from the :web/resolved-dialogs log: the
   message plus a decision pill."
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
  "A pending dialog as an assistant-side bubble. Answering logs the decision
   into :web/resolved-dialogs and drops the live dialog in one render, then
   forwards the response and a :ui/dialog-close (the mirrored core handler is
   what clears it on other clients). A sub-agent's asks (:sub-id) are
   answered in its view instead (subagent-asks)."
  [dispatch! state room history inline-ids]
  (when-let [{:keys [id type message text options] :as live-dialog}
             (first (remove #(or (inline-ids (:id %)) (:sub-id %))
                            (get-in room [:ui :dialogs])))]
    (let [room-id (:id room)
          answer! (fn [value]
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
             ;; A select resolves nil when dismissed (xi.api.dialog), like
             ;; Esc in the TUI's build-select-dialog. A :menu? one picks in
             ;; the palette (palette-dialog-page).
             :select
             (concat
              (if (:menu? live-dialog)
                [[:button {:class ["confirm-btn" "confirm-btn--allow"]
                           :on {:click (fn [_] (dispatch! {:type :palette/open-dialog
                                                           :room-id room-id
                                                           :dialog-id id}))}}
                  (str "Choose… (" (count options) ")")]]
                (for [{:keys [label value]} options]
                  [:button {:class ["confirm-btn" "confirm-btn--allow"]
                            :on {:click (fn [_] (answer! value))}} label]))
              [(button/button
                {:variant :ghost :size :sm
                 :on-click (fn [_] (answer! nil))}
                "Cancel")])
             :alert
             [:button {:class ["confirm-btn" "confirm-btn--allow"]
                       :on {:click (fn [_] (answer! nil))}} "OK"]
             (confirm-buttons live-dialog answer!
                              #(dispatch! {:type :deny-reason/start
                                           :room-id room-id :dialog-id id})))])]])))

;; ── Diff view ────────────────────────────────────────────────────────────────

(defn- diff-file-grammar
  [filename]
  (when filename
    (when-let [ext (file-ext filename)]
      (grammars/get-grammar ext))))

(def ^:private diff-text-cache
  (js/WeakMap.))

(defn- diff-text-code
  "A diff line's (or :split side's) :text as [:code …]: grammar tokens, cut at
   its change :spans (xi.diff/mark-spans) into .diff-span pieces. Every piece
   is its own span, so .diff-view--quiet-syntax can dim all but the changes."
  [grammar {:keys [text spans] :as line}]
  (or (.get diff-text-cache line)
      (let [text   (or text "")
            tokens (if grammar
                     (hl/merge-adjacent (hl/tokenize grammar text))
                     [{:value text}])
            result (into [:code]
                         (map (fn [{:keys [type value changed?]}]
                                (into [:span {:class (cond-> []
                                                       (theme/token-class type) (conj (theme/token-class type))
                                                       changed? (conj "diff-span"))}]
                                      (md/linkify value))))
                         (diff/mark-spans tokens spans))]
        (.set diff-text-cache line result)
        result)))

(def ^:private diff-rows-memo
  (atom nil))

(defn- diff-rows-for-text
  "diff/diff-rows of `text`, memoized on the last text so re-renders hand
   diff-rows-view the identical rows."
  [text]
  (let [[t rows] @diff-rows-memo]
    (if (and rows (= t text))
      rows
      (let [rows (diff/diff-rows (diff/parse-diff-text text))]
        (reset! diff-rows-memo [text rows])
        rows))))

(def ^:private buffer-rows-memo
  (atom nil))

(defn- diff-buffer-rows
  "diff/buffer-rows, memoized on the last buffer content + layout like
   diff-rows-for-text."
  [{:keys [rows text] :as buffer} layout]
  (let [[k memo] @buffer-rows-memo
        k'       [(or rows text) layout]]
    (if (and memo (= k k'))
      memo
      (let [rows (diff/buffer-rows buffer layout)]
        (reset! buffer-rows-memo [k' rows])
        rows))))

(defn- diff-split-side
  "Gutter + code cell of one side of a :split line (`kind` :del old, :add
   new): tinted when the side changed, a faint marker in a changed row whose
   side kept its text, a hatched gap when the side has no line."
  [kind {:keys [n changed? spans] :as side} change-row? code]
  (let [state (cond (nil? side) "empty"
                    changed?    (name kind)
                    change-row? "moved"
                    :else       "context")]
    [[:span {:class ["diff-ln" (str "diff-ln--" state)] :data-ln (str n)}]
     [:span {:class ["diff-text" "diff-side" (str "diff-side--" state)
                     (when (seq spans) "diff-side--spans")]}
      code]]))

(defn- diff-line-view
  "One selectable diff line, unified or :split (xi.diff layouts); tapping
   dispatches :diff/select-line. `highlight?` / `reviewed?` are the review
   canvas's marks."
  ([dispatch! grammar selected? line sel-idx row-key]
   (diff-line-view dispatch! grammar selected? line sel-idx row-key false false))
  ([dispatch! grammar selected? line sel-idx row-key highlight?]
   (diff-line-view dispatch! grammar selected? line sel-idx row-key highlight? false))
  ([dispatch! grammar selected? {:keys [type old-line new-line lhs rhs] :as line} sel-idx row-key highlight? reviewed?]
  (let [split? (= type :split)
        cls (cond-> ["diff-line"]
              split?            (conj "diff-line--split")
              (seq (:spans line)) (conj "diff-line--spans")
              (= type :add)     (conj "diff-line--add")
              (= type :delete)  (conj "diff-line--del")
              (= type :meta)    (conj "diff-line--meta")
              sel-idx           (conj "diff-line--selectable")
              reviewed?         (conj "diff-line--reviewed")
              highlight?        (conj "diff-line--highlight")
              selected?         (conj "diff-line--selected"))
        code   (fn [l] (if (or grammar (seq (:spans l)))
                         (diff-text-code grammar l)
                         (or (:text l) "")))
        file-line (cond split?                  (:n rhs)
                        (#{:add :context} type) new-line)]
    (into
     [:div (cond-> {:class cls :replicant/key row-key}
             file-line (assoc :data-file-line file-line)
             sel-idx (assoc :on {:click (fn [_] (dispatch! {:type :diff/select-line
                                                            :idx sel-idx}))}))]
     (if split?
       (let [moved? (diff/split-change? line)]
         (concat (diff-split-side :del lhs moved? (some-> lhs code))
                 (diff-split-side :add rhs moved? (some-> rhs code))))
       (let [old-nr (case type
                      (:delete :context) (str old-line)
                      "")
             new-nr (case type
                      (:add :context) (str new-line)
                      "")
             sign   (case type :add "+" :delete "-" " ")]
         [[:span {:class ["diff-ln"] :data-ln old-nr}]
          [:span {:class ["diff-ln"] :data-ln new-nr}]
          [:span {:class ["diff-sign"] :data-sign sign}]
          [:span {:class ["diff-text"]} (code line)]]))))))

(def ^:private diff-groups-cache
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
  (js/WeakMap.))

(defn- diff-group-md-body
  "md-diff-body of a file group's hunks, memoized per group; nil when no
   rendered block changed."
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
  "file group → [dispatch! range shown after-line body-hiccup]: files the
   selection and the inline cards don't touch keep returning the identical
   hiccup."
  (js/WeakMap.))

(defn- diff-group-range
  [group range]
  (when range
    (let [lo (some :sel-idx group)
          hi (some :sel-idx (rseq group))]
      (when (and lo (<= (first range) hi) (<= lo (second range)))
        range))))

(def ^:private diff-row-budget
  "Body rows a size-limited diff view renders up front, shared across files in
   order; the rest sits behind each file's \"Show more\"."
  1500)

(defn- diff-visible-rows
  "Per-file cap on rendered body rows, parallel to `groups`: a row count, or
   nil for files rendered in full (folded or `expanded`). Files draw from
   `budget` in order."
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

(defn- file-menu-items
  "ui.context-menu entries for a file header: View file (:file/open), Copy
   path, the caller's `extra` entries, then the extensions' entries for the
   file (xi.web.block-menu, block {:path}). A file `gone?` from disk (deleted
   in a diff) only offers Copy path; `view?` false drops View file (the
   file's own viewer). Copy path copies `abs-path` when given (the
   server-resolved path of a file buffer)."
  [dispatch! state path {:keys [gone? view? abs-path extra] :or {view? true}}]
  (cond-> []
    (and view? (not gone?))
    (conj {:label    "View file"
           :icon     :file-text
           :on-click #(dispatch! {:type :file/open :path path})})
    true
    (conj {:label    "Copy path"
           :icon     :copy
           :on-click #(copy! dispatch! (or abs-path path))})
    (seq extra)
    (into extra)
    (not gone?)
    (into (map (fn [{:keys [label icon event]}]
                 {:label label :icon (or icon :chevron-right)
                  :on-click #(dispatch! event)}))
          (block-menu/items {:path path} state))))

(defn- file-header-actions
  "The extensions' file menu entries marked `:header? true`
   (xi.web.block-menu), as buttons in a file header."
  [dispatch! state path]
  (when-let [items (seq (filter :header? (block-menu/items {:path path} state)))]
    [:span {:class ["file-header-actions"]}
     (for [{:keys [label icon event]} items]
       (button/button {:variant :ghost :size :sm :icon-left (or icon :chevron-right)
                       :attrs {:type "button" :replicant/key label}
                       :on-click (fn [^js e]
                                   (.stopPropagation e)
                                   (dispatch! event))}
                      label))]))

(defn file-header
  "A file's header with the file menu on right-click / long-press
   (file-menu-items). The trigger is the outermost element (`:class`), so a
   sticky header stays sticky. Opts also take file-menu-items' :gone? / :view?."
  [dispatch! state path {:keys [class] :as opts} & children]
  (into (context-menu/context-menu-trigger
         {:items (file-menu-items dispatch! state path opts) :class class})
        children))

(def ^:private scroll-tops
  "scroll key → the view's last scrollTop (UI-only, like the timeline's
   scroll metrics in xi.web.core)."
  (js/Map.))

(defn- remembered-scroll
  "Attrs that keep a scrollable element's position under `k`: saved on
   scroll, restored on mount. The key remounts it when `k` changes, so one
   view's position never carries into another's."
  [k]
  {:replicant/key k
   :replicant/on-mount
   (fn [{:replicant/keys [^js node]}]
     (when-let [top (.get scroll-tops k)]
       (set! (.-scrollTop node) top)
       ;; once more after layout: late content (fonts, sticky headers) can
       ;; clamp the first write
       (js/requestAnimationFrame #(set! (.-scrollTop node) top))))
   :on {:scroll (fn [^js e] (.set scroll-tops k (.. e -target -scrollTop)))}})

(defn diff-rows-view
  "Flattened diff rows as a scrollable view grouped by file (sticky
   file-header, horizontally scrollable body), the selection `toolbar`
   anchored to the selected range. Opts: :state (the app state, for the
   extensions' file menu entries), :highlight / :reviewed (sets of :sel-idx, review
   canvas), :line-suffix ((fn [sel-idx]) → hiccup, inline comment threads),
   :after-line ({xi.diff/line-key [hiccup …]}, rendered after that line; unlike
   :line-suffix it keeps the body cache of files it doesn't touch),
   :collapsed (folded filenames; present ⇒ headers toggle :diff/toggle-file),
   :expanded (filenames shown in full; present ⇒ size-limited by
   diff-row-budget with :diff/show-all), :md-code (the :web/md-diff-code set;
   present ⇒ markdown files render as a rendered diff with a toggle keyed
   [:diff filename]), :scroll-key (remember the scroll position under it,
   restored when a view with that key mounts again)."
  ([dispatch! rows range toolbar] (diff-rows-view dispatch! rows range toolbar nil))
  ([dispatch! rows range toolbar {:keys [state highlight reviewed line-suffix after-line
                                         collapsed expanded md-code scroll-key]}]
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
    [:div (cond-> {:class ["diff-view"]}
            scroll-key (merge (remembered-scroll (pr-str scroll-key))))
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
            ;; data-file-path: the code-block menu on a line offers the file
            ;; to extensions (xi.web.block-menu), data-file-line the line
            [:div (cond-> {:class ["diff-file"
                                   (when folded? "diff-file--collapsed")]
                           :replicant/key filename}
                    (not= status :deleted) (assoc :data-file-path filename))
             ;; The trigger is the sticky element: wrapping the header in a
             ;; plain div would confine its stickiness to the wrapper's height.
             (file-header
              dispatch! state filename
              {:class "diff-file-header-wrap" :gone? (= status :deleted)}
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
               (when-not (= status :deleted)
                 (file-header-actions dispatch! state filename))
               (when md-body
                 (md-diff-toggle dispatch! md-key md-code?))])
             (when (and md-body (not md-code?) (not folded?))
               [:div {:class ["diff-file-md"]} md-body])
             (when-not (or folded? (and md-body (not md-code?)))
               (let [file-range (diff-group-range group range)
                     after      (into {} (filter #(= filename (first (key %)))) after-line)
                     [c-dispatch c-range c-shown c-after c-body]
                     (when cache-body? (.get diff-body-cache group))]
                 (if (and c-body (identical? c-dispatch dispatch!) (= c-range file-range)
                          (= c-shown shown) (= c-after after))
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
                           suffix    (when line-suffix (line-suffix sel-idx))
                           cards     (when (seq after) (seq (mapcat after (diff/line-keys r))))
                           line-el   (diff-line-view dispatch! (grammar-for (:filename r))
                                                     selected? line sel-idx (str "l" k) hl? rev?)]
                       (if (or suffix cards)
                         (list line-el suffix cards)
                         line-el))
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
                       (.set diff-body-cache group #js [dispatch! file-range shown after body]))
                     body))))]))
        file-groups)
       (empty-state/empty-state {} "No changes."))
     toolbar])))

(defn position-sel-toolbar!
  "Anchor the selection toolbar to the bottom edge of the last selected diff
   line; runs on mount and update. Public for the canvas-review extension."
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
   panel so the stylesheet can pin it above the on-screen keyboard."
  [^js node]
  (when-let [vv (.-visualViewport js/window)]
    (let [^js st (.-style node)]
      (.setProperty st "--vv-top" (str (.-offsetTop vv) "px"))
      (.setProperty st "--vv-height" (str (.-height vv) "px"))
      (.setProperty st "--vv-bottom" (str (+ (.-offsetTop vv) (.-height vv)) "px")))))

(defn position-modify-panel!
  "Keep the modify panel pinned above the keyboard (not anchored to the diff
   range), tracking the VisualViewport; listeners are torn down on unmount.
   Public for the canvas-review extension."
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

(def ^:private diff-intents
  "The composer's switch: what its text does (xi.web.core diff-compose-submit)."
  [{:intent :ask     :label "Ask"     :placeholder "Ask about these lines…"}
   {:intent :explain :label "Explain" :placeholder "What to focus on (optional)…"}
   {:intent :modify  :label "Modify"  :placeholder "Describe the change to make…"}])

(defn- enter-submits
  "Keydown handler: Enter sends, Shift+Enter breaks the line."
  [submit!]
  (fn [^js e]
    (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)) (not (.-isComposing e)))
      (.preventDefault e)
      (submit! e))))

(defn- diff-composer
  "The selection's composer, opened from diff-sel-pill and attached under the
   range's last line: a gutter rail ties it to the range, the text goes where
   the Ask / Explain / Modify switch says and the suggestion chips ask right
   away."
  [dispatch! room-id rows range intent draft]
  (let [sel-rows (filterv (fn [{:keys [row sel-idx]}]
                            (and (= :line row) sel-idx (<= (first range) sel-idx (second range))))
                          rows)
        n        (count sel-rows)
        file     (:filename (peek sel-rows))
        intent   (or intent :ask)
        {:keys [placeholder]} (some #(when (= intent (:intent %)) %) diff-intents)
        submit!  (fn [_]
                   (when-let [el (.getElementById js/document "diff-compose-input")]
                     (dispatch! {:type :diff/compose-submit :room-id room-id :text (.-value el)})))]
    [:div {:class ["diff-thread" "diff-thread--compose"] :replicant/key "diff-composer"}
     [:div {:class ["diff-thread-head"]}
      [:span (str n " line" (when (not= 1 n) "s") " selected")]
      [:span {:class ["diff-thread-spacer"]}]
      (button/button {:variant :ghost :size :sm :attrs {:aria-label (str "Open " file)}
                      :on-click (fn [_] (dispatch! {:type :file/open :path file}))}
                     (icon/icon {:icon-name :external-link :size :sm}))
      (button/button {:variant :ghost :size :sm :attrs {:aria-label "Clear selection"}
                      :on-click (fn [_] (dispatch! {:type :diff/clear-selection}))}
                     (icon/icon {:icon-name :x :size :sm}))]
     (form/form-textarea-auto
      {:placeholder placeholder
       :value       draft
       :max-rows    6
       :class       "diff-thread-input"
       :attrs       {:id "diff-compose-input"
                     :replicant/on-mount
                     (fn [{:replicant/keys [^js node]}]
                       (.focus node #js {:preventScroll true}))
                     :on {:keydown (enter-submits submit!)
                          :input   (fn [^js e] (dispatch! {:type :diff/draft
                                                           :text (.. e -target -value)}))}}})
     (when (= :ask intent)
       [:div {:class ["diff-thread-chips"]}
        (for [q (sa/ask-suggestions (map (comp :type :line) sel-rows))]
          (chip/chip {:on-click (fn [_] (dispatch! {:type :diff/ask-submit :room-id room-id :text q}))
                      :attrs {:replicant/key q}}
                     q))])
     [:div {:class ["diff-thread-foot"]}
      (into (button-group/button-group {:variant :boxed :attrs {:aria-label "What the text does"}})
            (for [{i :intent :keys [label]} diff-intents]
              (button-group/button-group-item
               {:active (= i intent)
                :attrs {:aria-pressed (str (= i intent))}
                :on-click (fn [_] (dispatch! {:type :diff/intent :intent i}))}
               label)))
      [:span {:class ["diff-thread-spacer"]}]
      [:span {:class ["diff-thread-kbd"]} "↵"]
      (button/button {:variant :primary :size :sm :attrs {:aria-label "Send"} :class "diff-thread-send"
                      :on-click submit!}
                     (icon/icon {:icon-name :arrow-up :size :sm}))]]))

(defn- diff-sel-pill
  "Floating actions for a diff selection, a frosted capsule anchored to its
   last line without taking layout space (position-sel-toolbar!), so extending
   the range never shifts the diff. Ask and Modify open the inline composer;
   Explain starts its sub-agent thread right away. Each shows its :buffer/diff key
   (xi.keys :diff/*-selection)."
  [dispatch! state room-id file]
  (let [item (fn [{:keys [label action variant on-click]}]
               (button/button
                {:variant (or variant :ghost) :size :sm :class "diff-sel-pill-btn"
                 :on-click on-click}
                label
                (when-let [k (keymap/shortcut state action)]
                  [:kbd {:class ["diff-sel-pill-key"]} k])))]
    [:div {:class ["diff-sel-anchor" "diff-sel-pill"]
           :replicant/key "diff-sel-pill"
           :replicant/on-mount position-sel-toolbar!
           :replicant/on-update position-sel-toolbar!}
     (button/button {:variant :ghost :size :sm :class "diff-sel-pill-btn" :attrs {:aria-label "Clear selection"}
                     :on-click (fn [_] (dispatch! {:type :diff/clear-selection}))}
                    (icon/icon {:icon-name :x :size :sm}))
     [:span {:class ["diff-sel-pill-sep"]}]
     (item {:label "Modify" :action :diff/modify-selection
            :on-click (fn [_] (dispatch! {:type :diff/intent :intent :modify}))})
     (item {:label "Explain" :action :diff/explain-selection
            :on-click (fn [_] (dispatch! {:type :diff/explain :room-id room-id}))})
     (button/button {:variant :ghost :size :sm :class "diff-sel-pill-btn" :attrs {:aria-label (str "Open " file)}
                     :on-click (fn [_] (dispatch! {:type :file/open :path file}))}
                    (icon/icon {:icon-name :external-link :size :sm}))
     (item {:label (list (icon/icon {:icon-name :zap :size :sm}) "Ask")
            :action :diff/ask-selection :variant :primary
            :on-click (fn [_] (dispatch! {:type :diff/intent :intent :ask}))})]))

(defn- ask-activity
  "What a running ask sub-agent is doing: its latest tool call as `tool
   target`, or nil before the first one."
  [history]
  (when-let [{:keys [tool arguments]} (last (filter #(= :tool-call (:kind %)) history))]
    (let [target (some #(get arguments %) [:path :file_path :pattern :query :command :code])]
      (str (util/strip-mcp-prefix (str tool))
           (when target (str " " (let [t (str/trim (str target))]
                                    (if (> (count t) 60) (str (subs t 0 60) "…") t))))))))

(defn- ask-meta
  "`9s · 3 tool calls` for the thread's children."
  [children]
  (let [ms    (reduce + (keep (fn [{:keys [started ended]}] (when (and started ended) (- ended started)))
                              children))
        calls (count (filter #(= :tool-call (:kind %)) (mapcat :history children)))]
    (str/join " · " (cond-> []
                      (pos? ms)    (conj (format-elapsed (js/Math.round (/ ms 1000))))
                      (pos? calls) (conj (str calls " tool call" (when (not= 1 calls) "s")))))))

(defn- answer-turn
  "One question (none for a sub-agent's task) with its answer `text`: while
   `status` is :running and no text has come, what `history` shows it doing.
   `answer-class` styles the answer (a thread caps its height)."
  [{:keys [key question text status history answer-class]}]
  [:div {:class ["diff-thread-turn"] :replicant/key key}
   (when (seq question)
     [:div {:class ["diff-thread-q"]} question])
   (cond
     (seq text)
     [:div {:class [answer-class "post-content"]} (render-md text)]

     (= :running status)
     (list
      [:div {:class ["diff-thread-activity"] :replicant/key "act"}
       [:span {:class ["agent-status-spinner--sm"]}]
       [:span (or (ask-activity history) "Thinking…")]]
      [:div {:class ["diff-thread-skeleton"] :replicant/key "sk"} [:i] [:i] [:i]])

     :else
     [:div {:class [answer-class "diff-thread-a--empty"]}
      (if (= :stopped status) "Stopped." "The answer failed.")])])

(defn- turn-transcript
  "Every assistant text block since the latest prompt, oldest first: a
   Modify's narration of what it changed, where final-text keeps only the last."
  [history]
  (->> (rseq (vec history))
       (take-while #(not= :user (:kind %)))
       (keep #(when (= :text (:kind %)) (:text %)))
       reverse
       (str/join "\n\n")
       not-empty))

(defn- ask-turn [transcript? {:keys [question sub-id]} child]
  (answer-turn {:key          sub-id
                :question     question
                :text         (if transcript?
                                (turn-transcript (:history child))
                                (or (:result child) (sa/final-text (:history child))))
                :status       (or (:status child) :running)
                :history      (:history child)
                :answer-class "diff-thread-a"}))

(defn- thread-changes
  "The file changes of a thread's sub-agents (sa/modifications), one at a
   time, with ‹ › to step through them oldest first. `rev` is the shown
   one's index, nil (or out of range) for the latest."
  [dispatch! state mods rev on-rev]
  (let [n    (count mods)
        i    (if (and (number? rev) (< -1 rev n)) rev (dec n))
        {:keys [id path text]} (nth mods i)
        step (fn [d] (fn [_] (on-rev (+ i d))))]
    [:div {:class ["thread-changes"] :replicant/key "changes"}
     [:div {:class ["thread-changes-bar"]}
      (button/button {:variant :ghost :size :sm :disabled (zero? i)
                      :attrs {:aria-label "Earlier change" :title "Earlier change"}
                      :on-click (step -1)}
                     (icon/icon {:icon-name :chevron-left :size :sm}))
      [:span {:class ["thread-changes-count"]} (str "Change " (inc i) " of " n)]
      (button/button {:variant :ghost :size :sm :disabled (= i (dec n))
                      :attrs {:aria-label "Later change" :title "Later change"}
                      :on-click (step 1)}
                     (icon/icon {:icon-name :chevron-right :size :sm}))
      (when path [:span {:class ["thread-changes-path"]} (str path)])]
     (tool-diff-view dispatch! {:path path :text text :k id
                                :code? (contains? (:web/md-diff-code state) id)
                                :grammar (grammars/get-grammar (file-ext (str path)))})]))

(defn- thread-ask
  "A pending :confirm ask of a thread's running sub-agent, answered in the
   card: the diff viewer hides the chat timeline whose dialog-post shows it
   otherwise. The answer is logged there like dialog-post's (`anchor`)."
  [dispatch! state room-id anchor {:keys [id type message text options diff] :as dialog}]
  (let [answer! (fn [value]
                  (dispatch! {:type :web/dialog-resolved
                              :room-id room-id
                              :dialog-id id
                              :entry {:key id :anchor anchor
                                      :message (or message text) :type type :value value
                                      :label (dialog-decision-label type options value)}})
                  (dispatch! {:type :ui/dialog-response
                              :room-id room-id :dialog-id id :value value})
                  (dispatch! {:type :ui/dialog-close
                              :room-id room-id :dialog-id id}))]
    [:div {:class ["thread-ask"] :replicant/key (str "ask-" id)}
     [:div {:class ["tool-call-permission-msg"]} (or message text)]
     (diff-preview dispatch! diff id (contains? (:web/md-diff-code state) id))
     [:div {:class ["tool-call-permission-actions"]}
      (confirm-buttons dialog answer! nil)]]))

(defn- thread-card
  "A question thread answered by sub-agents: each turn's question with its
   streamed answer, Stop while one runs, a reply field, collapse and close,
   or a one-line summary when collapsed. Shared by the diff Ask
   (diff-ask-card) and questions on chat blocks (block-thread-card).
   `turns` [{:question :sub-id}], `children` the matching sub-agents (nil
   until the server echoes the spawn); :on-reply gets the reply text;
   :label names what the thread is about, :quote the text it is about
   (under the head); :actions are extra head buttons;
   :draft / :on-draft keep the reply field's text in state; :transcript?
   shows every text block of a turn instead of only the last. The files
   the children changed step by step (thread-changes) under the head:
   :rev / :on-rev the shown change. :ask is a pending ask of theirs to
   answer in the card (thread-ask)."
  [dispatch! state room-id {:keys [key turns children collapsed? label quote input-id actions
                                    on-reply on-toggle on-close draft on-draft transcript?
                                    rev on-rev ask]}]
  (let [running (some (fn [[t c]] (when (or (nil? c) (= :running (:status c))) t))
                      (map vector turns children))
        reply!  (fn [_]
                  (when-let [el (.getElementById js/document input-id)]
                    (on-reply (.-value el))))
        toggle! (fn [_] (on-toggle))]
    (if collapsed?
      [:div {:class ["diff-thread-mini" "thread-card" (when running "diff-thread-mini--running")]
             :replicant/key key
             :on {:click toggle!}}
       [:span {:class ["diff-thread-dot"]}]
       [:span {:class ["diff-thread-mini-q"]} (:question (peek turns))]
       [:span {:class ["diff-thread-mini-status"]}
        (cond running "answering…"
              (< 1 (count turns)) (str (count turns) " questions")
              :else "answered")]
       (icon/icon {:icon-name :chevron-right :size :sm})]
      [:div {:class ["diff-thread" "thread-card" (if running "diff-thread--running" "diff-thread--done")]
             :replicant/key key}
       [:div {:class ["diff-thread-head"]}
        [:span {:class ["diff-thread-agent"]} (icon/icon {:icon-name :zap :size :sm}) "sub-agent"]
        (when label [:span {:class ["thread-label"]} label])
        (let [m (ask-meta children)] (when (seq m) [:span (str "· " m)]))
        [:span {:class ["diff-thread-spacer"]}]
        (when running
          (button/button {:variant :ghost :size :sm
                          :on-click (fn [_] (dispatch! {:type :subagent/abort :room-id room-id
                                                        :sub-id (:sub-id running)}))}
                         "Stop"))
        actions
        (button/button {:variant :ghost :size :sm :attrs {:aria-label "Collapse"} :on-click toggle!}
                       (icon/icon {:icon-name :chevron-down :size :sm}))
        (button/button {:variant :ghost :size :sm :attrs {:aria-label "Close thread"}
                        :on-click (fn [_] (on-close))}
                       (icon/icon {:icon-name :x :size :sm}))]
       (when quote
         [:blockquote {:class ["thread-quote"]} quote])
       (when-let [mods (not-empty (sa/modifications children))]
         (thread-changes dispatch! state mods rev on-rev))
       (map (partial ask-turn transcript?) turns children)
       (when ask
         (thread-ask dispatch! state room-id
                     (count (get-in state [:rooms room-id :history])) ask))
       (when-not running
         [:div {:class ["diff-thread-reply"]}
          (form/form-textarea-auto
           {:placeholder "Reply…"
            :value       draft
            :max-rows    4
            :class       "diff-thread-input"
            :attrs       {:id input-id
                          :on (cond-> {:keydown (enter-submits reply!)}
                                on-draft (assoc :input (fn [^js e] (on-draft (.. e -target -value)))))}})
          (button/button {:variant :ghost :size :sm :attrs {:aria-label "Send reply"} :class "diff-thread-send"
                          :on-click reply!}
                         (icon/icon {:icon-name :arrow-up :size :sm}))])])))

(defn- pending-ask
  "The first pending :confirm ask of one of the sub-agents `sub-ids`."
  [state room-id sub-ids]
  (let [ids (set sub-ids)]
    (some #(when (and (= :confirm (:type %)) (contains? ids (:sub-id %))) %)
          (get-in state [:rooms room-id :ui :dialogs]))))

(defn- diff-ask-card
  "A diff Ask / Explain / Modify thread (xi.web.core diff-thread-start)
   attached after the lines it is about."
  [dispatch! state room-id {:keys [id turns collapsed? kind draft rev]}]
  (thread-card dispatch! state room-id
               {:key        (str "ask-" id)
                :label      ({:explain "Explain" :modify "Modify"} kind)
                :transcript? (= :modify kind)
                :turns      turns
                :children   (mapv #(sa/find-child state room-id (:sub-id %)) turns)
                :collapsed? collapsed?
                :rev        rev
                :on-rev     #(dispatch! {:type :diff/ask-rev :ask-id id :rev %})
                :ask        (pending-ask state room-id (map :sub-id turns))
                :input-id   (str "diff-ask-reply-" id)
                :draft      draft
                :on-draft   #(dispatch! {:type :diff/ask-draft :ask-id id :text %})
                :on-reply   #(dispatch! {:type :diff/ask-follow-up :ask-id id :text %})
                :on-toggle  #(dispatch! {:type :diff/ask-collapse-toggle :ask-id id})
                :on-close   #(dispatch! {:type :diff/ask-close :ask-id id})}))

(def ^:private diff-methods
  "Selectable diff sources. :title is the buffer title :diff/load assigns, used
   to reflect the active method back into the select."
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
  [{:value "git"   :label "git"}
   {:value "difft" :label "difftastic"}])

(defn- diff-method-for-title [title]
  (or (get diff-title->method title)
      ;; git-upstream's title carries the ref, e.g. "Upstream (origin/main)"
      (when (str/starts-with? (or title "") "Upstream") "git-upstream")
      "session-edits"))

(def ^:private diff-layouts
  [{:layout :stacked :label "Stacked" :title "Old above new"}
   {:layout :split   :label "Split"   :title "Old and new side by side"}])

(defn- diff-layout-toggle [dispatch! layout]
  (into (button-group/button-group {:variant :boxed :class "diff-layout-toggle"
                                    :attrs {:aria-label "Diff layout"}})
        (for [{l :layout :keys [label title]} diff-layouts]
          (button-group/button-group-item
           {:active (= l layout)
            :attrs {:aria-pressed (str (= l layout)) :title title}
            :on-click (fn [_] (dispatch! {:type :diff/layout :layout l}))}
           label))))

(defn- diff-method-bar
  "Selects switching the diff source and renderer, each firing :diff/reopen,
   and the layout toggle (:diff/layout). The commit in view appears as a
   dynamic option plus a \"Pick a commit…\" entry opening the commits palette
   page."
  [dispatch! room-id diff-buffer engine {:keys [layout] :as collapse-opts}]
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
     (when layout
       (diff-layout-toggle dispatch! layout))
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
  "Message + metadata of a single-commit diff from the buffer's structured
   commit metadata (the parser strips the `git show` preamble)."
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
  "Fold one SGR escape's codes into the running style state; only the codes
   difftastic emits matter."
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
  "difftastic's ANSI-colored output as styled hiccup spans."
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

(defn- diff-inline-cards
  "diff-rows-view's :after-line, {line-key [card …]}: the selection's composer
   (once an action opened it) under its last line, then the view's threads
   under theirs."
  [dispatch! state room-id rows range]
  (let [intent   (diff-state/intent state)
        view     (diff-state/view-key state)
        last-row (when (and range intent)
                   (some #(when (= (second range) (:sel-idx %)) %) rows))]
    (reduce (fn [m {:keys [anchor] :as ask}]
              (update m anchor (fnil conj []) (diff-ask-card dispatch! state room-id ask)))
            (if last-row
              {(diff/line-key last-row)
               [(diff-composer dispatch! room-id rows range intent (diff-state/draft state))]}
              {})
            (filter #(= view (:view %)) (:web/diff-asks state)))))

(defn- diff-tab-view
  "The diff buffer as the active tab: the interactive viewer over its rows in
   the chosen layout — git's unified text or difftastic's :rows (with dimmed
   syntax under its change spans). A difft buffer without rows (`/diff difft`
   typed by hand) is its read-only ANSI text."
  [dispatch! state room-id diff-buffer]
  (let [sel       (diff-state/sel state)
        collapsed (diff-state/collapsed state)
        md-code   (:web/md-diff-code state)
        engine (or (:engine diff-buffer) :git)
        ansi?  (and (= engine :difft) (nil? (:rows diff-buffer)))
        layout (or (:web/diff-layout state) (diff/default-layout engine))
        rows   (when-not ansi? (diff-buffer-rows diff-buffer layout))
        filenames (into [] (comp (filter #(= :file (:row %))) (map :filename)) rows)]
    [:div {:class ["diff-tab" (when (= engine :difft) "diff-tab--difft")]}
     (diff-method-bar dispatch! room-id diff-buffer engine
                      {:filenames filenames
                       :all-collapsed? (every? collapsed filenames)
                       :layout (when-not ansi? layout)})
     (when-let [c (:commit diff-buffer)]
       (commit-info-header c))
     (if ansi?
       (into [:pre {:class ["diff-difft"]}] (difft-spans (:text diff-buffer)))
       (let [range (diff/selection-range sel)
             pill  (when (and range (not (diff-state/intent state)))
                     (diff-sel-pill dispatch! state room-id
                                    (some #(when (= (second range) (:sel-idx %)) (:filename %)) rows)))]
         (diff-rows-view dispatch! rows range pill
                         {:state state :collapsed collapsed :expanded (diff-state/expanded state)
                          :md-code (or md-code #{})
                          :scroll-key (diff-state/view-key state)
                          :after-line (diff-inline-cards dispatch! state room-id rows range)})))]))

(defn open-linked-file!
  "Document click handler (xi.web.core): a relative link to a text file opens
   that file in a buffer instead of a dead browser tab, at the heading its
   #fragment names. It resolves against the file the link sits in (the
   nearest data-file-path: file buffer, Write tool block, diffed file), else
   stays relative for the server to resolve against the room's cwd. A bare
   #fragment scrolls to that heading of the same markdown."
  [dispatch! ^js e]
  ;; target=_blank: a link of rendered markdown (xi.markdown.hiccup)
  (when-let [a (some-> (.-target e) (.closest "a[href][target=_blank]"))]
    (let [href     (.getAttribute a "href")
          fragment (url/link-fragment href)
          file-el  (.closest a "[data-file-path]")
          base     (some-> file-el (.getAttribute "data-file-path"))]
      (if (str/starts-with? href "#")
        (do (.preventDefault e)
            (heading-anchor/reveal! (or file-el (.closest a ".post-content")) fragment))
        (when-let [target (url/relative-file-link base href)]
          (when (viewable-file? target)
            (.preventDefault e)
            (dispatch! (cond-> {:type :file/open :path target}
                         fragment (assoc :anchor fragment)))))))))

(defn- file-tab-view
  [dispatch! state {:keys [path abs-path text]}]
  (let [ext     (file-ext path)
        grammar (grammars/get-grammar ext)]
    ;; data-file-path: the code-block menu offers the file to extensions
    [:div {:class ["file-tab"] :data-file-path path}
     (file-header dispatch! state path {:class "file-tab-header" :view? false :abs-path abs-path}
                  [:span {:class ["file-tab-path"]} path]
                  (file-header-actions dispatch! state path))
     (if (contains? md-diff/markdown-exts ext)
       [:div {:class ["file-tab-md"]}
        [:div {:class ["post-content"]} (md/render text)]]
       [:pre {:class ["file-tab-code"]}
        (if grammar (highlight-code grammar text) text)])]))

(defn- prompt-part-preview
  [text]
  (let [t (str/triml (or text ""))]
    (if (<= (count t) 200) t (str (str/trimr (subs t 0 200)) "…"))))

(defn- prompt-tab-view
  "The system-prompt buffer as the active tab: one collapsible card per part.
   `expanded` is the :web/prompt-expanded set; `specs` the published
   providers, whose manifest may note a prompt of their own (Claude's preset)."
  [dispatch! room expanded specs]
  (let [parts   (get-in room [:agent :system-parts])
        note    (:prompt-note (routing/agent-spec specs (:agent room)))
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
      (when note
        [:div {:class ["prompt-note"]} note])
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
  "The framework icon name of a buffer kind (xi.buffers). Kinds an extension
   renders bring their own (xi.web.buffer-views). `:subagent` has none: render
   kinds with `buffer-kind-icon`."
  [kind]
  (case kind
    :diff     :code
    :file     :file-text
    :terminal :terminal
    :prompt   :terminal
    (or (buffer-views/icon kind) :list)))

(defn- pane-icon
  "The window glyphs the clj-ui-framework icon set lacks, Lucide style:
   :split-right, :split-down, :dock-left, :dock-right, :undock, :only."
  [kind]
  [:svg {:class ["icon" "icon-sm"]
         :xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "2"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:rect {:x 3 :y 3 :width 18 :height 18 :rx 2}]
   (case kind
     :split-right [:path {:d "M12 3v18"}]
     :split-down  [:path {:d "M3 12h18"}]
     :dock-left   [:path {:d "M9 3v18"}]
     :dock-right  [:path {:d "M15 3v18"}]
     :undock      [:path {:d "M15 3v18M12 9l-3 3 3 3"}]
     :only        [:path {:d "M8 8h8v8H8z"}]
     nil)])

(defn- messages-icon
  "Tabler's messages, the sub-agent icon, which the clj-ui-framework icon set lacks."
  ([] (messages-icon nil))
  ([class]
   [:svg {:class ["icon" "icon-sm" class]
          :xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 24 24"
          :fill "none"
          :stroke "currentColor"
          :stroke-width "2"
          :stroke-linecap "round"
          :stroke-linejoin "round"
          :aria-hidden "true"}
    [:path {:d "M21 14l-3 -3h-7a1 1 0 0 1 -1 -1v-6a1 1 0 0 1 1 -1h9a1 1 0 0 1 1 1v10"}]
    [:path {:d "M14 15v2a1 1 0 0 1 -1 1h-7l-3 3v-10a1 1 0 0 1 1 -1h2"}]]))

(defn- buffer-kind-icon
  "The icon of a buffer kind as hiccup; `:subagent` is a background sub-agent
   listed with the buffers (xi.server.room-manager/session-buffers)."
  ([kind] (buffer-kind-icon kind nil))
  ([kind class]
   (if (= :subagent kind)
     (messages-icon class)
     (icon/icon {:icon-name (buffer-icon kind) :size :sm :class class}))))

(defn- text-tab-view
  [id {:keys [text] :as buf}]
  [:div {:class ["file-tab"]}
   [:div {:class ["file-tab-header"]}
    [:span {:class ["file-tab-path"]} (buffers/label id buf)]]
   [:pre {:class ["file-tab-code"]} (or text "")]])

;; ── Chat view ────────────────────────────────────────────────────────────────

(defn- offline-badge [state]
  (when (false? (:web/connected? state))
    [:span {:class ["offline-label"]} "Offline"]))


(defn- yolo-chip
  "Chat header chip while the room's yolo mode is on: just the flame
   (yolo-flame); a click runs /yolo off."
  [dispatch! room]
  (when-let [[flame title] (yolo-flame room)]
    (let [title (str title " Click to turn it off.")]
      (chip/chip {:attrs    {:class      ["yolo-chip"]
                             :title      title
                             :aria-label title}
                  :on-click (fn [_] (dispatch-command! dispatch! (:id room) "yolo off"))}
        flame))))

(defn- process-chip
  "Chat header chip while the room tracks background processes
   (xi.ext.process-manager, started by clj's process/start): the count, and a
   popover listing them; picking one opens its live log buffer."
  [dispatch! room]
  (when-let [procs (seq (get-in room [:ext :process-manager :processes]))]
    (let [menu-id "process-menu"
          n       (count procs)
          title   (str n (if (= 1 n) " background process" " background processes")
                       " — view a log")]
      (list
       (chip/chip {:class "process-chip"
                   :attrs (merge {:title title :aria-label title
                                  :replicant/key "process-chip"}
                                 (popover/trigger-attrs menu-id))}
         (icon/icon {:icon-name :terminal :size :sm})
         [:span (str n)])
       (popover/popover-content
        {:id    menu-id
         :side  :bottom
         :align :end
         :class "sidebar-more-menu buffer-menu"
         :attrs {:replicant/key "process-menu"}}
        (for [{:keys [pid command]} procs]
          [:button {:class ["sidebar-more-item" "buffer-menu-item"]
                    :replicant/key (str "proc-" pid)
                    :title command
                    :on {:click (fn [^js e]
                                  (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover))
                                  (dispatch! {:type :process-manager/open-log
                                              :room-id (:id room) :pid pid}))}}
           (icon/icon {:icon-name :terminal :size :sm})
           [:span {:class ["buffer-menu-title"]} (str "$ " command)]]))))))

(defn shorten-path
  [path]
  (when path
    (let [parts (str/split path #"/")]
      (last parts))))


(defn menu-button
  [dispatch!]
  (sidebar/sidebar-mobile-toggle
   {:on-click (fn [_] (dispatch! {:type :sidebar/toggle}))
    :attrs    {:data-key-action "sidebar/toggle"}}))

(defn- message-circle-icon
  "The chat icon, which the clj-ui-framework icon set lacks."
  ([] (message-circle-icon nil))
  ([class]
   [:svg {:class ["icon" "icon-sm" class]
          :xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 24 24"
          :fill "none"
          :stroke "currentColor"
          :stroke-width "2"
          :stroke-linecap "round"
          :stroke-linejoin "round"
          :aria-hidden "true"}
    [:path {:d "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"}]]))

(defn- with-leading-icon
  "A cmd/command-item rendered without `:icon`, given `svg` in the icon slot —
   for icons outside the framework set."
  [[tag attrs & children] svg]
  (into [tag attrs svg] children))

(defn- more-vertical-icon
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

(def ^:private subagent-status-label
  {:running "running" :done "done" :error "error" :stopped "stopped"})

(defn- subagent-status-dot
  "card-status-indicator flags for a sub-agent's status; stopped shows none."
  [status]
  (case (or status :running)
    :running {:busy? true}
    :error   {:error? true}
    :done    {:active? true}
    nil))

(defn- buffer-menu
  "The topbar's view switch once the room has a buffer or sub-agent: a pill
   naming the view in front, opening a popover listing the chat, every buffer
   (× closes it for everyone), the sub-agents (`sub-agents`, read-only views)
   and \"Close all\". Switching stays client-local; a virtual new chat keeps
   its buffers on the pending room. A window's header reuses it with `opts`:
   `:switch!` (fn [view]) for its own pane, `:menu-id` (one popover per
   pane), `:class` on the pill."
  ([dispatch! room-id active-buffer buffers sub-agents people-of]
   (buffer-menu dispatch! room-id active-buffer buffers sub-agents people-of nil))
  ([dispatch! room-id active-buffer buffers sub-agents people-of opts]
  (let [switch! (or (:switch! opts)
                    (fn [buffer-id]
                      (dispatch! (if room-id
                                   {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id}
                                   {:type :pending/buffer-switch :buffer-id buffer-id}))))
        close!  (fn [buffer-id]
                  (dispatch! (if room-id
                               (if buffer-id
                                 {:type :ui/buffer-close :room-id room-id :buffer-id buffer-id}
                                 {:type :ui/buffers-close-all :room-id room-id})
                               {:type :pending/buffer-close :buffer-id buffer-id})))
        hide!   (fn [^js e]
                  (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover)))
        active  (get buffers active-buffer)
        sub     (when-not active
                  (some #(when (= active-buffer (:id %)) %) sub-agents))
        batch   (when-let [first-id (sav/group-view-id active-buffer)]
                  (when-let [a (some #(when (= first-id (:id %)) %) sub-agents)]
                    (filter #(= (:anchor a) (:anchor %)) sub-agents)))
        batch?  (some? batch)
        kind    (cond active (buffers/kind active-buffer active)
                      (or sub batch?) :subagent)
        label   (cond active (buffers/label active-buffer active)
                      sub    (or (:label sub) (:task sub) "Sub-agent")
                      batch? (str (or (sav/shared-label (map sav/label batch)) "Sub-agents")
                                  " ×" (count batch))
                      (= :empty active-buffer) "Pick a view"
                      :else  "Chat")
        menu-id (or (:menu-id opts) "buffer-menu")
        item    (fn [{:keys [id icon title active? people on-click on-close key]}]
                  [:button {:class ["sidebar-more-item" "buffer-menu-item"
                                    (when active? "buffer-menu-item--active")]
                            :replicant/key (or key id)
                            :title title
                            :on {:click (fn [e] (hide! e) (on-click))}}
                   (if (keyword? icon) (icon/icon {:icon-name icon :size :sm}) icon)
                   [:span {:class ["buffer-menu-title"]} title]
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
     [:button (merge {:class ["tab-pill" "buffer-menu-trigger" (:class opts)]
                      :title "Switch view"
                      :replicant/key (str menu-id "-trigger")}
                     (popover/trigger-attrs menu-id))
      (when kind (buffer-kind-icon kind))
      [:span {:class ["buffer-menu-label"]} label]
      (icon/icon {:icon-name :chevron-down :size :sm})]
     (popover/popover-content
      {:id    menu-id
       :side  :bottom
       :align (if (:switch! opts) :start :center)
       :class "sidebar-more-menu buffer-menu"
       :attrs {:replicant/key menu-id}}
      (item {:id "chat" :icon (message-circle-icon) :title "Chat"
             :active? (not (or active sub batch?)) :people (people-of :chat)
             :on-click #(switch! :chat)})
      (for [[id buf] (buffers/ordered buffers)
            :let [title (buffers/label id buf)]]
        (item {:id (str id) :icon (buffer-kind-icon (buffers/kind id buf)) :title title
               :active? (= id active-buffer)
               :people (people-of id)
               :on-click #(switch! id)
               :on-close #(close! id)}))
      (for [{:keys [id label task status]} sub-agents]
        (item {:id id :icon (messages-icon)
               :title (str (or label task "Sub-agent") " · "
                           (get subagent-status-label status (name (or status :running))))
               :active? (= id active-buffer)
               :people (people-of id)
               :on-click #(switch! id)}))
      (when (seq buffers)
        (list
         [:div {:class ["overflow-menu-divider"] :replicant/key "buffer-menu-divider"}]
         (item {:id "close-all" :icon :x
                :title (str "Close all buffers (" (count buffers) ")")
                :on-click #(close! nil)}))))))))

(defn overflow-menu
  "Three-dots overflow menu of every topbar (:web/overflow-menu?). `git-ctx`
   enables \"Git status\": {:mode :room :room-id} opens the room's :diff
   buffer, {:mode :project :cwd} navigates to the git-status page, nil hides
   it."
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
         ;; Windows (xi.web.layout): on a joined chat
         (when (wlayout/room-ctx state)
           (list
            [:div {:class ["overflow-menu-divider"] :replicant/key "overflow-windows-divider"}]
            (for [[k label dir] [["split-right" "Split window right" :row]
                                 ["split-down" "Split window down" :col]]]
              [:button {:class ["overflow-menu-item"]
                        :replicant/key k
                        :data-key-action (str "layout/" k)
                        :on {:click (fn [e]
                                      (.stopPropagation e)
                                      (dispatch! {:type :overflow/close})
                                      (dispatch! {:type :layout/split :dir dir}))}}
               (pane-icon (keyword k))
               [:span label]])
            (when (wlayout/windows? state)
              [:button {:class ["overflow-menu-item"]
                        :replicant/key "layout-reset"
                        :data-key-action "layout/reset"
                        :on {:click (fn [e]
                                      (.stopPropagation e)
                                      (dispatch! {:type :overflow/close})
                                      (dispatch! {:type :layout/reset}))}}
               (pane-icon :only)
               [:span "Back to one window"]])))
         ;; Extension nav items: :mode-scoped ones only in the matching git-ctx
         ;; mode, with the ctx keys merged into their event.
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
         (when-let [sid (get-in room [:session :id])]
           (for [{:keys [label icon on-click]} (session-link-items dispatch! sid (.-title js/document))]
             [:button {:class ["overflow-menu-item"]
                       :replicant/key (str "session-link-" label)
                       :on {:click (fn [e]
                                     (.stopPropagation e)
                                     (dispatch! {:type :overflow/close})
                                     (on-click))}}
              (icon/icon {:icon-name icon :size :sm})
              [:span label]]))
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

(defn- pending-for-viewed-room?
  "Whether a stashed prompt / command belongs to the chat on screen. A known
   `room-id` (the room it was aimed at) must match; `session-id` alone may
   have drifted to the now-viewed chat, so it only counts while no room has
   joined. A virtual new chat matches when neither side has a session."
  [room sid room-id session-id]
  (cond
    room-id    (= room-id (:id room))
    session-id (and sid (= session-id sid))
    :else      (and (nil? sid) (nil? (:id room)))))

(defn- optimistic-post
  "The user's bubble at the tail of the timeline the instant a prompt is sent,
   suppressed once the matching :user entry lands in history."
  [dispatch! state room sid history]
  (when-let [{:keys [room-id session-id text images]} (:web/optimistic state)]
    (let [for-this? (pending-for-viewed-room? room sid room-id session-id)
          last-user (->> history (filter #(= :user (:kind %))) last)
          confirmed? (and last-user
                          (= (not-empty (some-> text str/trim))
                             (not-empty (some-> (:text last-user) str/trim)))
                          (= (count images) (count (:images last-user))))]
      (when (and for-this? (not confirmed?))
        (entry->post dispatch! {:kind :user :text text :images images})))))

(defn- pending-command-post
  "Spinner bubble for a backend slash command stashed until its room joins
   (:web/pending-command). Matched to the viewed room like optimistic-post."
  [state room sid]
  (when-let [{:keys [room-id session-id name args]} (:web/pending-command state)]
    (let [for-this? (pending-for-viewed-room? room sid room-id session-id)]
      (when for-this?
        [:div {:class ["post" "post--user" "post--pending-command"]}
         [:div {:class ["post-body"]}
          [:div {:class ["post-content"]}
           [:span {:class ["pending-command-text"]}
            (str "/" name (when args (str " " args)))]
           (spinner)]]]))))

(def ^:private launch-logo-width
  "Rendered width (px) of the logo glyphs; the tagline is stretched to it."
  84)

(defn- launch-logo
  []
  ;; The viewBox hugs the glyphs (X spans 0–36, i 46–55) so the box edges
  ;; are the shape edges the tagline lines up with.
  [:svg {:class ["launch-logo"] :viewBox "0 0 55 40" :width launch-logo-width
         :aria-hidden "true"}
   [:defs
    [:linearGradient {:id "launch-logo-gradient" :gradientUnits "userSpaceOnUse"
                      :x1 "0" :y1 "0" :x2 "64" :y2 "40"}
     [:stop {:class ["launch-logo-stop-start"] :offset "0" :stop-color "#e83fb4"}]
     [:stop {:class ["launch-logo-stop-mid"] :offset "0.55" :stop-color "#8b4cf0"}]
     [:stop {:class ["launch-logo-stop-end"] :offset "1" :stop-color "#3b8fd6"}]]]
   [:g {:fill "url(#launch-logo-gradient)"}
    [:polygon {:points "0,0 9,0 36,40 27,40"}]
    [:polygon {:points "27,0 36,0 9,40 0,40"}]
    [:rect {:x "46" :y "0" :width "9" :height "9"}]
    [:rect {:x "46" :y "15" :width "9" :height "25"}]]])

(defn- launch-tagline
  "SVG text whose letter spacing is stretched (`textLength`) so the line is
   exactly as wide as the logo glyphs above it."
  [text]
  [:svg {:class ["launch-tagline"] :width launch-logo-width}
   [:text {:x "0" :y "100%" :textLength launch-logo-width :lengthAdjust "spacing"}
    text]])

(defn agents-file-label
  "An instruction file's path as the welcome card shows it: relative to
   `cwd` when inside it, else as given."
  [cwd path]
  (if (and cwd (str/starts-with? path (str cwd "/")))
    (subs path (inc (count cwd)))
    path))

(defn- launch-header
  "Welcome card at the top of every chat's timeline (logo, model, cwd,
   AGENTS.md files). Ordinary timeline content. For a virtual room the caller
   fills model and agents-files from the pending room. The cwd and each
   instruction file open the file menu (file-row-attrs)."
  [{:keys [model cwd agents-files pa? dispatch!]}]
  [:div {:class ["launch-header"]}
   [:div {:class ["launch-hero"]}
    (launch-logo)
    (launch-tagline (if pa? "PERSONAL AGENT" "CODING AGENT"))]
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
           [:span (file-row-attrs {:class ["launch-value" "launch-value--menu"] :title cwd}
                                  cwd {:dir? true})
            cwd]]))
       (when-let [files (seq agents-files)]
         (list
          [:dt {:replicant/key "agents-k"} "Loaded"]
          (into [:dd {:replicant/key "agents-v"}]
                (map (fn [path]
                       [:span (file-row-attrs {:class ["launch-value" "launch-value--menu" "launch-file"]
                                               :title path :replicant/key path}
                                              path)
                        (agents-file-label cwd path)]))
                files)))]])
   [:div {:class ["launch-footer"]}
    "Type /help for the full list of commands."]])

(defn- clamp-bubble-menu!
  "Keep the tap menu inside the visual viewport (flip above the tap point,
   nudge left), accounting for the iOS keyboard."
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
  "Action sheet for a tapped user bubble: Edit (inline editor; Save forks at
   that message and resubmits), Retry (fork and resubmit unchanged), Delete
   (fork and discard), Copy. Edit and Retry carry the message's images."
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
                              (dispatch! {:type :thread/compose-open
                                          :room-id room-id :index index}))}}
       (icon/icon {:icon-name :zap :size :sm})
       [:span "Ask about this"]]
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

(defn code-lines
  "The non-blank lines of `text`, trimmed: what the code menu's Split lines
   view offers to copy one by one."
  [text]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split-lines (or text ""))))

(defn- code-lines-menu
  "The code menu's Split lines view: one entry per line, a tap copies it."
  [dispatch! close! lines x y]
  (into [:div {:class ["bubble-menu" "code-lines-menu"]
               :replicant/key "lines"
               :style {:top (str y "px") :left (str x "px")}
               :replicant/on-mount clamp-bubble-menu!
               :on {:click (fn [e] (.stopPropagation e))}}]
        (map-indexed
         (fn [i line]
           [:button {:replicant/key i
                     :class ["bubble-menu-item" "code-lines-menu-item"]
                     :title line
                     :on {:click (fn [e]
                                   (.stopPropagation e)
                                   (close!)
                                   (copy! dispatch! line))}}
            (icon/icon {:icon-name :copy :size :sm})
            [:code line]]))
        lines))

(defn- file-path-menu
  "Floating menu for a right-clicked / long-pressed row of a tool block
   naming a file (file-row-attrs): the file header's entries
   (file-menu-items), View diff on an edit result (xi.diff/tool-diff->unified,
   no server round-trip) and Ask about this (the long-press that opens it
   would otherwise ask, xi.web.threads). A directory (`dir?`, the welcome
   card's cwd) gets Find file instead of View file. Opened by the delegated
   listener in xi.web.core like code-copy-menu."
  [dispatch! state room-id {:keys [path dir? diff-path diff-text x y ask-index ask-sub]}]
  (let [close!  (fn [] (dispatch! {:type :code/menu-close}))
        unified (some->> diff-text (diff/tool-diff->unified diff-path))
        extra   (cond-> []
                  dir?
                  (conj {:label    "Find file"
                         :icon     :search
                         :on-click #(dispatch! {:type :palette/open-file-finder :cwd path})})
                  unified
                  (conj {:label    "View diff"
                         :icon     :code
                         :on-click #(dispatch! {:type :ui/diff-open :room-id room-id
                                                :title (str "Diff: " (last (str/split diff-path #"/")))
                                                :text unified :engine :git})})
                  (and room-id (or ask-index ask-sub))
                  (conj {:label    "Ask about this"
                         :icon     :zap
                         :on-click #(dispatch! {:type :thread/compose-open :room-id room-id
                                                :index ask-index :sub-id ask-sub})}))]
    [:div {:class ["bubble-menu-backdrop"]
           :on {:click (fn [_] (close!))}}
     (into [:div {:class ["bubble-menu"]
                  :style {:top (str y "px") :left (str x "px")}
                  :replicant/on-mount clamp-bubble-menu!
                  :on {:click (fn [e] (.stopPropagation e))}}]
           (map (fn [{:keys [label icon on-click]}]
                  [:button {:class ["bubble-menu-item"]
                            :on {:click (fn [e]
                                          (.stopPropagation e)
                                          (close!)
                                          (on-click))}}
                   (icon/icon {:icon-name icon :size :sm})
                   [:span label]]))
           (file-menu-items dispatch! state path {:extra extra :view? (not dir?)}))]))

(defn- table-copy-menu
  "Floating menu under a rendered table's ⋯ button: copy it as the Markdown
   source (`text`) or as tab-separated plain text (`tsv`) for a spreadsheet.
   Opened by the delegated listener in xi.web.core like code-copy-menu."
  [dispatch! {:keys [text tsv x y]}]
  (let [close! (fn [] (dispatch! {:type :code/menu-close}))
        entry  (fn [label icon-name content]
                 [:button {:class ["bubble-menu-item"]
                           :on {:click (fn [e]
                                         (.stopPropagation e)
                                         (close!)
                                         (copy! dispatch! content))}}
                  (icon/icon {:icon-name icon-name :size :sm})
                  [:span label]])]
    [:div {:class ["bubble-menu-backdrop"]
           :on {:click (fn [_] (close!))}}
     [:div {:class ["bubble-menu"]
            :style {:top (str y "px") :left (str x "px")}
            :replicant/on-mount clamp-bubble-menu!
            :on {:click (fn [e] (.stopPropagation e))}}
      (entry "Copy as Markdown" :copy text)
      (entry "Copy for spreadsheet" :file-spreadsheet tsv)]]))

(defn- code-copy-menu
  "Floating menu for a tapped code block: Copy, Split lines (a multi-line
   block: lists its lines to copy one by one), View file (when the block
   belongs to a tool call naming a file), and View diff (an edit result or
   permission preview, converted by xi.diff/tool-diff->unified; no server
   round-trip), then the extensions' entries (xi.web.block-menu). The tap is
   detected by a delegated listener in xi.web.core."
  [dispatch! state room-id {:keys [text path diff-path diff-text x y ask-index ask-sub split?] :as menu}]
  (let [close! (fn [] (dispatch! {:type :code/menu-close}))
        lines  (code-lines text)]
    [:div {:class ["bubble-menu-backdrop"]
           :on {:click (fn [_] (close!))}}
     ;; distinct keys: the swapped-in view mounts fresh and re-clamps
     (if split?
       (code-lines-menu dispatch! close! lines x y)
       [:div {:class ["bubble-menu"]
              :replicant/key "main"
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
        (when (> (count lines) 1)
          [:button {:class ["bubble-menu-item"]
                    :on {:click (fn [e]
                                  (.stopPropagation e)
                                  (dispatch! {:type :code/menu-split}))}}
           (icon/icon {:icon-name :list :size :sm})
           [:span "Split lines"]])
        ;; the code sits in a chat block: ask a sub-agent about that block
        (when (and room-id (or ask-index ask-sub))
          [:button {:class ["bubble-menu-item"]
                    :on {:click (fn [e]
                                  (.stopPropagation e)
                                  (close!)
                                  (dispatch! {:type :thread/compose-open :room-id room-id
                                              :index ask-index :sub-id ask-sub}))}}
           (icon/icon {:icon-name :zap :size :sm})
           [:span "Ask about this"]])
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
           [:span "View diff"]])
        (for [{:keys [label icon event]}
              (block-menu/items (select-keys menu [:text :path :line :lang :diff-path]) state)]
          [:button {:class ["bubble-menu-item"]
                    :on {:click (fn [e]
                                  (.stopPropagation e)
                                  (close!)
                                  (dispatch! event))}}
           (icon/icon {:icon-name (or icon :chevron-right) :size :sm})
           [:span label]])])]))


(defn- block-info
  [entry cwd tool-args]
  (case (:kind entry)
    :tool-call (let [name (util/strip-mcp-prefix (:tool entry))]
                 {:kind     :tool-call
                  :name     name
                  :detail   (some-> (tool-detail tool-args name (:arguments entry) cwd)
                                    str str/split-lines first)
                  :running? (= :running (:status entry))
                  :error?   (boolean (:is-error entry))})
    :thinking  {:kind :thinking :name "Thinking"}
    nil))

(defn- subagent-duration [{:keys [started ended]}]
  (when started
    (format-elapsed (quot (- (or ended (.now js/Date)) started) 1000))))

(defn- subagent-actions
  "A sub-agent's buttons, on its row, its result and its view: stop while it
   runs, then open as chat (the session it was saved as when it finished),
   keep that session as a regular one, and dismiss. Icons by default;
   `{:text? true}` renders the titles instead, `{:window? false}` leaves
   out Show in a window (it is in one already), `{:stop? false}` Stop (its
   composer has it)."
  ([dispatch! room-id child] (subagent-actions dispatch! room-id child nil))
  ([dispatch! room-id {:keys [id status session-id upgraded?]}
    {:keys [text? window? stop?] :or {window? true stop? true}}]
  (let [btn (fn [cls title ev icon]
              [:button {:class [cls] :title title :replicant/key cls
                        :on {:click (fn [e]
                                      (.stopPropagation e)
                                      (dispatch! ev))}}
               (if text? title icon)])]
    (if (= :running status)
      (list (when window?
              (btn "subagent-window" "Show in a window"
                   {:type :layout/open-view :view id :where :right :focus? true}
                   (pane-icon :dock-right)))
            (when stop?
              (btn "subagent-stop" "Stop sub-agent"
                   {:type :subagent/abort :room-id room-id :sub-id id}
                   (icon/icon {:icon-name :circle-x :size :sm}))))
      (list (when window?
              (btn "subagent-window" "Show in a window"
                   {:type :layout/open-view :view id :where :right :focus? true}
                   (pane-icon :dock-right)))
            (btn "subagent-open" "Open as chat"
                 (if session-id
                   {:type :route/navigate :page :chat :session-id session-id}
                   {:type :subagent/promote :room-id room-id :sub-id id})
                 (message-circle-icon))
            (when (and session-id (not upgraded?))
              (btn "subagent-upgrade" "Keep as session"
                   {:type :subagent/upgrade :room-id room-id :sub-id id}
                   (icon/icon {:icon-name :bookmark :size :sm})))
            (btn "subagent-dismiss" "Dismiss"
                 {:type :subagent/dismiss :room-id room-id :sub-id id}
                 (icon/icon {:icon-name :x :size :sm})))))))

;; ── Threads on blocks (xi.ext.subagent.threads, xi.web.threads) ──────────────

(defn- node-label
  "What a thread about a sub-agent's block names: the block's start, else
   its address. nil for the room's own blocks, which the thread sits under."
  [node]
  (when (:sub-id node)
    (str "about " (if-let [h (:hint node)]
                     (str "“" h "”")
                     (sa-threads/address node)))))

(defn- block-thread-card
  "A question thread on a block (xi.ext.subagent.threads/threads): its turns
   are sub-agents, so the latest one with a saved session opens as a chat."
  [dispatch! state room-id {:keys [id node turns]} collapsed?]
  (thread-card dispatch! state room-id
               {:key        (str "thr-" id)
                :turns      (mapv (fn [c] {:question (:question c) :sub-id (:id c)}) turns)
                :children   turns
                :collapsed? collapsed?
                :rev        (get-in state [:web/thread-rev id])
                :on-rev     #(dispatch! {:type :thread/rev :thread id :rev %})
                :label      (node-label node)
                :quote      (some-> (:selection (first turns)) (sa/clip 280))
                :input-id   (str "thread-reply-" id)
                :on-reply   #(dispatch! {:type :thread/reply :room-id room-id :thread id :text %})
                :on-toggle  #(dispatch! {:type :thread/toggle :thread id})
                :on-close   #(dispatch! {:type :subagent/thread-close :room-id room-id :thread id})
                :actions    (when-let [sid (some :session-id (rseq turns))]
                              (button/button {:variant :ghost :size :sm
                                              :attrs {:aria-label "Open as chat" :title "Open as chat"}
                                              :on-click (fn [_] (dispatch! {:type :route/navigate :page :chat
                                                                            :session-id sid}))}
                                             (message-circle-icon)))}))

(defn- thread-composer
  "The question field of a new thread on a block (:web/thread-compose,
   xi.web.threads), with one-tap questions for the block's kind; a
   `selection` (text selected in the block) is quoted above the field."
  [dispatch! {:keys [node selection]}]
  (let [input-id "thread-compose-input"
        close!   (fn [] (dispatch! {:type :thread/compose-close}))
        submit!  (fn [text] (dispatch! {:type :thread/compose-submit :text text}))
        send!    (fn [_] (when-let [el (.getElementById js/document input-id)]
                           (submit! (.-value el))))
        enter!   (enter-submits send!)]
    [:div {:class ["diff-thread" "thread-card" "thread-compose"] :replicant/key "thread-compose"}
     [:div {:class ["diff-thread-head"]}
      [:span {:class ["diff-thread-agent"]} (icon/icon {:icon-name :zap :size :sm}) "Ask a sub-agent"]
      (when-let [l (node-label node)] [:span {:class ["thread-label"]} l])
      [:span {:class ["diff-thread-spacer"]}]
      (button/button {:variant :ghost :size :sm :attrs {:aria-label "Cancel"}
                      :on-click (fn [_] (close!))}
                     (icon/icon {:icon-name :x :size :sm}))]
     (when selection
       [:blockquote {:class ["thread-quote"]} (sa/clip selection 280)])
     (form/form-textarea-auto
      {:placeholder (if selection "Ask about the selected text…" "Ask about this block…")
       :max-rows    6
       :class       "diff-thread-input"
       :attrs       {:id input-id
                     :replicant/on-mount
                     (fn [{:replicant/keys [^js node]}]
                       (.focus node #js {:preventScroll true})
                       ;; opened from a menu: it may sit under the top bar
                       (some-> (.closest node ".thread-compose")
                               (.scrollIntoView #js {:block "center" :behavior "smooth"})))
                     :on {:keydown (fn [^js e]
                                     (if (= "Escape" (.-key e))
                                       (close!)
                                       (enter! e)))}}})
     [:div {:class ["diff-thread-foot"]}
      [:div {:class ["diff-thread-chips"]}
       (for [q (sa-threads/suggestions (cond selection                     :selection
                                             (sa-threads/entry-node? node) (:kind node)))]
         (chip/chip {:on-click (fn [_] (submit! q)) :attrs {:replicant/key q}} q))]
      [:span {:class ["diff-thread-spacer"]}]
      (button/button {:variant :primary :size :sm :attrs {:aria-label "Send"} :class "diff-thread-send"
                      :on-click send!}
                     (icon/icon {:icon-name :arrow-up :size :sm}))]]))

(defn- thread-cards
  "`threads` (xi.ext.subagent.threads/threads) and the open `compose`r, as
   cards for one place in the timeline."
  [dispatch! state room-id threads compose]
  (let [collapsed (:web/thread-collapsed state #{})]
    (concat
     (for [th threads]
       (block-thread-card dispatch! state room-id th (contains? collapsed (:id th))))
     (when compose
       [(thread-composer dispatch! compose)]))))

(defn- thread-post
  "A thread card as a timeline post of its own."
  [card]
  [:div {:class ["post" "post--thread"]
         :replicant/key (str "p-" (get-in card [1 :replicant/key]))}
   card])

(defn- thread-menu
  "The touch menu of a tapped block (:web/thread-menu, xi.web.threads): ask
   a sub-agent about it, or copy it."
  [dispatch! room-id {:keys [index sub-id text x y]}]
  (let [close! (fn [] (dispatch! {:type :thread/menu-close}))
        item   (fn [icon-name label f]
                 [:button {:class ["bubble-menu-item"] :replicant/key label
                           :on {:click (fn [e] (.stopPropagation e) (close!) (f))}}
                  (icon/icon {:icon-name icon-name :size :sm})
                  [:span label]])]
    [:div {:class ["bubble-menu-backdrop"] :on {:click (fn [_] (close!))}}
     [:div {:class ["bubble-menu"]
            :style {:top (str y "px") :left (str x "px")}
            :replicant/on-mount clamp-bubble-menu!
            :on {:click (fn [e] (.stopPropagation e))}}
      (item :zap "Ask about this"
            #(dispatch! {:type :thread/compose-open :room-id room-id :index index :sub-id sub-id}))
      (when (seq text)
        (item :copy "Copy" #(copy! dispatch! text)))]]))

(defn- subagent-origin-banner
  "Atop a chat that is a sub-agent's hidden session (its :subagent-origin):
   back to the chat that spawned it, or keep it as a regular session, listed
   with the others (xi.ext.subagent upgrade-session)."
  [dispatch! room]
  (when-let [{parent :session-id} (get-in room [:session :subagent-origin])]
    [:div {:class ["subagent-origin"]}
     (messages-icon)
     [:span {:class ["subagent-origin-text"]} "A sub-agent's session, hidden from your chats"]
     [:span {:class ["diff-thread-spacer"]}]
     (when parent
       (button/button {:variant :ghost :size :sm
                       :on-click (fn [_] (dispatch! {:type :route/navigate :page :chat
                                                     :session-id parent}))}
                      "Back to parent"))
     (button/button {:variant :ghost :size :sm
                     :on-click (fn [_] (dispatch! {:type :session/subagent-upgrade
                                                   :session-id (get-in room [:session :id])}))}
                    (icon/icon {:icon-name :bookmark :size :sm})
                    "Keep as session")]))

(defn- ask-hover-button
  "The one floating Ask button of a timeline: xi.web.threads moves it to the
   hovered block and names that block in its data attributes, without a
   render; a click opens the block's thread composer."
  [dispatch! room-id]
  [:button {:class ["ask-hover"] :type "button" :replicant/key "tl-ask-hover"
            :title "Ask a sub-agent about this block" :aria-label "Ask about this block"
            ;; mousedown would collapse the selection the button may be on
            :on {:mousedown (fn [^js e] (.preventDefault e))
                 :click (fn [^js e]
                          (let [ds (.. e -currentTarget -dataset)]
                            (dispatch! (cond-> {:type    :thread/compose-open
                                                :room-id room-id
                                                :index   (some-> (.-askIndex ds) not-empty parse-long)
                                                :sub-id  (not-empty (.-askSub ds))}
                                         (not-empty (.-askSelection ds))
                                         (assoc :selection (.-askSelection ds))))))}}
   (icon/icon {:icon-name :zap :size :sm})])

(defn- subagent-block-ctx
  "What a sub-agent's rows need to follow the chat's block rules, from the
   app `state` for `room`: the effective appearance, the session's
   remembered open / closed blocks (:web/block-open) and the key they file
   under (the chat's `sid`, else the room id, as in chat-body)."
  [state room sid]
  (let [session-key (or sid (:id room))]
    {:room-id     (:id room)
     :cwd         (or (:cwd room) (get-in state [:web/pending-room :cwd]))
     :app         (appearance/effective-in state)
     :session-key session-key
     :block-open  (get-in state [:web/block-open session-key])}))

(defn- subagent-items
  "A sub-agent's history as viewer items ({:key :node :group? :collapsed?
   :info}, what group-viewer-items folds) under the same block rules as the
   chat's own timeline: a tool or thinking row starts open or collapsed per
   the appearance (appearance/block-collapsed?), shows its arguments per
   :tool-args, and the user's toggle is remembered in the session's
   :web/block-open under `sa-<sub-id>-<i>` (remember-open). `ctx` is a
   subagent-block-ctx; with `ask?` each row can be asked about (with-ask-ref)."
  [dispatch! {:keys [room-id cwd app session-key block-open]} prefix ask?
   {:keys [id history]}]
  (keep-indexed
   (fn [i entry]
     (let [block?     (contains? #{:tool-call :thinking} (:kind entry))
           k          (when block? (str "sa-" id "-" i))
           collapsed? (and block? (appearance/block-collapsed? app entry))
           key        (str prefix i)]
       (when-let [post (entry->post
                        dispatch!
                        (cond-> (assoc entry :room-id room-id :cwd cwd
                                       :tool-args (:tool-args app))
                          block? (assoc :grouped? true
                                        :collapsed? collapsed?
                                        :open-choice (get block-open k)
                                        :toggle! (remember-open dispatch! session-key k))))]
         {:key        key
          :group?     block?
          :collapsed? collapsed?
          :info       (when block? (block-info entry cwd (:tool-args app)))
          :node       (with-post-key key (cond-> post
                                           ask? (with-ask-ref {:sub-id id :index i})))})))
   history))


(defn- subagent-open-event
  "Show `view` (a sub-agent id or a batch's sav/group-view) in the right
   sidebar and focus it."
  [view]
  {:type :layout/open-view :view view :where :right :focus? true})

(defn- subagent-state-mark
  "A sub-agent's state (sav/state-of) at the head of its rows: the spinner
   while it runs, else a dot."
  [st]
  (if (= :running st)
    (spinner)
    [:span {:class ["subagent-dot" (str "subagent-dot--" (name st))]}]))

(defn- subagent-tool-row
  "A sub-agent row dressed exactly as a tool block's header (tool-post:
   .tool-call-block > .tool-call-toggle): the spinner in the chevron's slot
   while it runs, `action` and `label` like a tool's name and argument, then
   a muted `summary`, `meta` and ✕ when it failed. A click opens it in the
   right sidebar (`view`) instead of expanding."
  [dispatch! {:keys [view running? ask? error? action label summary meta ask-sub]}]
  [:div (cond-> {:class ["post" "post--tool" "post--subagent-row"]}
          ask-sub (assoc :data-ask-sub ask-sub))
   [:div {:class ["tool-call-block" (when ask? "subagent-row--ask")]}
    [:button {:class ["tool-call-toggle" "subagent-row"] :type "button"
              :title "Open in the sidebar"
              :on {:click (fn [_] (dispatch! (subagent-open-event view)))}}
     [:span {:class (cond-> ["tool-call-toggle-icon"]
                      running? (conj "tool-call-toggle-icon--running"))}
      (if running? (spinner) (icon/icon {:icon-name :chevron-right :size :sm}))]
     [:span {:class ["tool-call-toggle-label"]}
      [:span {:class ["tool-call-action"]} action]
      (when (seq label) (str " " label))]
     (when (seq summary)
       [:span {:class ["subagent-row-summary"]} summary])
     (when meta
       [:span {:class ["subagent-row-dur"]} meta])
     (when error?
       [:span {:class ["tool-call-status" "tool-call-status--error"] :title "Failed"}
        (icon/icon {:icon-name :x :size :sm})])]]])

(defn- subagent-row
  "A sub-agent where it was spawned, in place of its spawn_subagent call: a
   tool-block header (subagent-tool-row) with its label, a one-line summary
   (sav/summary) and its running time. A click opens it in the right
   sidebar (subagent-view)."
  [dispatch! asks {:keys [id] :as child}]
  (let [st (sav/state-of asks child)]
    (subagent-tool-row dispatch!
                       {:view     id
                        :ask-sub  id
                        :running? (= :running st)
                        :ask?     (= :ask st)
                        :error?   (= :error st)
                        :action   "Sub-agent"
                        :label    (sav/label child)
                        :summary  (if (= :ask st) "needs you" (sav/summary asks child))
                        :meta     (subagent-duration child)})))

(defn- need-you [n]
  (str n (if (= 1 n) " needs you" " need you")))

(defn- subagent-batch-row
  "The sub-agents spawned at one anchor (a commit-all style batch) as one
   tool-block header: their count and shared label (sav/shared-label), how
   many wait on you or failed, and how many finished. A click opens the
   batch view (subagent-group-view) in the right sidebar."
  [dispatch! asks children]
  (let [{:keys [ask finished error total]} (sav/counts asks children)
        done? (= finished total)]
    (subagent-tool-row dispatch!
                       {:view     (sav/group-view (:id (first children)))
                        :running? (and (zero? ask) (not done?))
                        :ask?     (pos? ask)
                        :error?   (and done? (pos? error))
                        :action   (str "Sub-agents ×" total)
                        :label    (sav/shared-label (map sav/label children))
                        :summary  (cond (pos? ask)   (need-you ask)
                                        (pos? error) (str error " failed"))
                        :meta     (str finished "/" total)})))

(defn- subagent-asks
  "A sub-agent's pending permission asks (xi.subagent/confirm-fn) atop its
   view, answered like the chat's own (:web/dialog-answer)."
  [dispatch! state room-id asks]
  (when (seq asks)
    [:div {:class ["subagent-asks"] :replicant/key "sv-asks"}
     (for [{:keys [id diff] :as d} asks]
       [:div {:class ["subagent-ask"] :replicant/key id}
        [:div {:class ["subagent-ask-msg"]} (sav/ask-text d)]
        (diff-preview dispatch! diff id (contains? (:web/md-diff-code state) id))
        [:div {:class ["tool-call-permission-actions"]}
         (confirm-buttons d
                          (fn [value] (dispatch! {:type :web/dialog-answer :room-id room-id
                                                  :dialog-id id :value value}))
                          #(dispatch! {:type :deny-reason/start :room-id room-id :dialog-id id}))]])]))

(defn- room-sub-agents
  "The room's sub-agents that list as such (not a block's Explain / Ask)."
  [room]
  (remove #(sa/inline-sub? (:id %)) (get-in room [:ext :subagents :agents])))

(defn- show-in-pane!
  "Show `view` in window `pane-id`, else in the right sidebar."
  [dispatch! pane-id view]
  (dispatch! (if pane-id
               {:type :layout/set-view :pane-id pane-id :view view}
               (subagent-open-event view))))

(defn- subagent-group-view
  "A batch (sav/batch-of) in a window: its children, the ones waiting on you
   first, and Allow all for their asks; a row shows that child in the same
   window, whose view links back here."
  [dispatch! room pane-id children]
  (let [room-id (:id room)
        asks    (sav/asks-by-sub (get-in room [:ui :dialogs]))
        prefix  (sav/shared-label (map sav/label children))
        {:keys [finished total]} (sav/counts asks children)
        rank    {:ask 0 :running 1}
        pending (filter #(= :confirm (:type %)) (mapcat #(get asks (:id %)) children))]
    [:div {:class ["file-tab" "subagent-view" "subagent-group-view"]
           :replicant/key (str "subagent-group-" (:id (first children)))}
     [:div {:class ["subagent-group-list"]}
      [:div {:class ["subagent-group-head"]}
       [:span (str finished " of " total " finished")]
       (when (seq pending)
         (button/button {:variant :primary :size :sm :class "subagent-allow-all"
                         :on-click (fn [_]
                                     (doseq [{:keys [id]} pending]
                                       (dispatch! {:type :web/dialog-answer :room-id room-id
                                                   :dialog-id id :value true})))}
                        (str "Allow all " (count pending))))]
      (for [c (sort-by #(get rank (sav/state-of asks %) 2) children)
            :let [st (sav/state-of asks c)]]
        [:button {:class ["subagent-group-item" (str "subagent-group-item--" (name st))]
                  :replicant/key (:id c)
                  :on {:click (fn [_] (show-in-pane! dispatch! pane-id (:id c)))}}
         (subagent-state-mark st)
         [:span {:class ["subagent-group-title"]} (sav/child-title prefix (sav/label c))]
         [:span {:class ["subagent-group-dur"]} (subagent-duration c)]
         [:span {:class ["subagent-group-summary"]} (sav/summary asks c)]])]]))

(defn- agents-popover-item
  [open! asks prefix {:keys [id] :as child}]
  (let [st (sav/state-of asks child)]
    [:button {:class ["agents-pop-item" (str "agents-pop-item--" (name st))]
              :replicant/key id
              :on {:click (fn [e] (open! e id))}}
     (subagent-state-mark st)
     [:span {:class ["agents-pop-label"]} (sav/child-title prefix (sav/label child))]
     [:span {:class ["agents-pop-dur"]} (subagent-duration child)]
     [:span {:class ["agents-pop-summary"]} (sav/summary asks child)]]))

(defn- agents-popover-batch
  [open! asks children]
  (let [{:keys [ask finished error total] :as n} (sav/counts asks children)
        prefix (sav/shared-label (map sav/label children))
        asking (filter #(= :ask (sav/state-of asks %)) children)]
    [:button {:class ["agents-pop-item" (str "agents-pop-item--" (if (pos? ask) "ask" "batch"))]
              :replicant/key (str "batch-" (:id (first children)))
              :on {:click (fn [e] (open! e (sav/group-view (:id (first children)))))}}
     (subagent-state-mark (sav/batch-state n))
     [:span {:class ["agents-pop-label"]} (str (or prefix "Sub-agents") " ×" total)]
     [:span {:class ["agents-pop-dur"]} (str finished "/" total)]
     [:span {:class ["agents-pop-summary"]}
      (if (pos? ask)
        (need-you ask)
        (str finished " of " total " finished" (when (pos? error) (str " · " error " failed"))))]
     (when (seq asking)
       [:span {:class ["agents-pop-chips"]}
        (for [c asking]
          [:span {:class ["agents-pop-chip"] :replicant/key (:id c)}
           (sav/child-title prefix (sav/label c))])])]))

(defn- agents-button
  "The composer bar's Agents button (floating-actions) while the room has
   sub-agents, with how many wait on you; its popover lists them by the
   prompt that spawned them (sav/by-turn), a batch as one entry. A pick opens
   it in the right sidebar."
  [dispatch! room history children]
  (when (seq children)
    (let [asks  (sav/asks-by-sub (get-in room [:ui :dialogs]))
          {:keys [ask running total]} (sav/counts asks children)
          open! (fn [e view] (close-popover! e) (dispatch! (subagent-open-event view)))]
      (list
       [:button (merge {:class ["quick-cmd" "agents-btn"]
                        :title "Sub-agents" :aria-label "Sub-agents"
                        :replicant/key "agents-btn"}
                       (popover/trigger-attrs "agents-popover"))
        (messages-icon)
        [:span {:class ["qc-label"]} "Agents"]
        [:span {:class ["agents-btn-count"]} total]
        (when (pos? ask)
          [:span {:class ["agents-btn-ask"] :title (need-you ask)} ask])]
       (popover/popover-content
        {:id    "agents-popover"
         :side  :top
         :align :end
         :class "agents-popover"
         :attrs {:replicant/key "agents-popover"}}
        [:div {:class ["agents-pop-head"]}
         (messages-icon)
         [:span {:class ["agents-pop-title"]} "Sub-agents"]
         [:span {:class ["agents-pop-count"]} total]
         [:span {:class ["agents-pop-spacer"]}]
         (when (pos? running)
           [:span {:class ["agents-pop-running"]} (spinner) (str running " running")])
         (when (pos? ask)
           [:span {:class ["agents-btn-ask"] :title (need-you ask)} ask])]
        [:div {:class ["agents-pop-list"]}
         (for [{:keys [index prompt batches]} (sav/by-turn history children)]
           (list
            [:div {:class ["agents-pop-turn"] :replicant/key (str "turn-" index)}
             (or prompt "Before the first prompt")]
            (for [b batches]
              (if (next b)
                (agents-popover-batch open! asks b)
                (agents-popover-item open! asks nil (first b))))))])))))

(defn- quick-replies-row
  "One-tap reply chips for the last assistant response (xi.quick-replies); a
   tap sends the message as a normal prompt. Hidden while a turn is in flight."
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

(defn- group-line
  "A block's `Name detail` in a super-group summary. `phase` :in / :out
   sweeps it in or out with a soft mask (style.css .viewer-group-line--in
   / --out); the sweep in takes longer for longer lines, like typing them."
  [k {action :name :keys [detail]} phase]
  [:span (cond-> {:class (cond-> ["viewer-group-line"]
                           phase (conj (str "viewer-group-line--" (name phase))))
                  :replicant/key k}
           (= :in phase)
           (assoc :style {:animation-duration
                          (str (min 320 (+ 140 (* 3 (+ (count action) (count detail))))) "ms")}))
   [:span {:class ["tool-call-action"]} action]
   (when (seq detail)
     (str " " detail))])

(defn- super-group
  "One summary row standing in for a run of collapsed viewer rows (step count,
   newest block, spinner, error badge). A <details>, shut unless the user's
   remembered `open-choice` says otherwise; `toggle!` is its remember-open.
   While the run streams, each new step erases the previous block's line and
   types in its own: both are keyed by the step count, so they remount (and
   replay their sweep) exactly when a block is added."
  [run-key run open-choice toggle!]
  (let [{n :count :keys [error-count running? latest previous]}
        (viewer-group/summarize (map :info run))]
    [:details {:class ["viewer-tool-group" "viewer-tool-group--super"]
               :replicant/key (str "vg-" run-key)
               :open (boolean open-choice)}
     [:summary (cond-> {:class ["viewer-group-summary"]}
                 toggle! (assoc :on {:click toggle!}))
      [:span {:class ["tool-call-toggle-icon"]}
       (icon/icon {:icon-name :chevron-right :size :sm})]
      [:span {:class ["viewer-group-count"]} (viewer-group/count-label n)]
      (when latest
        [:span {:class ["viewer-group-latest"]}
         [:span {:class ["viewer-group-swap"]}
          (when (and running? previous)
            (group-line (str "out-" n) previous :out))
          (group-line (str "in-" n) latest (when running? :in))]])
      (when running?
        [:span {:class ["tool-call-running"]} (spinner)])
      (when (pos? error-count)
        [:span {:class ["tool-call-status" "tool-call-status--error"]
                :title (str error-count (if (= 1 error-count) " error" " errors"))}
         (icon/icon {:icon-name :x :size :sm})])]
     [:div {:class ["viewer-group-rows"]} (map :node run)]]))

(def ^:private timeline-item-cache
  (js/WeakMap.))

(defn- memo-timeline-items
  "chat-view's timeline items for history `entry`: `(build)`, reused while
   `ctx` (everything besides the entry the post reads) is unchanged, so
   Replicant skips unchanged posts on every keystroke. A nil `ctx` always
   rebuilds."
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
  "Viewer mode: fold runs of consecutive collapsible tool and thinking posts
   ({:key :node :group? :collapsed? :info}) into one `.viewer-tool-group`
   showing just their headers; with `super?`, an all-collapsed run folds
   further into a `super-group`, opened per `block-open` (a session's
   :web/block-open) and toggled through `toggle-fn` (key → remember-open)."
  [{:keys [super? block-open toggle-fn]} items]
  (mapcat
   (fn [run]
     (if (:group? (first run))
       (let [run-key (:key (first run))
             k       (str "vg-" run-key)]
         [(if (and super? (viewer-group/super-collapsible? run))
            (super-group run-key run (get block-open k)
                         (when toggle-fn (toggle-fn k)))
            [:div {:class ["viewer-tool-group"]
                   :replicant/key (str "vg-" run-key)}
             (map :node run)])])
       (map :node run)))
   (partition-by :group? items)))


(defn- flatten-children
  "Hiccup children with nested seqs spliced in, as Replicant flattens them."
  [xs]
  (mapcat #(if (seq? %) (flatten-children %) [%]) xs))


(defn- timeline-content
  "The .timeline-content div without nil children. Replicant keeps a nil child
   as a slot, and when keyed siblings shift past one (an answered permission
   ask moving on to the next tool block) it removes that slot as a node and
   throws; render! then rebuilds the whole page and the chat jumps to the top
   and back."
  [& children]
  (into [:div {:class ["timeline-content"]}]
        (remove nil?)
        (flatten-children children)))

(defn- subagent-back
  "A batch member's link back to its batch view (subagent-group-view)."
  [dispatch! pane-id batch]
  (let [prefix (sav/shared-label (map sav/label batch))]
    [:button {:class ["subagent-back"]
              :title "Back to the batch"
              :on {:click (fn [_] (show-in-pane! dispatch! pane-id
                                                 (sav/group-view (:id (first batch)))))}}
     (icon/icon {:icon-name :chevron-left :size :sm})
     (str (or prefix "Sub-agents") " ×" (count batch))]))

(defn- subagent-compose
  "The composer under a sub-agent's view: the chat's compose box without its
   action row and status line. Attach, the field (Enter sends
   :subagent/reply, which steers it while it runs), the spinner and Stop
   while it runs. Draft and images keep under `subagent:<id>` like a chat's."
  [dispatch! state room-id {:keys [id status]}]
  (let [running? (= :running status)
        dkey     (str "subagent:" id)
        draft    (get-in state [:web/drafts dkey])
        images   (get-in state [:web/compose-images dkey])
        input-id (str "subagent-compose-" id)
        file-id  (str input-id "-file")
        abort!   (fn [_] (dispatch! {:type :subagent/abort :room-id room-id :sub-id id}))
        send!    (fn [_]
                   (let [^js el (.getElementById js/document input-id)
                         text   (str/trim (or (some-> el .-value) draft ""))]
                     (when (seq text)
                       (dispatch! (cond-> {:type :subagent/reply :room-id room-id :sub-id id
                                           :text text}
                                    (seq images) (assoc :images (vec images))))
                       (when el (set! (.-value el) ""))
                       (dispatch! {:type :compose/clear-draft :draft-key dkey})
                       (when (seq images)
                         (dispatch! {:type :compose/clear-images :draft-key dkey})))))]
    [:div {:class ["compose-dock" "subagent-compose"] :replicant/key "sv-compose"}
     [:div {:class ["compose-box"]}
      (compose-image-strip dispatch! dkey images)
      [:div {:class ["compose-frame"]}
       [:div {:class ["compose-input-row"]}
        [:button {:class ["icon-btn" "compose-attach-btn"] :type "button"
                  :on {:click (fn [_] (some-> (.getElementById js/document file-id) (.click)))}}
         (icon/icon {:icon-name :image :size :md})]
        [:input {:id file-id :type "file" :multiple true :style {:display "none"}
                 :on {:change (fn [^js e]
                                (add-files! dispatch! dkey (array-seq (.. e -target -files)))
                                (set! (.. e -target -value) ""))}}]
        [:div {:class ["compose-input-wrapper"]}
         (form/form-textarea-auto
          {:placeholder (if running? "Steer…" "Reply…")
           :value       (or draft "")
           :max-rows    6
           :attrs {:id input-id
                   :replicant/on-render (fn [{:replicant/keys [^js node]}]
                                          (sync-compose-draft! node dkey draft))
                   :on {:input (fn [^js e]
                                 (dispatch! {:type :compose/set-draft :draft-key dkey
                                             :text (.. e -target -value)}))
                        :paste (fn [^js e] (handle-compose-paste! dispatch! dkey e))
                        ;; iOS Return (no Enter keydown), see compose-box
                        :beforeinput (fn [^js e]
                                       (when (= "insertLineBreak" (.-inputType e))
                                         (.preventDefault e)
                                         (send! e)))
                        :keydown (enter-submits send!)}}})]
        [:div {:class ["compose-actions"]}
         (when running?
           [:button {:class ["icon-btn" "compose-spinner-abort"] :aria-label "Stop sub-agent"
                     :on {:click abort!}}
            (spinner)])
         (when (or (not running?) (seq draft))
           [:button {:class ["icon-btn"] :aria-label (if running? "Send into its turn" "Send reply")
                     :on {:click send!}}
            (if running?
              (steer-icon)
              (icon/icon {:icon-name :arrow-up :size :md}))])
         (when running?
           [:button {:class ["icon-btn"] :aria-label "Stop sub-agent" :on {:click abort!}}
            (icon/icon {:icon-name :circle-x :size :md})])]]]]]))

(defn- subagent-view
  "A sub-agent in a window (the right sidebar its row opens) or in place of
   the chat, laid out like the chat: its pending asks (subagent-asks), task
   and history as the chat's timeline, the question threads on its blocks,
   then its composer (subagent-compose). The window bar (or the topbar)
   names it and carries its actions (subagent-bar-items). The rows follow
   the chat's block rules (subagent-items, folded like the timeline by
   group-viewer-items) and can be asked about. Its id stands in the room's
   :active-buffer though it names no buffer
   (xi.web.router/buffer-open-event)."
  [state dispatch! room {:keys [id task status history result exchanges session-id] :as child}]
  (let [room-id (:id room)
        status  (or status :running)
        ctx     (subagent-block-ctx state room (get-in room [:session :id]))
        asks    (get (sav/asks-by-sub (get-in room [:ui :dialogs])) id)
        threads (filter #(= id (get-in % [:node :sub-id]))
                        (sa-threads/threads (get-in room [:ext :subagents :agents])))
        compose (let [c (:web/thread-compose state)]
                  (when (and (= room-id (:room-id c)) (= id (get-in c [:node :sub-id]))) c))
        post    (fn [k entry]
                  (with-post-key k (entry->post dispatch! (assoc entry :room-id room-id
                                                                 :cwd (:cwd ctx)))))]
    [:div {:class ["file-tab" "subagent-view"] :replicant/key (str "subagent-view-" id)}
     ;; --sub: never the chat's timeline for xi.web.core's scroll follow
     [:div {:class ["timeline" "timeline--sub"]}
      (timeline-content
       (subagent-asks dispatch! state room-id asks)
       (when (seq task) (post "sv-task" {:kind :user :text task}))
       ;; A reloaded chat keeps its children without their history
       ;; (sa/saved-child), and a reply then starts it again: the exchanges
       ;; it no longer holds come first (sav/exchanges-before), else the
       ;; answer alone.
       (map-indexed
        (fn [i {:keys [question answer]}]
          (list (when (seq question)
                  (post (str "sv-xq-" i) {:kind :user :text question}))
                (when (seq answer)
                  (post (str "sv-xa-" i) {:kind :text :text answer}))))
        (sav/exchanges-before history exchanges))
       (when (and (empty? history) (empty? exchanges) (seq result))
         (post "sv-result" {:kind :text :text result}))
       (group-viewer-items
        {:super?     (:super-collapsed? (:app ctx))
         :block-open (:block-open ctx)
         :toggle-fn  (fn [k] (remember-open dispatch! (:session-key ctx) k))}
        (subagent-items dispatch! ctx "sv-" true child))
       (when (and (empty? history) (empty? result))
         [:div {:class ["subagent-empty"] :replicant/key "sv-empty"}
          (cond session-id            "Promoted to its own session: open it as a chat."
                (= :running status)   "No output yet."
                :else                 "No output.")])
       (map thread-post (thread-cards dispatch! state room-id threads compose))
       (ask-hover-button dispatch! room-id))]
     (subagent-compose dispatch! state room-id child)]))

(defn- subagent-bar-items
  "What a window bar (pane-header, `pane-id`) or the topbar (nil) shows for
   sub-agent `sub` beside its pill: a batch member's link back to its batch
   (subagent-back) and its actions (subagent-actions; Show in a window only
   outside a sidebar)."
  [dispatch! room pane-id side sub]
  (let [batch (sav/batch-of (:history room) (room-sub-agents room) (:id sub))]
    (list
     (when (next batch) (subagent-back dispatch! pane-id batch))
     (when pane-id
       [:span {:class ["pane-header-spacer"] :replicant/key "sa-spacer"}])
     [:span {:class ["subagent-bar-actions"] :replicant/key "sa-actions"}
      (subagent-actions dispatch! (:id room) sub {:window? (not side) :stop? false})])))

(defn- viewed-room
  "The active room only when its session id matches the route: mid-switch
   the client is still attached to the previous room, and a new chat (sid
   nil) must not adopt it."
  [state sid]
  (let [active (state/active-room state)]
    (when (= (get-in active [:session :id]) sid) active)))

(defn- never-persisted?
  "A joined room whose session has no provider / CLI id yet (a new chat
   nobody has prompted) has no history to wait for."
  [room]
  (and (some? room)
       (not (get-in room [:session :cli-session-id]))
       (not (get-in room [:session :provider-session-id]))))

(defn- chat-history
  "What the chat view paints for `sid`: the room's history once it has
   landed, else the :web/cache snapshot. An empty room history must not
   shadow the cache (the resume path installs [] before :session/resumed
   streams the messages in). `:resuming?` while the server copy of an
   existing session is still in flight; `:loading?` when there is no cache
   to paint meanwhile."
  [state room sid]
  (let [cached         (get-in state [:web/cache sid])
        room-history   (:history room)
        authoritative? (boolean (seq room-history))
        history        (if authoritative? room-history (:history cached))
        resuming?      (and (some? sid)
                            (not authoritative?)
                            (not (never-persisted? room))
                            (not (:web/optimistic state))
                            (not (:web/pending-submit state))
                            (not (:web/pending-command state)))]
    {:history   history
     :cached    cached
     :resuming? resuming?
     :loading?  (and resuming? (empty? history))}))



(defn- chat-ctx
  "What every part of the chat page reads: the room in view, its history,
   buffers and sub-agents, the composer's draft key, the file-drop state."
  [state dispatch!]
  (let [sid     (get-in state [:web/route :session-id])
        room    (viewed-room state sid)
        {:keys [history cached resuming? loading?]} (chat-history state room sid)
        busy?   (get-in room [:agent :busy?])
        new?    (nil? sid)
        model   (or (get-in room [:agent :model]) (:model cached)
                    (get-in state [:web/pending-room :model])
                    (when new? (get-in state [:lobby :model])))
        ready?  (not loading?)
        pa?     (get-in state [:lobby :agent-id])
        dkey    (draft-key state)
        ;; A virtual new chat keeps its buffers on the pending room.
        ui         (if room (:ui room) (when new? (get-in state [:web/pending-room :ui])))
        buffers    (:buffers ui)
        active-buf (get ui :active-buffer :chat)
        active-buffer (get buffers active-buf)
        buffer-kind (when active-buffer (buffers/kind active-buf active-buffer))
        ;; Sub-agents list with the buffers; one opened in front (subagent-view)
        ;; names no buffer.
        sub-agents (remove #(sa/inline-sub? (:id %)) (get-in room [:ext :subagents :agents]))
        sub-view   (when-not active-buffer
                     (some #(when (= active-buf (:id %)) %) sub-agents))
        has-tabs?  (boolean (or (seq buffers) (seq sub-agents)))
        nav-ctx (prompt-nav-ctx state history)
        ;; Drops stage on the composer, so only while it is actually showing.
        deny-reason (:web/deny-reason state)
        drop-zone? (and (nil? buffer-kind)
                        (nil? sub-view)
                        (not (string? (:text active-buffer)))
                        (not (:web/skill-form state))
                        (not (and room (= (:room-id deny-reason) (:id room))
                                  (some #(= (:dialog-id deny-reason) (:id %))
                                        (get-in room [:ui :dialogs])))))
        file-drag? (and drop-zone? (:web/file-drag? state))]
    {:sid sid :room room :history history :cached cached :resuming? resuming?
     :loading? loading? :busy? busy? :new? new? :model model :ready? ready?
     :pa? pa? :dkey dkey :buffers buffers :active-buf active-buf
     :sub-agents sub-agents :has-tabs? has-tabs? :nav-ctx nav-ctx
     :drop-zone? drop-zone? :file-drag? file-drag?}))

(defn- chat-body
  "The chat itself: the timeline, its overlays and the compose dock."
  [state dispatch! {:keys [sid room history busy? new? model ready? pa? dkey nav-ctx sub-agents]}]
       (list
        [:div {:class ["timeline"
                       (when-not pa? "timeline--float-footer")]}
         (timeline-content
          (if ready?
            (let [entries (vec history)
                  total   (count entries)
                  win     (or (:web/timeline-window state) initial-window-size)
                  natural (max 0 (- total win))
                  ;; The top edge is frozen while scrolled up (:web/set-scrolled-up).
                  start   (if-let [fs (:web/frozen-window-start state)]
                            (min fs natural)
                            natural)
                  resolved-list (get-in state [:web/resolved-dialogs (:id room)])
                  resolved-by-tool (into {} (keep (fn [e] (when-let [t (:tool-id e)] [t e]))
                                                  resolved-list))
                  ;; Each pending :confirm dialog renders inside its gated tool
                  ;; block (dlg/permission-hosts), but only when that block is
                  ;; in the rendered window; otherwise it shows standalone.
                  perm-hosts (dlg/permission-hosts (get-in room [:ui :dialogs]) entries start)
                  perm-answer!
                  (fn [p]
                    (let [{:keys [id type message text options]} (perm-hosts p)
                          room-id (:id room)
                          tool-id (:id (nth entries p))]
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
               (with-post-key "tl-subagent-origin"
                              (subagent-origin-banner dispatch! room))
               (when (pos? start)
                 [:div {:class ["load-earlier"] :replicant/key "tl-load-earlier"}
                  (button/button
                   {:variant :ghost :size :sm
                    :on-click (fn [_] (dispatch! {:type :timeline/set-window
                                                  :window (+ win window-step)}))}
                   (str "Show " (min window-step start) " earlier messages"
                        " (" start " hidden)"))])
               (let [editing   (:web/editing-bubble state)
                     app       (appearance/effective-in state)
                     ;; Answered dialogs are anchored to the history length at
                     ;; answer time; ones tagged :tool-id render inside their
                     ;; tool block instead (resolved-by-tool).
                     by-anchor (group-by :anchor resolved-list)
                     ;; A sub-agent's answered asks (:sub-id) live in its view.
                     ritems    (fn [p] (map (fn [e] {:key (str "rdlg-" (:key e))
                                                     :group? false
                                                     :node (resolved-dialog-post (:key e) e)})
                                            (remove #(or (:tool-id %) (:sub-id %))
                                                    (get by-anchor p))))
                     agents    (get-in room [:ext :subagents :agents])
                     ;; Sub-agents (`sub-agents`, the inline Explain/Ask ones
                     ;; left out) post one row per anchor where they were
                     ;; spawned, after their spawn call once the history is
                     ;; rebuilt (sa-threads); the call itself hides behind it.
                     by-spawn  (group-by #(sa-threads/anchor-of entries %) sub-agents)
                     spawned   (into #{} (keep :call-id) sub-agents)
                     asks      (sav/asks-by-sub (get-in room [:ui :dialogs]))
                     ;; Question threads on the room's blocks follow their
                     ;; block; a sub-agent's are in its view (subagent-view).
                     compose   (let [c (:web/thread-compose state)]
                                 (when (= (:id room) (:room-id c)) c))
                     threads   (sa-threads/threads agents)
                     sub-node? #(some? (get-in % [:node :sub-id]))
                     after-pos (fn [node] (some-> (sa-threads/locate entries node) inc))
                     cards-at  (fn [ths c] (thread-cards dispatch! state (:id room) ths c))
                     by-block  (group-by (comp after-pos :node) (remove sub-node? threads))
                     compose-p (when (and compose (not (sub-node? compose)))
                                 (after-pos (:node compose)))
                     titems    (fn [p]
                                 (map (fn [card] {:key (str "thr-" (get-in card [1 :replicant/key]))
                                                  :group? false
                                                  :node (thread-post card)})
                                      (cards-at (get by-block p) (when (= p compose-p) compose))))
                     sitems    (fn [p]
                                 (when-let [as (seq (get by-spawn p))]
                                   [{:key (str "sa-" p) :group? false
                                     :node (with-post-key (str "sa-" p)
                                             (if (next as)
                                               (subagent-batch-row dispatch! asks as)
                                               (subagent-row dispatch! asks (first as))))}]))
                     ;; Extension posts (xi.web.timeline-posts), e.g. agent
                     ;; blocks after their tool call, which they stand in for.
                     xposts    (timeline-posts/items state dispatch! room entries)
                     by-xpost  (group-by :pos xposts)
                     replaced? (let [hides (into #{} (keep :hides) xposts)]
                                 (fn [p entry]
                                   (and (hides p)
                                        (not (perm-hosts p))
                                        (not (resolved-by-tool (:id entry))))))
                     xitems    (fn [p]
                                 (map (fn [{:keys [key node]}]
                                        {:key (str "xp-" key) :group? false
                                         :node (with-post-key (str "xp-" key) node)})
                                      (get by-xpost p)))
                     ;; The user's open/closed blocks, kept per session across
                     ;; switches (core's :timeline/block-toggle).
                     session-key (or sid (:id room))
                     block-open  (get-in state [:web/block-open session-key])
                     toggle-fn   (fn [k] (remember-open dispatch! session-key k))]
                 (group-viewer-items
                  {:super?     (:super-collapsed? app)
                   :block-open block-open
                   :toggle-fn  toggle-fn}
                  (concat
                   ;; Resolved bubbles and sub-agent posts anchored above the
                   ;; window pin at the top.
                   (mapcat (fn [p] (concat (ritems p) (sitems p) (xitems p)))
                           (sort (filter #(< % start)
                                         (distinct (concat (keys by-anchor)
                                                           (keys by-spawn)
                                                           (keys by-xpost))))))
                   (mapcat
                    (fn [p]
                      (concat
                       (titems p)
                       (ritems p)
                       (sitems p)
                       (xitems p)
                       (when (and (< p total) (not (replaced? p (nth entries p)))
                                  ;; a spawn_subagent call whose row stands in for it
                                  (not (and (= :tool-call (:kind (nth entries p)))
                                            (spawned (:id (nth entries p)))
                                            (not (perm-hosts p)))))
                         (let [entry (nth entries p)
                               ;; A tool awaiting Allow/Deny stays open and out of
                               ;; any viewer group.
                               groupable? (and (#{:tool-call :thinking} (:kind entry))
                                               (not (perm-hosts p)))
                               collapsed? (and (not (perm-hosts p))
                                               (appearance/block-collapsed? app entry))
                               ;; A pending ask's block keeps the native toggle.
                               block-key  (when (and (#{:tool-call :thinking} (:kind entry))
                                                     (not (perm-hosts p)))
                                            (str "h-" p))
                               open-choice (get block-open block-key)
                               cwd      (or (:cwd room) (get-in state [:web/pending-room :cwd]))
                               sender   (when (and (= :user (:kind entry))
                                               (last-of-run? entries p))
                                          (other-user state (:user entry)))
                               tool?    (= :tool-call (:kind entry))
                               md-code? (contains? (:web/md-diff-code state) (:id entry))
                               editing? (and (= :user (:kind entry)) (= p (:index editing)))
                               resolved (resolved-by-tool (:id entry))
                               explanation (when tool?
                                             (sa/find-explain agents (:id entry)))
                               retry    (error-retry-target entries p)]
                           (memo-timeline-items
                            entry
                            ;; The memo ctx; nil for the post with the pending ask
                            ;; (it carries fresh answer fns), which always rebuilds.
                            (when-not (perm-hosts p)
                              [p dispatch! cwd (:id room) sender groupable? collapsed?
                               session-key open-choice
                               (when tool?
                                 (tool-views/inputs (util/strip-mcp-prefix (:tool entry)) (:ext room)))
                               (when tool? (:tool-args app))
                               md-code? (when editing? [(:text editing) (:images editing)]) resolved
                               explanation retry])
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
                                                 block-key
                                                 (assoc :open-choice open-choice
                                                        :toggle! (toggle-fn block-key))
                                                 tool?
                                                 (assoc :room-ext (:ext room)
                                                        :tool-args (:tool-args app))
                                                 md-code?
                                                 (assoc :md-diff-code? true)
                                                 editing?
                                                 (assoc :editing? true :edit-text (:text editing)
                                                        :edit-images (:images editing))
                                                 (perm-hosts p)
                                                 (assoc :permission {:dialog (perm-hosts p)
                                                                     :answer! (perm-answer! p)
                                                                     :deny-reason!
                                                                     #(dispatch! {:type :deny-reason/start
                                                                                  :room-id (:id room)
                                                                                  :dialog-id (:id (perm-hosts p))})})
                                                 resolved
                                                 (assoc :resolved-permission resolved)
                                                 explanation
                                                 (assoc :explanation explanation)
                                                 retry
                                                 (assoc :retry retry)))]
                                [{:key (str "h-" p)
                                  :group? groupable?
                                  :collapsed? collapsed?
                                  :info (when groupable? (block-info entry cwd (:tool-args app)))
                                  :node (with-post-key (str "h-" p)
                                          (cond-> post
                                            (askable-kinds (:kind entry))
                                            (with-ask-ref {:index p})))}])))))))
                    (range start (inc total))))))
               ;; Every child of .timeline-content is keyed (and nils are
               ;; dropped, see timeline-content): mixing keyed and unkeyed
               ;; siblings makes Replicant reconcile the tail positionally and
               ;; throw `removeChild ... not a Node` as these toggle.
               (with-post-key "tl-optimistic"
                              (optimistic-post dispatch! state room sid history))
               (with-post-key "tl-pending-command"
                              (pending-command-post state room sid))
               (with-post-key "tl-dialog"
                              (dialog-post dispatch! state room history
                                           (into #{} (map :id) (vals perm-hosts))))
               (when room (ask-hover-button dispatch! (:id room)))))
            (with-post-key "tl-empty"
                           (history-skeleton (false? (:web/connected? state)))))
          (with-post-key "tl-quick-replies"
                         (quick-replies-row dispatch! room busy?)))]
        (copy-dialog-overlay dispatch! (:web/copy-text state))
        (when-let [menu (:web/bubble-menu state)]
          (bubble-menu dispatch! (:id room) menu))
        (when-let [menu (:web/thread-menu state)]
          (thread-menu dispatch! (:id room) menu))
        (some->> (:web/lightbox state) (image-viewer dispatch!))
        [:div {:class ["compose-dock"]}
         (when (:web/copy-flash state) (copy-toast))
         ;; Deny-with-reason holds the dock only while its ask is still pending.
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
                          (false? (:web/connected? state))
                          (slash-ctx state)
                          (compose-status dispatch! room model
                                          (get-in state [:lobby :claude-usage]))
                          (agents-button dispatch! room history sub-agents))))]))

;; ── Windows (xi.layout, xi.web.layout) ───────────────────────────────────────

(defn- pane-button [title on-click child]
  [:button {:class ["icon-btn" "icon-btn--sm" "pane-btn"]
            :title title
            :on {:click (fn [^js e] (.stopPropagation e) (on-click))}}
   child])

(defn- pane-header
  "A window's bar: its view pill (buffer-menu, switching this pane), then
   split / dock / close; a sidebar pane offers undock instead of the splits."
  [dispatch! state {:keys [room buffers sub-agents]} layout {:keys [id view]} side]
  (let [room-id  (:id room)
        viewers  (buffers/viewers room)
        people   (fn [v] (sb/room-people state (get viewers v)))
        ev!      (fn [ev] (dispatch! (assoc ev :pane-id id)))
        several? (> (count (layout/main-panes layout)) 1)
        sub      (when-not (contains? buffers view)
                   (some #(when (= view (:id %)) %) sub-agents))]
    [:div {:class ["pane-header"]}
     (buffer-menu dispatch! room-id view buffers sub-agents people
                  {:menu-id (str "pane-menu-" id)
                   :class   "pane-pill"
                   :switch! (fn [v] (ev! {:type :layout/set-view :view v}))})
     (if sub
       (subagent-bar-items dispatch! room id side sub)
       [:span {:class ["pane-header-spacer"]}])
     (if side
       (pane-button "Back into the main area"
                    #(dispatch! {:type :layout/undock :side side}) (pane-icon :undock))
       (list
        (pane-button "Split right" #(ev! {:type :layout/split :dir :row}) (pane-icon :split-right))
        (pane-button "Split down" #(ev! {:type :layout/split :dir :col}) (pane-icon :split-down))
        (when several?
          (pane-button "Keep only this window" #(ev! {:type :layout/only}) (pane-icon :only)))
        (pane-button "Into the left sidebar" #(ev! {:type :layout/dock :side :left}) (pane-icon :dock-left))
        (pane-button "Into the right sidebar" #(ev! {:type :layout/dock :side :right}) (pane-icon :dock-right))))
     (pane-button "Close window" #(ev! {:type :layout/close-pane})
                  (icon/icon {:icon-name :x :size :sm}))]))

(defn- empty-pane
  "A window without a view yet: pick one. The chat (unless shown already),
   the room's buffers and sub-agents, then a file or the git status."
  [dispatch! {:keys [room buffers sub-agents]} layout pane-id]
  (let [pick! (fn [v] (dispatch! {:type :layout/set-view :pane-id pane-id :view v}))
        item  (fn [k icon title on-click]
                [:button {:class ["sidebar-more-item" "pane-pick-item"]
                          :replicant/key k
                          :on {:click (fn [_] (on-click))}}
                 (if (keyword? icon) (icon/icon {:icon-name icon :size :sm}) icon)
                 [:span {:class ["buffer-menu-title"]} title]])]
    [:div {:class ["pane-empty"]}
     [:div {:class ["pane-empty-title"]} "Show here"]
     [:div {:class ["pane-pick"]}
      (when-not (layout/pane-of-view layout :chat)
        (item "chat" (message-circle-icon) "Chat" #(pick! :chat)))
      (for [[id buf] (buffers/ordered buffers)]
        (item (str id) (buffer-kind-icon (buffers/kind id buf)) (buffers/label id buf) #(pick! id)))
      (for [{:keys [id label task]} sub-agents]
        (item id (messages-icon) (or label task "Sub-agent") #(pick! id)))
      [:div {:class ["overflow-menu-divider"] :replicant/key "pick-divider"}]
      (item "find" :search "Find a file…" #(dispatch! {:type :palette/open-file-finder}))
      (item "git" :code "Git status"
            #(dispatch! {:type :diff/reopen :room-id (:id room) :method "git" :engine :git}))
      (item "close" :x "Close this window" #(dispatch! {:type :layout/close-pane :pane-id pane-id}))]]))


(defn- pane-view
  "The view `view` names (a buffer id, a sub-agent id, :chat, :empty) for a
   window, or the whole page below the topbar when the chat is not split."
  [state dispatch! {:keys [room buffers sub-agents] :as ctx} layout pane-id view]
  (let [buffer (get buffers view)
        kind   (when buffer (buffers/kind view buffer))
        sub    (when-not buffer (some #(when (= view (:id %)) %) sub-agents))
        batch  (when-let [first-id (sav/group-view-id view)]
                 (seq (sav/batch-of (:history room) sub-agents first-id)))]
    (case kind
      :diff
      (diff-tab-view dispatch! state (:id room) buffer)

      :file
      (file-tab-view dispatch! state buffer)

      :terminal
      (terminal/view dispatch! state room view buffer
                     (contains? (:web/terminals-gone state) (:term-id buffer)))

      :prompt
      (prompt-tab-view dispatch! room (or (:web/prompt-expanded state) #{}) (:providers state))

      (cond
        (and buffer (buffer-views/view kind))
        ((buffer-views/view kind) state dispatch! room view buffer)

        (and buffer (string? (:text buffer)))
        (text-tab-view view buffer)

        sub
        (subagent-view state dispatch! room sub)

        batch
        (subagent-group-view dispatch! room pane-id batch)

        (and layout (= :empty view))
        (empty-pane dispatch! ctx layout pane-id)

        :else
        (chat-body state dispatch! ctx)))))

;; Every layout node is keyed so Replicant never morphs one role's DOM node
;; into another's (a sized panel into a pane header, a sidebar panel into the
;; main fill): the ui.panels runtime writes inline sizes, data-edge/bare and a
;; controller expando onto panel nodes, and those outlive a positional reuse —
;; a window then keeps a stale width and overlaps its neighbour.
(defn- solo-pane?
  "Whether `layout` shows one window alone, which then draws no focus ring:
   on a narrow screen always (a sidebar is a drawer over it), on a wide one
   with a single main window and no open sidebar."
  [state layout]
  (or (not (:web/wide? state))
      (and (= 1 (count (layout/main-panes layout)))
           (not-any? #(get-in layout [% :open?]) layout/sides))))

(defn- pane-frame
  "One window: header + its view. `data-pane-id` / `data-pane-focused` are
   what xi.web.core's pointer listener and scroller lookup read."
  [state dispatch! ctx layout {:keys [id view] :as pane} side]
  (let [focused? (= id (:focus layout))]
    [:div {:class ["pane"
                   (when focused? "pane--focused")
                   (when (solo-pane? state layout) "pane--solo")
                   (when (= :chat view) "pane--chat")
                   (when side "pane--side")]
           :replicant/key (str "pane-" id)
           :data-pane-id id
           :data-pane-focused (when focused? "true")}
     (pane-header dispatch! state ctx layout pane side)
     [:div {:class ["pane-body"]}
      (pane-view state dispatch! ctx layout id view)]]))

(defn- first-pane-id
  "The id of a subtree's first pane: a split's children and the split itself
   are keyed by it, so a window keeps its DOM while its siblings come and go."
  [node]
  (if (layout/pane? node) (:id node) (recur (first (:children node)))))

(defn- layout-node
  "The main tree: a split is a ui.panels group (`data-split-path`), its
   children sized panels but the last, which fills; xi.web.core turns the
   runtime's resize events back into :layout/resize. Sized slots and the fill
   are keyed apart: a window changing role gets a fresh panel node."
  [state dispatch! ctx layout node path]
  (if (layout/pane? node)
    (pane-frame state dispatch! ctx layout node nil)
    (let [{:keys [dir children sizes]} node
          n (count children)]
      (apply panels/group
             {:orientation (if (= :row dir) :horizontal :vertical)
              :class "pane-split"
              :attrs {:data-split-path (str/join "." path)
                      :replicant/key (str "split-" (name dir) "-" (first-pane-id node))}}
             (mapcat (fn [i child]
                       (let [sub (layout-node state dispatch! ctx layout child (conj path i))
                             k   (first-pane-id child)]
                         (if (< i (dec n))
                           [(panels/panel {:size (str (* 100 (nth sizes i)) "%")
                                           :min-size 120
                                           :class "pane-slot"
                                           :attrs {:data-split-index i
                                                   :replicant/key (str "slot-" k)}}
                                          sub)
                            (panels/separator {:label "Resize windows"})]
                           [(panels/panel {:class "pane-slot pane-slot--fill"
                                           :attrs {:replicant/key (str "fill-" k)}}
                                          sub)])))
                     (range) children)))))

(defn- side-slot
  "A docked sidebar on a wide screen: a collapsible sized panel."
  [state dispatch! ctx layout side]
  (when-let [{:keys [pane size open?]} (get layout side)]
    (panels/panel {:size size :min-size layout/side-min :max-size layout/side-max
                   :collapsible true :collapsed (not open?)
                   :class (str "pane-side pane-side--" (name side))
                   :attrs {:data-side (name side)
                           :replicant/key (str "side-" (name side))}}
                  (pane-frame state dispatch! ctx layout pane side))))

(defn- layout-view
  "The chat page split into windows (xi.web.layout). Wide: sidebars dock
   beside the main tree. Narrow: the focused main pane alone, a sidebar a
   drawer over it while its pane has focus (scrim tap hands focus back)."
  [state dispatch! ctx layout]
  (let [focused (layout/focused layout)
        side    (layout/side-of layout (:id focused))]
    (if (:web/wide? state)
      [:div {:class ["pane-layout"]}
       (panels/group
        {:orientation :horizontal :class "pane-root"}
        (side-slot state dispatch! ctx layout :left)
        (when (:left layout) (panels/separator {:label "Resize left sidebar"}))
        (panels/panel {:class "pane-root-main" :attrs {:replicant/key "main"}}
                      (layout-node state dispatch! ctx layout (:main layout) []))
        (when (:right layout) (panels/separator {:label "Resize right sidebar"}))
        (side-slot state dispatch! ctx layout :right))]
      (let [main-pane (if side (first (layout/main-panes layout)) focused)]
        [:div {:class ["pane-layout" "pane-layout--narrow"]}
         (pane-frame state dispatch! ctx layout main-pane nil)
         (for [s layout/sides
               :when (get layout s)
               :let [open? (= s side)]]
           (list
            [:div {:class ["pane-drawer-scrim" (when open? "pane-drawer-scrim--open")]
                   :replicant/key (str "scrim-" (name s))
                   :on {:click (fn [_] (dispatch! {:type :layout/toggle-side :side s}))}}]
            [:div {:class ["pane-drawer" (str "pane-drawer--" (name s)) (when open? "pane-drawer--open")]
                   :replicant/key (str "drawer-" (name s))}
             (pane-frame state dispatch! ctx layout (get-in layout [s :pane]) s)]))]))))


(defn- chat-view [state dispatch!]
  (let [{:keys [room history resuming? dkey buffers active-buf sub-agents has-tabs?
                drop-zone? file-drag?]
         :as ctx} (chat-ctx state dispatch!)
        windows  (when room (wlayout/stored state))
        split?   (and windows (not (layout/default? windows)))]
    [:div (cond-> {:class ["container" "chat-drop-zone"] :replicant/key "chat"}
            drop-zone? (assoc :on (file-drop-attrs dispatch! dkey file-drag?)))
     (file-drop-overlay dispatch! file-drag?)
     [:div {:class ["topbar" "topbar--chat"]}
      (menu-button dispatch!)
      [:div {:class ["topbar-title"]}
       ;; Positioned against the topbar: a hairline along its bottom edge.
       (when (and resuming? (seq history))
         [:span {:class ["topbar-sync-bar"] :role "progressbar" :aria-label "Updating"}])]
      (offline-badge state)
      (yolo-chip dispatch! room)
      (process-chip dispatch! room)
      ;; each window carries its own pill (pane-header) once the chat is split
      (when (and has-tabs? (not split?))
        (buffer-menu dispatch! (:id room) active-buf buffers sub-agents
                     (let [viewers (buffers/viewers room)]
                       (fn [id] (sb/room-people state (get viewers id))))))
      (when-not split?
        (when-let [sub (and (not (contains? buffers active-buf))
                            (some #(when (= active-buf (:id %)) %) sub-agents))]
          (subagent-bar-items dispatch! room nil nil sub)))
      (overflow-menu dispatch! state (when room {:mode :room :room-id (:id room)}))]
     (if split?
       (layout-view state dispatch! ctx windows)
       (pane-view state dispatch! ctx nil nil active-buf))
     ;; outside the panes: code blocks in file buffers open it too
     (when-let [menu (:web/code-menu state)]
       (cond
         (:file? menu)  (file-path-menu dispatch! state (:id room) menu)
         (:table? menu) (table-copy-menu dispatch! menu)
         :else          (code-copy-menu dispatch! state (:id room) menu)))]))

;; ── Home view ────────────────────────────────────────────────────────────────

(defn with-projects
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
  "ui.context-menu entries for a session card: the extensions' items,
   rename, pin/unpin, hide/show in Recent (where the caller opts in via
   :dismissable?), copy session ID, and Delete. Hiding is gated on idle;
   Delete is always offered (the server keeps a busy room alive, see
   room_manager/session-delete)."
  [dispatch! ext-items {:keys [session-id name dismissed? pinned? dismissable? busy? has-dialog?
                               workspace-id] :as card}]
  (let [idle? (not (or busy? has-dialog?))]
    (cond-> (mapv #(ext-session-menu-item dispatch! card %) ext-items)
      ;; a card of the sidebar's Workspace section (recent-sidebar*)
      workspace-id
      (conj {:label    "Remove from workspace"
             :icon     :x
             :on-click #(dispatch! {:type :workspace/forget :id workspace-id
                                    :session-id session-id})})
      true
      (conj {:label    "Rename…"
             :icon     :edit
             :on-click #(dispatch! {:type :rename/open :session-id session-id :name name})}
            {:label    (if pinned? "Unpin session" "Pin session")
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
      (into (session-link-items dispatch! session-id name))
      true
      (conj {:type :separator}
            {:label    "Delete"
             :icon     :trash
             :variant  :danger
             :confirm  "Delete this session permanently?"
             :on-click #(dispatch! {:type :session/delete :session-id session-id})}))))

(defn- open-card-menu!
  [^js e]
  (.stopPropagation e)
  (let [btn (.-currentTarget e)
        r   (.getBoundingClientRect btn)]
    (when-let [open! (aget js/window "__uiLongPress")]
      (when-let [trigger (.closest btn ".context-menu-trigger")]
        (open! trigger (.-left r) (.-bottom r))))))

(defn- session-buffer-rows
  "The buffers open for a session (`:buffers` on the card), listed under it: a
   row navigates to the session on that buffer, × closes it for everyone. A
   live room's sub-agents list here too (`:kind :subagent`,
   xi.server.room-manager/session-buffers): a row opens one read-only in
   place of the chat (subagent-view), × stops / dismisses it."
  [dispatch! state {:keys [session-id buffers current? viewers]}]
  (let [room   (state/active-room state)
        active (when (and current? (= session-id (get-in room [:session :id])))
                 (get-in room [:ui :active-buffer]))]
    [:div {:class ["session-buffers"] :replicant/key (str "buffers-" session-id)}
     (for [{:keys [id kind title status]} buffers
           :let [sub?     (= :subagent kind)
                 running? (and sub? (= :running status))
                 people   (sb/room-people state (get viewers id))]]
       [:button {:class ["session-buffer-row"
                         (when (= id active) "session-buffer-row--active")]
                 :replicant/key (str id)
                 :data-nav (str "b:" session-id ":" id)
                 :title (if sub?
                          (str title " · " (get subagent-status-label status (name (or status :running))))
                          title)
                 :on {:click (fn [^js e]
                               (.stopPropagation e)
                               (dispatch! {:type :route/navigate :page :chat
                                           :session-id session-id :buffer-id id}))}}
        (if sub?
          [:span {:class ["session-buffer-icon--badged"]}
           (messages-icon)
           (card-status-indicator (when-not (= id active) (subagent-status-dot status)))]
          (buffer-kind-icon kind))
        [:span {:class ["session-buffer-title"]} title]
        ;; buffer presence: who else is on it
        (when (seq people)
          [:span {:class ["session-buffer-people"]} (avatar-stack people)])
        [:span {:class ["session-buffer-close"]
                :role "button"
                :title (cond running? "Stop sub-agent"
                             sub?     "Dismiss sub-agent"
                             :else    "Close buffer")
                :on {:click (fn [^js e]
                              (.stopPropagation e)
                              (dispatch! (cond running? {:type :session/subagent-stop
                                                         :session-id session-id :sub-id id}
                                               sub?     {:type :session/subagent-dismiss
                                                         :session-id session-id :sub-id id}
                                               :else    {:type :session/buffer-close
                                                         :session-id session-id :buffer-id id})))}}
         (icon/icon {:icon-name (if running? :circle-x :x) :size :sm})]])]))

(defn session-card
  "Session row with a ui.context-menu of secondary actions (right-click,
   long-press, or the ⋮ button); open buffers unfold under it
   (session-buffer-rows)."
  [dispatch! state {:keys [session-id name cwd timestamp current? active? busy? has-dialog? error? unread? show-project? people buffers pinned?]
              :as card-data}]
  (let [n-rows    (count buffers)
        n-subs    (count (filter #(= :subagent (:kind %)) buffers))
        n-buffers (- n-rows n-subs)
        rows-label (str/join " · "
                             (remove nil?
                                     [(when (pos? n-buffers)
                                        (str n-buffers (if (= 1 n-buffers) " buffer" " buffers")))
                                      (when (pos? n-subs)
                                        (str n-subs (if (= 1 n-subs) " sub-agent" " sub-agents")))]))
        buffers-open? (and (pos? n-rows)
                           (contains? (:web/sidebar-buffers-open state) session-id))
        card
  [:div {:class ["project-card"
                 (when has-dialog? "project-card--dialog")
                 (when current? "project-card--current")]
         :replicant/key (or session-id (str "card-" name))
         :data-nav (when session-id (str "s:" session-id))
         :on {:pointerdown (fn [_] (when session-id
                                     (dispatch! {:type :cache/prefetch
                                                 :session-id session-id})))
              ;; The chat in view: back from a buffer to its chat. Another
              ;; chat opens on its last view (router/restore-view).
              :click (fn [_] (dispatch! (cond-> {:type :route/navigate
                                                 :page :chat :session-id session-id}
                                          current? (assoc :buffer-id :chat))))}}
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
           (let [rel (format-relative-time timestamp)]
             (when-not (= rel "just now") rel))
           (when has-dialog? "needs response")]
          (remove str/blank?)
          (str/join " · "))
     (when (pos? n-rows)
       (list
        " · "
        [:span {:class ["project-card-buffers" (when buffers-open? "project-card-buffers--open")]
                :role "button"
                :title (if buffers-open? "Hide the buffers" "Show the buffers")
                :on {:click (fn [^js e]
                              (.stopPropagation e)
                              (dispatch! {:type :sidebar/buffers-toggle :session-id session-id}))}}
         rows-label]))]]
   [:div {:class ["project-card-trail"]}
    (avatar-stack people)
    (when session-id
      [:button {:class ["project-card-action" "session-more-btn"]
                :title "More actions"
                :on {:click open-card-menu!}}
       (more-vertical-icon)])]]]
    ;; No nil slot for closed buffers: Replicant (2025.12.1) misplaces keyed
    ;; siblings that move past a nil, and later renders then patch the wrong
    ;; card (see `sidebar-section`).
    (cond-> (list
             (if session-id
               ;; The trigger wraps the card so the card keeps its own click handler.
               ;; data-session-id is how :sidebar/animate-leave finds the row.
               (context-menu/context-menu-trigger
                {:items (session-menu-items dispatch! (:web/session-menu-items state) card-data)
                 :class "project-card-trigger"
                 :attrs {:replicant/key session-id
                         :data-session-id session-id
                         :data-flip (str "s:" session-id)}}
                card)
               card))
      buffers-open? (concat [(session-buffer-rows dispatch! state card-data)]))))

(defn- draft-chat-title
  "A parked draft chat's first non-blank prompt line."
  [state id]
  (some->> (str/split-lines (get-in state [:web/drafts id] ""))
           (some #(when-not (str/blank? %) (str/trim %)))))

(defn- draft-chat-card
  [dispatch! state {:keys [id cwd]}]
  (let [text (draft-chat-title state id)]
    [:div {:class ["project-card"]
           :replicant/key (str "draft-" id)
           :data-flip (str "draft:" id)
           :data-nav (str "draft:" id)
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

(defn- workspace-menu-items
  "Context-menu / palette rows that put project `path` in or out of each of
   the user's workspaces (xi.workspace), plus one that starts a new workspace
   with it. Config-defined workspaces are not editable here."
  [dispatch! state path]
  (-> (for [{:keys [id label dirs]} (filter #(= :user (:source %)) (workspace/workspaces state))
            :let [in? (boolean (some #{path} dirs))]]
        {:label    (str (if in? "Remove from " "Add to ") label)
         :icon     (if in? :x :layers)
         :on-click (fn [_] (dispatch! {:type :workspace/toggle-dir :id id :dir path}))})
      vec
      (conj {:label    "New workspace with this project…"
             :icon     :plus
             :on-click (fn [_] (dispatch! {:type :workspace/edit-open :mode :create :dir path}))})))

(defn- workspace-add-row
  "The \"Add project…\" row of a user-defined workspace: opens the palette's
   project list, where a pick joins the workspace (:workspace-add). `variant`
   is \"project-card\" (the projects page) or \"sidebar\"."
  [dispatch! variant]
  (let [open! (fn [_] (dispatch! {:type :palette/open-projects :action :workspace-add}))]
    (if (= variant "sidebar")
      (sidebar/sidebar-menu-item
       {:icon-name :plus
        :class     "sidebar-row"
        :attrs     {:replicant/key "ws-add-project" :data-flip "ws-add-project"
                    :data-nav "ws-add-project"}
        :on-click  open!}
       "Add project…")
      [:div {:class ["project-card" "project-card--add"]
             :replicant/key "ws-add-project"
             :on {:click open!}}
       [:div {:class ["project-card-icon"]} (icon/icon {:icon-name :plus :size :sm})]
       [:div {:class ["project-card-info"]}
        [:span {:class ["project-card-name"]} "Add a project to this workspace"]]])))

(defn- project-menu-items
  "ui.context-menu entries of a project card."
  [dispatch! state path]
  (into [{:label "New chat" :icon :plus
          :on-click (fn [_] (dispatch! {:type :projects/new-session :cwd path}))}
         {:label "Open sessions" :icon :list
          :on-click (fn [_] (dispatch! {:type :projects/select-dir :cwd path}))}
         {:type :separator}]
        (workspace-menu-items dispatch! state path)))

(defn project-dir-card
  [dispatch! state path dirty? current?]
  (context-menu/context-menu-trigger
   {:items (project-menu-items dispatch! state path)
    :class "project-card-trigger"
    :attrs {:replicant/key (str "dir-" path)}}
   [:div {:class ["project-card" "project-card--dir"
                  (when current? "project-card--current")]
          :data-nav (str "dir:" path)
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
     (icon/icon {:icon-name :plus :size :sm})]]))


(defn- session-matches?
  [query name]
  (str/includes? (str/lower-case (or name "")) query))

(defn- filter-sessions
  [sessions query content? matches]
  (cond
    (empty? query)  sessions
    content?        (let [ids (or matches #{})]
                      (filter #(contains? ids (:session-id %)) sessions))
    :else           (filter #(session-matches? query (:name %)) sessions)))


(defn- search-box
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
  [state dispatch!]
  (let [raw-query  (get-in state [:web/search :all-sessions])
        query      (str/lower-case (str/trim (or raw-query "")))
        content?   (boolean (get-in state [:web/content-search :all-sessions]))
        matches    (get-in state [:web/content-matches :all-sessions])
        ;; The lobby broadcast only carries a capped recent list; the full
        ;; list is fetched on demand (:sessions/all) when this view opens.
        ;; Fall back to the capped list for an instant paint while it loads.
        ws         (workspace/selected state)
        sessions   (workspace/filter-sessions ws (or (:web/all-sessions state)
                                                     (get-in state [:lobby :sessions])))
        connected? (:web/connected? state)
        orphans    (orphan-rooms (workspace/scope state) sessions)
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


(defn recent-projects
  "The five most recently used project directories, within the selected
   workspace (xi.workspace)."
  [state]
  (let [state     (workspace/scope state)
        room-cwds (->> (get-in state [:lobby :rooms]) (keep :cwd))
        sess-cwds (->> (get-in state [:lobby :sessions])
                       (filter :cwd)
                       (sort-by palette/session-time #(compare %2 %1))
                       (map :cwd))]
    (->> (concat room-cwds sess-cwds)
         distinct
         (take 5))))

(defn- home-topbar-actions
  "The topbar buttons of the dashboard and the project list: extension
   :home-topbar items and New chat."
  [state dispatch!]
  (when (:web/connected? state)
    (list
     (for [item (nav-items-for state :home-topbar)]
       [:button {:class ["icon-btn"]
                 :title (:label item)
                 :replicant/key (str "nav-" (:label item))
                 :on {:click (fn [_] (dispatch! (:event item)))}}
        (icon/icon {:icon-name (:icon item) :size :md})])
     [:button {:class ["icon-btn"]
               :replicant/key "new-chat"
               :data-key-action "chat/new"
               :on {:click (fn [_] (dispatch! {:type :room/new}))}}
      (icon/icon {:icon-name :plus :size :md})])))

(defn- projects-view
  "/projects: every project directory, the live rooms and All sessions."
  [state dispatch!]
  (let [ws         (workspace/selected state)
        dirs       (workspace/filter-dirs ws (:web/project-dirs state))
        raw-query  (get-in state [:web/search :home])
        query      (str/lower-case (str/trim (or raw-query "")))
        content?   (boolean (get-in state [:web/content-search :home]))
        matches    (get-in state [:web/content-matches :home])
        content-active? (and content? (seq query))
        matched-sessions (when content-active?
                           (->> (get-in (workspace/scope state) [:lobby :sessions])
                                (filter #(contains? (or matches #{}) (:session-id %)))))
        dirs       (if (seq query)
                     (filter #(str/includes? (str/lower-case %) query) dirs)
                     dirs)
        loading?   (:web/projects-loading? state)
        rooms      (get-in (workspace/scope state) [:lobby :rooms])
        connected? (:web/connected? state)
        ;; Active rooms without a known project
        orphans    (filter (fn [r] (:session-id r)) rooms)]
    [:div {:class ["container"] :replicant/key "projects"}
     [:div {:class ["topbar"]}
      (menu-button dispatch!)
      [:div {:class ["topbar-title"]} (if ws (str "Projects · " (:label ws)) "Projects")]
      (offline-badge state)
      (home-topbar-actions state dispatch!)
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
          (if (and (seq query) (empty? dirs))
            (empty-state/empty-state {} [:p "No matching projects."])
            (let [dirty (:web/project-dirty state)]
              (for [d dirs]
                (project-dir-card dispatch! state d (contains? dirty d) false))))
          (when (and (= :user (:source ws)) (not (seq query)))
            (workspace-add-row dispatch! "project-card"))]])]]))

(defn composer-projects
  "The dashboard composer's project choices: recently used first, then every
   known project directory, within the selected workspace."
  [state]
  (vec (distinct (concat (recent-projects state)
                         (workspace/filter-dirs (workspace/selected state)
                                                (:web/project-dirs state))))))

(defn composer-cwd
  "The project the dashboard composer starts a chat in: the user's pick
   (\"\" for none, the server default), else the most recent project."
  [state projects]
  (let [picked (:web/home-cwd state)]
    (cond
      (= "" picked)  nil
      (some? picked) picked
      :else          (first projects))))

(defn- dashboard-composer
  "Starts a chat in a project from the dashboard (:home/start): Enter sends,
   Shift+Enter breaks the line."
  [state dispatch!]
  (let [draft    (get-in state [:web/drafts :home] "")
        projects (composer-projects state)
        cwd      (composer-cwd state projects)
        submit!  (fn [text]
                   (when-not (str/blank? text)
                     (dispatch! {:type :home/start :cwd cwd :text text})))]
    [:div {:class ["dash-composer"] :replicant/key "dash-composer"}
     (form/form-textarea-auto
      {:placeholder (if cwd
                      (str "Start a chat in " (shorten-path cwd) "…")
                      "Start a chat…")
       :value       draft
       :max-rows    8
       :class       "dash-composer-input"
       :attrs {:aria-label "New chat message"
               :on {:input (fn [^js e]
                             (dispatch! {:type :compose/set-draft :draft-key :home
                                         :text (.. e -target -value)}))
                    ;; iOS Safari's Return key fires no Enter keydown, only a
                    ;; beforeinput "insertLineBreak".
                    :beforeinput (fn [^js e]
                                   (when (= "insertLineBreak" (.-inputType e))
                                     (.preventDefault e)
                                     (submit! (.. e -target -value))))
                    :keydown (fn [^js e]
                               (when (and (= "Enter" (.-key e)) (not (.-isComposing e)))
                                 (.preventDefault e)
                                 (if (.-shiftKey e)
                                   (let [^js el (.-target e)
                                         start  (.-selectionStart el)
                                         v      (.-value el)
                                         nv     (str (subs v 0 start) "\n" (subs v (.-selectionEnd el)))]
                                     (set! (.-value el) nv)
                                     (.setSelectionRange el (inc start) (inc start))
                                     (dispatch! {:type :compose/set-draft :draft-key :home :text nv}))
                                   (submit! (.. e -target -value)))))}}})
     [:div {:class ["dash-composer-bar"]}
      (select/select
       {:options   (into [{:value "" :label "No project"}]
                         (map (fn [p] {:value p :label (shorten-path p)}))
                         projects)
        :value     (or cwd "")
        :class     "dash-composer-project"
        :attrs     {:aria-label "Project"}
        :on-change (fn [v] (dispatch! {:type :home/set-cwd :cwd v}))})
      [:button {:class ["icon-btn" "dash-composer-send"]
                :title "Start chat"
                :disabled (str/blank? draft)
                :on {:click (fn [_] (submit! draft))}}
       (icon/icon {:icon-name :arrow-up :size :md})]]]))

(defn- dashboard-view
  "/: the composer above the extension-filled card grid (xi.web.dashboard)."
  [state dispatch!]
  [:div {:class ["container"] :replicant/key "home"}
   [:div {:class ["topbar"]}
    (menu-button dispatch!)
    [:div {:class ["topbar-title"]} "Xi"]
    (offline-badge state)
    (home-topbar-actions state dispatch!)
    (overflow-menu dispatch! state {:mode :dashboard})]
   [:div {:class ["home" "dashboard"]}
    (if (:web/connected? state)
      (list
       (dashboard-composer state dispatch!)
       (dashboard/grid state dispatch!))
      (empty-state/empty-state {}
       (spinner)
       [:p "Connecting to server…"]))]])

(defn- home-view [state dispatch!]
  (let [selected-dir (:web/selected-project-dir state)]
    (cond
      (get-in state [:lobby :agent-id])
      (personal-agent-home-view state dispatch!)

      (= selected-dir :all)
      (all-sessions-view state dispatch!)

      (= selected-dir :projects)
      (projects-view state dispatch!)

      (string? selected-dir)
      (project-sessions-view state dispatch!)

      :else
      (dashboard-view state dispatch!))))

;; ── Root ─────────────────────────────────────────────────────────────────────


(defn- claude-usage-meter
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
  [pct]
  (let [circumference 94.25] ; 2πr for r=15
    [:svg {:class ["claude-usage-ring"] :viewBox "0 0 36 36" :aria-hidden "true"}
     [:circle {:class ["claude-usage-ring-track"] :cx "18" :cy "18" :r "15"}]
     (when pct
       [:circle {:class ["claude-usage-ring-fill"] :cx "18" :cy "18" :r "15"
                 :stroke-dasharray (str (* circumference pct 0.01) " " circumference)
                 :transform "rotate(-90 18 18)"}])]))

(defn- claude-usage-popover
  "Claude subscription usage (rides on the lobby broadcast) for the sidebar
   footer: a ring that opens a popover with the session and weekly windows and
   a link to the /usage page (every account and provider, xi.ext.usage.web).
   Only the session window carries a server severity; the weekly one is derived
   from its percent."
  [state dispatch!]
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
                                   (when weekly-reset (str "Resets " weekly-reset))))))
          (button/button
           {:variant :ghost :size :sm :icon-right :arrow-right
            :class "claude-usage-details"
            :on-click (fn [e]
                        (some-> (.-target e) (.closest "[popover]") (.hidePopover))
                        (dispatch! {:type :route/navigate :page :usage}))}
           "All usage")))))))

(defn- switch-user!
  "Act as user `id` from this browser (localStorage xi-user, sent in
   :auth/hello) and reconnect. Unauthenticated by design; see xi.server.ws."
  [id]
  (try (.setItem js/localStorage "xi-user" id) (catch :default _ nil))
  (.reload js/location))

(defn- user-switcher
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

(defn- sidebar-more-menu
  "The sidebar footer's ⋯ popover. `items` are {:label :icon :on-click :badge
   :danger?} maps or :separator."
  [items]
  (let [close! (fn [^js e]
                 (some-> (.-currentTarget e) (.closest "[popover]") (.hidePopover)))]
    (list
     [:button (merge {:class ["sidebar-footer-btn"]
                      :title "More actions"
                      :replicant/key "sidebar-more-trigger"}
                     (popover/trigger-attrs "sidebar-more-menu"))
      (md/more-horizontal-icon)]
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
  "Collapsible drawer group. Collapsed ids are per workspace
   (xi.workspace/collapsed-groups); a collapsed group doesn't build its cards
   at all. Its cards reorder, so
   `children` must not leave nil slots between them: Replicant (2025.12.1)
   then moves the wrong DOM node and its vdom no longer matches the DOM."
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
       [:div {:class ["sidebar-section-items"]} (remove nil? children)]))))

(def ^:private mac?
  (boolean (re-find #"Mac|iPhone|iPad" (or (some-> js/navigator .-platform) ""))))

(defn- workspace-tab-menu
  "The context menu of a workspace tab other than All: prune its sidebar
   section, close it, and for one of the user's own also rename or delete it."
  [dispatch! state {:keys [id source]}]
  (cond-> []
    (= :user source)
    (conj {:label "Rename…" :icon :edit
           :on-click (fn [_] (dispatch! {:type :workspace/edit-open :mode :rename :id id}))})
    (seq (workspace/opened state id))
    (conj {:label "Prune workspace" :icon :zap
           :on-click (fn [_] (dispatch! {:type :workspace/prune :id id}))})
    :always
    (conj {:label "Close workspace" :icon :x
           :on-click (fn [_] (dispatch! {:type :workspace/close :id id}))})
    (= :user source)
    (conj {:type :separator}
          {:label "Delete workspace" :icon :trash :variant :danger
           :confirm "Delete this workspace? Its chats and projects stay."
           :on-click (fn [_] (dispatch! {:type :workspace/remove :id id}))})))

(defn- workspace-bar*
  "The workspace tab bar across the top of the app, above the sidebar and the
   chat (xi.workspace): All first, then the open tabs of config.edn's and the
   user's own workspaces, a + for a new one. Shown once a tab besides All is
   open, never for a personal agent (New workspace stays in the palette and
   the sidebar footer menu). Every tab but All has an × that closes it and
   a context menu (`workspace-tab-menu`)."
  [state dispatch!]
  (when-let [wss (and (not (get-in state [:lobby :agent-id]))
                      (seq (workspace/open-workspaces state)))]
    (let [sel    (workspace/selected-id state)
          select (fn [id] (dispatch! {:type :workspace/select :id id}))
          tab    (fn [{:keys [id label source]}]
                   (let [active? (= id sel)
                         root?   (= id workspace/all-id)]
                     ;; a div, not a button: the close × inside is a button
                     [:div {:class ["ws-tab" (when active? "ws-tab--active")
                                    (when-not root? "ws-tab--closable")]
                            :replicant/key (str "tab-" id)
                            :role "tab"
                            :tabindex "0"
                            :aria-selected (str active?)
                            :title (case source
                                     :config (str label " · defined in config.edn")
                                     :user   (str label " · right-click to rename, close or delete")
                                     label)
                            :on {:click   (fn [_] (select id))
                                 :keydown (fn [^js e]
                                            (when (#{"Enter" " "} (.-key e))
                                              (.preventDefault e)
                                              (select id)))}}
                      [:span {:class ["ws-tab-label"]} label]
                      (when-not root?
                        [:button {:class ["ws-tab-close"]
                                  :type "button"
                                  :title (str "Close " label)
                                  :aria-label (str "Close " label)
                                  :on {:click (fn [^js e]
                                                (.stopPropagation e)
                                                (dispatch! {:type :workspace/close :id id}))}}
                         (icon/icon {:icon-name :x :size :sm})])]))]
      [:div {:class ["ws-bar"] :role "tablist" :aria-label "Workspaces"
             :replicant/key "ws-bar"}
       (tab {:id workspace/all-id :label "All"})
       (for [{:keys [id] :as ws} wss]
         (context-menu/context-menu-trigger
          {:items (workspace-tab-menu dispatch! state ws)
           :class "ws-tab-trigger"
           :attrs {:replicant/key id}}
          (tab ws)))
       [:button {:class ["ws-tab" "ws-tab--add"]
                 :type "button"
                 :title "New workspace"
                 :aria-label "New workspace"
                 :on {:click (fn [_] (dispatch! {:type :workspace/edit-open :mode :create}))}}
        (icon/icon {:icon-name :plus :size :sm})]])))

;; {:sig :html} memo of the bar: the root re-renders on every streamed token.
(def ^:private workspace-bar-cache (atom nil))

(defn- workspace-bar
  [state dispatch!]
  (let [sig    [(get-in state [:lobby :workspaces])
                (get-in state [:lobby :agent-id])
                (:workspace/selected state)
                (:workspace/user state)
                (:workspace/closed state)
                (:workspace/sessions state)]
        cached @workspace-bar-cache]
    (if (and cached (= (:sig cached) sig))
      (:html cached)
      (let [html (workspace-bar* state dispatch!)]
        (reset! workspace-bar-cache {:sig sig :html html})
        html))))

(defn- sidebar-search
  [dispatch!]
  [:button {:class ["sidebar-search-trigger"]
            :type "button"
            :replicant/key "sidebar-search"
            :on {:click (fn [_] (dispatch! {:type :palette/open}))}}
   (icon/icon {:icon-name :search :size :sm})
   [:span {:class ["sidebar-search-trigger-label"]} "Search"]
   [:kbd {:class ["sidebar-search-trigger-kbd"]} (if mac? "⌘K" "Ctrl K")]])

;; {:sig :html} memo of the sidebar hiccup, keyed on the state slices it reads
;; (see recent-sidebar).
(def ^:private recent-sidebar-cache (atom nil))

(defn session-cleanups
  "Cleanup actions that currently apply, shared by the sidebar ⋯ menu, the
   palette and the prune shortcut. Each is {:label :icon :event} (+ :badge)."
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
  "The drawer panel: recent projects above recent sessions, both sorted by last
   visited. Slid in/out by the floating layout's data-sidebar-open attribute."
  [state dispatch!]
  (let [open?    (boolean (:web/sidebar-open? state))
        wide?    (boolean (:web/wide? state))
        render?  (or open? wide?)
        pa?      (get-in state [:lobby :agent-id])
        projects (when (and render? (not pa?)) (recent-projects state))
        ws       (workspace/selected state)
        ;; the chats opened in this workspace, whatever their directory
        ws-cards (when (and render? ws)
                   (let [sids (set (workspace/opened state (:id ws)))]
                     (when (seq sids)
                       (:cards (sb/extension-group (workspace/unscoped state)
                                                   {:where #(sids (:session-id %))})))))
        {:keys [recent hidden earlier]} (when render? (sidebar-session-groups state))
        ext-groups (when render?
                     (sb/extension-groups state (:web/sidebar-groups state)))
        cards    (concat recent hidden earlier)
        drafts   (:web/draft-chats state)
        collapsed (workspace/collapsed-groups state)
        section  (partial sidebar-section dispatch! collapsed)
        {route-page :page route-dir :dir} (:web/route state)]
    (sidebar/sidebar
     {}
     ;; Keyed by render? so the closed drawer holds no card list and a fresh
     ;; one is built on open (a hidden list accumulated stale spinner nodes).
     (sidebar/sidebar-content
      {:attrs {:style {:padding-top "max(var(--size-2), var(--safe-top))"}
               :replicant/key (str "sidebar-content-" render?)}}
      (when render?
        (list
         (sidebar-search dispatch!)
         (sidebar/sidebar-menu-item
          {:icon-name :home
           :class     "sidebar-row"
           :active    (and (= :home route-page) (nil? route-dir))
           :attrs     {:replicant/key "home" :data-flip "home" :data-nav "home"}
           :on-click  (fn [_] (dispatch! {:type :route/navigate :page :home}))}
          "Home")
         (when (not pa?)
           (section {:id :projects :label "Projects"}
             (let [dirty (:web/project-dirty state)]
               (for [p projects]
                 (project-dir-card dispatch! state p (contains? dirty p) (= p route-dir))))
             (when (= :user (:source ws))
               (workspace-add-row dispatch! "sidebar"))
             (sidebar/sidebar-menu-item
              {:icon-name :layout-dashboard
               :class     "sidebar-row"
               :active    (and (= :home route-page) (contains? #{:projects :all} route-dir))
               :attrs     {:replicant/key "all-projects" :data-flip "all-projects"
                           :data-nav "projects"}
               :on-click  (fn [_] (dispatch! {:type :route/navigate :page :home :dir :projects}))}
              "All projects")))
         (when (seq ws-cards)
           (section {:id :workspace :label "Workspace"}
             (for [c (with-projects ws-cards)]
               (session-card dispatch! state (assoc c :workspace-id (:id ws))))))
         (when (seq drafts)
           (section {:id :drafts :label "Drafts"}
             (for [d (rseq drafts)]
               (draft-chat-card dispatch! state d))))
         ;; Extension groups' sessions also show in their time group below.
         (for [{:keys [id label cards total more]} ext-groups]
           (section {:id id :label label}
             (for [c (with-projects cards)]
               (session-card dispatch! state c))
             (when (and more (> total (count cards)))
               (sidebar/sidebar-menu-item
                {:icon-name (:icon more)
                 :class     "sidebar-row"
                 :attrs     {:replicant/key (str "more-" (subs (str id) 1))
                             :data-flip (str "more-" (subs (str id) 1))
                             :data-nav (str "more:" (subs (str id) 1))}
                 :on-click  (fn [_] (dispatch! (:event more)))}
                (:label more)))))
         (when (seq recent)
           (section {:id :recent :label "Recent"}
             (for [c (with-projects recent)]
               (session-card dispatch! state (assoc c :dismissable? true)))))
         (when (seq hidden)
           (section {:id :hidden :label "Hidden"}
             (for [c (with-projects hidden)]
               (session-card dispatch! state (assoc c :dismissable? true)))))
         (when (seq earlier)
           (section {:id :earlier :label "Earlier"}
             (for [c (with-projects earlier)]
               (session-card dispatch! state c))))
         (let [ext-items (when (not pa?) (nav-items-for state :sidebar))]
           (when (seq ext-items)
             (section {:id :extensions :label "Extensions"}
               (for [item ext-items]
                 (sidebar/sidebar-menu-item
                  {:icon-name (:icon item)
                   :class     "sidebar-row"
                   :active    (and (= :route/navigate (get-in item [:event :type]))
                                   (= route-page (get-in item [:event :page])))
                   :badge     (nav-badge state item)
                   :attrs     {:replicant/key (str "nav-" (:label item))
                               :data-flip (str "nav-" (:label item))
                               :data-nav (str "nav:" (:label item))}
                   :on-click  (fn [_] (dispatch! (:event item)))}
                  (:label item)))))))))
     ;; Every state read here must be in recent-sidebar's memo key.
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
                        (not pa?)   (conj :separator
                                          {:label    "New workspace…"
                                           :icon     :layers
                                           :on-click #(dispatch! {:type :workspace/edit-open :mode :create})})
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
           (claude-usage-popover state dispatch!)]
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
  "Memoized `recent-sidebar*`: the identical hiccup while its input slices are
   unchanged, so typing doesn't rebuild the docked sidebar."
  [state dispatch!]
  (let [sig    [(:web/sidebar-open? state)
                (:web/wide? state)
                (:lobby state)
                (:web/response-counts state)
                (:web/watched state)
                (get-in state [:web/route :session-id])
                (get-in state [:web/route :page])
                (get-in state [:web/route :dir])
                (:web/sidebar-collapsed state)
                (:workspace/collapsed state)
                (:web/draft-chats state)
                (:web/project-dirty state)
                (:workspace/selected state)
                (:workspace/user state)
                (:workspace/closed state)
                (:workspace/sessions state)
                (:web/theme-mode state)
                (:web/nav-items state)
                (mapv #(nav-badge state %) (:web/nav-items state))
                (:web/sidebar-groups state)
                (:web/session-menu-items state)
                (state/own-user state)
                (:web/sidebar-buffers-open state)
                (get-in (state/active-room state) [:ui :active-buffer])]
        cached @recent-sidebar-cache]
    (if (and cached (= (:sig cached) sig))
      (:html cached)
      (let [html (recent-sidebar* state dispatch!)]
        (reset! recent-sidebar-cache {:sig sig :html html})
        html))))

(defn- git-status-view
  "Roomless working-tree diff page: the read-only diff renderer without the
   selection / explain / modify actions (those need a room)."
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
                        nil nil {:state    state
                                 :expanded (diff-state/expanded state)
                                 :md-code  (or (:web/md-diff-code state) #{})}))]]))

(defn- palette-chat-item
  "cmd/command-item variant with a trailing status slot
   (card-status-indicator). Follows the ui.command DOM contract so
   ui-runtime.js keyboard navigation works. Default rows show only at the empty
   query (empty data-command-value); `:search?` rows are the inverse
   (data-command-search-only), so every session is reachable by typing."
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
      (if has-dialog?
        (icon/icon {:icon-name :alert-circle :size :sm :class "command-item-icon"})
        (message-circle-icon "command-item-icon"))
      [:span {:class ["command-item-label"]} label]
      [:div {:class ["command-item-status"]}
       (card-status-indicator {:busy? busy? :error? error? :unread? unread? :active? active?})]])))


(defn- keycaps
  [display]
  (into [:span {:class ["keycaps"]}]
        (for [chord (str/split display #" ")]
          (into [:span {:class ["keycap-chord"]}]
                (for [k (str/split chord #"\+(?=.)")]
                  [:kbd {:class ["keycap"]} k])))))

(defn- project-actions
  "A project's palette actions as data ({:icon :label :on-click}): the rows of
   its second-level page, mirroring the project overflow menu plus
   :project-scoped extension nav items, open sessions last."
  [state dispatch! cwd]
  (concat
   ;; Alt+Enter on this row opens the background-chat prompt (palette-alt-enter!).
   [{:icon :plus :label "New chat"
     :shortcut (keycaps "Alt+Enter")
     :attrs {:data-palette-new-chat cwd}
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
     :on-click (fn [_] (dispatch! {:type :projects/select-dir :cwd cwd}))}]
   (workspace-menu-items dispatch! state cwd)))

(defn- palette-project-actions
  [state dispatch! cwd]
  (for [{:keys [label] :as action} (project-actions state dispatch! cwd)]
    (cmd/command-item (select-keys action [:icon :on-click :shortcut :attrs]) label)))

(defn- palette-new-chat-page
  "A background chat's prompt as a palette sub-page: the palette input is the
   prompt (:web/palette-prompt), Enter starts the chat in the page's cwd
   without leaving the current view. The row's :value tracks the input so
   ui-runtime's filter never hides it."
  [state dispatch! {:keys [cwd label]}]
  (let [prompt (or (:web/palette-prompt state) "")
        images (:web/palette-images state)]
    [:div {:replicant/key :new-chat-page}
     (cmd/command-group {:heading (str "New chat · " label)}
       (cmd/command-item
        {:icon :arrow-up
         :value prompt
         :description (if (and (str/blank? prompt) (empty? images))
                        "Type a prompt · Ctrl+V pastes images"
                        (shorten-path cwd))
         :shortcut (keycaps "Enter")
         :on-click (fn [_] (dispatch! {:type :palette/new-chat-start
                                       :cwd cwd :text prompt}))}
        "Start in the background"))
     (when (seq images)
       [:div {:class ["compose-images" "compose-images--palette"]}
        (map-indexed
         (fn [idx {:keys [data media-type]}]
           [:div {:replicant/key idx :class ["compose-image-thumb"]}
            [:img {:src (str "data:" media-type ";base64," data) :alt "attachment"}]
            ;; Mousedown is swallowed so the palette input keeps focus.
            [:button {:class ["compose-image-remove"] :type "button" :tabindex "-1"
                      :aria-label "Remove image"
                      :on {:mousedown (fn [^js e] (.preventDefault e))
                           :click (fn [_] (dispatch! {:type :palette/remove-image :idx idx}))}}
             (icon/icon {:icon-name :x :size :sm})]])
         images)])]))

(defn- handle-palette-paste!
  "Ctrl+V of images into the background-chat prompt: resized like the
   composer's (resize-image-file), staged in :web/palette-images."
  [dispatch! ^js e]
  (let [files (pasted-image-files e)]
    (when (seq files)
      (.preventDefault e)
      (-> (js/Promise.all (to-array (map resize-image-file files)))
          (.then (fn [results]
                   (when-let [valid (seq (remove nil? (array-seq results)))]
                     (dispatch! {:type :palette/add-images :images (vec valid)}))))
          (.catch (fn [err] (js/console.error "[xi-web] palette image read failed:" err)))))))

(defn- palette-search-page
  "Full-text session search as a palette sub-page. The palette input is the
   query (:palette/search-input, debounced server-side). Rows set
   data-command-value to the live query so ui-runtime's filter never hides
   them."
  [state dispatch!]
  (let [{:keys [query results]} (:web/palette-search state)
        blank? (str/blank? (str/trim (or query "")))]
    (cond
      (nil? results)
      [:div {:class ["command-loading"]} (spinner)]

      (empty? results)
      [:div {:class ["command-empty"]}
       (if blank? "No sessions yet" "No matching sessions")]

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
  "Model list as a palette sub-page, painted from the cached :web/model-list
   (xi.web.models); :web/model-error shows when a pick turned out unavailable."
  [state dispatch!]
  (let [room    (state/active-room state)
        models  (:web/model-list state)
        error   (:web/model-error state)
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


(defn- with-shortcut
  "cmd/command-item `opts` plus a keycap badge of the key bound to the row's
   `action` (xi.keys id) or `event` (xi.web.keymap/event-shortcut)."
  [opts state {:keys [action event]}]
  (if-let [s (or (some->> action (keymap/shortcut state))
                 (some->> event (keymap/event-shortcut state)))]
    (assoc opts :shortcut (keycaps s))
    opts))

(defn- palette-workspace-page
  "Workspaces (xi.workspace) as a palette sub-page: the ones to switch to
   (xi.web.workspaces/switch-targets: All and every workspace, closed tabs
   too, never the selected one; picking a closed one reopens it), then New
   workspace."
  [state dispatch!]
  (let [by-id (into {workspace/all-id {:id workspace/all-id :label "All"}}
                    (map (juxt :id identity))
                    (workspace/workspaces state))
        item  (fn [{:keys [id label source dirs]}]
                (cmd/command-item
                 {:icon        :layers
                  :value       (str "workspace " label)
                  :description (str (case source
                                      :config "config.edn"
                                      :user   (str (count dirs) (if (= 1 (count dirs)) " project" " projects"))
                                      "everything")
                                    (when (workspace/closed? state id) " · closed"))
                  :on-click    (fn [_] (dispatch! {:type :workspace/select :id id}))}
                 label))]
    [:div {:replicant/key :workspace-page}
     (apply cmd/command-group {:heading "Switch to"}
       (map (comp item by-id) (workspaces/switch-targets state)))
     (cmd/command-group {}
       (cmd/command-item
        (with-shortcut {:icon :plus
                        :value "new workspace create"
                        :on-click (fn [_] (dispatch! {:type :workspace/edit-open :mode :create}))}
          state {:action :workspace/new})
        "New workspace…"))]))

(defn- palette-workspace-items
  "The main palette's Workspaces rows: switch and create, close the selected
   tab, and for one of the user's own workspaces when it is selected, add a
   project, rename, delete."
  [state dispatch!]
  (let [ws   (workspace/selected state)
        own? (= :user (:source ws))
        row  (fn [opts action label]
               (cmd/command-item (with-shortcut opts state {:action action}) label))]
    (cond-> []
      (seq (workspace/workspaces state))
      (conj (row {:icon :layers
                  :value (str "switch workspace tab " (str/join " " (map :label (workspace/workspaces state))))
                  :on-click (fn [_] (dispatch! {:type :palette/open-workspaces}))}
                 :workspace/switch "Switch workspace…"))
      :always
      (conj (row {:icon :plus
                  :value "new workspace create"
                  :on-click (fn [_] (dispatch! {:type :workspace/edit-open :mode :create}))}
                 :workspace/new "New workspace…"))
      (and ws (seq (workspace/opened state (:id ws))))
      (conj (row {:icon :zap
                  :value (str "prune workspace clear section " (:label ws))
                  :on-click (fn [_] (dispatch! {:type :workspace/prune-current}))}
                 :workspace/prune (str "Prune workspace " (:label ws))))
      ws
      (conj (row {:icon :x
                  :value (str "close workspace tab " (:label ws))
                  :on-click (fn [_] (dispatch! {:type :workspace/close-current}))}
                 :workspace/close (str "Close workspace " (:label ws))))
      own?
      (conj (row {:icon :folder
                  :value (str "add project to workspace " (:label ws))
                  :on-click (fn [_] (dispatch! {:type :palette/open-projects :action :workspace-add}))}
                 nil (str "Add project to " (:label ws) "…"))
            (row {:icon :edit
                  :value (str "rename workspace " (:label ws))
                  :on-click (fn [_] (dispatch! {:type :workspace/rename-current}))}
                 :workspace/rename (str "Rename workspace " (:label ws) "…"))
            (row {:icon :trash
                  :value (str "remove delete workspace " (:label ws))
                  :on-click (fn [_] (dispatch! {:type :workspace/remove-current}))}
                 :workspace/delete (str "Delete workspace " (:label ws)))))))

(defn- palette-theme-page
  "Color themes (xi.web.theme) as a palette sub-page: Default, the built-ins
   and the user's themes, the active one checked; picking one applies it
   like a row in the Appearance dialog."
  [state dispatch!]
  (let [themes (:web/themes state)
        active (:active themes)
        item   (fn [name label]
                 (cmd/command-item
                  {:icon (if (= name active) :check :star)
                   :value (str "theme " label)
                   :on-click (fn [_] (dispatch! {:type :themes/select :name name}))}
                  label))]
    [:div {:replicant/key :theme-page}
     (apply cmd/command-group {:heading "Color theme"}
       (item nil "Default")
       (concat (for [[n _] (ctheme/custom-themes themes)] (item n n))
               (for [[id label] ctheme/builtin-themes] (item id label))))
     (cmd/command-group {}
       (cmd/command-item
        {:icon :settings
         :value "edit themes appearance"
         :on-click (fn [_] (dispatch! {:type :appearance/open :tab :theme}))}
        "Edit themes…"))]))

(defn- palette-skill-item [dispatch! {:keys [name description]}]
  (cmd/command-item
   {:icon :zap
    :value (str name " " description)
    :description description
    :on-click (fn [_] (dispatch! {:type :skill/select :name name}))}
   name))

(defn- palette-skill-page
  "Skills as a palette sub-page: recently used first, then alphabetical."
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
        ;; Keyed: an unkeyed div would reuse the spinner's, which the
        ;; command runtime has marked `hidden`.
        [:div {:replicant/key :skill-page}
         (when (seq recent)
           (apply cmd/command-group {:heading "Recent"}
             (map #(palette-skill-item dispatch! %) recent)))
         (apply cmd/command-group {:heading (if (seq recent) "All skills" "Skills")}
           (map #(palette-skill-item dispatch! %) the-rest))]))))


(defn- palette-projects-page
  "Projects as a palette sub-page. :action says what a row does: :drill opens
   the project's action page, :insert puts its path into the draft. Tab opens
   the project's file finder with the same action (palette-keydown reads
   `data-palette-drill`)."
  [state dispatch! {:keys [action]}]
  (let [ws     (workspace/selected state)
        ;; adding to a workspace: every project, the recently used ones
        ;; (whatever tab is selected) first, not just the configured list
        dirs   (if (= action :workspace-add)
                 (vec (distinct (concat (recent-projects (workspace/unscoped state))
                                        (:web/project-dirs state))))
                 (:web/project-dirs state))
        dkey   (draft-key state)
        select (fn [d short]
                 (case action
                   :insert        {:type :projects/picker-insert :path d :draft-key dkey}
                   :workspace-add {:type :workspace/toggle-dir :id (:id ws) :dir d :close-palette? true}
                   {:type :palette/drill :cwd d :label short :reopen? true}))]
    (if (empty? dirs)
      [:div {:class ["command-loading"]} (spinner)]
      (apply cmd/command-group {:heading (case action
                                           :insert        "Insert project path"
                                           :workspace-add (str "Add to " (:label ws))
                                           "Projects")}
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
  [dispatch! room-id dkey {:keys [name description takes-args?]}]
  (cmd/command-item
   {:icon :terminal
    :value (str name " " description)
    :description description
    :on-click (fn [_]
                (if takes-args?
                  (dispatch! {:type :compose/prefill :draft-key dkey :text (str "/" name " ")})
                  (dispatch-command! dispatch! room-id name)))}
   name))

(defn- palette-commands-page
  [state dispatch!]
  (let [room-id  (:id (state/active-room state))
        dkey     (draft-key state)
        recents  (->> (:web/recent-commands state)
                      (map #(first (str/split % #" ")))
                      distinct
                      (take 6))
        cmds     (web-commands state)
        by-name  (into {} (map (juxt :name identity)) cmds)
        recent   (keep by-name recents)
        the-rest (remove (comp (set recents) :name) cmds)]
    [:div
     (when (seq recent)
       (apply cmd/command-group {:heading "Recent"}
         (map #(palette-command-item dispatch! room-id dkey %) recent)))
     (apply cmd/command-group {:heading (if (seq recent) "All commands" "Commands")}
       (map #(palette-command-item dispatch! room-id dkey %) the-rest))]))

(defn- palette-snippets-page
  "Snippets as a palette sub-page, grouped into project (from the snippets
   extension) and global; a pick inserts the text into the draft."
  [state dispatch!]
  (let [{:keys [global project] :as loaded} (:web/snippet-list state)
        dkey (draft-key state)]
    (cond
      (nil? loaded)
      [:div {:class ["command-loading"]} (spinner)]

      (and (empty? global) (empty? project))
      [:div {:class ["command-empty"]} "No snippets found"]

      :else
      [:div {:replicant/key :snippet-page}
       (when (seq project)
         (apply cmd/command-group {:heading "Project snippets"}
           (map #(snippet-command-item dispatch! dkey %) project)))
       (when (seq global)
         (apply cmd/command-group {:heading "Snippets"}
           (map #(snippet-command-item dispatch! dkey %) global)))])))

(defn- palette-commits-page
  "A commit list as a palette sub-page; a pick opens the commit's diff.
   :scope :session — the commits made this session (git base..HEAD); :log —
   the repo's recent git log."
  [state dispatch! {:keys [scope]}]
  (let [room    (state/active-room state)
        commits (:web/commit-list state)
        log?    (= scope :log)]
    (cond
      (nil? commits)   [:div {:class ["command-loading"]} (spinner)]
      (empty? commits) [:div {:class ["command-empty"]}
                        (if log? "No commits" "No commits this session")]
      :else
      (apply cmd/command-group {:heading (if log? "Git log" "Session commits")}
        (for [{:keys [sha short subject rel-time author]} commits]
          (cmd/command-item
           {:icon :code
            :value (str short " " subject " " author)
            :description (str/join " · " (remove str/blank? [short author rel-time]))
            :on-click (fn [_] (dispatch! {:type :commits/open-diff
                                          :sha sha :room-id (:id room)}))}
           subject))))))

(defn- palette-files-page
  "File browser as a palette sub-page: directories drill (\"..\" steps up), a
   file opens in the :file tab. Absolute paths throughout."
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
  "The palette's buffer rows as `{:heading :items}` groups: the room in view's
   buffers, other sessions' buffers (a pick opens that chat on it), then buffer
   actions (close this room's, or every session's but sub-agents). Buffers list
   most recently viewed first (:web/buffer-access); the view in front and empty
   groups are left out."
  [state dispatch!]
  (let [room      (state/active-room state)
        room-id   (:id room)
        cur-sid   (get-in state [:web/route :session-id])
        access    (:web/buffer-access state)
        own       (get-in room [:ui :buffers])
        active    (get-in room [:ui :active-buffer] :chat)
        ;; the chat ranks among its buffers, listed once there are any
        mru       (buffers/by-recency (cond-> own (seq own) (assoc :chat {}))
                                      (get access (get-in room [:session :id])))
        name-of   (fn [sid]
                    (or (some #(when (= sid (:session-id %)) (or (:name %) (:session-name %)))
                              (concat (get-in state [:lobby :sessions])
                                      (get-in state [:lobby :rooms])))
                        "New session"))
        others    (->> (for [[sid bufs] (get-in state [:lobby :buffers])
                             :when (not= sid cur-sid)
                             b bufs]
                         (assoc b :session-id sid :session-name (name-of sid)))
                       (sort-by #(get-in access [(:session-id %) (:id %)] 0) >))
        switch!   (fn [id]
                    (dispatch! {:type :ui/buffer-switch :room-id room-id :buffer-id id}))
        all-open  (->> (vals (get-in state [:lobby :buffers]))
                       (mapcat identity)
                       (remove #(= :subagent (:kind %)))
                       count)]
    (->> [{:heading "Buffers"
           :items (for [[id buf] mru
                        :when (and room (not= id active))
                        :let [chat? (= :chat id)
                              title (if chat? "Chat" (buffers/label id buf))]]
                    (cond-> (cmd/command-item
                             {:icon (when-not chat? (buffer-icon (buffers/kind id buf)))
                              :value (str "buffer " (if chat? "chat" title))
                              :on-click (fn [_] (switch! id))}
                             title)
                      chat? (with-leading-icon (message-circle-icon "command-item-icon"))))}
          {:heading "Other sessions"
           :items (for [{:keys [id kind title session-id session-name]} others]
                    (with-leading-icon
                      (cmd/command-item
                       {:value (str "buffer " title " " session-name)
                        :on-click (fn [_] (dispatch! {:type :route/navigate :page :chat
                                                      :session-id session-id :buffer-id id}))}
                       (str title " · " session-name))
                      (buffer-kind-icon kind "command-item-icon")))}
          {:heading "Buffer actions"
           :items (concat
                   (when (seq own)
                     [(cmd/command-item
                       {:icon :x
                        :value "close all buffers"
                        :on-click (fn [_] (dispatch! {:type :ui/buffers-close-all :room-id room-id}))}
                       (str "Close all buffers (" (count own) ")"))])
                   (when (pos? all-open)
                     [(cmd/command-item
                       {:icon :x
                        :value "close all buffers of every session kill"
                        :on-click (fn [_] (dispatch! {:type :sessions/buffers-close-all}))}
                       (str "Close buffers of all sessions (" all-open ")"))]))}
          ;; Windows (xi.web.layout)
          {:heading "Windows"
           :items (when (wlayout/room-ctx state)
                    (concat
                     (for [[k label dir] [["split-right" "Split window right" :row]
                                          ["split-down" "Split window down" :col]]]
                       (with-leading-icon
                         (cmd/command-item
                          {:value (str "window " label)
                           :on-click (fn [_] (dispatch! {:type :layout/split :dir dir}))}
                          label)
                         (assoc-in (pane-icon (keyword k)) [1 :class] ["icon" "icon-sm" "command-item-icon"])))
                     (when (wlayout/windows? state)
                       (for [[label ev] [["Close window" {:type :layout/close-pane}]
                                         ["Keep only this window" {:type :layout/only}]
                                         ["Move window into the left sidebar" {:type :layout/dock :side :left}]
                                         ["Move window into the right sidebar" {:type :layout/dock :side :right}]
                                         ["Equal window sizes" {:type :layout/balance}]
                                         ["Back to one window" {:type :layout/reset}]]]
                         (cmd/command-item
                          {:icon :layers
                           :value (str "window " label)
                           :on-click (fn [_] (dispatch! ev))}
                          label)))
                     (let [[undos redos] (wlayout/history state)]
                       (concat
                        (when (pos? undos)
                          [(cmd/command-item
                            {:icon :arrow-left :value "window undo layout change"
                             :on-click (fn [_] (dispatch! {:type :layout/undo}))}
                            "Undo window change")])
                        (when (pos? redos)
                          [(cmd/command-item
                            {:icon :arrow-right :value "window redo layout change"
                             :on-click (fn [_] (dispatch! {:type :layout/redo}))}
                            "Redo window change")])))))}]
         (keep (fn [{:keys [items] :as g}]
                 (when (seq items) (assoc g :items (vec items)))))
         vec)))

(defn- palette-buffers-page
  [state dispatch!]
  (if-let [groups (seq (buffer-palette-groups state dispatch!))]
    (for [{:keys [heading items]} groups]
      (apply cmd/command-group {:heading heading} items))
    [:div {:class ["command-empty"]} "No open buffers"]))

(defn- session-edited-files
  "{:cwd :files} — the active chat's cwd and the cwd-relative paths its edit
   tools touched under it, newest first (xi.util/history-edit-paths). Reads
   the room's history, else the :web/cache snapshot, like chat-history."
  [state]
  (let [room   (state/active-room state)
        cached (get-in state [:web/cache (get-in state [:web/route :session-id])])
        cwd    (or (:cwd room) (:cwd cached))
        hist   (or (seq (:history room)) (:history cached))]
    {:cwd   cwd
     :files (if cwd
              (->> (util/history-edit-paths hist)
                   reverse
                   (keep #(util/relative-under cwd %))
                   distinct
                   vec)
              [])}))

(defn- chat-links
  "{:cwd :urls :files} the active chat mentions (xi.session-links), from the
   room's history or the :web/cache snapshot, like session-edited-files."
  [state]
  (let [room   (state/active-room state)
        cached (get-in state [:web/cache (get-in state [:web/route :session-id])])
        cwd    (or (:cwd room) (:cwd cached))
        hist   (or (seq (:history room)) (:history cached))]
    (assoc (session-links/links hist cwd) :cwd cwd)))

(defn- palette-links-page
  "The chat's URLs (open in a new tab) and files (open in a buffer), newest
   first; :scope :urls or :files shows one group."
  [state dispatch! {:keys [scope]}]
  (let [{:keys [cwd urls files]} (chat-links state)
        urls  (when (#{:all :urls} scope) urls)
        files (when (#{:all :files} scope) files)]
    (if (and (empty? urls) (empty? files))
      [:div {:class ["command-empty"]}
       (case scope
         :urls  "No URLs in this chat"
         :files "No files in this chat"
         "No links or files in this chat")]
      (list
       (when (seq urls)
         (apply cmd/command-group {:heading "URLs"}
           (for [u urls]
             (cmd/command-item
              {:icon :globe
               :value (str "url " u)
               :on-click (fn [_] (.open js/window u "_blank" "noopener"))}
              u))))
       (when (seq files)
         (apply cmd/command-group {:heading "Files"}
           (for [path files]
             (cmd/command-item
              {:icon :file-text
               :value (str "file " path)
               :on-click (fn [_] (dispatch! {:type :files/open :path path :cwd cwd}))}
              path))))))))

(defn- palette-file-finder-page
  "Fuzzy file finder as a palette sub-page: the palette input is the query
   (:file-finder/input), ranked client-side over the preloaded file list. Rows
   carry :value = the live query so ui-runtime's substring filter never
   re-hides a match. :action is :open (view in the :file tab, path resolved
   server-side against the tree's cwd) or :insert (absolute path into the
   draft). :scope :edited lists the chat's session-edited files instead of
   the project tree."
  [state dispatch! {:keys [action scope]}]
  (let [edited? (= :edited scope)
        {:keys [cwd files error]} (if edited?
                                    (session-edited-files state)
                                    (:web/file-tree state))
        query (or (:web/file-finder-query state) "")
        dkey  (draft-key state)
        select (fn [rel]
                 (case action
                   :insert {:type :projects/picker-insert :path (str cwd "/" rel) :draft-key dkey}
                   {:type :files/open :path rel :cwd cwd}))]
    (cond
      (and (not edited?) (nil? (:web/file-tree state)))
      [:div {:class ["command-loading"]} (spinner)]
      error [:div {:class ["command-empty"]} error]
      (empty? files) [:div {:class ["command-empty"]}
                      (if edited? "No files edited in this chat" "No files")]
      :else
      (let [ranked (fuzzy/rank query files {:limit 50})]
        (if (empty? ranked)
          [:div {:class ["command-empty"]} "No matching files"]
          (apply cmd/command-group {:heading (cond
                                               (= action :insert) "Insert file path"
                                               edited?            "Edited in this chat"
                                               :else              "Files")}
            (for [rel ranked]
              (cmd/command-item
               {:icon :file-text
                :value query
                :on-click (fn [_] (dispatch! (select rel)))}
               rel))))))))

(defn- palette-ext-row
  "A user extension's palette row (xi.web.palette-items): its :event runs,
   :children drill into a page of those rows, :menu opens the named menu
   (Tab does too, palette-keydown; the badge is the menu's key, if bound)."
  [state dispatch! {:keys [label description children menu event] :as row}]
  (cmd/command-item
   (with-shortcut (cond-> {:icon     (:icon (with-default-icon row))
                           :value    (str label " " description)
                           :on-click (fn [_]
                                       (dispatch!
                                        (cond
                                          children {:type :palette/drill-rows
                                                    :title (or (:title row) label)
                                                    :placeholder (:placeholder row)
                                                    :rows children}
                                          menu     {:type :palette/open-menu
                                                    :ext (:ext menu) :menu (:name menu)}
                                          :else    event)))}
                    description (assoc :description description)
                    (or children menu) (assoc :shortcut (keycaps "⇥")
                                              :attrs {:data-palette-ext-drill "true"}))
     state (cond-> row
             menu (assoc :action (keyword (name (:ext menu)) (:name menu)))))
   label))

(defn- palette-ext-menu-page
  "A user extension's menu or a drilled row's children, as a palette sub-page."
  [state dispatch! page]
  (let [{:keys [title rows] :as m} (palette-items/menu-page state page)]
    (cond
      (nil? m)     [:div {:class ["command-loading"]} (spinner)]
      (empty? rows) [:div {:class ["command-empty"]} "Nothing here"]
      :else
      [:div {:replicant/key [:ext-menu (:ext page) (:menu page) title]}
       (apply cmd/command-group {:heading title}
         (map #(palette-ext-row state dispatch! %) rows))])))

(defn- palette-dialog-page
  "A pending :menu? select dialog (xi.api.dialog/select) as a palette page:
   its options in order, picking one answers it. Empty once it is answered."
  [state dispatch! {:keys [room-id dialog-id]}]
  (if-let [{:keys [message options]}
           (some #(when (= dialog-id (:id %)) %)
                 (get-in state [:rooms room-id :ui :dialogs]))]
    [:div {:replicant/key [:dialog-page dialog-id]}
     (apply cmd/command-group {:heading (str/replace message #"^\[extension [^\]]+\]\s*" "")}
       (map
        (fn [{:keys [label value description]}]
          (cmd/command-item
           (cond-> {:icon     :chevron-right
                    :value    (str label " " description)
                    :on-click (fn [_] (dispatch! {:type :web/dialog-answer
                                                  :room-id room-id
                                                  :dialog-id dialog-id
                                                  :value value}))}
             description (assoc :description description))
           label))
        options))]
    [:div {:class ["command-empty"]} "Already answered"]))

(defn- pin-palette-items!
  "Keep the `.command-item--pinned` row selectable under any filter: after each
   ui-runtime.js filter pass, un-hide the row and its group, sink the group
   last, and make the row active when nothing else matches."
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
  "Extra keyboard layer over ui-runtime.js: Tab drills the active project row
   or extension menu row (or opens its file finder on the picker page, or
   flips the search page's toggle); Shift+Tab / Backspace-on-empty backs out of a sub-page."
  [dispatch! palette-page]
  (fn [^js e]
    (let [dialog (.-currentTarget e)
          key    (.-key e)
          tab?   (and (= key "Tab") (not (.-shiftKey e)))
          active (fn [] (.querySelector dialog ".command-item--active"))]
      (cond
        ;; an extension row with :children or :menu (palette-ext-row)
        (and tab? (some-> ^js (active) (.. -dataset -paletteExtDrill)))
        (do (.preventDefault e)
            (.click ^js (active)))

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

(defn- palette-alt-enter!
  "Alt+Enter on a row carrying `data-palette-new-chat` (a project's New chat)
   opens the background-chat prompt page for its cwd. Listens on window in the
   capture phase: ui-runtime.js takes Enter on document capture and would
   pick the row."
  [dispatch! ^js dialog]
  (.addEventListener
   js/window "keydown"
   (fn [^js e]
     (when (and (.-open dialog) (= "Enter" (.-key e)) (.-altKey e)
                (not (or (.-ctrlKey e) (.-metaKey e) (.-shiftKey e))))
       (when-let [cwd (some-> (.querySelector dialog ".command-item--active")
                              (.. -dataset -paletteNewChat))]
         (.preventDefault e)
         (.stopPropagation e)
         (dispatch! {:type :palette/open-new-chat :cwd cwd
                     :label (shorten-path cwd)}))))
   true))

(def ^:private palette-quick-keys
  "sfghlqweryiotzcvbm")


(defn- palette-search-toggle
  "Full-text vs. names-only toggle at the right edge of the search page's
   input, passed through the dialog's :leading slot with the back button.
   Mousedown is swallowed so the input keeps focus."
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
  "Global Cmd/Ctrl+K palette (ui.command), mounted once in root-view;
   ui-runtime.js handles open/filter/keyboard-nav and clicks the picked item.
   Sub-pages live in :web/palette-page."
  [state dispatch!]
  (let [open?        (boolean (:web/palette-open? state))
        palette-page (:web/palette-page state)
        search-page? (= :search (:kind palette-page))
        finder-page? (= :file-finder (:kind palette-page))
        buffers-page? (= :buffers (:kind palette-page))
        projects-page? (= :projects (:kind palette-page))
        new-chat-page? (= :new-chat (:kind palette-page))
        names-only?  (boolean (get-in state [:web/palette-search :names-only?]))
        dialog-attrs {:id "cmdk" :hotkey (keymap/palette-hotkey state)
                      ;; Hold Alt → key badges on the first rows, Alt+key picks one.
                      :quick-nav palette-quick-keys
                      :placeholder (cond
                                     search-page? (if names-only?
                                                    "Search session names…"
                                                    "Search session text…")
                                     finder-page? (if (= :edited (:scope palette-page))
                                                    "Find edited file…"
                                                    "Find file…")
                                     buffers-page? "Switch buffer…"
                                     (= :links (:kind palette-page))
                                     (case (:scope palette-page)
                                       :urls  "Find URL…"
                                       :files "Find file mentioned in this chat…"
                                       "Find link or file…")
                                     projects-page? "Find project…"
                                     new-chat-page? (str "Prompt for a new chat in "
                                                         (:label palette-page) "…")
                                     (= :theme (:kind palette-page)) "Switch color theme…"
                                     (= :workspaces (:kind palette-page)) "Switch workspace…"
                                     (= :ext-menu (:kind palette-page))
                                     (or (:placeholder (palette-items/menu-page state palette-page))
                                         "Filter…")
                                     (= :dialog (:kind palette-page)) "Filter…"
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
                                  (palette-alt-enter! dispatch! node)
                                  ;; The runtime re-filters on every list re-render,
                                  ;; re-hiding the pinned row.
                                  (.observe (js/MutationObserver.
                                             (fn [_ _]
                                               (js/setTimeout #(pin-palette-items! node) 0)))
                                            node
                                            #js {:childList true :subtree true})))
                              :on {:keydown (palette-keydown dispatch! palette-page)
                                   :input (fn [^js e]
                                            (let [v (.. e -target -value)]
                                              (cond
                                                (nil? palette-page)
                                                (pin-palette-items! (.-currentTarget e))
                                                search-page?
                                                (dispatch! {:type :palette/search-input :query v})
                                                finder-page?
                                                (dispatch! {:type :file-finder/input :query v})
                                                new-chat-page?
                                                (dispatch! {:type :palette/prompt-input :query v}))))
                                   :paste (fn [^js e]
                                            (when new-chat-page?
                                              (handle-palette-paste! dispatch! e)))
                                   ;; A drill's close can land after the reopen;
                                   ;; a stale close must not blank the palette.
                                   :close (fn [^js e]
                                            (when-not (.-open (.-target e))
                                              (dispatch! {:type :palette/closed})))}}}]
    (cond
      ;; Closed: just the dialog shell, so keystrokes elsewhere cost nothing.
      (not open?)
      (cmd/command-dialog dialog-attrs)

      palette-page
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
         :theme          (palette-theme-page state dispatch!)
         :workspaces     (palette-workspace-page state dispatch!)
         :skill          (palette-skill-page state dispatch!)
         :commits        (palette-commits-page state dispatch! palette-page)
         :files          (palette-files-page state dispatch!)
         :file-finder    (palette-file-finder-page state dispatch! palette-page)
         :buffers        (palette-buffers-page state dispatch!)
         :links          (palette-links-page state dispatch! palette-page)
         :projects       (palette-projects-page state dispatch! palette-page)
         :snippets       (palette-snippets-page state dispatch!)
         :commands       (palette-commands-page state dispatch!)
         :search         (palette-search-page state dispatch!)
         :new-chat       (palette-new-chat-page state dispatch! palette-page)
         :ext-menu       (palette-ext-menu-page state dispatch! palette-page)
         :dialog         (palette-dialog-page state dispatch! palette-page)
         (apply cmd/command-group {:heading (str "Project · " (:label palette-page))}
           (palette-project-actions state dispatch! (:cwd palette-page)))))

      :else
      (let [room         (state/active-room state)
            recents      (->> (palette/recent-sessions state)
                              (active-first state)
                              (remove :current?)
                              (take 8))
            chat-items   (mapv #(palette-chat-item % dispatch!) recents)
            project-dirs (:web/project-dirs state)
            pa?          (get-in state [:lobby :agent-id])
            {:keys [recent hidden earlier] :as groups} (sidebar-session-groups state)
            cleanups     (session-cleanups pa? groups)
            cur-sid      (get-in state [:web/route :session-id])
            cur-session  (when cur-sid
                           (some #(when (= cur-sid (:session-id %)) %)
                                 (get-in state [:lobby :sessions])))
            cur-hidden?  (boolean (:dismissed? cur-session))
            cur-pinned?  (boolean (:pinned? cur-session))
            ;; Search-only tier: every session, reachable by typing (palette-chat-item).
            all-sessions (->> (concat recent hidden earlier)
                              (remove #(= cur-sid (:session-id %))))]
     (cmd/command-dialog dialog-attrs
     (when (seq chat-items)
       (apply cmd/command-group {:heading "Chats"} chat-items))
     ;; Parked draft chats (the sidebar's Drafts section), newest first.
     (when-let [drafts (seq (:web/draft-chats state))]
       (apply cmd/command-group {:heading "Drafts"}
         (for [{:keys [id cwd]} (rseq (vec drafts))
               :let [title (or (draft-chat-title state id) "Draft")
                     path  (some-> cwd shorten-path)]]
           (cmd/command-item {:icon        :edit
                              :description path
                              :value       (str "draft " title " " path)
                              :on-click    (fn [_] (dispatch! {:type :draft-chat/open :id id}))}
             title))))
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
         room (conj (let [event {:type :palette/open-file-finder}]
                      (cmd/command-item
                       (with-shortcut {:icon :file-text
                                       :on-click (fn [_] (dispatch! event))}
                         state {:event event})
                       "Find file…")))
         (and room (seq (:files (session-edited-files state))))
         (conj (let [event {:type :palette/open-file-finder :scope :edited}]
                 (cmd/command-item
                  (with-shortcut {:icon :edit
                                  :on-click (fn [_] (dispatch! event))}
                    state {:event event})
                  "Find edited file…")))
         (and room (let [{:keys [urls files]} (chat-links state)]
                     (or (seq urls) (seq files))))
         (conj (let [event {:type :palette/open-links :scope :all}]
                 (cmd/command-item
                  (with-shortcut {:icon :link
                                  :value "Find link or file in this chat urls"
                                  :on-click (fn [_] (dispatch! event))}
                    state {:event event})
                  "Find link or file in this chat…")))
         :always (into (for [item (nav-items-for state :palette)]
                         (cmd/command-item (with-shortcut {:icon (:icon item)
                                                           :on-click (fn [_] (dispatch! (:event item)))}
                                             state item)
                           (:label item))))
         :always (into (map #(palette-ext-row state dispatch! %)
                            (palette-items/items state)))))
     (when-not pa?
       (apply cmd/command-group {:heading "Workspaces"}
         (palette-workspace-items state dispatch!)))
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
                :on-click (fn [_] (dispatch! {:type :palette/drill :cwd d
                                              :label short :reopen? true}))}
               short)))
          project-dirs)))
     ;; Actions come from the shared xi.palette spec; the web maps each :key.
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
                       (cmd/command-item (with-shortcut {:icon icon :on-click (action-onclick key)}
                                           state {:action action})
                         label))
             ;; [:ext :pushover] on the room means the pushover user extension
             ;; is loaded server-side.
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
             (conj (let [on? (:terminal-hints? (appearance/effective-in state))]
                     (cmd/command-item
                      {:icon (if on? :eye-off :eye)
                       :value "terminal tooltips diagnostics lint hints toggle"
                       :on-click (fn [_] (dispatch! {:type :appearance/set
                                                     :key :terminal-hints? :value (not on?)}))}
                      (if on? "Hide terminal tooltips" "Show terminal tooltips"))))
             :always
             (into (let [mode  (or (:web/theme-mode state) "auto")
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
                              {:icon :star
                               :value "change color theme palette colors"
                               :on-click (fn [_] (dispatch! {:type :palette/open-themes}))}
                              "Change color theme…"))
                       :always
                       (conj (cmd/command-item
                              {:icon :settings
                               :value "appearance settings viewer mode tool thinking blocks"
                               :on-click (fn [_] (dispatch! {:type :appearance/open}))}
                              "Appearance settings")))))))))
     (when cur-sid
       (cmd/command-group {:heading "Current session"}
         (for [item (:web/session-menu-items state)
               :let [{:keys [label icon on-click]}
                     (ext-session-menu-item dispatch! (assoc cur-session :session-id cur-sid) item)]]
           (cmd/command-item {:icon icon :on-click (fn [_] (on-click))} label))
         (for [{:keys [label icon on-click]} (session-link-items dispatch! cur-sid (.-title js/document))]
           (cmd/command-item {:icon icon :value (str label " share copy url") :on-click (fn [_] (on-click))} label))
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
           (conj (cmd/command-item
                  (with-shortcut
                    {:icon  :zap
                     :value (str "prune all " (str/join " " (map :label cleanups)))
                     :on-click (fn [_] (run! (comp dispatch! :event) cleanups))}
                    state {:action :sessions/prune})
                  "Prune all")))))
     (when room
       (apply cmd/command-group {:heading "Commands"}
         (for [{:keys [name description takes-args?]} (palette/expand-commands (web-commands state))]
           (cmd/command-item
            {:icon :terminal
             :value (str name " " description)
             :on-click (cond
                         (= name "model")
                         (fn [_] (dispatch! {:type :palette/open-models}))
                         takes-args?
                         (fn [_] (dispatch! {:type :compose/prefill :draft-key (draft-key state)
                                             :text (str "/" name " ")}))
                         :else
                         (fn [_] (dispatch-command! dispatch! (:id room) name)))}
            name))))
     ;; Pinned last row (kept visible under any filter, see pin-palette-items!).
     (cmd/command-group {:class ["command-group--pinned"]}
       (cmd/command-item
        {:icon :search
         :class ["command-item--pinned"]
         ;; Looked up by id, not [open]: the runtime closes the dialog first.
         :on-click (fn [_]
                     (let [^js input (.querySelector js/document "#cmdk .command-input")]
                       (dispatch! {:type :palette/open-search :cwd nil
                                   :query (some-> input .-value)})))}
        "Search all sessions")))))))

(defn- auth-overlay
  "Full-screen block while this browser awaits pairing approval (or was
   denied). The code comparison is the security, not the device label."
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
  [dispatch! files]
  (when (seq files)
    (-> (js/Promise.all (to-array (map resize-image-file files)))
        (.then (fn [results]
                 (when-let [valid (seq (remove nil? (array-seq results)))]
                   (dispatch! {:type :skill-form/add-images :images (vec valid)}))))
        (.catch (fn [err] (js/console.error "[xi-web] skill-form image read failed:" err))))))

(defn- handle-skill-form-paste!
  [dispatch! ^js e]
  (let [files (pasted-image-files e)]
    (when (seq files)
      (.preventDefault e)
      (skill-form-stage-images! dispatch! files))))

(defn- skill-form-image-field
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
  "The composer reshaped into a skill input form while :web/skill-form is set:
   one section per <input /> placeholder (textarea or image picker). Esc twice
   cancels; Enter or the send button submits."
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
  [label hint control]
  [:div {:class ["appearance-row"]}
   [:div {:class ["appearance-row-text"]}
    [:div {:class ["appearance-row-label"]} label]
    (when hint [:div {:class ["form-hint"]} hint])]
   control])

(defn- appearance-choice
  "One button per `[value label]` option; the active one is the setting's
   current `value`."
  [dispatch! key value options]
  (apply button-group/button-group {:variant :boxed}
         (for [[v label] options]
           (button-group/button-group-item
            {:active (= v value)
             :on-click (fn [_] (dispatch! {:type :appearance/set :key key :value v}))}
            label))))

(defn- open-collapsed-choice
  [dispatch! key value]
  (appearance-choice dispatch! key value [[:open "Open"] [:collapsed "Collapsed"]]))

(defn- tool-exceptions-title
  "The per-tool list's name: what a checked tool does under \"Tool blocks\"."
  [app]
  (if (= :open (:tool-blocks app)) "Start collapsed" "Start open"))

(defn- tool-exceptions-link
  "The Chat pane's row into its Tool blocks pane: the checked tools in one
   line (xi.web.appearance/tool-summary)."
  [dispatch! app]
  [:button {:type "button"
            :class ["appearance-link"]
            :on {:click (fn [_] (dispatch! {:type :appearance/tab :tab :chat-tools}))}}
   [:span {:class ["appearance-row-label"]} (tool-exceptions-title app)]
   [:span {:class ["appearance-link-summary"]}
    (or (appearance/tool-summary (:tool-blocks-except app)) "None")]
   (icon/icon {:icon-name :chevron-right :size :sm})])

(defn- appearance-tools-pane
  "Chat › Tool blocks: every tool (the server's list, :tools/web-list)
   grouped by where it comes from, the checked ones (:tool-blocks-except)
   first, narrowed by the filter chips and the search; a checked tool starts
   the other way from \"Tool blocks\"."
  [state dispatch!]
  (let [app      (appearance/effective-in state)
        open?    (= :open (:tool-blocks app))
        listed   (:tool-blocks-except app)
        tools    (:web/tools state)
        query    (:web/appearance-tool-query state)
        kind     (or (:web/appearance-tool-filter state) :all)
        sections (appearance/tool-sections tools listed {:query query :filter kind})]
    [:div {:class ["appearance-pane"] :replicant/key "chat-tools"}
     [:div {:class ["appearance-row-text"]}
      [:div {:class ["appearance-row-label"]} (tool-exceptions-title app)]
      [:div {:class ["form-hint"]}
       (str "Switched-on tools start " (if open? "collapsed" "open")
            "; the others follow “Tool blocks: " (if open? "Open" "Collapsed") "”.")]]
     (form/form-input
      {:value (or query "")
       :placeholder "Search tools"
       :attrs {:aria-label "Search tools"
               :on {:input (fn [^js e] (dispatch! {:type :appearance/tool-query
                                                   :query (.. e -target -value)}))}}})
     [:div {:class ["appearance-tool-filters"]}
      (for [[id label n] (appearance/tool-filter-counts tools listed)]
        (chip/chip {:active   (= id kind)
                    :attrs    {:replicant/key (name id)}
                    :on-click (fn [_] (dispatch! {:type :appearance/tool-filter :filter id}))}
          label
          [:span {:class ["appearance-tool-filter-count"]} n]))]
     ;; One flat run of keyed siblings that never reorder: a checked tool
     ;; gets a second row under Selected instead of moving there (a keyed
     ;; row that moved and changed in one render left a stray icon behind).
     (into [:div {:class ["appearance-tool-list"]}]
           (if (seq sections)
             (mapcat
              (fn [[title tools]]
                (cons
                 [:div {:class ["appearance-tool-section"] :replicant/key (str "s-" title)}
                  (cond-> title (= "Selected" title) (str " · " (count tools)))]
                 (for [{:keys [id key label hint]} tools
                       :let [on? (boolean (some #{key} listed))]]
                   [:button {:type "button"
                             :class ["appearance-tool-item"]
                             :replicant/key id
                             :role "switch"
                             :aria-checked (str on?)
                             :title key
                             :on {:click (fn [_]
                                           (dispatch! {:type :appearance/set
                                                       :key :tool-blocks-except
                                                       :value (appearance/toggle-tool listed key)}))}}
                    [:span {:class ["appearance-tool-name"]} label]
                    [:span {:class ["appearance-tool-hint"]} hint]
                    ;; the switch's track only (ui.switch wraps a label +
                    ;; input, which can't nest in the row's button)
                    [:span {:class (cond-> ["switch-track" "appearance-tool-switch"]
                                     on? (conj "switch-track--checked"))}
                     [:span {:class ["switch-thumb"]}]]])))
              sections)
             [[:div {:class ["appearance-tool-empty"] :replicant/key "empty"}
               (cond
                 (nil? tools)         "Loading tools…"
                 (= :selected kind)   "No tools checked yet"
                 :else                "No tools match")]]))]))

;; ── Color themes (xi.web.theme) ─────────────────────────────────────────────

(defn- theme-slider
  "One parameter of the draft: label and value on a line above a slim range.
   The input works in integer units (`scale` × the stored value) so hues
   read as degrees and ratios as percentages."
  [dispatch! draft key label {:keys [min max step scale fmt gradient]}]
  (let [v (get-in draft [:params key])]
    [:label {:class ["theme-slider"]}
     [:span {:class ["theme-slider-head"]}
      [:span {:class ["theme-slider-label"]} label]
      [:span {:class ["theme-slider-value"]} (fmt v)]]
     (form/form-range
      {:min min :max max :step step
       :value (js/Math.round (* v scale))
       :attrs (cond-> {:aria-label label
                       :on {:input (fn [^js e]
                                     (dispatch! {:type :themes/draft-set :key key
                                                 :value (/ (js/parseInt (.. e -target -value) 10) scale)}))}}
                gradient (assoc :style {:background gradient}))})]))

(defn- color-chip
  "A swatch-and-label chip opening ui.color-picker in a popover, limited to
   the formats xi.web.theme/color? accepts. A `derived?` chip shows a
   computed color, dimmed; `on-clear` puts a set one back to derived."
  [{:keys [id value label derived? on-change on-clear]}]
  [:div {:class (cond-> ["theme-color"] derived? (conj "theme-color--derived"))
         :replicant/key id}
   [:button (merge {:type "button" :class ["theme-color-chip"]
                    :title (if derived? (str value " · derived") value)
                    :replicant/key (str id "-trigger")}
                   (popover/trigger-attrs id))
    [:span {:class ["theme-color-swatch"] :style {:background value}}]
    [:span {:class ["theme-color-label"]} label]]
   (when on-clear
     (button/button {:variant :ghost :size :sm :icon :x
                     :class "theme-color-clear"
                     :attrs {:aria-label (str "Reset " label) :title "Back to derived"}
                     :on-click on-clear}))
   (popover/popover-content
    {:id id :side :bottom :align :start :class "theme-color-popover"
     :attrs {:replicant/key (str id "-popover")}}
    (color-picker/color-picker
     {:value value
      :formats [:oklch :hex]
      :on-change on-change}))])

(defn- theme-color
  "One color parameter of the draft. `fallback` is what an unset parameter
   shows (and makes a set one clearable)."
  [dispatch! draft key label & [fallback]]
  (let [v (get-in draft [:params key])]
    (color-chip {:id (str "theme-color-" (name key))
                 :value (or v fallback) :label label
                 :on-change (fn [^js e]
                              (dispatch! {:type :themes/draft-set :key key
                                          :value (.. e -target -value)}))
                 :on-clear (when (and v fallback)
                             (fn [_] (dispatch! {:type :themes/draft-set :key key :value nil})))})))

(defn- theme-palette-color
  "Palette color `key` of `mode`: set, or showing the derived `shown`."
  [dispatch! draft mode key label shown]
  (let [set? (some? (get-in draft [:params mode key]))
        ev   {:type :themes/draft-set :mode mode :key key}]
    (color-chip {:id (str "theme-palette-" (name mode) "-" (name key))
                 :value shown :label label :derived? (not set?)
                 :on-change (fn [^js e] (dispatch! (assoc ev :value (.. e -target -value))))
                 :on-clear (when set? (fn [_] (dispatch! (assoc ev :value nil))))})))

(defn- theme-section
  "A collapsible group of the editor (ui.accordion, starts open, the open
   state kept by the browser): `summary` (swatches, values) in its header,
   readable closed."
  [{:keys [label summary]} & children]
  (apply accordion/accordion
         {:title [:span {:class ["theme-section-head"]}
                  [:span {:class ["theme-section-label"]} label]
                  (into [:span {:class ["theme-section-summary"]}] summary)]
          :open true
          :class "theme-section"
          :attrs {:replicant/key label}}
         children))

(defn- swatch-dot [color]
  [:span {:class ["theme-dot"] :style {:background color}}])

(defn- ramp-bar [colors]
  [:div {:class ["theme-ramp"]}
   (for [c colors] [:span {:style {:background c}}])])

(defn- theme-controls
  "Sliders in a two-column grid."
  [& sliders]
  (into [:div {:class ["theme-controls"]}] sliders))

(defn- theme-colors
  "Color chips in a row."
  [& chips]
  (into [:div {:class ["theme-colors"]}] chips))

;; ── Specimens: what each editor section changes, previewed in the section.
;; Those on page tokens (var(--accent), var(--font-xl)…) follow the draft live
;; like the rest of the page; those showing both modes take the colors from
;; xi.web.theme (backgrounds, surfaces).

(defn- theme-specimen [& children]
  (into [:div {:class ["theme-specimen"] :inert ""}] children))

(defn- mini-app
  "A page with its sidebar in one mode: `surface` is that mode's
   xi.web.theme/surfaces map."
  [label [page sidebar] surface]
  [:div {:class ["theme-mini-app"] :style {:background page :color (surface "fg-0")}}
   [:span {:class ["theme-mini-side"] :style {:background sidebar}}
    [:i {:style {:background (surface "fg-2")}}]
    [:i {:style {:background (surface "bg-2")}}]]
   [:span {:class ["theme-mini-main"]}
    [:b label]
    [:i {:style {:background (surface "bg-2")}}]
    [:i {:style {:background (surface "border-1") :width "60%"}}]]])

(defn- mini-diff
  [surface]
  [:div {:class ["theme-mini-diff"] :style {:background (surface "bg-1") :color (surface "fg-0")}}
   [:div "(defn parse [s]"]
   [:div {:style {:background (surface "del-bg")}} "-  (read s))"]
   [:div {:style {:background (surface "add-bg")}} "+  (read (str/trim s)))"]])

(defn- mini-code
  "A few lines of code on one mode's page in its syntax colors."
  [page surface]
  (let [c (fn [token text] [:span {:style {:color (surface (str "hl-" token))}} text])]
    [:div {:class ["theme-mini-diff"] :style {:background page :color (surface "fg-0")}}
     [:div (c "comment" ";; split a header line")]
     [:div "(" (c "keyword" "defn") " " (c "fn" "parse") " [s]"]
     [:div "  (" (c "fn" "str/split") " s " (c "string" "\": \"") " " (c "number" "2") "))"]
     [:div "{" (c "symbol" ":ok") " " (c "keyword" "true") "}"]]))

(defn- theme-specimen-for
  "The specimen of editor section `section` for the draft's `params`."
  [section params]
  (case section
    :palette
    (let [{[light-page] :light [dark-page] :dark} (ctheme/backgrounds params)
          {:keys [light dark]} (ctheme/surfaces params)]
      (theme-specimen
       [:div {:class ["theme-specimen-pair"]}
        (mini-code light-page light)
        (mini-code dark-page dark)]))
    :fonts
    (theme-specimen
     [:div {:class ["theme-type"]}
      [:div {:class ["theme-type-heading"]} "The quick brown fox"]
      [:div "Body text the chat is set in, jumping over the lazy dog."]
      [:pre {:class ["theme-font-code"]} "(defn parse [s] (str/split s #\":\" 2)) ; 0O 1lI"]])
    :accent
    (theme-specimen
     [:div {:class ["theme-specimen-row"]}
      (button/button {:variant :primary :size :sm} "Send")
      (chip/chip {:active true :dot-color (ctheme/accent-color params)} "Active")
      (switch/switch-toggle {:checked true})
      [:span {:class ["theme-specimen-link"]} "Open diff →"]]
     (ramp-bar (ctheme/accent-swatches params)))
    :gray
    (theme-specimen
     [:div {:class ["theme-specimen-row"]}
      (button/button {:variant :ghost :size :sm} "Cancel")
      (chip/chip {} "Neutral")
      [:span {:class ["theme-specimen-muted"]} "acme-web · 2h ago"]]
     (ramp-bar (ctheme/gray-swatches params)))
    :background
    (let [{:keys [light dark]} (ctheme/backgrounds params)
          surfaces (ctheme/surfaces params)]
      (theme-specimen
       [:div {:class ["theme-specimen-pair"]}
        (mini-app "Light" light (:light surfaces))
        (mini-app "Dark" dark (:dark surfaces))]))
    :status
    (theme-specimen
     [:div {:class ["theme-specimen-row"]}
      [:span {:class ["theme-status"]}
       [:i {:style {:background (:success-color params)}}] "Done"]
      [:span {:class ["theme-status"]}
       [:span {:class ["theme-meter"]}
        [:i {:style {:background (:warning-color params)}}]]
       "69%"]
      [:span {:class ["theme-status"] :style {:color (:danger-color params)}}
       "Request failed"]])
    :diff
    (let [{:keys [light dark]} (ctheme/surfaces params)]
      (theme-specimen
       [:div {:class ["theme-specimen-pair"]}
        (mini-diff light)
        (mini-diff dark)]))
    :type
    (theme-specimen
     [:div {:class ["theme-type"]}
      [:div {:class ["theme-type-heading"]} "Heading"]
      [:div "Body text the chat is set in, with " [:code "inline code"] "."]])
    :space
    (theme-specimen
     [:div {:class ["theme-space"]}
      [:p "A paragraph of the reply."]
      [:p "The next one, a paragraph gap below."]
      [:div {:class ["theme-space-card"]}
       [:span "A card"]
       (button/button {:variant :secondary :size :sm} "Button")]])))

(def ^:private palette-groups
  [[[:fg "Text"] [:fg-muted "Muted"] [:fg-subtle "Subtle"]]
   [[:sidebar "Sidebar"] [:surface "Surface"] [:border "Border"]]
   [[:accent "Accent"] [:bubble "Bubble"] [:success "Success"] [:warning "Warning"]
    [:danger "Danger"]]
   [[:keyword "Keyword"] [:string "String"] [:fn "Function"] [:number "Number"]
    [:symbol "Symbol"] [:comment "Comment"]]])

(defn- theme-palette
  "Every palette color of both modes (ui.theme-model palette-keys): the
   derived ones dimmed, a set one with a reset."
  [dispatch! draft]
  (let [shown (ctheme/palette-colors (:params draft))]
    [:div {:class ["theme-palette"]}
     (for [[mode title] [[:light "Light"] [:dark "Dark"]]]
       [:div {:class ["theme-palette-mode"] :replicant/key (name mode)}
        [:div {:class ["theme-palette-title"]} title]
        (for [group palette-groups]
          (into [:div {:class ["theme-colors"] :replicant/key (str (ffirst group))}]
                (for [[k label] group]
                  (theme-palette-color dispatch! draft mode k label (get-in shown [mode k])))))])]))

(defn- theme-font
  "One font family list of the draft: a text field suggesting
   `ctheme/font-suggestions` and the browser's installed families."
  [dispatch! draft key label local-fonts]
  (let [list-id (str "theme-fonts-" (name key))]
    [:label {:class ["theme-font"]}
     [:span {:class ["theme-slider-label"]} label]
     (form/form-input
      {:value (or (get-in draft [:params key]) "")
       :placeholder "System"
       :attrs {:list list-id :aria-label label :spellcheck "false" :autocomplete "off"
               :on {:input (fn [^js e]
                             (dispatch! {:type :themes/draft-set :key key
                                         :value (.. e -target -value)}))}}})
     (into [:datalist {:id list-id}]
           (for [f (distinct (concat (ctheme/font-suggestions key) local-fonts))]
             [:option {:value f}]))]))

(defn- theme-editor
  "The draft (:web/theme-draft): name, color presets, a slider or color
   input per parameter, previewed live on the page until Save or Cancel
   (the dialog footer, `appearance-footer`)."
  [state dispatch!]
  (let [{:keys [original name params] :as draft} (:web/theme-draft state)
        degrees  (fn [v] (str v "°"))
        percent  (fn [v] (str (js/Math.round (* v 100)) "%"))
        signed   (fn [v] (let [n (js/Math.round (* v 100))] (str (if (pos? n) "+" "") n "%")))
        decimal  (fn [v] (.toFixed v 2))
        hue-opts {:min 0 :max 360 :step 1 :scale 1 :fmt degrees :gradient (ctheme/hue-gradient)}
        chroma   (fn [hue] {:min 0 :max 200 :step 1 :scale 100 :fmt percent
                            :gradient (ctheme/chroma-gradient hue)})]
    ;; keyed per theme: opening another one mounts afresh and scrolls to it
    [:div {:class ["theme-editor"]
           :replicant/key (str "theme-editor-" (or original "new"))
           :replicant/on-mount (fn [{:replicant/keys [^js node]}]
                                 (.scrollIntoView node #js {:block "start" :behavior "smooth"}))}
     (form/form-input
      {:value name :placeholder "Theme name"
       :attrs {:maxlength ctheme/max-name-length
               :aria-label "Theme name"
               :on {:input (fn [^js e] (dispatch! {:type :themes/draft-name
                                                   :name (.. e -target -value)}))}}})
     [:div {:class ["theme-chips"]}
      (for [p ctheme/presets]
        (chip/chip {:active (ctheme/preset-active? p params)
                    :dot-color (ctheme/accent-color p)
                    :attrs {:replicant/key (:name p)}
                    :on-click (fn [_] (dispatch! {:type :themes/draft-preset :preset p}))}
          (:name p)))]
     [:div {:class ["theme-sections"]}
      (theme-section {:label "Accent"
                      :summary [(swatch-dot (ctheme/accent-color params))
                                (str (degrees (:accent-hue params)) " · " (percent (:accent-chroma params)))]}
        (theme-specimen-for :accent params)
        (theme-controls
         (theme-slider dispatch! draft :accent-hue "Hue" hue-opts)
         (theme-slider dispatch! draft :accent-chroma "Chroma" (chroma (:accent-hue params)))))
      (theme-section {:label "Gray"
                      :summary [(swatch-dot (ctheme/gray-color params))
                                (str (degrees (:gray-hue params)) " · " (percent (:gray-chroma params)))]}
        (theme-specimen-for :gray params)
        (theme-controls
         (theme-slider dispatch! draft :gray-hue "Hue" hue-opts)
         (theme-slider dispatch! draft :gray-chroma "Chroma" (chroma (:gray-hue params)))))
      (theme-section {:label "Background"
                      :summary [(swatch-dot (:bg-light params))
                                (swatch-dot (:bg-dark params))
                                (str "sidebar " (signed (:sidebar-shift params)))]}
        (theme-specimen-for :background params)
        (theme-colors
         (theme-color dispatch! draft :bg-light "Light page")
         (theme-color dispatch! draft :bg-dark "Dark page"))
        (theme-controls
         (theme-slider dispatch! draft :sidebar-shift "Sidebar" {:min -20 :max 20 :step 1 :scale 100 :fmt signed})))
      ;; exact per-mode colors over everything derived above
      (theme-section {:label "Palette"
                      :summary (let [n (count (mapcat #(get params %) [:light :dark]))]
                                 [(if (pos? n) (str n " set") "all derived")])}
        (theme-specimen-for :palette params)
        (theme-palette dispatch! draft))
      ;; --success / --warning / --danger: status dots, usage meters, errors
      (theme-section {:label "Status"
                      :summary (map #(swatch-dot (get params %))
                                    [:success-color :warning-color :danger-color])}
        (theme-specimen-for :status params)
        (theme-colors
         (theme-color dispatch! draft :success-color "Success")
         (theme-color dispatch! draft :warning-color "Warning")
         (theme-color dispatch! draft :danger-color "Danger")))
      ;; --diff-add-bg / --diff-del-bg: diff line backgrounds; unset colors
      ;; follow Success / Danger
      (theme-section {:label "Diff"
                      :summary [(swatch-dot (ctheme/diff-color params :add))
                                (swatch-dot (ctheme/diff-color params :del))
                                (percent (:diff-strength params))]}
        (theme-specimen-for :diff params)
        (theme-colors
         (theme-color dispatch! draft :diff-add-color "Added" (ctheme/diff-color params :add))
         (theme-color dispatch! draft :diff-del-color "Removed" (ctheme/diff-color params :del)))
        (theme-controls
         (theme-slider dispatch! draft :diff-strength "Strength" {:min 0 :max 250 :step 5 :scale 100 :fmt percent})))
      ;; --font-body / --font-mono (ui.theme-model font-stack)
      (theme-section {:label "Fonts"
                      :summary [(str (ctheme/font-label params :font-body) " · "
                                     (ctheme/font-label params :font-mono))]}
        (theme-specimen-for :fonts params)
        (theme-controls
         (theme-font dispatch! draft :font-body "Text" (:web/local-fonts state))
         (theme-font dispatch! draft :font-mono "Code" (:web/local-fonts state)))
        (when (and (empty? (:web/local-fonts state)) (exists? js/window.queryLocalFonts))
          [:div
           (button/button {:variant :ghost :size :sm
                           :on-click (fn [_] (dispatch! {:type :themes/query-fonts}))}
             "Suggest installed fonts")]))
      (theme-section {:label "Type"
                      :summary [(str (decimal (:font-base params)) " · ×" (decimal (:font-ratio params))
                                     " · code " (percent (:code-scale params)))]}
        (theme-specimen-for :type params)
        (theme-controls
         (theme-slider dispatch! draft :font-base "Base" {:min 75 :max 125 :step 1 :scale 100 :fmt decimal})
         (theme-slider dispatch! draft :font-ratio "Ratio" {:min 105 :max 150 :step 1 :scale 100 :fmt decimal})
         (theme-slider dispatch! draft :code-scale "Code" {:min 70 :max 150 :step 5 :scale 100 :fmt percent})))
      (theme-section {:label "Space & shape"
                      :summary [(str (decimal (:size-base params)) " · ¶ " (decimal (:paragraph-space params))
                                     "em · radius " (percent (:radius-scale params)))]}
        (theme-specimen-for :space params)
        (theme-controls
         (theme-slider dispatch! draft :size-base "Spacing" {:min 10 :max 50 :step 1 :scale 100 :fmt decimal})
         (theme-slider dispatch! draft :paragraph-space "Paragraphs"
                       {:min 0 :max 200 :step 5 :scale 100 :fmt (fn [v] (str (decimal v) "em"))})
         (theme-slider dispatch! draft :radius-scale "Radius" {:min 0 :max 200 :step 5 :scale 100 :fmt percent})))]]))

(defn- theme-row
  "One theme of the picker: a radio that applies it, its `ctheme/strip-colors`
   and name, then `aside` (Edit / Duplicate, an \"Editing\" note). `name` is
   a custom theme's name, a built-in's keyword, nil for the default;
   `selectable?` false leaves the radio out (an unsaved new theme). A click
   anywhere on the active custom theme's row edits it."
  [dispatch! {:keys [key name label params checked? editing? selectable? aside]}]
  (let [edit-on-click? (and checked? (string? name) (not editing?))]
    [:div (cond-> {:class (cond-> ["theme-row"]
                            checked?       (conj "theme-row--active")
                            editing?       (conj "theme-row--editing")
                            edit-on-click? (conj "theme-row--editable"))
                   :replicant/key key}
            edit-on-click? (assoc :title "Edit this theme"
                                  :on {:click (fn [_] (dispatch! {:type :themes/edit :name name}))}))
     [:label {:class ["theme-row-main"]}
      (if selectable?
        [:input {:type "radio" :class ["form-radio"] :name "xi-color-theme"
                 :checked (boolean checked?)
                 :on {:change (fn [_] (dispatch! {:type :themes/select :name name}))}}]
        [:span {:class ["theme-row-radio-gap"]}])
      [:span {:class ["theme-strip"]}
       (for [c (ctheme/strip-colors params)] [:span {:style {:background c}}])]
      [:span {:class ["theme-row-name"]} label]]
     aside]))

(defn- theme-picker
  "Default, the built-ins and the user's themes as radio rows; Edit opens a
   theme in the editor below, Duplicate starts a new one from a built-in,
   \"New theme\" one from the defaults."
  [state dispatch!]
  (let [themes  (:web/themes state)
        active  (:active themes)
        draft   (:web/theme-draft state)
        custom  (ctheme/custom-themes themes)
        new?    (and draft (nil? (:original draft)))
        full?   (>= (count custom) ctheme/max-themes)
        editing [:span {:class ["theme-row-note"]} "Editing"]]
    (into [:div {:class ["theme-list"]}
           (theme-row dispatch! {:key "default" :label "Default" :selectable? true
                                 :checked? (nil? active)})]
          (concat
           [[:div {:class ["theme-list-heading"] :replicant/key "builtin-heading"} "Built-in"]]
           (for [[id label p] ctheme/builtin-themes]
             (theme-row dispatch!
                        {:key (str "builtin-" (name id)) :name id :label label :params p
                         :selectable? true :checked? (= id active)
                         :aside (button/button
                                 {:variant :ghost :size :sm :disabled full?
                                  :attrs {:title "A copy of it to edit"}
                                  :on-click (fn [_] (dispatch! {:type :themes/edit :name id}))}
                                 "Duplicate")}))
           [[:div {:class ["theme-list-heading"] :replicant/key "custom-heading"} "Yours"]]
           (for [[n p] custom]
             (theme-row dispatch!
                        {:key n :name n :label n :params p :selectable? true
                         :checked? (= n active)
                         :editing? (= n (:original draft))
                         :aside (if (= n (:original draft))
                                  editing
                                  (button/button
                                   {:variant :ghost :size :sm
                                    :on-click (fn [_] (dispatch! {:type :themes/edit :name n}))}
                                   "Edit"))}))
           [(if new?
              (theme-row dispatch! {:key "new" :label (:name draft) :params (:params draft)
                                    :editing? true
                                    :aside [:span {:class ["theme-row-note"]} "New"]})
              [:div {:class ["theme-row" "theme-row--new"] :replicant/key "new"}
               (button/button
                {:variant :ghost :size :sm
                 :disabled full?
                 :on-click (fn [_] (dispatch! {:type :themes/edit :name nil}))}
                (icon/icon {:icon-name :plus :size :sm}) "New theme")])]))))

(defn- appearance-theme-pane [state dispatch!]
  [:div {:class ["appearance-pane"] :replicant/key "theme"}
   (appearance-row
    "Mode" nil
    (theme-toggle/theme-toggle
     {:mode (or (:web/theme-mode state) "auto")
      :size :sm
      :on-change (fn [mode] (dispatch! {:type :theme/set-mode :mode mode}))}))
   [:div {:class ["appearance-block"]}
    [:div {:class ["appearance-row-label"]} "Color theme"]
    [:div {:class ["form-hint"]}
     "A built-in palette or your own: colors, fonts, spacing, type scale and radii, for light and dark mode."]
    (theme-picker state dispatch!)]
   (when (:web/theme-draft state)
     (theme-editor state dispatch!))])

(defn- appearance-chat-pane [state dispatch!]
  (let [app (appearance/effective-in state)]
    [:div {:class ["appearance-pane"] :replicant/key "chat"}
     (appearance-row
      "Super collapsed"
      "Fold a run of collapsed tool and thinking rows into one summary row"
      (switch/switch-toggle
       {:checked (:super-collapsed? app)
        :on-change (fn [^js e]
                     (dispatch! {:type :appearance/set
                                 :key :super-collapsed?
                                 :value (boolean (.. e -target -checked))}))}))
     (appearance-row
      "Tool blocks"
      "Read, edit, clj… calls and their results, and thinking"
      (open-collapsed-choice dispatch! :tool-blocks (:tool-blocks app)))
     (tool-exceptions-link dispatch! app)
     (appearance-row
      "Tool arguments"
      "Next to the tool name: a per-tool summary (the command, the path, the pattern) or every argument"
      (appearance-choice dispatch! :tool-args (:tool-args app)
                         [[:summary "Summary"] [:all "All"]]))]))

(defn- theme-draft-footer
  "An open theme draft's Delete / Cancel / Save under the Theme pane."
  [state dispatch!]
  (when-let [draft (:web/theme-draft state)]
    (dialog/dialog-footer {:class "appearance-draft-footer"}
      (when-let [original (:original draft)]
        (button/button {:variant :ghost :size :sm
                        :on-click (fn [_] (dispatch! {:type :themes/delete :name original}))}
          "Delete"))
      [:span {:class ["flex-1"]}]
      (button/button {:variant :ghost :size :sm
                      :on-click (fn [_] (dispatch! {:type :themes/draft-cancel}))}
        "Cancel")
      (button/button {:variant :primary :size :sm
                      :disabled (not (ctheme/draft-savable? (:web/themes state) draft))
                      :on-click (fn [_] (dispatch! {:type :themes/save}))}
        "Save theme"))))

(def ^:private appearance-tabs
  "[id label parent]: a tab with a parent is its sub-menu entry, and its
   bar drills back to the parent."
  [[:theme "Theme"]
   [:chat "Chat"]
   [:chat-tools "Tool blocks" :chat]])

(defn- appearance-tab-label [id]
  (some (fn [[tab label]] (when (= tab id) label)) appearance-tabs))

(defn- appearance-bar
  "The pane's drill-down bar: a back link to the parent pane on a sub-pane,
   the pane's name and Close."
  [dispatch! tab close!]
  (let [parent (some (fn [[id _ parent]] (when (= id tab) parent)) appearance-tabs)]
    [:header {:class ["appearance-bar"]}
     [:span {:class ["appearance-bar-side"]}
      (when parent
        (button/button
         {:variant :ghost :size :sm
          :class "appearance-bar-back"
          :icon-left :chevron-left
          :on-click (fn [_] (dispatch! {:type :appearance/tab :tab parent}))}
         (appearance-tab-label parent)))]
     [:span {:class ["appearance-bar-title"]} (appearance-tab-label tab)]
     [:span {:class ["appearance-bar-side" "appearance-bar-side--end"]}
      [:button {:class ["icon-btn" "icon-btn--sm"]
                :title "Close"
                :on {:click (fn [_] (close!))}}
       (icon/icon {:icon-name :x :size :md})]]]))

(defn- appearance-dialog
  "The Appearance settings dialog: a dark rail (the menu; a top strip when
   narrow) beside the pane under its drill-down bar (appearance-bar). Panes:
   Theme (mode, color themes and their editor) and Chat (this browser's
   overrides, :web/appearance, xi.web.appearance, over xi.config/appearance)
   with its Tool blocks sub-pane. :web/appearance-tab is the pane."
  [state dispatch!]
  (when (:web/appearance-open? state)
    (let [tab    (or (:web/appearance-tab state) :theme)
          close! (fn [] (dispatch! {:type :appearance/close}))]
      (dialog/dialog-overlay
       {:on-close close!
        :class "appearance-overlay"
        :attrs {:replicant/key "appearance-dialog"}}
       (dialog/dialog-panel {:class "appearance-dialog"}
         [:div {:class ["appearance-layout"]}
          [:aside {:class ["appearance-rail"]}
           [:h3 {:class ["appearance-rail-title"]} "Appearance"]
           (apply sidebar/sidebar-menu {:class "appearance-nav"}
                  (for [[id label parent] appearance-tabs
                        :let [n (when (= :chat-tools id)
                                  (count (:tool-blocks-except (appearance/effective-in state))))]]
                    (sidebar/sidebar-menu-item
                     {:active (= tab id)
                      :badge (when (and n (pos? n)) n)
                      :class (when parent "appearance-nav-sub")
                      :attrs {:replicant/key id}
                      :on-click (fn [_] (dispatch! {:type :appearance/tab :tab id}))}
                     label)))]
          [:div {:class ["appearance-main"]}
           (appearance-bar dispatch! tab close!)
           (dialog/dialog-body {:class "appearance-body"}
             (case tab
               :chat       (appearance-chat-pane state dispatch!)
               :chat-tools (appearance-tools-pane state dispatch!)
               (appearance-theme-pane state dispatch!)))
           (theme-draft-footer state dispatch!)]])))))

(def ^:private suggestion-style-label
  {:short "short" :descriptive "descriptive" :topic "topic"})

(defn- rename-suggestions
  "The suggestion rows (skeletons while loading); the row Tab fills next
   carries the key hint."
  [dispatch! {:keys [names status selected]}]
  (let [loading? (= :loading status)
        tab-idx  (rename/next-index selected (count names) 1)]
    (into [:div {:class ["rename-suggestions"]}]
          (if loading?
            (for [i (range 3)]
              [:div {:class ["rename-skeleton"] :replicant/key (str "sk" i)}])
            (map-indexed
             (fn [i {:keys [name style]}]
               [:button {:class ["rename-suggestion"
                                 (when (= i selected) "rename-suggestion--on")]
                         :replicant/key (str "s-" name)
                         :type "button"
                         :on {:mousedown (fn [^js e] (.preventDefault e))
                              :click (fn [_] (dispatch! {:type :rename/pick :idx i}))}}
                [:span {:class ["rename-suggestion-name"]} name]
                (if (= i tab-idx)
                  (keycaps "Tab")
                  [:span {:class ["rename-suggestion-style"]}
                   (suggestion-style-label style)])])
             names)))))

(defn- rename-status [{:keys [status stats]}]
  [:div {:class ["rename-status"]}
   (case status
     :loading "Reading the conversation… keep typing, nothing waits on it"
     :failed  "No suggestions this time. ↻ More tries again"
     (when stats (rename/stats-line stats)))])

(defn- workspace-dialog
  "Name a new workspace or rename one (:workspace/editor, xi.web.workspaces)."
  [state dispatch!]
  (when-let [{:keys [mode label dir]} (:workspace/editor state)]
    (let [close! (fn [] (dispatch! {:type :workspace/edit-close}))
          blank? (str/blank? label)
          save!  (fn [] (when-not blank? (dispatch! {:type :workspace/edit-save})))]
      (dialog/dialog-overlay
       {:on-close close!
        :attrs {:replicant/key "workspace-dialog"}}
       (dialog/dialog-panel {:class "workspace-dialog"}
         (dialog/dialog-header {}
           [:h3 (if (= mode :rename) "Rename workspace" "New workspace")]
           [:button {:class ["icon-btn" "icon-btn--sm"]
                     :title "Close"
                     :on {:click (fn [_] (close!))}}
            (icon/icon {:icon-name :x :size :md})])
         (dialog/dialog-body {:class "workspace-body"}
           (form/form-input
            {:value label
             :placeholder "Workspace name"
             :attrs {:value label
                     :maxlength workspace/max-label
                     :aria-label "Workspace name"
                     :replicant/on-mount (fn [{:replicant/keys [^js node]}]
                                           (.focus node)
                                           (.select node))
                     :on {:input   (fn [^js e]
                                     (dispatch! {:type :workspace/edit-set
                                                 :label (.. e -target -value)}))
                          ;; Escape here, else the compose layer's Escape only
                          ;; leaves the field
                          :keydown (fn [^js e]
                                     (case (.-key e)
                                       "Enter"  (do (.preventDefault e) (save!))
                                       "Escape" (do (.preventDefault e) (close!))
                                       nil))}}})
           [:p {:class ["workspace-hint"]}
            (if dir
              (str "Starts with " (shorten-path dir) ". Add more projects from a project's menu.")
              "Add projects from a project's menu, or with the Add project row of its tab.")])
         (dialog/dialog-footer {}
           [:span {:class ["flex-1"]}]
           (button/button {:variant :ghost :size :sm :on-click (fn [_] (close!))}
             "Cancel")
           (button/button {:variant :primary :size :sm
                           :disabled blank?
                           :on-click (fn [_] (save!))}
             (if (= mode :rename) "Save" "Create"))))))))

(defn- rename-dialog
  "Rename a session (:web/rename, xi.web.rename). Suggestions start with the
   dialog and fill in under the field; Tab cycles them into it."
  [state dispatch!]
  (when-let [{:keys [name status names] :as r} (:web/rename state)]
    (let [close!   (fn [] (dispatch! {:type :rename/close}))
          blank?   (str/blank? name)
          loading? (= :loading status)]
      (dialog/dialog-overlay
       {:on-close close!
        :class "rename-overlay"
        :attrs {:replicant/key "rename-dialog"}}
       (dialog/dialog-panel {:class "rename-dialog"}
         (dialog/dialog-header {}
           [:h3 "Rename session"]
           [:button {:class ["icon-btn" "icon-btn--sm"]
                     :title "Close"
                     :on {:click (fn [_] (close!))}}
            (icon/icon {:icon-name :x :size :md})])
         (dialog/dialog-body {:class "rename-body"}
           [:div {:class ["rename-field" (when loading? "rename-field--thinking")]}
            (form/form-input
             {:value name
              :placeholder "Session name"
              :attrs {:value name
                      :maxlength util/session-title-max
                      :aria-label "Session name"
                      :replicant/on-mount (fn [{:replicant/keys [^js node]}]
                                            (.focus node)
                                            (.select node))
                      :on {:input   (fn [^js e]
                                      (dispatch! {:type :rename/set :name (.. e -target -value)}))
                           :keydown (fn [^js e]
                                      (case (.-key e)
                                        "Enter" (when-not blank?
                                                  (.preventDefault e)
                                                  (dispatch! {:type :rename/save}))
                                        "Tab"   (when (seq names)
                                                  (.preventDefault e)
                                                  (dispatch! {:type :rename/tab
                                                              :dir (if (.-shiftKey e) -1 1)}))
                                        nil))}}})]
           [:div {:class ["rename-label"]}
            [:span "Suggestions"]
            (button/button {:variant :ghost :size :sm
                            :class "rename-more"
                            :disabled loading?
                            :on-click (fn [_] (dispatch! {:type :rename/more}))}
              (icon/icon {:icon-name :refresh :size :sm})
              "More")]
           (rename-suggestions dispatch! r)
           (rename-status r))
         (dialog/dialog-footer {}
           [:span {:class ["flex-1"]}]
           (button/button {:variant :ghost :size :sm :on-click (fn [_] (close!))}
             "Cancel")
           (button/button {:variant :primary :size :sm
                           :disabled blank?
                           :on-click (fn [_] (dispatch! {:type :rename/save}))}
             "Save")))))))

(def ^:private fixed-shortcuts
  [["Ctrl+K / Cmd+K" "Command palette"]
   ["Alt" "Palette: hold for key badges, Alt + badge key picks the row"]
   ["Alt+Enter" "Palette, a project's New chat: type a prompt, start the chat in the background"]
   ["Tab" "Message box: expand the snippet word before the cursor"]
   ["Shift+Enter" "Message box: new line"]
   ["Alt+Enter" "Message box, while the agent works: send into the running turn instead of the queue"]
   ["Ctrl+Enter" "On one of your messages: go back to before it and edit it"]])

(defn- keys-row
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

(defn command-source-label
  "Where a command comes from (its xi.commands/specs :source), for the reader."
  [{:keys [kind ext file]}]
  (case kind
    :builtin   "built-in"
    :extension (str "built-in · " (name ext))
    :user      file
    :demo      (str file " · demo")
    :web       "web client"
    nil))

(defn- reference-row
  "A row of the dialog's Commands and Skills tabs: name, description and
   source (on the right, or a `path` on a line of its own); `on-click` nil
   renders it inert."
  [{:keys [key label aliases description source path sub? on-click]}]
  [:li {:replicant/key key}
   [:button (cond-> {:class ["keys-row" "keys-row--action" (when sub? "keys-row--sub")]
                     :type "button"
                     :disabled (nil? on-click)}
              on-click (assoc :on {:click (fn [_] (on-click))}))
    [:span {:class ["keys-row-main"]}
     (into [:span {:class ["keys-row-name"]} label]
           (for [a aliases] [:span {:class ["keys-row-alias"]} (str "/" a)]))
     (when (seq description)
       [:span {:class ["keys-row-desc"]} description])
     (when path
       [:span {:class ["keys-row-path"]} path])]
    (when source
      [:span {:class ["keys-row-source"] :title source} source])]])

(defn- keys-commands-tab
  "Every command the web lists (`web-commands`), subcommands below their
   command; a click runs it as picking it in the '/' list would (one that
   takes an argument goes into the message box). Inert without a chat."
  [state dispatch!]
  (let [room  (state/active-room state)
        chat? (or room (:web/pending-room state))
        dkey  (draft-key state)
        run   (fn [{:keys [name takes-args?]}]
                (when chat?
                  (fn []
                    (dispatch! {:type :keys/close})
                    (if takes-args?
                      (dispatch! {:type :compose/prefill :draft-key dkey :text (str "/" name " ")})
                      (dispatch-command! dispatch! (:id room) name)))))]
    (into [:ul {:class ["keys-list" "keys-list--reference"]}]
          (mapcat (fn [{:keys [name aliases description subcommands source] :as cmd}]
                    (cons (reference-row {:key         name
                                          :label       (str "/" name)
                                          :aliases     aliases
                                          :description description
                                          :source      (command-source-label source)
                                          :on-click    (run cmd)})
                          (for [{sub :name sub-desc :description} subcommands]
                            (reference-row {:key         (str name " " sub)
                                            :label       (str "/" name " " sub)
                                            :description sub-desc
                                            :sub?        true
                                            :on-click    (run {:name (str name " " sub)})}))))
                  (web-commands state)))))

(defn- keys-skills-tab
  "The skills (:web/skill-list, fetched when the dialog opens) with the
   directory each lives in; a click picks it as the palette does."
  [state dispatch!]
  (let [skills (:web/skill-list state)]
    (cond
      (nil? skills)   [:div {:class ["command-loading"]} (spinner)]
      (empty? skills) [:p {:class ["keys-empty"]} "No skills in ~/.config/xi/skills."]
      :else
      (into [:ul {:class ["keys-list" "keys-list--reference"]}]
            (for [{:keys [name description source]} skills]
              (reference-row {:key         name
                              :label       (str "/" name)
                              :description description
                              :path        source
                              :on-click    (fn []
                                             (dispatch! {:type :keys/close})
                                             (dispatch! {:type :skill/select :name name}))}))))))

(defn- keys-tab-label [label n]
  [:span label (when n [:span {:class ["keys-count"]} n])])

(defn- keys-dialog
  "The shortcuts dialog, in tabs: every key of the effective keymap
   (xi.web.keymap/listing) as keycaps, one section per layer, active layers
   first (rows changed in config.edn carry a bar); the slash commands; the
   skills."
  [state dispatch!]
  (when (:web/keys-open? state)
    (let [close!   (fn [] (dispatch! {:type :keys/close}))
          sections (keymap/listing state)
          n-keys   (reduce + (for [{:keys [rows]} sections
                                   {ks :keys :keys [action]} rows
                                   :when action]
                               (count ks)))
          key-sections
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
                                              {:display d})})))]]))]
      (dialog/dialog-overlay
       {:on-close close!
        :class "keys-overlay"
        :attrs {:replicant/key "keys-dialog"}}
       (dialog/dialog-panel {:class "keys-dialog"}
         (dialog/dialog-header {}
           [:h3 "Shortcuts"]
           [:button {:class ["icon-btn" "icon-btn--sm"]
                     :title "Close"
                     :on {:click (fn [_] (close!))}}
            (icon/icon {:icon-name :x :size :md})])
         (dialog/dialog-body {:class "keys-body"}
           (tabs/tabs
            {:variant :line
             :name    "keys-dialog-tab"
             :class   "keys-tabs"
             :tabs    [{:id "keys" :label (keys-tab-label "Keys" n-keys)
                        :content key-sections}
                       {:id "commands" :label (keys-tab-label "Commands" (count (web-commands state)))
                        :content (keys-commands-tab state dispatch!)}
                       {:id "skills" :label (keys-tab-label "Skills" (some-> (:web/skill-list state) count))
                        :content (keys-skills-tab state dispatch!)}]}))
         (dialog/dialog-footer {}
           (button/button
            {:variant :primary :size :sm
             :on-click (fn [_] (close!))}
            "Done")))))))

(defn root-view
  "The app shell: the workspace bar (when there are workspaces) across the
   top, the sidebar layout below it."
  [state dispatch! pages]
  (let [open? (boolean (:web/sidebar-open? state))
        page  (get-in state [:web/route :page])
        bar   (workspace-bar state dispatch!)]
    [:div {:class ["app-shell" (when bar "app-shell--ws")]}
     bar
     (apply sidebar/sidebar-layout
            {:class "sidebar-layout--floating"
             :attrs (cond-> {:style {:flex "1 1 0" :min-height "0"}}
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
            (rename-dialog state dispatch!)
            (workspace-dialog state dispatch!)
            (auth-request-banner state dispatch!)
            (auth-overlay state)
            ;; extension overlays (xi.web.overlays), over every page
            (overlays/views state dispatch!))]))