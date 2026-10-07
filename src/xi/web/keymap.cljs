(ns xi.web.keymap
  "Keyboard shortcuts for the web client, on the shared xi.keys model.

   Actions are registered here by id (`register-action!`, from
   xi.web.core/install-actions!) with the code that runs them; which key runs
   which action is the effective keymap in app state (`:web/keymap`): the
   built-in defaults, overlaid with the operator's `:keys` from config.edn
   once the server sends `:keys/config` on connect.

   On every keydown the event is turned into a canonical chord
   (`event->chord`), the active layers are computed from state and the DOM —
   transient layers (a pending permission request, a busy agent), the open
   buffer tab (`:buffer/diff` …), the router page (`:page/chat` …), the mode,
   then `:global` — and xi.keys/lookup picks the action. Mode is `:compose`
   while a *visible* text field has focus (a hidden field that kept focus does
   not own the keyboard), else `:navigate`; in compose mode chords that would
   type a character are never looked up. A key an inner element already
   handled (defaultPrevented, e.g. the composer's Enter) is left alone.

   An action map:
     :id     keyword, the id the keymap binds (see xi.keys/catalog)
     :label  display label (defaults to the catalog's)
     :when   optional (fn [state]) → boolean guard; a failing guard lets the
             key fall through to the next layer
     :run    (fn [state dispatch! event]) side-effecting action

   Transient layers are registered with `register-layer!` ({:id :when})."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.keys :as keys]))

(defonce ^:private actions (atom {}))
(defonce ^:private layers (atom []))

(defn register-action!
  "Add or replace an action (see ns docstring). Returns its id."
  [{:keys [id] :as action}]
  (assert (keyword? id) "action needs a keyword :id")
  (assert (ifn? (:run action)) "action needs a :run fn")
  (swap! actions assoc id (update action :label #(or % (keys/label id))))
  id)

(defn unregister-action! [id]
  (swap! actions dissoc id)
  nil)

(defn registered-actions
  "id → {:label …} of every registered action (for listings)."
  []
  @actions)

(defn register-layer!
  "Add a transient layer `{:id kw :when (fn [state])}`, active while its guard
   holds. Checked in registration order, before the buffer / page / mode
   layers."
  [{:keys [id] :as layer}]
  (assert (keyword? id))
  (swap! layers (fn [ls] (conj (vec (remove #(= id (:id %)) ls)) layer)))
  id)

;; ── Keymap ───────────────────────────────────────────────────────────────────

(def default-keymap
  "The built-in web keymap (no user config)."
  (keys/effective-keymap {:surface :web}))

(defn keymap
  "The effective keymap: the one `:keys/config` installed, else the defaults."
  [state]
  (or (:web/keymap state) default-keymap))

(def handlers
  "`:keys/config` — the operator's config.edn `:keys`, sent by the server on
   connect; recomputes the effective keymap."
  {:keys/config
   (fn [st {:keys [keys]}]
     {:state (assoc st
                    :web/keys-config keys
                    :web/keymap (keys/effective-keymap {:surface :web :user keys}))})})

;; ── DOM: mode ────────────────────────────────────────────────────────────────

(defn- editable?
  "True when `el` is a text-editing element (so typing should own the key)."
  [^js el]
  (boolean
   (when el
     (let [tag (.-tagName el)]
       (or (= tag "TEXTAREA")
           (and (= tag "INPUT")
                (not (#{"checkbox" "radio" "button" "submit" "reset" "range"
                        "color" "file"}
                      (.-type el))))
           (.-isContentEditable el))))))

(defn compose-focused?
  "True when the chat composer textarea currently holds focus."
  []
  (boolean
   (some-> (.-activeElement js/document)
           (.closest ".compose-input-wrapper"))))

(defn- visible?
  "True when `el` is actually rendered — not display:none / visibility:hidden
   (or content-visibility:hidden) anywhere up the tree. Browsers leave focus
   on a text field that gets hidden *after* it was focused (a panel closed by
   CSS, a popover that unrendered its content…), and that invisible field
   then swallows every keystroke. Engines without checkVisibility fall back to
   the offsetParent test (nil for display:none, except position:fixed)."
  [^js el]
  (if (.-checkVisibility el)
    (.checkVisibility el #js {:visibilityProperty true})
    (or (some? (.-offsetParent el))
        (= "fixed" (.-position (js/getComputedStyle el))))))

(defn current-mode
  "`:compose` when a *visible* text field is focused, else `:navigate`."
  []
  (let [el (.-activeElement js/document)]
    (if (and (editable? el) (visible? el)) :compose :navigate)))

(defn in-open-dialog?
  "True when the focused element sits inside an open native <dialog>."
  []
  (boolean (some-> (.-activeElement js/document) (.closest "dialog[open]"))))

(defn blur-active!
  "Drop focus from whatever element holds it (back to <body> / navigate mode)."
  []
  (some-> (.-activeElement js/document) .blur))

;; ── DOM: event → chord ───────────────────────────────────────────────────────

(def ^:private dom-named
  {"Escape" "escape" "Enter" "enter" "Tab" "tab" "Backspace" "backspace"
   "Delete" "delete" " " "space" "Spacebar" "space" "Insert" "insert"
   "ArrowUp" "up" "ArrowDown" "down" "ArrowLeft" "left" "ArrowRight" "right"
   "Home" "home" "End" "end" "PageUp" "pageup" "PageDown" "pagedown"})

(def ^:private code-chars
  "Physical-key codes → the character of the unshifted US key. Used for
   modifier combos so Alt+N fires whatever character the layout produces."
  {"Backslash" "\\" "Slash" "/" "Period" "." "Comma" "," "Semicolon" ";"
   "Quote" "'" "BracketLeft" "[" "BracketRight" "]" "Minus" "-" "Equal" "="
   "Backquote" "`" "IntlBackslash" "\\" "Space" "space"})

(def ^:private modifier-keys
  #{"Control" "Alt" "Shift" "Meta" "AltGraph" "CapsLock" "Dead" "Unidentified"
    "Process" "Fn" "Hyper" "Super" "OS" "NumLock" "ScrollLock"})

(defn- code->char [code]
  (cond
    (nil? code) nil
    (str/starts-with? code "Key") (str/lower-case (subs code 3))
    (str/starts-with? code "Digit") (subs code 5)
    :else (get code-chars code)))

(defn event->chord
  "A KeyboardEvent → canonical chord, or nil for a lone modifier / unusable
   key. Named keys come from `.key`; with Ctrl/Alt/Meta held the character
   comes from the physical `.code` (layout-independent), else from `.key`."
  [^js e]
  (let [key   (.-key e)
        ctrl  (.-ctrlKey e)
        alt   (.-altKey e)
        meta  (.-metaKey e)
        shift (.-shiftKey e)]
    (when-not (or (nil? key) (contains? modifier-keys key))
      (let [base (cond
                   (contains? dom-named key)    (get dom-named key)
                   (re-matches #"F\d{1,2}" key) (str/lower-case key)
                   (or ctrl alt meta)           (or (code->char (.-code e))
                                                    (when (= 1 (count key)) key))
                   (= 1 (count key))            key
                   :else                        nil)]
        (when base
          (keys/chord {:key base :ctrl ctrl :alt alt :meta meta :shift shift}))))))

;; ── Layers ───────────────────────────────────────────────────────────────────

(defn viewed-room
  "The room whose buffers the chat view shows: the virtual new chat's
   :web/pending-room while one is open without a session, else the active
   room (see xi.web.core/new-chat-view?)."
  [state]
  (if (and (some? (:web/pending-room state))
           (nil? (get-in state [:web/route :session-id])))
    (:web/pending-room state)
    (state/active-room state)))

(defn active-layers
  "The ordered layers to look a key up in, inner → outer, for `mode`."
  ([state] (active-layers state (current-mode)))
  ([state mode]
   (let [page   (get-in state [:web/route :page])
         active (get-in (viewed-room state) [:ui :active-buffer])]
     (-> []
         (into (keep (fn [{:keys [id] pred :when}] (when (pred state) id))) @layers)
         (cond->
          (and active (not= active :chat) (= page :chat))
           (conj (keyword "buffer" (name active)))
           page (conj (keyword "page" (name page))))
         (conj (if (= mode :compose) :mode/compose :mode/navigate) :global)))))

(defn- enabled?
  "The guard of a registered action, as the `enabled?` xi.keys/lookup takes."
  [state]
  (fn [id]
    (when-let [a (get @actions id)]
      (let [pred (:when a)]
        (or (nil? pred) (boolean (pred state)))))))

;; ── Dispatch ─────────────────────────────────────────────────────────────────

(defonce ^:private pending (atom []))
(defonce ^:private pending-timer (atom nil))

(def ^:private sequence-timeout-ms
  "How long a chord sequence waits for its next key (\"]\" then \"f\")."
  1500)

(defn- set-pending! [chords]
  (reset! pending (vec chords))
  (when-let [t @pending-timer] (js/clearTimeout t))
  (reset! pending-timer
          (when (seq chords)
            (js/setTimeout #(reset! pending []) sequence-timeout-ms))))

(defn handle-keydown
  "Resolve the keydown against the active layers and run the action it maps
   to. Keys that type a character are left to the focused field in compose
   mode; a key an inner handler already took (defaultPrevented) is skipped."
  [state dispatch! ^js e]
  (when-not (.-defaultPrevented e)
    (when-let [chord (event->chord e)]
      (let [mode (current-mode)]
        (if (and (= mode :compose) (keys/bare-printable? chord))
          (set-pending! [])
          (let [res (keys/lookup (keymap state) (active-layers state mode)
                                 @pending chord (enabled? state))]
            (case (:status res)
              :action  (do (set-pending! [])
                           (.preventDefault e)
                           ((:run (get @actions (:action res))) state dispatch! e))
              :pending (do (set-pending! (:pending res))
                           (.preventDefault e))
              (set-pending! []))))))))

;; ── For views ────────────────────────────────────────────────────────────────

(defn shortcut
  "Display string of the key that runs `action` right now (navigate-mode
   layers), or nil when it has none — for palette badges and menus."
  [state action]
  (keys/shortcut (keymap state) (active-layers state :navigate) action))

(defn listing
  "Rows for the shortcuts dialog (xi.keys/listing): every layer with a key
   bound to an action this client implements, the active layers first, user
   changes flagged `:custom?`."
  [state]
  (keys/listing (keymap state) default-keymap @actions (active-layers state)))
