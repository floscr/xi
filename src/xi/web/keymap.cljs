(ns xi.web.keymap
  "View- and mode-scoped keyboard shortcuts for the web client.

   A small registry of bindings, each tagged with the view(s) and editing
   mode it applies to. On every keydown we resolve the current view (from
   `[:web/route :page]`) and the current mode (`:insert` when a *visible* text
   field is focused, else `:normal`) and run the first binding that matches.

   Views don't need an explicit mount/unmount lifecycle: a binding is simply
   inert while its `:view`/`:mode` don't match the current context, so scoping
   a shortcut to a pane is just `:view :chat`. Bindings can be added/removed at
   runtime with `register!`/`unregister!`, so future panes or extensions can
   contribute their own shortcuts.

   A binding map:
     :id     unique keyword (re-registering the same id replaces it)
     :code   physical key from KeyboardEvent.code, e.g. \"KeyN\" / \"Escape\"
     :key    OR the logical key from KeyboardEvent.key (use one of :code/:key)
     :alt :ctrl :meta :shift  required modifier state (default false — the
             modifier must be *up* unless the binding opts in)
     :view   view keyword, a set of them, or :any (default :any)
     :mode   :normal | :insert | :any (default :normal)
     :when   optional (fn [state]) -> boolean extra guard
     :run    (fn [state dispatch! event]) side-effecting action")

(defonce ^:private registry (atom {}))

(def ^:private default-binding
  {:alt false :ctrl false :meta false :shift false :view :any :mode :normal})

(defn register!
  "Add or replace a keybinding (see ns docstring for the shape). Returns :id."
  [{:keys [id] :as binding}]
  (assert (keyword? id) "keybinding needs a keyword :id")
  (assert (ifn? (:run binding)) "keybinding needs a :run fn")
  (swap! registry assoc id (merge default-binding binding))
  id)

(defn unregister!
  "Remove a keybinding by id."
  [id]
  (swap! registry dissoc id)
  nil)

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
  "`:insert` when a *visible* text field is focused, else `:normal`. A hidden
   text field that still holds focus doesn't own the keyboard — normal-mode
   shortcuts (`i` → composer) keep working instead of typing into the void."
  []
  (let [el (.-activeElement js/document)]
    (if (and (editable? el) (visible? el)) :insert :normal)))

(defn in-open-dialog?
  "True when the focused element sits inside an open native <dialog>."
  []
  (boolean (some-> (.-activeElement js/document) (.closest "dialog[open]"))))

(defn blur-active!
  "Drop focus from whatever element holds it (back to <body> / normal mode)."
  []
  (some-> (.-activeElement js/document) .blur))

(defn- view-matches? [want cur]
  (cond
    (= want :any) true
    (set? want)   (contains? want cur)
    :else         (= want cur)))

(defn- binding-matches? [b ^js e view mode]
  (and (view-matches? (:view b) view)
       (or (= (:mode b) :any) (= (:mode b) mode))
       (if-let [c (:code b)] (= c (.-code e)) true)
       (if-let [k (:key b)]  (= k (.-key e))  true)
       (= (:alt b)   (.-altKey e))
       (= (:ctrl b)  (.-ctrlKey e))
       (= (:meta b)  (.-metaKey e))
       (= (:shift b) (.-shiftKey e))))

(defn handle-keydown
  "Resolve the current view + mode and run the first matching binding. A key
   already handled by an inner element (defaultPrevented — e.g. the composer's
   own command-menu Escape) is left alone."
  [state dispatch! ^js e]
  (when-not (.-defaultPrevented e)
    (let [view (get-in state [:web/route :page])
          mode (current-mode)]
      (when-let [b (some (fn [b]
                           (when (and (binding-matches? b e view mode)
                                      (let [pred (:when b)]
                                        (or (nil? pred) (pred state))))
                             b))
                         (vals @registry))]
        (.preventDefault e)
        ((:run b) state dispatch! e)))))
