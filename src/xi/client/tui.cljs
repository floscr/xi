(ns xi.client.tui
  "Standalone TUI client — a renderer + input layer over app state.

   Render is a function of the active room's state: history entries map to
   memoized component blocks (xi.client.view), the bottom panel follows
   room :ui (:menu → completion menu, else editor), the visible buffer
   follows :ui :active-buffer. Input never mutates components directly —
   the editor and menus only dispatch events.

   The one piece of contained mutation is `ctx`: runtime resources
   (terminal, component refs, block cache, last dispatch!/state for input
   handlers). It mirrors app state, it never owns it — the anti-pattern
   this replaces is master's client/tui.cljs with ~30 feature atoms.

   The renderer emits :render/start / :render/done (with :duration-ms of
   the component-tree sync; the terminal paint itself is debounced inside
   xi.tui.core). Both are state-neutral events — they land in the event
   ring without scheduling another render.

   Effects owned by the TUI: :app/quit, :app/reload, :clipboard/copy."
  (:require [clojure.string :as str]
            [xi.client.view :as view]
            [xi.commands :as commands]
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.palette :as palette]
            [xi.session :as session]
            [xi.tui.ansi :as ansi]
            [xi.tui.clipboard-image :as clip-image]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.core :as tui]
            [xi.tui.diff-buffer :as diff-buffer]
            [xi.tui.editor :as editor]
            [xi.tui.history-selector :as history-selector]
            [xi.tui.pager :as pager]
            [xi.tui.path-complete :as path-complete]
            [xi.tui.terminal :as term]
            [xi.tui.word-complete :as word-complete]))

;; ── Key detection (for dialogs / ext keybindings) ────────────────────────────

(def ^:private ESC (str (char 27)))

(defn- enter? [d] (or (= d "\r") (= d "\n")))
(defn- escape? [d] (or (= d ESC) (= d (str ESC "[27u"))))

