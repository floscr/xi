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
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.session :as session]
            [xi.tui.ansi :as ansi]
            [xi.tui.clipboard-image :as clip-image]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.core :as tui]
            [xi.tui.diff-buffer :as diff-buffer]
            [xi.tui.editor :as editor]
            [xi.tui.history-selector :as history-selector]
            [xi.tui.terminal :as term]))

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

(defn- reload!
  "Restart the process with the same argv, picking up recompiled code.
   Passes session-id via env so the new process auto-resumes."
  [session-id on-exit]
  (let [child-process (js/require "child_process")
        argv (vec (js->clj js/process.argv))
        env (js/Object.assign #js {} js/process.env)]
    (when session-id
      (aset env "XI_RELOAD_SESSION" session-id))
    (term/restore-stdout!)
    (tui/stop-tui!)
    (when on-exit (on-exit))
    (.execFileSync child-process (first argv) (clj->js (rest argv))
                   #js {:stdio "inherit" :env env})
    (js/process.exit 0)))

(defn- copy-to-clipboard!
  "Copy text to the system clipboard via OSC 52."
  [text]
  (when (seq text)
    (let [b64 (.toString (js/Buffer.from text "utf-8") "base64")]
      (term/write! (str "\033]52;c;" b64 "\007")))))

;; ── Menus (room :ui :menu → completion menu) ─────────────────────────────────

(defn- build-menu
  "Completion menu component from a menu description:
     {:id kw :prompt str :items [...] :alt-items [...] :tab-labels [...]}
   Items carry {:label :description :event} — selecting dispatches the
   event (after closing the menu). :alt-items adds a Tab-switched second
   item set (e.g. /resume current-folder vs all).
   :key-bindings — vec of {:key str :event map :selected? bool}. When :key
   matches, closes the menu and dispatches the event (with :room-id merged).
   When :selected? is true, the currently selected item is merged into the
   event under :selected."
  [{:keys [prompt items alt-items tab-labels key-bindings search-field]} room-id dispatch!]
  (let [;; Tab state is interaction-local (like the menu's filter query) —
        ;; it lives in the component, not in app state.
        tab #js {:alt false}
        close! (fn [] (dispatch! {:type :ui/menu-close :room-id room-id}))
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
                                   (close!)
                                   (when-let [event (:event item)]
                                     (dispatch! event)))
                      :on-cancel close!}
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
               (assoc :key-bindings all-kbs))]
    (completion/make-completion-menu opts)))

(defn- palette-menu
  "Command palette: the assembly's command list as a menu."
  [room-id commands]
  {:id :palette
   :prompt "palette> "
   :items (into []
                (mapcat (fn [{:keys [name description subcommands]}]
                          (cons {:label (str "/" name)
                                 :description description
                                 :event {:type :command/run :room-id room-id :name name}}
                                (map (fn [{sub-name :name sub-desc :description}]
                                       {:label (str "/" name " " sub-name)
                                        :description sub-desc
                                        :event {:type :command/run :room-id room-id
                                                :name name :args sub-name}})
                                     subcommands))))
                commands)})

;; ── Dialogs (room :ui :dialogs → focused bottom-panel component) ──────────────

(defn- build-dialog
  "A focused component for the active dialog. :confirm answers y/n (Enter =
   yes, Esc = no); the answer dispatches :ui/dialog-response, which the
   dialog owner (xi.ext.core/create-dialogs) resolves."
  [{:keys [id message prompt] :as dialog} room-id dispatch!]
  (let [respond! (fn [value]
                   (dispatch! {:type :ui/dialog-response
                               :room-id room-id :dialog-id id :value value}))
        text (or message prompt "Confirm?")]
    {:type :dialog
     :render (fn [width]
               [(ansi/fg :border (apply str (repeat width "─")))
                (str "  " text)
                (str "  " (ansi/fg :accent "[y]") "es   "
                     (ansi/fg :accent "[n]") "o   "
                     (ansi/fg :dim "(Enter=yes, Esc=no)"))])
     :handle-input (fn [data]
                     (cond
                       (#{"y" "Y"} data) (respond! true)
                       (#{"n" "N"} data) (respond! false)
                       (enter? data)     (respond! true)
                       (escape? data)    (respond! false)
                       :else nil))}))

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

(defn- diff-view!
  "Interactive diff viewer component, cached on the buffer value's identity
   (a /diff with new output replaces it; reopening via /buffers reuses it).
   Building a fresh component grabs focus and scrolls to the top."
  [^js ctx buf room-id dispatch!]
  (when-not (identical? buf (.-diffVal ctx))
    (let [c (diff-buffer/make-diff-buffer
             {:diff-text (:text buf)
              :title (:title buf)
              :on-close (fn [] (dispatch! {:type :ui/buffer-switch
                                           :room-id room-id :buffer-id :chat}))
              :on-command-mode (fn [] (tui/set-focus! (.-editor ctx)))})]
      (set! (.-diffVal ctx) buf)
      (set! (.-diffComp ctx) c)
      (tui/set-focus! c)
      (tui/scroll-to-offset! 999999)))
  (.-diffComp ctx))

(defn- sync-view!
  "Point the view wrapper at the active buffer (:chat is the persistent
   chat container; logs/other buffers are rebuilt from state each pass).
   Diff buffers get the interactive viewer, which takes focus while open."
  [^js ctx room ring dispatch!]
  (let [active (get-in room [:ui :active-buffer] :chat)
        switched? (not= active (.-activeBuffer ctx))]
    (when (or switched? (not= active :chat))
      (let [buf (get-in room [:ui :buffers active])
            diff? (boolean (:diff? buf))
            target (cond
                     (= active :chat) (.-chat ctx)
                     (= active :logs) (view/logs-view (log/entries ring) (:id room))
                     diff? (diff-view! ctx buf (:id room) dispatch!)
                     buf (view/buffer-view buf)
                     :else (.-chat ctx))]
        (set! (.-activeBuffer ctx) active)
        (reset! (:children (.-viewWrapper ctx)) [target])
        ;; Focus/scroll transitions in and out of the interactive viewer
        (when switched?
          (cond
            diff? (do (tui/set-focus! target)
                      (tui/scroll-to-offset! 999999))
            (.-wasDiff ctx) (do (tui/set-focus! (.-editor ctx))
                                (tui/scroll-to-offset! 0)))
          (set! (.-wasDiff ctx) diff?))))))

(defn- focus-panel!
  "Wrap a focused component in a spacer'd container and install it as the
   bottom panel."
  [comp]
  (let [panel (tui/make-container)]
    ((:add-child panel) (comp/make-spacer 1))
    ((:add-child panel) comp)
    (tui/set-bottom-panel! panel)
    (tui/set-focus! comp)))

(defn- diff-help-bar
  "Single-line help bar shown at the bottom while the diff viewer is open."
  []
  (let [dim  (partial ansi/fg :dim)
        key  (partial ansi/fg :accent)]
    (comp/make-text
     (str (key "j") "/" (key "k") (dim ":scroll  ")
          (key "]c") "/" (key "[c") (dim ":changes  ")
          (key "]f") "/" (key "[f") (dim ":files  ")
          (key "gg") "/" (key "G") (dim ":top/bottom  ")
          (key "q") (dim ":close  ")
          (key ":") (dim ":command")))))

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
  "Bottom-panel priority: dialog > menu > tree > diff-help-bar > editor.
   Rebuilds only when the selected target identity changes."
  [^js ctx room dispatch!]
  (let [dialog (first (get-in room [:ui :dialogs]))
        menu   (get-in room [:ui :menu])
        tree?  (boolean (get-in room [:ui :tree-open?]))
        diff?  (boolean (get-in room [:ui :buffers (get-in room [:ui :active-buffer]) :diff?]))
        target (or dialog menu (when tree? :tree) (when diff? :diff))]
    (when-not (identical? target (.-panelVal ctx))
      (set! (.-panelVal ctx) target)
      (cond
        dialog (focus-panel! (build-dialog dialog (:id room) dispatch!))
        menu   (focus-panel! (build-menu menu (:id room) dispatch!))
        tree?  (focus-panel! (build-history-selector room dispatch!))
        diff?  (tui/set-bottom-panel! (diff-help-bar))
        :else  (do (tui/set-bottom-panel! (.-editor ctx))
                   (tui/set-focus! (.-editor ctx)))))))

