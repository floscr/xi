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
            [xi.client.sidebar :as sidebar]
            [xi.client.subagents-buffer :as subagents-buffer]
            [xi.client.view :as view]
            [xi.commands :as commands]
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.dialog :as dialog]
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
            [xi.tui.theme-mode :as theme-mode]
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
   "ctrl+p"       #{(str (char 16)) (str ESC "[112;5u")}
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
   folded into :key-fn so a guarded binding (e.g. a Ctrl+C only active in
   some mode) lets the key fall through to the editor's own handler when the
   guard fails. :event is dispatched with the active
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

(defn- quick-reply-keybindings
  "Native editor bindings alt+1..alt+4: submit the Nth quick-reply chip
   (xi.quick-replies) of the active room. The chip is looked up at press time
   and sent via :input/submit — the universal submit path — so it works in
   standalone and client alike with no core handler. Guarded so the key falls
   through to the editor when there is no chip at that index."
  [get-state dispatch!]
  (mapv (fn [i]
          (let [seqs #{(str ESC (inc i)) (str ESC "[" (+ 49 i) ";3u")}
                chip-at (fn [] (some-> (get-state) state/active-room
                                       (get-in [:quick-replies :chips]) (nth i nil)))]
            {:key-fn  (fn [data] (and (contains? seqs data) (some? (chip-at))))
             :handler (fn []
                        (let [st   (get-state)
                              room (state/active-room st)
                              chip (chip-at)]
                          (when (and room chip)
                            (dispatch! {:type :input/submit :room-id (:id room)
                                        :text (:send chip)}))))}))
        (range 4)))

(defn- quick-replies-hint
  "Prompt-suffix hint for the active room's quick-reply chips, e.g.
   '  ⌥1 Yes  ⌥2 No'. nil when there are none."
  [room]
  (when-let [chips (seq (get-in room [:quick-replies :chips]))]
    (apply str
           (map-indexed
            (fn [i {:keys [label]}]
              (let [label (if (> (count label) 18) (str (subs label 0 17) "…") label)]
                (str (ansi/fg :accent (str "  ⌥" (inc i) " ")) (ansi/fg :dim label))))
            chips))))

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
   /skill), while the web palette sticks to the curated subset."
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
                              ;; Join the session's LIVE room (server prefers it
                              ;; via room-for-session) so a working chat streams
                              ;; live, instead of /resume loading a stale disk
                              ;; snapshot into the current room. Mirrors the web
                              ;; (see xi.web.router/session->room-id).
                              :event {:type :room/join
                                      :target {:session-id (:session-id s)}}})
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

(defn- confirm-diff-scroll
  "New top line of a confirm dialog's expanded diff after key `data`, or nil
   when it isn't a scroll key. `h` is the visible window height; the render
   clamps the result to the diff's length."
  [data top h]
  (case data
    ("j" "\u001b[B")  (inc top)
    ("k" "\u001b[A")  (dec top)
    (" " "\u001b[6~") (+ top h)
    ("b" "\u001b[5~") (- top h)
    "\u0004"          (+ top (quot h 2))
    "\u0015"          (- top (quot h 2))
    "g"               0
    "G"               js/Number.MAX_SAFE_INTEGER
    nil))