(def ^:private key-sequences
  "Named ext keybindings → the raw input sequences that trigger them
   (legacy + kitty CSI-u encodings). Extend as extensions need keys."
  {"alt+r"        #{(str ESC "r") (str ESC "[114;3u")}
   "alt+p"        #{(str ESC "p") (str ESC "[112;3u")}
   "ctrl+shift+n" #{(str ESC "[110;6u")}
   "ctrl+shift+p" #{(str ESC "[112;6u")}
   "ctrl+o"       #{(str (char 15)) (str ESC "[111;5u")}
   "ctrl+c"       #{(str (char 3))}})

(def ^:private builtin-keybindings
  "Core (non-extension) editor keybindings, wired the same way as ext
   keybindings. ctrl+o toggles full/preview rendering of the system-prompt
   buffer, gated so the key falls through to the editor elsewhere."
  [{:key   "ctrl+o"
    :event {:type :ui/prompt-toggle}
    :when  (fn [st] (= :prompt (get-in (state/active-room st)
                                       [:ui :active-buffer])))}])

(defn- ->editor-keybindings
  "Translate ext keybindings ({:key :event :when}) into editor bindings
   ({:key-fn :handler}). :when (if present) gates on full app state and is
   folded into :key-fn so a guarded binding (e.g. dictation's Ctrl+C, only
   active while recording) lets the key fall through to the editor's own
   handler when the guard fails. :event is dispatched with the active
   room's :room-id added."
  [keybindings get-state dispatch!]
  (vec (keep (fn [{:keys [key event] pred :when}]
               (when-let [seqs (key-sequences key)]
                 {:key-fn  (fn [data]
                            (and (contains? seqs data)
                                 (let [st (get-state)]
                                   (and (state/active-room st)
                                        (or (nil? pred) (pred st))))))
                  :handler (fn []
                             (let [st (get-state)
                                   room (state/active-room st)]
                               (when room
                                 (dispatch! (assoc event :room-id (:id room))))))}))
             keybindings)))

;; ── Lifecycle ────────────────────────────────────────────────────────────────

(defn- shutdown! [on-exit]
  (term/restore-stdout!)
  (tui/stop-tui!)
  (when on-exit (on-exit))
  (js/process.exit 0))

(defn- strip-arg-pair
  "Drop every occurrence of `flag` and its following value from an argv vector."
  [argv flag]
  (loop [in (seq argv) out []]
    (if-not in
      out
      (if (= (first in) flag)
        (recur (nnext in) out)
        (recur (next in) (conj out (first in)))))))

(defn- reload!
  "Restart the process with the same argv, picking up recompiled code.
   Passes the current session id as `--session <sid>` so the new process
   resumes it (replacing any stale --session already on the command line)."
  [session-id on-exit]
  (let [child-process (js/require "child_process")
        argv (-> (vec (js->clj js/process.argv))
                 (strip-arg-pair "--session")
                 (cond-> session-id (into ["--session" session-id])))]
    (term/restore-stdout!)
    (tui/stop-tui!)
    (when on-exit (on-exit))
    (.execFileSync child-process (first argv) (clj->js (rest argv))
                   #js {:stdio "inherit"})
    (js/process.exit 0)))

(defn- copy-to-clipboard!
  "Copy text to the system clipboard via OSC 52."
  [text]
  (when (seq text)
    (let [b64 (.toString (js/Buffer.from text "utf-8") "base64")]
      (term/write! (str "\033]52;c;" b64 "\007")))))

;; ── Menus (room :ui :menu → completion menu) ─────────────────────────────────

(def ^:private menu-spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- build-loading-menu
  "Spinner panel shown while an async menu frame fetches its items (e.g. the
   model list). Self-animates via its own timer (:start/:stop are driven by
   sync-bottom-panel!, since focus-panel! has no lifecycle). Esc pops back."
  [{:keys [prompt]} room-id dispatch!]
  (let [st    (atom {:frame 0 :timer nil})
        label (or prompt "> ")]
    {:type :loading-menu
     :start (fn []
              (when-not (:timer @st)
                (let [t (js/setInterval
                         (fn []
                           (swap! st update :frame inc)
                           (tui/request-render!))
                         80)]
                  (swap! st assoc :timer t))))
     :stop (fn []
             (when-let [t (:timer @st)]
               (js/clearInterval t)
               (swap! st assoc :timer nil)))
     :invalidate (fn [])
     :handle-input (fn [data]
                     (when (escape? data)
                       (dispatch! {:type :ui/menu-pop :room-id room-id})))
     :render (fn [_width]
               (let [n     (count menu-spinner-frames)
                     spin  (nth menu-spinner-frames (mod (:frame @st) n))]
                 [(str (ansi/fg :accent spin) " "
                       (ansi/fg :dim label)
                       (ansi/fg :dim "loading…"))]))}))

(defn- session-status-fn
  "Live status lookup for menu items that carry a :session-id (e.g. the palette
   Chats section, /resume). Reads the lobby mirror fresh on every call so a menu
   item animates a spinner while that session's agent is running — mirroring the
   web session cards. Returns nil for items without a :session-id."
  [get-state]
  (fn [item]
    (when-let [sid (:session-id item)]
      (let [rooms (get-in (get-state) [:lobby :rooms])
            match? (fn [pred] (boolean (some (fn [r] (and (= (:session-id r) sid) (pred r)))
                                             rooms)))]
        {:busy?       (match? :busy?)
         :has-dialog? (match? :has-dialog?)}))))

(defn- build-completion-menu
  "Completion menu component from a menu description:
     {:id kw :prompt str :items [...] :alt-items [...] :tab-labels [...]}
   Items carry {:label :description :event | :drill} — an :event item is
   terminal (closes the menu then dispatches), a :drill item pushes a
   sub-view frame and keeps the palette open. :alt-items adds a Tab-switched
   second item set (e.g. /resume current-folder vs all).
   :key-bindings — vec of {:key str :event map :selected? bool}. When :key
   matches, closes the menu and dispatches the event (with :room-id merged).
   When :selected? is true, the currently selected item is merged into the
   event under :selected."
  [{:keys [prompt items alt-items tab-labels key-bindings search-field freeform]} room-id dispatch! get-state]
  (let [;; Tab state is interaction-local (like the menu's filter query) —
        ;; it lives in the component, not in app state.
        tab #js {:alt false}
        close! (fn [] (dispatch! {:type :ui/menu-close :room-id room-id}))
        back!  (fn [] (dispatch! {:type :ui/menu-pop :room-id room-id}))
        ;; Event-dispatching key-bindings from menu descriptor
        menu-kbs (mapv (fn [{:keys [key event selected?]}]
                         {:key-fn  (fn [data] (= data key))
                          :handler (fn [state _update-items!]
                                     (let [evt (cond-> (assoc event :room-id room-id)
                                                 selected?
                                                 (merge (let [{:keys [filtered selected]} @state]
                                                          (when (seq filtered)
                                                            {:selected (nth filtered selected)}))))]
                                       (close!)
                                       (dispatch! evt)))}) key-bindings)
        ;; Alt-items tab-switch key-bindings
        alt-kbs (when (seq alt-items)
                  [{:key-fn (fn [data] (= data "	"))
                    :handler (fn [_state update-items!]
                               (set! (.-alt tab) (not (.-alt tab)))
                               (update-items! (if (.-alt tab) alt-items items)))}])
        all-kbs (into (vec menu-kbs) alt-kbs)
        opts (cond-> {:items items
                      :prompt (or prompt "> ")
                      :on-select (fn [item]
                                   ;; :drill items open a sub-view (push a
                                   ;; frame) and keep the palette open; :event
                                   ;; items are terminal — close then run.
                                   (if-let [drill (:drill item)]
                                     (dispatch! drill)
                                     (do (close!)
                                         (when-let [event (:event item)]
                                           (dispatch! event)))))
                      ;; Esc pops one drill frame (or closes at the root).
                      :on-cancel back!}
               ;; Freeform menu (slash commands): Enter with no match runs the
               ;; raw typed text as a command; Esc restores it (with its '/')
               ;; to the editor instead of discarding it.
               freeform
               (assoc :on-submit-query
                      (fn [query]
                        (close!)
                        (dispatch! {:type :input/submit :room-id room-id
                                    :text (str "/" query)}))
                      :on-cancel-query
                      (fn [query]
                        (close!)
                        (dispatch! {:type :editor/insert :room-id room-id
                                    :text (str "/" query)})))
               search-field
               (assoc :search-field search-field
                      :search-enrich-fn
                      (fn [items]
                        (mapv (fn [item]
                                (if (:search-text item)
                                  item
                                  (assoc item :search-text
                                         (session/build-search-text (:summary item)))))
                              items)))
               (seq alt-items)
               (assoc :header-fn
                      (fn []
                        (let [[a b] (or tab-labels ["A" "B"])
                              alt? (.-alt tab)]
                          (str "  "
                               (if alt?
                                 (str (ansi/fg :dim (str "○ " a)) (ansi/fg :dim " | ")
                                      (ansi/fg :accent (str "◉ " b)))
                                 (str (ansi/fg :accent (str "◉ " a)) (ansi/fg :dim " | ")
                                      (ansi/fg :dim (str "○ " b))))
                               (ansi/fg :dim "  (Tab to switch)")))))
               (seq all-kbs)
               (assoc :key-bindings all-kbs)
               ;; Only enable the live status slot when the menu actually
               ;; carries session items (palette Chats, /resume) — otherwise
               ;; the reserved slot would needlessly indent session-less menus
               ;; (commands, model list).
               (and get-state (some :session-id (concat items alt-items)))
               (assoc :status-fn (session-status-fn get-state)))]
    (completion/make-completion-menu opts)))

(defn- build-menu
  "Build the bottom-panel component for a menu descriptor: a spinner panel
   while :loading?, otherwise the completion menu."
  [menu room-id dispatch! get-state]
  (if (:loading? menu)
    (build-loading-menu menu room-id dispatch!)
    (build-completion-menu menu room-id dispatch! get-state)))

(defn- palette-action-event
  "Map a shared xi.palette action to the TUI event that runs it. Every action
   resolves to a :command/run — the same command a user could type — so the
   Ctrl+/ palette and the web Cmd/K palette stay in sync (see xi.web.views for
   the web mapping of the same actions)."
  [room-id action]
  (let [run (fn [name & [args]]
              (cond-> {:type :command/run :room-id room-id :name name}
                args (assoc :args args)))]
    (case (first action)
      :chat    (run "resume" (str "id:" (second action)))
      :command (let [{:keys [name args]} (commands/parse-input (str "/" (second action)))]
                 (run name args))
      :action  (case (second action)
                 :new-chat     (run "new")
                 :change-model (run "model")
                 :skills       (run "skill")
                 :git-status   (run "diff" "git")
                 :copy-debug   (run "debug")
                 :reload       (run "reload")))))

(defn- full-command-list
  "The curated palette commands (ordering, descriptions, subcommand
   expansions) followed by every other assembly command not in the curated
   set — the TUI shows ALL commands (built-ins + extensions, e.g. /reload,
   /gtd, /skill), while the web palette sticks to the curated subset."
  [cmd-list]
  (let [curated-names (into #{} (map :name) palette/palette-commands)]
    (into (vec palette/palette-commands)
          (comp (remove (comp curated-names :name))
                (map #(select-keys % [:name :description])))
          cmd-list)))

(defn- commands-menu
  "Slash-commands menu (Ctrl+/ and '/' on an empty editor): the full command
   list — the curated shared entries first (matching the web '/' suggestions),
   then every remaining assembly command — as a flat menu, no sections."
  [room-id cmd-list]
  {:id :commands
   :prompt "/"
   :freeform true
   :items (mapv (fn [{:keys [name description]}]
                  {:label (str "/" name)
                   :description description
                   :event (palette-action-event room-id [:command name])})
                (palette/expand-commands (full-command-list cmd-list)))})

;; Commands/actions whose selection opens a sub-picker: in the palette they
;; drill in-place (push a frame, keep the palette open) instead of closing.
(def ^:private palette-drill-commands #{"model" "resume" "sessions" "favorites"})
(def ^:private palette-drill-actions  #{:change-model :skills})

(defn- palette-menu
  "Command palette (Ctrl+P): shared sections — Chats, Actions, Commands — from
   xi.palette, each item mapped to a TUI :command/run event. Section headings
   show only at an empty query (xi.tui.completion drops them once you type).
   Picker-opening entries become :drill items so they open a sub-view within
   the palette (with Esc back-nav) rather than closing it."
  [state room-id cmd-list]
  (let [room?   (boolean room-id)
        cur-sid (get-in state [:rooms room-id :session :id])
        chats   (->> (palette/recent-sessions state)
                     (remove #(= cur-sid (:session-id %)))
                     (take 8))
        heading (fn [label] {:heading label})
        chat-items    (map (fn [s]
                             {:label (or (:name s) "New session")
                              :session-id (:session-id s)
                              :event (palette-action-event room-id [:chat (:session-id s)])})
                           chats)
        action-items  (map (fn [{:keys [key label]}]
                             (let [evt (palette-action-event room-id [:action key])]
                               (if (palette-drill-actions key)
                                 {:label label :drill evt}
                                 {:label label :event evt})))
                           (palette/actions room?))
        command-items (map (fn [{:keys [name description]}]
                             (let [evt (palette-action-event room-id [:command name])]
                               (cond-> {:label (str "/" name) :description description}
                                 (palette-drill-commands name) (assoc :drill evt)
                                 (not (palette-drill-commands name)) (assoc :event evt))))
                           (palette/expand-commands (full-command-list cmd-list)))]
    {:id :palette
     :prompt "palette> "
     :items (vec (concat (when (seq chat-items) (cons (heading "Chats") chat-items))
                         (cons (heading "Actions") action-items)
                         (when (seq command-items) (cons (heading "Commands") command-items))))}))

;; ── Dialogs (room :ui :dialogs → focused bottom-panel component) ──────────────

(defn- build-confirm-dialog
  "A y/n confirm dialog rendered as a bordered box that sits above the prompt
   line. Enter = yes, Esc = no. The message word-wraps to the terminal width so
   long guarded commands no longer overflow and corrupt the layout, and the
   editor stays visible below the dialog for context."
  [{:keys [message prompt]} respond! editor]
  (let [text (or message prompt "Confirm?")]
    {:type :dialog
     :render (fn [width]
               (let [wrapped (ansi/wrap-text text (max 1 (- width 2)))
                     box (into [(ansi/fg :border (apply str (repeat width "─")))]
                               (concat
                                (map #(str "  " %) wrapped)
                                [(str "  " (ansi/fg :accent "[y]") "es   "
                                      (ansi/fg :accent "[n]") "o   "
                                      (ansi/fg :dim "(Enter=yes, Esc=no)"))
                                 ""]))]
                 (into box (when editor ((:render editor) width)))))
     :handle-input (fn [data]
                     (cond
                       (#{"y" "Y"} data) (respond! true)
                       (#{"n" "N"} data) (respond! false)
                       (enter? data)     (respond! true)
                       (escape? data)    (respond! false)
                       :else nil))}))

(defn- build-cwd-select-dialog
  "Missing-working-directory recovery dialog. Renders the concrete (string)
   options as a numbered list; a digit key answers with that path, Esc
   cancels (answers nil). The :custom sentinel option is omitted — typing a
   path isn't supported from the TUI; the web client handles that case."
  [{:keys [message options]} respond!]
  (let [choices (filterv #(string? (:value %)) options)]
    {:type :dialog
     :render (fn [width]
               (into [(ansi/fg :border (apply str (repeat width "─")))
                      (str "  " (or message "Choose a working directory:"))]
                     (concat
                      (map-indexed
                       (fn [i {:keys [label]}]
                         (str "  " (ansi/fg :accent (str "[" (inc i) "]")) " " label))
                       choices)
                      [(str "  " (ansi/fg :dim "(number=select, Esc=cancel)"))])))
     :handle-input (fn [data]
                     (cond
                       (escape? data) (respond! nil)
                       (re-matches #"[0-9]" data)
                       (let [idx (dec (js/parseInt data 10))]
                         (when-let [{:keys [value]} (get choices idx)]
                           (respond! value)))
                       :else nil))}))

(defn- build-select-dialog
  "A generic single-choice dialog. Renders `options` (each {:label :value})
   as a numbered list; a digit key answers with that option's value, Esc
   cancels (answers nil). Values may be any type."
  [{:keys [message options]} respond!]
  {:type :dialog
   :render (fn [width]
             (into [(ansi/fg :border (apply str (repeat width "─")))
                    (str "  " (or message "Choose:"))]
                   (concat
                    (map-indexed
                     (fn [i {:keys [label]}]
                       (str "  " (ansi/fg :accent (str "[" (inc i) "]")) " " label))
                     options)
                    [(str "  " (ansi/fg :dim "(number=select, Esc=cancel)"))])))
   :handle-input (fn [data]
                   (cond
                     (escape? data) (respond! nil)
                     (re-matches #"[0-9]" data)
                     (let [idx (dec (js/parseInt data 10))]
                       (when-let [choice (get (vec options) idx)]
                         (respond! (:value choice))))
                     :else nil))})

(defn- build-dialog
  "A focused component for the active dialog. Dispatches the answer via
   :ui/dialog-response, which the dialog owner (xi.ext.core/create-dialogs)
   resolves. :cwd-select and :select offer a numbered list; everything else
   is a y/n confirm."
  [{:keys [id type] :as dialog} room-id dispatch! editor]
  (let [respond! (fn [value]
                   (dispatch! {:type :ui/dialog-response
                               :room-id room-id :dialog-id id :value value})
                   ;; The removal must reach the mirror: in client mode the
                   ;; :ui/dialog-response handler is a no-op on remote echoes,
                   ;; so dispatch :ui/dialog-close (a core handler) to actually
                   ;; clear the live dialog on every client. Without this the
                   ;; dialog stays focused and swallows input after answering.
                   (dispatch! {:type :ui/dialog-close
                               :room-id room-id :dialog-id id}))]
    (case type
      :cwd-select (build-cwd-select-dialog dialog respond!)
      :select     (build-select-dialog dialog respond!)
      (build-confirm-dialog dialog respond! editor))))

;; ── Render sync helpers ──────────────────────────────────────────────────────

(defn sync-history!
  "Reconcile the block cache (ctx.blocks, a JS array of
   #js {:entry e :block {:nodes :update!}}) against the room history.
   Returns true when anything changed."
  [^js ctx history]
  (let [blocks (.-blocks ctx)
        n (count history)]
    (loop [i 0 changed? (> (.-length blocks) n)]
      (if (>= i n)
        (do (when (> (.-length blocks) n)
              (.splice blocks n))
            changed?)
        (let [entry (nth history i)
              ^js cached (when (< i (.-length blocks)) (aget blocks i))]
          (cond
            (and cached (identical? (.-entry cached) entry))
            (recur (inc i) changed?)

            (and cached ((:update! (.-block cached)) (.-entry cached) entry))
            (do (set! (.-entry cached) entry)
                (recur (inc i) true))

            :else
            (do (.splice blocks i)
                (doseq [e (subvec (vec history) i)]
                  (.push blocks #js {:entry e :block (view/entry->block e)}))
                true)))))))

(defn- loader-visible?
  "Show the thinking loader while busy, unless the trailing entry already
   animates itself (open text stream or a running tool's spinner)."
  [room]
  (and (get-in room [:agent :busy?])
       (let [last-entry (peek (:history room))]
         (not (or (and (= :text (:kind last-entry)) (not (:done? last-entry)))
                  (and (= :tool-call (:kind last-entry))
                       (= :running (:status last-entry))))))))

(defn- sync-chat! [^js ctx room loader]
  (let [history-changed? (sync-history! ctx (:history room))
        show-loader? (loader-visible? room)
        loader-toggled? (not= show-loader? (.-loaderShown ctx))]
    (when loader-toggled?
      (set! (.-loaderShown ctx) show-loader?)
      (if show-loader? ((:start loader)) ((:stop loader))))
    (when (or history-changed? loader-toggled? (.-chatDirty ctx))
      (set! (.-chatDirty ctx) false)
      (reset! (:children (.-chat ctx))
              (-> (vec (.-header ctx))
                  (into (mapcat #(:nodes (.-block ^js %))) (vec (.-blocks ctx)))
                  (cond-> show-loader? (conj loader)))))))

;; ── Prompt navigation (Alt+j / Alt+k) ────────────────────────────────────────

(defn- measure-nodes
  "Total rendered line count of a seq of TUI nodes at `width`."
  [nodes width]
  (reduce (fn [acc n] (+ acc (count ((:render n) width)))) 0 nodes))

(defn- prompt-anchor-lines
  "Content line indices (0 = top) where each :user prompt block begins, oldest
   first. Mirrors sync-chat!'s child order: header nodes ++ (mapcat :nodes
   blocks), so the indices line up with the rendered content lines."
  [^js ctx width]
  (let [blocks (.-blocks ctx)]
    (loop [i 0
           line (measure-nodes (.-header ctx) width)
           acc []]
      (if (>= i (.-length blocks))
        acc
        (let [^js b (aget blocks i)
              h (measure-nodes (:nodes (.-block b)) width)
              acc' (if (= :user (:kind (.-entry b))) (conj acc line) acc)]
          (recur (inc i) (+ line h) acc'))))))

(defn- jump-to-prompt!
  "Scroll to the nearest :user prompt above (:prev) / below (:next) the current
   viewport top. Stateless — like the web client's cross-history prompt nav.
   Only acts in the chat buffer (logs/pager have their own scroll handling)."
  [^js ctx dir]
  (let [room (some-> (.-state ctx) state/active-room)]
    (when (= :chat (get-in room [:ui :active-buffer] :chat))
      (let [anchors (prompt-anchor-lines ctx (term/columns))]
        (when (seq anchors)
          (let [top (tui/viewport-top-line)
                target (case dir
                         :prev (last (filter #(< % top) anchors))
                         :next (first (filter #(> % top) anchors)))]
            (when target
              (tui/scroll-line-to-top! target))))))))

(defn- pager-view!
  "Focused pager component for a buffer, cached on the buffer value's identity
   (a /diff with new output replaces it; reopening via /buffers reuses it).
   Git diffs (:diff?) get the interactive unified-diff viewer; other buffers
   (difft output, plain text) get the generic text pager, with the buffer's
   :engine renderer applied. Building a fresh component grabs focus and
   scrolls to the top."
  [^js ctx buf room-id dispatch!]
  (when-not (identical? buf (.-pagerVal ctx))
    (let [on-close (fn [] (dispatch! {:type :ui/buffer-switch
                                      :room-id room-id :buffer-id :chat}))
          on-command-mode (fn [] (tui/set-focus! (.-editor ctx)))
          ;; e: ask the room to explain the selected region. Diff buffers
          ;; fence the snippet as ```diff so the model reads it as a hunk.
          on-explain
          (fn [text]
            (let [prompt (if (:diff? buf)
                           (str "Explain the following changes from our "
                                "session in plain language — what they do "
                                "and why:\n\n```diff\n" text "\n```")
                           (str "Explain the following in plain language:"
                                "\n\n" text))]
              (dispatch! {:type :input/submit :room-id room-id :text prompt})
              (on-close)))
          ;; Enter: drop the selected region (plus a trailing newline) into
          ;; the chat editor, then return to chat with the editor focused.
          on-prompt
          (fn [text]
            (when-let [ins (:insert-text (.-editor ctx))]
              (ins (str text "\n")))
            (on-close))
          c (if (:diff? buf)
              (diff-buffer/make-diff-buffer
               {:diff-text (:text buf) :title (:title buf)
                :on-close on-close :on-command-mode on-command-mode
                :on-explain on-explain :on-prompt on-prompt})
              (pager/make-text-buffer
               {:text (view/buffer-display-text buf) :title (:title buf)
                :on-close on-close :on-command-mode on-command-mode
                :on-explain on-explain :on-prompt on-prompt}))]
      (set! (.-pagerVal ctx) buf)
      (set! (.-pagerComp ctx) c)
      (tui/set-focus! c)
      (tui/scroll-to-offset! 999999)))
  (.-pagerComp ctx))

(defn- pager-buffer?
  "A buffer that should be shown in a focused pager (scroll keybindings +
   help toolbar): the diff/difft buffers, identified by their :engine key.
   The system-prompt buffer stays on the static view so ctrl+o (an editor
   keybinding) keeps working; :chat and :logs have their own views."
  [buf]
  (contains? buf :engine))

(defn- sync-view!
  "Point the view wrapper at the active buffer (:chat is the persistent
   chat container; logs/other buffers are rebuilt from state each pass).
   Pager buffers get the focused viewer, which takes focus while open."
  [^js ctx room ring dispatch!]
  (let [active (get-in room [:ui :active-buffer] :chat)
        switched? (not= active (.-activeBuffer ctx))]
    (when (or switched? (not= active :chat))
      (let [buf (get-in room [:ui :buffers active])
            pager? (pager-buffer? buf)
            target (cond
                     (= active :chat) (.-chat ctx)
                     (= active :logs) (view/logs-view (log/entries ring) (:id room))
                     pager? (pager-view! ctx buf (:id room) dispatch!)
                     buf (view/buffer-view buf)
                     :else (.-chat ctx))]
        (set! (.-activeBuffer ctx) active)
        (reset! (:children (.-viewWrapper ctx)) [target])
        ;; Focus/scroll transitions in and out of the focused pager
        (when switched?
          (cond
            pager? (do (tui/set-focus! target)
                       (tui/scroll-to-offset! 999999))
            (.-wasPager ctx) (do (tui/set-focus! (.-editor ctx))
                                 (tui/scroll-to-offset! 0)))
          (set! (.-wasPager ctx) pager?))))))

(defn- focus-panel!
  "Wrap a focused component in a spacer'd container and install it as the
   bottom panel."
  [comp]
  (let [panel (tui/make-container)]
    ((:add-child panel) (comp/make-spacer 1))
    ((:add-child panel) comp)
    (tui/set-bottom-panel! panel)
    (tui/set-focus! comp)))

(defn- build-history-selector
  "Instantiate the interactive history selector for /tree."
  [room dispatch!]
  (history-selector/make-history-selector
   {:history (:history room)
    :max-visible (max 10 (- (term/rows) 10))
    :on-select
    (fn [index mode]
      (let [history (:history room)
            entry (nth history index)
            is-user? (= :user (:kind entry))
            ;; :edit on a user msg → fork before it, text goes to editor
            ;; :navigate on a user msg → include everything up to next user or end
            nav-index (cond
                        (and is-user? (= mode :edit))     index
                        (and is-user? (= mode :navigate))
                        (let [next-user (first (keep-indexed
                                                (fn [i e] (when (and (> i index)
                                                                     (= :user (:kind e)))
                                                            i))
                                                history))]
                          (or next-user (count history)))
                        :else (inc index))
            editor-text (when (and is-user? (= mode :edit)) (:text entry))]
        (dispatch! {:type :tree/navigate :room-id (:id room)
                    :index nav-index :mode mode :editor-text editor-text})))
    :on-cancel
    (fn [] (dispatch! {:type :tree/close :room-id (:id room)}))}))

(defn- sync-bottom-panel!
  "Bottom-panel priority: dialog > menu > tree > pager help-bar > editor.
   The pager help-bar is the active pager component's own :help toolbar.
   Rebuilds only when the selected target identity changes."
  [^js ctx room dispatch!]
  (let [dialog (first (get-in room [:ui :dialogs]))
        menu   (get-in room [:ui :menu])
        tree?  (boolean (get-in room [:ui :tree-open?]))
        active (get-in room [:ui :active-buffer] :chat)
        buf    (get-in room [:ui :buffers active])
        pager? (pager-buffer? buf)
        target (or dialog menu (when tree? :tree)
                   (when pager? (.-pagerComp ctx)))]
    (when-not (identical? target (.-panelVal ctx))
      ;; Stop any spinner timer started for the panel we're replacing.
      (when-let [stop (.-panelStop ctx)] (stop) (set! (.-panelStop ctx) nil))
      (set! (.-panelVal ctx) target)
      (cond
        dialog (focus-panel! (build-dialog dialog (:id room) dispatch! (.-editor ctx)))
        menu   (let [c (build-menu menu (:id room) dispatch! #(.-state ctx))]
                 (focus-panel! c)
                 (when-let [start (:start c)]
                   (start)
                   (set! (.-panelStop ctx) (:stop c))))
        tree?  (focus-panel! (build-history-selector room dispatch!))
        pager? (tui/set-bottom-panel! (comp/make-text (or (:help (.-pagerComp ctx)) "")))
        :else  (do (tui/set-bottom-panel! (.-editor ctx))
                   (tui/set-focus! (.-editor ctx)))))))

;; ── Client ───────────────────────────────────────────────────────────────────

(defn- shorten-home [path]
  (let [home (aget js/process.env "HOME")]
    (if (and path home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn- render-status-banner!
  "Render a connection/pairing status banner when a client has no room to show
   yet — still connecting, awaiting pairing approval, or denied. Keyed on the
   auth state so it only rebuilds on change, and clears :roomId so the normal
   room view rebuilds cleanly once a room finally joins."
  [^js ctx auth]
  (let [k [(:status auth) (:code auth)]]
    (when (not= k (.-authKey ctx))
      (set! (.-authKey ctx) k)
      (set! (.-roomId ctx) nil)
      (set! (.-headerKey ctx) nil)
      (set! (.-header ctx) #js [])
      (set! (.-panelVal ctx) nil)
      (reset! (:children (.-chat ctx)) (view/status-banner auth))
      (tui/set-bottom-panel! (.-editor ctx))
      (tui/set-focus! (.-editor ctx)))))

(defn create!
  "Boot the terminal UI. Returns
     {:render  (fn [state dispatch!])   ;; for app :on-render
      :effects {...}}                   ;; TUI-owned effect handlers

   opts:
     :ring         event ring buffer (feeds the logs buffer)
     :on-exit      (fn []) — flush hooks before process exit (quit/reload)
     :commands     command list for the menus (built-ins + ext commands;
                   merged after the curated palette entries)
     :prompt-badge (fn [state] → str) — extra prompt badge (ext indicators)
     :keybindings  ext keybindings ([{:key :event :when}]) wired into the
                   editor; :event dispatched with the active :room-id"
  [{:keys [ring on-exit commands prompt-badge keybindings]}]
  (let [content (tui/create-tui!)
        chat (tui/make-container)
        view-wrapper (tui/make-container)
        loader (comp/make-loader "thinking...")
        ctx #js {:dispatch nil :state nil
                 :chat chat :viewWrapper view-wrapper :editor nil
                 :roomId nil :blocks #js [] :header [] :headerKey nil :chatDirty true
                 :authKey nil
                 :panelVal nil :activeBuffer :chat
                 :pagerVal nil :pagerComp nil :wasPager false
                 :loaderShown false}
        dispatch! (fn [event] (when-let [d (.-dispatch ctx)] (d event)))
        get-state (fn [] (.-state ctx))
        current-room (fn [] (some-> (.-state ctx) state/active-room))
        room-event (fn [event] (when-let [room (current-room)]
                                 (dispatch! (assoc event :room-id (:id room)))))
        ext-keybindings (->editor-keybindings (into builtin-keybindings (vec keybindings))
                                              get-state dispatch!)

        editor-comp
        (editor/make-editor
         {:prompt "xi> "
          :on-submit (fn [text]
                       (when-let [room (current-room)]
                         (dispatch! {:type :input/submit :room-id (:id room) :text text})))
          :on-escape (fn []
                       (let [room (current-room)
                             active (get-in room [:ui :active-buffer] :chat)]
                         (if (not= active :chat)
                           ;; Non-chat buffer open → return to chat
                           (dispatch! {:type :ui/buffer-switch
                                       :room-id (:id room) :buffer-id :chat})
                           ;; Chat → abort agent if busy
                           (when (get-in room [:agent :busy?])
                             (room-event {:type :agent/abort})))))
          :on-interrupt (fn [] (shutdown! on-exit))
          :on-palette (fn []
                        (when-let [room (current-room)]
                          (dispatch! {:type :ui/menu-open :room-id (:id room)
                                      :menu (palette-menu (get-state) (:id room) commands)})))
          :on-commands (fn []
                         (when-let [room (current-room)]
                           (dispatch! {:type :ui/menu-open :room-id (:id room)
                                       :menu (commands-menu (:id room) commands)})))
          :ext-keybindings ext-keybindings
          :on-git (fn [] (tui/run-external! ["ngit"] {}))
          :on-paste-image
          (fn []
            (if-let [img (clip-image/read-clipboard-image)]
              (room-event {:type :ui/attach-image :image img :label "clipboard"})
              (room-event {:type :ui/status :text "No image in clipboard"})))
          ;; Completion order: snippets (handled in the editor) → folder paths
          ;; (only when the token carries a '/') → buffer words. Words are
          ;; completed inline: Tab cycles candidates (:word-candidates-fn),
          ;; Ctrl+I opens the ranked menu (:on-word-menu). :on-tab-complete
          ;; handles path/folder completion only.
          :on-tab-complete
          (fn [{:keys [token insert!]}]
            (when-let [room (current-room)]
              (when (str/includes? token "/")
                (let [result (path-complete/complete token (:cwd room))]
                  (case (:action result)
                    :insert (when (seq (:text result)) (insert! (:text result)))
                    :menu   (dispatch! {:type :ui/menu-open :room-id (:id room)
                                        :menu {:id :path-complete
                                               :prompt "path> "
                                               :items (mapv (fn [{:keys [label insert]}]
                                                              {:label label
                                                               :event {:type :editor/insert
                                                                       :room-id (:id room)
                                                                       :text insert}})
                                                            (:items result))}})
                    nil)))))
          :word-candidates-fn
          (fn [token]
            (when-let [room (current-room)]
              ;; Words only — path tokens (with '/') route to :on-tab-complete.
              (when-not (str/includes? token "/")
                (word-complete/candidates token (:history room)))))
          :on-word-menu
          (fn [{:keys [token]}]
            (when-let [room (current-room)]
              (let [words (word-complete/candidates token (:history room))]
                (when (seq words)
                  (dispatch! {:type :ui/menu-open :room-id (:id room)
                              :menu {:id :word-complete
                                     :prompt "word> "
                                     :items (mapv (fn [w]
                                                    {:label w
                                                     :event {:type :editor/insert
                                                             :room-id (:id room)
                                                             :text (subs w (count token))}})
                                                  words)}})))))
          :prompt-suffix-fn
          (fn []
            (let [room  (current-room)
                  n     (count (get-in room [:ui :pending-images]))
                  qn    (count (get-in room [:agent :queued]))
                  badge (when prompt-badge (prompt-badge (.-state ctx)))]
              (str (when (pos? n) (ansi/fg :accent (str " 📎" n)))
                   (when (pos? qn) (ansi/fg :accent (str " ⏳" qn " queued")))
                   (when (seq badge) badge))))
          :prompt-right-fn
          (fn []
            (when-let [cwd (:cwd (current-room))]
              (let [client? (= :client (state/mode (.-state ctx)))
                    ;; nil (pre-connect) counts as connected to avoid flicker;
                    ;; only an explicit drop shows the reconnecting indicator.
                    dropped? (false? (:client/connected? (.-state ctx)))]
                (str (ansi/fg :dim (shorten-home cwd))
                     (when client?
                       (if dropped?
                         (str " " (ansi/fg :yellow "●") (ansi/fg :dim " reconnecting"))
                         (str " " (ansi/fg :green "●"))))))))})

        render
        (fn [state dispatch!]
          (set! (.-dispatch ctx) dispatch!)
          (set! (.-state ctx) state)
          (dispatch! {:type :render/start})
          (let [t0 (js/Date.now)
                auth (:client/auth state)
                ;; Optimistic prompt echo: append the just-submitted user
                ;; message to the active room and force the thinking loader, so
                ;; the prompt shows instantly before the server round-trips the
                ;; real :user entry back. The entry object is stable in state
                ;; between :client/optimistic-set and -clear, so the block cache
                ;; only rebuilds when it appears/disappears (see cli's tap).
                opt  (:client/optimistic state)
                room (let [r (state/active-room state)]
                       (if (and r opt)
                         (-> r
                             (update :history (fnil conj []) opt)
                             (assoc-in [:agent :busy?] true))
                         r))]
            (cond
              ;; Client handshake with no room yet: waiting for pairing
              ;; approval, denied, or still connecting. Show a status banner
              ;; instead of a blank, unresponsive screen.
              (or (#{:pending :denied} (:status auth))
                  (and (= :client (state/mode state)) (nil? room)))
              (do (render-status-banner! ctx (or auth {:status :connecting}))
                  (tui/request-render!))

              room
              (do
              (set! (.-authKey ctx) nil)
              (when (not= (:id room) (.-roomId ctx))
                ;; Room switched (or first render) — rebuild from scratch
                (set! (.-roomId ctx) (:id room))
                (set! (.-blocks ctx) #js [])
                (set! (.-chatDirty ctx) true)
                (set! (.-activeBuffer ctx) nil))
              ;; Header keyed on content, not room id: the deferred :pending →
              ;; real room transition re-renders identical text (no header
              ;; change on first prompt), and a model arriving later
              ;; (:lobby/state) refreshes it in place.
              (let [hkey [(get-in room [:agent :model]) (:cwd room)
                          (get-in room [:agent :agents-files])]]
                (when (not= hkey (.-headerKey ctx))
                  (set! (.-headerKey ctx) hkey)
                  (set! (.-header ctx)
                        (view/launch-header {:model (get-in room [:agent :model])
                                             :cwd (:cwd room)
                                             :agents-files (get-in room [:agent :agents-files])}))
                  (set! (.-chatDirty ctx) true)))
              (sync-chat! ctx room loader)
              (sync-view! ctx room ring dispatch!)
              (sync-bottom-panel! ctx room dispatch!)
              (tui/request-render!)))
            (dispatch! {:type :render/done
                        :duration-ms (- (js/Date.now) t0)})))]

    (set! (.-editor ctx) editor-comp)
    ((:add-child view-wrapper) chat)
    ((:add-child content) view-wrapper)
    (tui/set-bottom-panel! editor-comp)
    (tui/set-focus! editor-comp)
    ;; Alt+j / Alt+k jump between the user's own prompts (like the web client).
    (tui/set-jump-fn! (fn [dir] (jump-to-prompt! ctx dir)))

    ;; Stray stdout/stderr (libraries, warnings) → event ring, so it shows
    ;; up in the logs buffer instead of corrupting the alternate screen.
    (when ring
      (term/intercept-stdout!
       (fn [stream text]
         (when (seq (str/trim text))
           (log/append! ring {:type :log/stray
                              :event/ts (js/Date.now)
                              :stream stream
                              :text (log/elide-string text)})))))

    {:render render
     :effects {:app/quit
               (fn [_ _] (shutdown! on-exit))

               :app/reload
               (fn [_ {:keys [session-id]}] (reload! session-id on-exit))

               :clipboard/copy
               (fn [_ {:keys [text]}] (copy-to-clipboard! text))

               ;; Editor seams for extensions (e.g. dictation inserting a
               ;; transcript, then optionally submitting).
               :editor/insert-text
               (fn [_ {:keys [text]}] (when (seq text) ((:insert-text editor-comp) text)))

               :editor/delete-before-cursor
               (fn [_ {:keys [n]}] ((:delete-chars-back editor-comp) (or n 0)))

               :editor/submit
               (fn [_ _] ((:submit editor-comp)))}}))