;; ── Client ───────────────────────────────────────────────────────────────────

(defn- shorten-home [path]
  (let [home (aget js/process.env "HOME")]
    (if (and path home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn create!
  "Boot the terminal UI. Returns
     {:render  (fn [state dispatch!])   ;; for app :on-render
      :effects {...}}                   ;; TUI-owned effect handlers

   opts:
     :ring         event ring buffer (feeds the logs buffer)
     :on-exit      (fn []) — flush hooks before process exit (quit/reload)
     :commands     command list for the palette (built-ins + ext commands;
                   defaults to nil → empty palette source)
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
                 :roomId nil :blocks #js [] :header [] :chatDirty true
                 :panelVal nil :activeBuffer :chat
                 :diffVal nil :diffComp nil :wasDiff false
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
          :on-submit (fn [text] (room-event {:type :input/submit :text text}))
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
                                      :menu (palette-menu (:id room) commands)})))
          :ext-keybindings ext-keybindings
          :on-git (fn [] (tui/run-external! ["ngit"] {}))
          :on-paste-image
          (fn []
            (if-let [img (clip-image/read-clipboard-image)]
              (room-event {:type :ui/attach-image :image img :label "clipboard"})
              (room-event {:type :ui/status :text "No image in clipboard"})))
          :prompt-suffix-fn
          (fn []
            (let [n (count (get-in (current-room) [:ui :pending-images]))
                  badge (when prompt-badge (prompt-badge (.-state ctx)))]
              (str (when (pos? n) (ansi/fg :accent (str " 📎" n)))
                   (when (seq badge) badge))))
          :prompt-right-fn
          (fn []
            (when-let [cwd (:cwd (current-room))]
              (ansi/fg :dim (shorten-home cwd))))})

        render
        (fn [state dispatch!]
          (set! (.-dispatch ctx) dispatch!)
          (set! (.-state ctx) state)
          (dispatch! {:type :render/start})
          (let [t0 (js/Date.now)]
            (when-let [room (state/active-room state)]
              (when (not= (:id room) (.-roomId ctx))
                ;; Room switched (or first render) — rebuild from scratch
                (set! (.-roomId ctx) (:id room))
                (set! (.-blocks ctx) #js [])
                (set! (.-header ctx)
                      (view/launch-header {:model (get-in room [:agent :model])
                                           :cwd (:cwd room)
                                           :agents-files (get-in room [:agent :agents-files])}))
                (set! (.-chatDirty ctx) true)
                (set! (.-activeBuffer ctx) nil))
              (sync-chat! ctx room loader)
              (sync-view! ctx room ring dispatch!)
              (sync-bottom-panel! ctx room dispatch!)
              (tui/request-render!))
            (dispatch! {:type :render/done
                        :duration-ms (- (js/Date.now) t0)})))]

    (set! (.-editor ctx) editor-comp)
    ((:add-child view-wrapper) chat)
    ((:add-child content) view-wrapper)
    (tui/set-bottom-panel! editor-comp)
    (tui/set-focus! editor-comp)

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