(defn- build-confirm-dialog
  "A y/n confirm dialog rendered as a bordered box that sits above the prompt
   line. Enter = yes, Esc = no. The message word-wraps to the terminal width so
   long guarded commands no longer overflow and corrupt the layout, and the
   editor stays visible below the dialog for context.

   A dialog carrying a :diff shows a capped preview; d expands it in place
   into a scrollable view of the whole change (j/k, space/b, ^d/^u, g/G) and
   d/q/Esc collapse it again. y/n and Enter still answer while expanded."
  [{:keys [message prompt diff] :as dlg} respond! editor]
  (let [text    (or message prompt "Confirm?")
        ;; A guarded write/edit's change, shown above the question so the
        ;; user sees what they're approving. Lines are clipped (not wrapped)
        ;; to keep the diff's columns intact, and capped so a big write
        ;; can't push the question off-screen.
        diff-lines (when diff (str/split-lines (view/diff-preview-text diff 30)))
        full-lines (when diff (delay (vec (str/split-lines
                                           (view/diff-preview-text diff ##Inf)))))
        ;; :height is the expanded window's row count, cached by render so
        ;; the paging keys know how far to move.
        !view   (atom {:expanded? false :top 0 :height 10})
        options (dialog/confirm-options dlg)
        by-key  (into {} (mapcat (fn [{:keys [key value]}]
                                   [[key value] [(str/upper-case key) value]]))
                      options)
        option-hints (mapv (fn [{:keys [key label]}]
                             (str (ansi/fg :accent (str "[" key "]")) " " label))
                           options)
        hint    (str "  "
                     (str/join "   " (cond-> option-hints
                                       diff (conj (str (ansi/fg :accent "[d]") " Full diff"))))
                     "   " (ansi/fg :dim "(Enter=yes, Esc=no)"))
        full-hint (str "  " (str/join "   " option-hints)
                       "   " (ansi/fg :dim "(j/k scroll, space/b page, g/G ends, d/q/Esc back)"))
        clip    (fn [width line]
                  (str (ansi/truncate-to-width (str "  " line) width) ansi/reset))
        set-view! (fn [f & args]
                    (apply swap! !view f args)
                    (tui/request-panel-render!))]
    {:type :dialog
     :render (fn [width]
               (let [wrapped (ansi/wrap-text text (max 1 (- width 2)))
                     border  (ansi/fg :border (apply str (repeat width "─")))]
                 (if (:expanded? @!view)
                   ;; Fill the terminal, leaving room for the question, the
                   ;; hint, the status line and a few chat lines.
                   (let [lines @full-lines
                         n     (count lines)
                         h     (max 3 (- (term/rows) (count wrapped) 10))
                         top   (-> (:top @!view) (min (- n h)) (max 0))
                         end   (min n (+ top h))]
                     (swap! !view assoc :top top :height h)
                     (-> [border]
                         (into (map #(clip width %)) (subvec lines top end))
                         (conj (str "  " (ansi/fg :dim (str "lines " (inc top) "–" end " of " n)))
                               "")
                         (into (map #(str "  " %)) wrapped)
                         (conj full-hint "")))
                   (let [box (into [border]
                                   (concat
                                    (map #(clip width %) diff-lines)
                                    (when diff-lines [""])
                                    (map #(str "  " %) wrapped)
                                    [hint ""]))]
                     (into box (when editor ((:render editor) width)))))))
     :handle-input (fn [data]
                     (let [{:keys [expanded? top height]} @!view
                           scroll (when expanded? (confirm-diff-scroll data top height))]
                       (cond
                         (and diff (#{"d" "D"} data))
                         (set-view! assoc :expanded? (not expanded?) :top 0)

                         (and expanded? (or (= "q" data) (escape? data)))
                         (set-view! assoc :expanded? false)

                         scroll (set-view! assoc :top scroll)

                         (contains? by-key data) (respond! (get by-key data))
                         (enter? data)  (respond! true)
                         (escape? data) (respond! false)
                         :else nil)))}))

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

(defn- form-backspace? [d] (or (= d (str (char 127))) (= d (str (char 8)))))
(defn- form-arrow-up? [d] (= d (str ESC "[A")))
(defn- form-arrow-down? [d] (= d (str ESC "[B")))
(defn- form-tab? [d] (= d "\t"))
(defn- form-printable? [d]
  (and (seq d)
       (not (str/starts-with? d ESC))
       (not (form-backspace? d))
       (>= (.charCodeAt d 0) 32)))

(defn- build-form-dialog
  "A multi-field text form dialog (:type :form). The fields come from the
   dialog's :fields data (normalized by xi.dialog/form-fields) — typing edits
   the active field, Enter advances (submitting on the last field), Tab/arrows
   switch fields, Esc cancels (answers nil). Resolves to a map of field name
   → entered text."
  [{:keys [message] :as dlg} respond!]
  (let [fields (dialog/form-fields dlg)
        n      (count fields)
        !form  (atom {:idx 0
                      :values (into {} (map (juxt :name :value)) fields)})]
    {:type :dialog
     :render
     (fn [width]
       (let [{:keys [idx values]} @!form]
         (into [(ansi/fg :border (apply str (repeat width "─")))
                (str "  " (or message "Fill in:"))]
               (concat
                (mapcat
                 (fn [i {:keys [name label]}]
                   (let [active? (= i idx)
                         v       (str (get values name))
                         lines   (ansi/wrap-text (str v (when active? "▌"))
                                                 (max 1 (- width 4)))]
                     (cons (str "  " (ansi/fg (if active? :accent :dim)
                                              (str label ":")))
                           (map #(str "    " %) (if (seq lines) lines [""])))))
                 (range) fields)
                [(str "  " (ansi/fg :dim "(Enter=next/submit, Tab=switch field, Esc=cancel)"))]))))
     :handle-input
     (fn [data]
       (let [{:keys [idx]} @!form
             fname (:name (get fields idx))
             last? (>= idx (dec n))]
         (cond
           (escape? data) (respond! nil)
           (enter? data)  (if last?
                            (respond! (:values @!form))
                            (do (swap! !form update :idx inc)
                                (tui/request-panel-render!)))
           (or (form-tab? data) (form-arrow-down? data))
           (when (pos? n)
             (swap! !form update :idx #(mod (inc %) n))
             (tui/request-panel-render!))
           (form-arrow-up? data)
           (when (pos? n)
             (swap! !form update :idx #(mod (dec %) n))
             (tui/request-panel-render!))
           (form-backspace? data)
           (when fname
             (swap! !form update-in [:values fname]
                    #(let [s (str %)] (subs s 0 (max 0 (dec (count s))))))
             (tui/request-panel-render!))
           (form-printable? data)
           (when fname
             (swap! !form update-in [:values fname] #(str % data))
             (tui/request-panel-render!))
           :else nil)))}))

(defn- build-dialog
  "A focused component for the active dialog. Dispatches the answer via
   :ui/dialog-response, which the dialog owner (xi.ext.core/create-dialogs)
   resolves. :cwd-select and :select offer a numbered list; :form is a
   multi-field text form; everything else is a y/n confirm."
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
      :form       (build-form-dialog dialog respond!)
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

(defn- terminal-title
  "Terminal title for a room: \"Xi: <label>\", prefixed with ⧗ (U+29D7) while a
   dialog awaits an answer, else ⟳ (U+27F3) while the agent is busy. xmonad's
   ewwLogHook greps those markers to highlight the workspace indicator in eww.
   Set client-side so it reaches the real terminal window (a server extension
   would write to the headless server's detached stdout)."
  [room]
  (when-let [label (or (get-in room [:session :name])
                       (some-> (:cwd room) (str/split #"/") last))]
    (str (cond
           (seq (get-in room [:ui :dialogs])) "\u29d7 "
           (get-in room [:agent :busy?])      "\u27f3 ")
         "Xi: " label)))

(defn- sync-chat! [^js ctx room loader]
  (let [history-changed? (sync-history! ctx (:history room))
        show-loader? (loader-visible? room)
        loader-toggled? (not= show-loader? (.-loaderShown ctx))
        title (terminal-title room)]
    (when (not= title (.-titleStr ctx))
      (set! (.-titleStr ctx) title)
      (when title (term/write! (str "\033]0;" title "\007"))))
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
          ;; v: suspend the TUI and open the file under the cursor in
          ;; $EDITOR (+line works for vi/vim/nvim/nano/emacs), resolved
          ;; against the room's cwd (diff paths are repo-relative).
          on-edit
          (fn [{:keys [file line]}]
            (let [cwd (get-in (.-state ctx) [:rooms room-id :cwd])
                  editor (or (aget js/process.env "VISUAL")
                             (aget js/process.env "EDITOR")
                             "vi")
                  cmd (-> (vec (str/split editor #"\s+"))
                          (cond-> line (conj (str "+" line)))
                          (conj file))]
              (tui/run-external! cmd (when cwd {:cwd cwd}))))
          c (if (:diff? buf)
              (diff-buffer/make-diff-buffer
               {:diff-text (:text buf) :title (:title buf)
                :on-close on-close :on-command-mode on-command-mode
                :on-explain on-explain :on-prompt on-prompt
                :on-edit on-edit})
              (pager/make-text-buffer
               {:text (view/buffer-display-text buf) :title (:title buf)
                :on-close on-close :on-command-mode on-command-mode
                :on-explain on-explain :on-prompt on-prompt}))]
      (set! (.-pagerVal ctx) buf)
      (set! (.-pagerComp ctx) c)
      (tui/set-focus! c)
      (tui/scroll-to-offset! 999999)))
  (.-pagerComp ctx))

(defn- subagents-pager-view!
  "Focused live pager over the room's sub-agents (xi.client.subagents-buffer).
   Cached per room in the same pagerComp/pagerVal slots as the other pagers
   (so the bottom-panel help bar works unchanged); unlike them its content is
   read from app state, so it is invalidated whenever the agents vector
   changes identity — streaming deltas repaint live while the buffer is open."
  [^js ctx room dispatch!]
  (let [room-id (:id room)
        agents  (get-in room [:ext :subagents :agents])
        cache-key [:subagents room-id]]
    (when-not (= cache-key (.-pagerVal ctx))
      (let [c (subagents-buffer/make-subagents-buffer
               {:get-agents (fn [] (get-in (.-state ctx)
                                           [:rooms room-id :ext :subagents :agents]))
                :on-stop (fn [sub-id]
                           (dispatch! {:type :subagent/abort
                                       :room-id room-id :sub-id sub-id}))
                :on-open (fn [sub-id]
                           ;; Promote (first time) + resume the sub-agent's
                           ;; session into this room, and land back in chat.
                           (dispatch! {:type :subagent/promote
                                       :room-id room-id :sub-id sub-id
                                       :open? true})
                           (dispatch! {:type :ui/buffer-switch
                                       :room-id room-id :buffer-id :chat}))
                :on-close (fn [] (dispatch! {:type :ui/buffer-switch
                                             :room-id room-id :buffer-id :chat}))
                :on-command-mode (fn [] (tui/set-focus! (.-editor ctx)))})]
        (set! (.-pagerVal ctx) cache-key)
        (set! (.-pagerComp ctx) c)
        (set! (.-subagentsVal ctx) agents)
        (tui/set-focus! c)
        (tui/scroll-to-offset! 999999)))
    (when-not (identical? agents (.-subagentsVal ctx))
      (set! (.-subagentsVal ctx) agents)
      ((:invalidate (.-pagerComp ctx))))
    (.-pagerComp ctx)))

(defn- pager-buffer?
  "A buffer that should be shown in a focused pager (scroll keybindings +
   help toolbar): the diff/difft buffers (identified by their :engine key)
   and file buffers (identified by their :path), so viewing a file gets the
   same vim navigation as the diff buffer. The system-prompt buffer stays on
   the static view so ctrl+o (an editor keybinding) keeps working; :chat and
   :logs have their own views."
  [buf]
  (or (contains? buf :engine)
      (contains? buf :path)))

(defn- pager-active?
  "True when the active buffer renders as a focused pager: a pager buffer
   value, or the live :subagents view (which has no [:ui :buffers] entry)."
  [room active]
  (or (= active :subagents)
      (pager-buffer? (get-in room [:ui :buffers active]))))

(defn- sync-view!
  "Point the view wrapper at the active buffer (:chat is the persistent
   chat container; logs/other buffers are rebuilt from state each pass).
   Pager buffers get the focused viewer, which takes focus while open."
  [^js ctx room ring dispatch!]
  (let [active (get-in room [:ui :active-buffer] :chat)
        switched? (not= active (.-activeBuffer ctx))]
    (when (or switched? (not= active :chat))
      (let [buf (get-in room [:ui :buffers active])
            pager? (pager-active? room active)
            target (cond
                     (= active :chat) (.-chat ctx)
                     (= active :logs) (view/logs-view (log/entries ring) (:id room))
                     (= active :subagents) (subagents-pager-view! ctx room dispatch!)
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
        pager? (pager-active? room active)
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
        ;; Quick-reply chips are disabled in the TUI for now (kept for the web
        ;; client). Re-add `(quick-reply-keybindings get-state dispatch!)` to
        ;; the `into` below to restore the alt+1..4 chip bindings.
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
          ;; Backspace on an empty prompt un-queues the most recently queued
          ;; message (the "⏳N queued" badge).
          :on-backspace
          (fn [text]
            (let [room (current-room)
                  qn   (count (get-in room [:agent :queued]))]
              (when (and (= "" text) (pos? qn))
                (dispatch! {:type :prompt/queue-remove :room-id (:id room)
                            :index (dec qn)})
                true)))
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
              ;; Quick-reply chips are disabled in the TUI for now (kept for
              ;; the web client). Re-add `(quick-replies-hint room)` here to
              ;; restore the chip hint suffix.
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
          ;; Sync the light/dark theme so tool/code blocks track the terminal.
          ;; On a mode flip, drop the roomId sentinel so the room-switch branch
          ;; below rebuilds every cached block with the new palette.
          (let [mode (theme-mode/refresh!)]
            (when (not= mode (.-themeMode ctx))
              (set! (.-themeMode ctx) mode)
              (set! (.-roomId ctx) nil)))
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
    ;; Session drawer (Alt+\). Gets first crack at input in xi.tui.core, so
    ;; while open it owns Alt+j/k (switch sessions); while closed those keys
    ;; fall through to the prompt-jump below.
    (tui/set-sidebar!
     (sidebar/make-sidebar {:get-state get-state
                            :dispatch! dispatch!
                            :render!   tui/request-render!
                            :repaint!  tui/full-repaint!}))
    ;; Alt+j / Alt+k jump between the user's own prompts (like the web client)
    ;; when the drawer is closed.
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
               (fn [_ _]
                 (when-let [stop (.-themeWatcher ctx)] (stop))
                 (shutdown! on-exit))

               :app/reload
               (fn [_ {:keys [session-id]}] (reload! session-id on-exit))

               :clipboard/copy
               (fn [_ {:keys [text]}] (copy-to-clipboard! text))

               ;; Editor seam for commands and extensions (e.g. inserting a
               ;; picked path or a re-edited prompt).
               :editor/insert-text
               (fn [_ {:keys [text]}] (when (seq text) ((:insert-text editor-comp) text)))}}))
