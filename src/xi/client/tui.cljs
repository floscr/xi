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
            [xi.tui.ansi :as ansi]
            [xi.tui.clipboard-image :as clip-image]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.core :as tui]
            [xi.tui.editor :as editor]
            [xi.tui.terminal :as term]))

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
   item set (e.g. /resume current-folder vs all)."
  [{:keys [prompt items alt-items tab-labels]} room-id dispatch!]
  (let [;; Tab state is interaction-local (like the menu's filter query) —
        ;; it lives in the component, not in app state.
        tab #js {:alt false}
        close! (fn [] (dispatch! {:type :ui/menu-close :room-id room-id}))
        opts (cond-> {:items items
                      :prompt (or prompt "> ")
                      :on-select (fn [item]
                                   (close!)
                                   (when-let [event (:event item)]
                                     (dispatch! event)))
                      :on-cancel close!}
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
                               (ansi/fg :dim "  (Tab to switch)"))))
                      :key-bindings
                      [{:key-fn (fn [data] (= data "\t"))
                        :handler (fn [_state update-items!]
                                   (set! (.-alt tab) (not (.-alt tab)))
                                   (update-items! (if (.-alt tab) alt-items items)))}]))]
    (completion/make-completion-menu opts)))

(defn- palette-menu
  "Command palette: the command registry as a menu."
  [room-id]
  {:id :palette
   :prompt "palette> "
   :items (mapv (fn [{:keys [name description]}]
                  {:label (str "/" name)
                   :description description
                   :event {:type :command/run :room-id room-id :name name}})
                commands/registry)})

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

(defn- sync-view!
  "Point the view wrapper at the active buffer (:chat is the persistent
   chat container; logs/other buffers are rebuilt from state each pass)."
  [^js ctx room ring]
  (let [active (get-in room [:ui :active-buffer] :chat)]
    (when (or (not= active (.-activeBuffer ctx)) (not= active :chat))
      (set! (.-activeBuffer ctx) active)
      (reset! (:children (.-viewWrapper ctx))
              [(cond
                 (= active :chat) (.-chat ctx)
                 (= active :logs) (view/logs-view (log/entries ring) (:id room))
                 :else (if-let [buf (get-in room [:ui :buffers active])]
                         (view/buffer-view buf)
                         (.-chat ctx)))]))))

(defn- sync-menu! [^js ctx room dispatch!]
  (let [menu (get-in room [:ui :menu])]
    (when-not (identical? menu (.-menuVal ctx))
      (set! (.-menuVal ctx) menu)
      (if menu
        (let [menu-comp (build-menu menu (:id room) dispatch!)
              panel (tui/make-container)]
          ((:add-child panel) (comp/make-spacer 1))
          ((:add-child panel) menu-comp)
          (tui/set-bottom-panel! panel)
          (tui/set-focus! menu-comp))
        (do (tui/set-bottom-panel! (.-editor ctx))
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
     :ring    event ring buffer (feeds the logs buffer)
     :on-exit (fn []) — flush hooks before process exit (quit/reload)"
  [{:keys [ring on-exit]}]
  (let [content (tui/create-tui!)
        chat (tui/make-container)
        view-wrapper (tui/make-container)
        loader (comp/make-loader "thinking...")
        ctx #js {:dispatch nil :state nil
                 :chat chat :viewWrapper view-wrapper :editor nil
                 :roomId nil :blocks #js [] :header [] :chatDirty true
                 :menuVal nil :activeBuffer :chat
                 :loaderShown false}
        dispatch! (fn [event] (when-let [d (.-dispatch ctx)] (d event)))
        current-room (fn [] (some-> (.-state ctx) state/active-room))
        room-event (fn [event] (when-let [room (current-room)]
                                 (dispatch! (assoc event :room-id (:id room)))))

        editor-comp
        (editor/make-editor
         {:prompt "xi> "
          :on-submit (fn [text] (room-event {:type :input/submit :text text}))
          :on-escape (fn []
                       (when (get-in (current-room) [:agent :busy?])
                         (room-event {:type :agent/abort})))
          :on-interrupt (fn [] (shutdown! on-exit))
          :on-palette (fn []
                        (when-let [room (current-room)]
                          (dispatch! {:type :ui/menu-open :room-id (:id room)
                                      :menu (palette-menu (:id room))})))
          :on-git (fn [] (tui/run-external! ["ngit"] {}))
          :on-paste-image
          (fn []
            (if-let [img (clip-image/read-clipboard-image)]
              (room-event {:type :ui/attach-image :image img :label "clipboard"})
              (room-event {:type :ui/status :text "No image in clipboard"})))
          :prompt-suffix-fn
          (fn []
            (let [n (count (get-in (current-room) [:ui :pending-images]))]
              (when (pos? n)
                (ansi/fg :accent (str " 📎" n)))))
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
              (sync-view! ctx room ring)
              (sync-menu! ctx room dispatch!)
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
               (fn [_ {:keys [text]}] (copy-to-clipboard! text))}}))
