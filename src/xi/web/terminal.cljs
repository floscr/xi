(ns xi.web.terminal
  "Terminal buffers in the browser: an xterm.js instance per `:terminal`
   buffer on screen (xi.ext.terminal runs the program on the server).

   xterm.js is vendored (resources/public/vendor/xterm) and loaded on the
   first terminal. Its I/O bypasses the app: keystrokes and sizes go out
   through the transport's raw `send!` (set-send!), and the server's
   `:terminal/output` is taken off the socket by `intercept` before it
   reaches the app, so typing and streaming never re-render the page. A
   terminal attaches on mount (the server replays its recent output and makes
   the program repaint), detaches on unmount, and re-attaches after a
   reconnect (`reattach-all!`).

   The floating toolbar's buttons come from extensions'
   `:terminal-toolbar-items` providers (register-toolbar!)."
  (:require [clojure.string :as str]
            [ui.button :as button]
            [ui.icon :as icon]
            [xi.keys :as keys]
            [xi.web.appearance :as appearance]
            [xi.web.clipboard :as clipboard]
            [xi.web.keymap :as keymap]))

;; ── Transport ────────────────────────────────────────────────────────────────

(defonce ^:private send-ref (atom nil))

(defn set-send!
  "Install the transport's raw send (xi.client.ws-transport :send!)."
  [f]
  (reset! send-ref f))

(defn- send! [ev]
  (when-let [f @send-ref] (f ev)))

;; ── Loading xterm.js ─────────────────────────────────────────────────────────

(def ^:private vendor "/vendor/xterm/")

(defonce ^:private lib (atom nil))

(defn- load-tag! [tag attrs]
  (js/Promise.
   (fn [resolve reject]
     (let [el (.createElement js/document tag)]
       (doseq [[k v] attrs] (.setAttribute el k v))
       (set! (.-onload el) resolve)
       (set! (.-onerror el) #(reject (js/Error. (str "Could not load " (or (get attrs "src")
                                                                            (get attrs "href"))))))
       (.appendChild (.-head js/document) el)))))

(defn- load-lib!
  "Promise of xterm.js + the fit addon being loaded (once per page)."
  []
  (or @lib
      (reset! lib
              (-> (js/Promise.all
                   #js [(load-tag! "script" {"src" (str vendor "xterm.js")})
                        (load-tag! "script" {"src" (str vendor "addon-fit.js")})
                        (load-tag! "link" {"rel" "stylesheet" "href" (str vendor "xterm.css")})])
                  (.catch (fn [e] (reset! lib nil) (throw e)))))))

;; ── Instances ────────────────────────────────────────────────────────────────

;; term-id → {:term :fit :observer :node}
(defonce ^:private instances (atom {}))

(defn- css-var [node k fallback]
  (let [v (str/trim (.getPropertyValue (js/getComputedStyle node) k))]
    (if (str/blank? v) fallback v)))

(defonce ^:private color-ctx (atom nil))

(defn- hex-color
  "Any CSS colour → #rrggbb, which xterm.js parses (it can't read the
   theme's oklch()); a canvas does the conversion. With `tint`, that colour
   is painted over `css` at `alpha` first."
  ([css] (hex-color css nil 0))
  ([css tint alpha]
   (let [^js ctx (or @color-ctx
                     (reset! color-ctx (.getContext (.createElement js/document "canvas") "2d"
                                                    #js {:willReadFrequently true})))]
     (.clearRect ctx 0 0 1 1)
     (set! (.-fillStyle ctx) css)
     (.fillRect ctx 0 0 1 1)
     (when tint
       (set! (.-globalAlpha ctx) alpha)
       (set! (.-fillStyle ctx) tint)
       (.fillRect ctx 0 0 1 1)
       (set! (.-globalAlpha ctx) 1))
     (let [d (.-data (.getImageData ctx 0 0 1 1))]
       (str "#" (apply str (map #(.padStart (.toString (aget d %) 16) 2 "0") [0 1 2])))))))

(def ^:private palette
  "xterm theme key → the web theme's CSS var (and a fallback). The 16 ANSI
   colours keep their usual meaning for shell programs (red = --danger, green
   = --success, black = a surface, bright black = dim text) and carry the
   syntax colours (--hl-*), so a colorscheme limited to them (TERM_PROGRAM=xi,
   xi.ext.terminal.pty) looks like the chat's code blocks in every theme."
  {:background          ["--bg-0" "#ffffff"]
   :foreground          ["--fg-0" "#1f2328"]
   :cursor              ["--accent" "#3b82f6"]
   :cursorAccent        ["--bg-0" "#ffffff"]
   :selectionBackground ["--accent-200" "#bfdbfe"]
   :black               ["--bg-2" "#e5e7eb"]
   :red                 ["--danger" "#d73a49"]
   :green               ["--success" "#22863a"]
   :yellow              ["--warning" "#b08800"]
   :blue                ["--hl-keyword" "#d73a49"]
   :magenta             ["--hl-symbol" "#6f42c1"]
   :cyan                ["--hl-fn" "#005cc5"]
   :white               ["--fg-1" "#586069"]
   :brightBlack         ["--hl-comment" "#6a737d"]
   :brightRed           ["--hl-operator" "#d73a49"]
   :brightGreen         ["--hl-string" "#22863a"]
   :brightYellow        ["--warning" "#b08800"]
   :brightBlue          ["--accent" "#3b82f6"]
   :brightMagenta       ["--hl-number" "#6f42c1"]
   :brightCyan          ["--hl-builtin" "#005cc5"]
   :brightWhite         ["--fg-0" "#1f2328"]})

(def ^:private tints
  "Colours past the 16, xterm's `extendedAnsi` (index 16 on): diff line
   backgrounds that follow the theme like the 16 do (the dotfiles' nvim `xi`
   colorscheme). A custom theme's --diff-add-bg / --diff-del-bg
   (xi.web.theme), else --success / --danger faintly over the background."
  [["--diff-add-bg" "--success" "#22863a"] ["--diff-del-bg" "--danger" "#d73a49"]])

(def ^:private tint-alpha 0.14)

(defn- theme [node]
  (let [bg (css-var node "--bg-0" "#ffffff")]
    (clj->js (assoc (update-vals palette (fn [[k fallback]] (hex-color (css-var node k fallback))))
                    :extendedAnsi (mapv (fn [[themed k fallback]]
                                          (if-let [c (css-var node themed nil)]
                                            (hex-color c)
                                            (hex-color bg (css-var node k fallback) tint-alpha)))
                                        tints)))))

(def ^:private base-font-size 13)

(defn- font-size
  "The base size times the theme's --code-scale (style.css, xi.web.theme)."
  [node]
  (let [scale (js/parseFloat (css-var node "--code-scale" "1"))]
    (js/Math.round (* base-font-size (if (js/isFinite scale) scale 1)))))

(defn- fit! [^js fit]
  (try (.fit fit) (catch :default _ nil)))

(defn- retheme!
  "Follow a theme change: re-read the colours and the font size once its CSS
   vars are applied; a new size re-fits the grid."
  [term-id ^js node]
  (js/requestAnimationFrame
   (fn []
     (when-let [{:keys [^js term fit]} (get @instances term-id)]
       (set! (.. term -options -theme) (theme node))
       (let [size (font-size node)]
         (when (not= size (.. term -options -fontSize))
           (set! (.. term -options -fontSize) size)
           (fit! fit)))))))

(defn- attach! [term-id ^js term]
  (send! {:type :terminal/attach :term-id term-id
          :cols (.-cols term) :rows (.-rows term)}))

;; ── Size ──
;; A fit that changes cols/rows fires xterm's onResize, which tells the server
;; (its PTY passes SIGWINCH on to the program). The PTY has one size: the last
;; any client sent. So the browser in use claims it again when its terminal
;; gets focus or its tab comes back, as tmux follows its latest client.

(defn- send-size! [term-id ^js term]
  (send! {:type :terminal/resize :term-id term-id
          :cols (.-cols term) :rows (.-rows term)}))

(defonce ^:private fit-frame (atom nil))

(defn- fit-all!
  "Re-fit every terminal on screen, once per frame however often it's asked."
  [& _]
  (when-not @fit-frame
    (reset! fit-frame
            (js/requestAnimationFrame
             (fn []
               (reset! fit-frame nil)
               (doseq [{:keys [fit]} (vals @instances)] (fit! fit)))))))

(defn- claim-size! [& _]
  (doseq [[term-id {:keys [term]}] @instances]
    (send-size! term-id term)))

;; Window-level triggers, installed with the first terminal: a window resize
;; the host's ResizeObserver can miss, and the tab or window coming back.
(defonce ^:private window-listeners
  (delay
    (.addEventListener js/window "resize" fit-all!)
    (.addEventListener js/window "focus" claim-size!)
    (.addEventListener js/document "visibilitychange"
                       #(when (= "visible" (.-visibilityState js/document))
                          (fit-all!)
                          (claim-size!)))))

(defonce ^:private xi-key-ref (atom (constantly false)))

;; Set by a keyboard walk through the sessions (xi.web.core's :session/next
;; and friends): a terminal that walk lands on mounts without taking focus, as
;; the walk's next key would then reach the program instead of Xi. The next key or pointer
;; press clears it; window capture runs before the keymap's document listener.
(defonce ^:private skip-mount-focus? (atom false))

(defonce ^:private input-listeners
  (delay
    (doseq [t ["keydown" "pointerdown"]]
      (.addEventListener js/window t #(reset! skip-mount-focus? false) true))))

(defn skip-mount-focus!
  "Keep the terminal the current key press brings on screen unfocused."
  []
  @input-listeners
  (reset! skip-mount-focus? true))

(defn set-xi-key!
  "Install (fn [keydown-event] → bool): true for the keys Xi keeps while a
   terminal has focus (xi.web.keymap/terminal-key?, the :mode/terminal
   layer). xterm ignores those, so they bubble to Xi's keymap."
  [f]
  (reset! xi-key-ref f))

(def osc-code
  "OSC number a program in the terminal writes to run a Xi action:
   ESC ] 7474 ; palette/open BEL. nvim's Ctrl+K in the dotfiles uses it."
  7474)

(def program-actions
  "The actions an OSC may run. Any output can carry the sequence (`cat` of
   a file), so only ones that open or move a view, nothing that deletes,
   answers a permission or sends a message."
  #{:palette/open :buffers/switch :files/find :files/find-edited
    :git/status :git/log :projects/pick :skills/search :themes/pick
    :keys/show :sidebar/toggle :buffer/close :terminal/leave})

(defn osc-action
  "The action keyword an OSC payload names (\"palette/open\"), or nil when
   it is not one of program-actions."
  [payload]
  (let [id (keyword (str/trim (str payload)))]
    (when (contains? program-actions id) id)))

(defonce ^:private run-action-ref (atom (constantly nil)))

(defn set-run-action!
  "Install (fn [action-id]) that runs a Xi action (xi.web.keymap/run-action!)."
  [f]
  (reset! run-action-ref f))

(defonce ^:private replaying (atom #{}))

(defn- on-osc
  "OSC handler of terminal `term-id`. A replayed scrollback holds the OSCs
   already run, so those are dropped."
  [term-id]
  (fn [payload]
    (when-not (contains? @replaying term-id)
      (when-let [id (osc-action payload)]
        (@run-action-ref id)))
    true))

;; ── OSC 52: the program sets the clipboard ──
;; ESC ] 52 ; <selections> ; <base64> BEL, as nvim's osc52 provider writes it.
;; Writes only: answering a query (`?`) would hand the clipboard to whatever
;; runs in the terminal.

(def clipboard-osc 52)

(defn- base64-text [data]
  (try
    (.decode (js/TextDecoder.)
             (.from js/Uint8Array (js/atob data) #(.charCodeAt % 0)))
    (catch :default _ nil)))

(defn osc52-text
  "The text an OSC 52 payload (\"c;aGk=\") puts on the clipboard, nil for a
   query or broken base64."
  [payload]
  (let [[_ data] (str/split (str payload) #";" 2)]
    (when (and data (not= "?" data))
      (base64-text data))))

(defn- on-osc52 [term-id]
  (fn [payload]
    (when-not (contains? @replaying term-id)
      (when-let [text (osc52-text payload)]
        (-> (clipboard/copy! text)
            (.catch #(js/console.warn "[terminal] clipboard:" %)))))
    true))

;; ── OSC 7475: a hint at a cell ──
;; ESC ] 7475 ; <base64 JSON> BEL, the JSON {"row" "col" "items" [{"severity"
;; "text" "source"}]}: a tooltip under that cell (0-based) of the screen, as
;; the dotfiles' nvim sends the diagnostics under its cursor. An empty
;; payload, or no items, hides it. Text only, never HTML.

(def hint-osc 7475)

(def ^:private hint-severities #{"error" "warn" "info" "hint"})

(defn- hint-item [{:keys [severity text source]}]
  (when (and (string? text) (not (str/blank? text)))
    {:severity (if (contains? hint-severities severity) severity "info")
     :text     text
     :source   (when (and (string? source) (not (str/blank? source))) source)}))

(defn- parse-json [s]
  (try (js->clj (js/JSON.parse s) :keywordize-keys true)
       (catch :default _ nil)))

(defn osc-hint
  "The hint an OSC 7475 payload shows → {:row :col :items}, nil to hide it
   (empty, broken, or nothing to show)."
  [payload]
  (let [{:keys [row col items]} (some-> (base64-text (str/trim (str payload))) parse-json)
        items (when (sequential? items) (keep #(when (map? %) (hint-item %)) items))]
    (when (and (nat-int? row) (nat-int? col) (seq items))
      {:row row :col col :items (vec (take 20 items))})))

(defn- hint-box [{:keys [items]}]
  (let [box (.createElement js/document "div")]
    (set! (.-className box) "popover-content terminal-hint-popup")
    (.setAttribute box "role" "tooltip")
    (doseq [{:keys [severity text source]} items]
      (let [row (.createElement js/document "div")
            msg (.createElement js/document "span")]
        (set! (.-className row) (str "terminal-hint-item terminal-hint-item--" severity))
        (set! (.-textContent msg) text)
        (.appendChild row msg)
        (when source
          (let [src (.createElement js/document "span")]
            (set! (.-className src) "terminal-hint-source")
            (set! (.-textContent src) source)
            (.appendChild row src)))
        (.appendChild box row)))
    box))

(defn- place-hint!
  "Below cell row/col of `term`'s screen, above it when the screen ends
   first; kept inside the screen sideways. The box is position: fixed."
  [^js term ^js box {:keys [row col]}]
  (when-let [screen (some-> (.-element term) (.querySelector ".xterm-screen"))]
    (let [r      (.getBoundingClientRect screen)
          cell-w (/ (.-width r) (max 1 (.-cols term)))
          cell-h (/ (.-height r) (max 1 (.-rows term)))
          w      (.-offsetWidth box)
          h      (.-offsetHeight box)
          below  (+ (.-top r) (* (inc row) cell-h) 2)
          above  (- (+ (.-top r) (* row cell-h)) h 2)
          top    (if (and (> (+ below h) (.-bottom r)) (>= above (.-top r))) above below)
          left   (-> (+ (.-left r) (* col cell-w))
                     (min (- (.-right r) w))
                     (max (.-left r)))]
      (set! (.. box -style -top) (str top "px"))
      (set! (.. box -style -left) (str left "px")))))

(defn- render-hint!
  "Show terminal `term-id`'s last :hint, none while :hints? (the
   :terminal-hints? appearance setting) is off. It stays remembered, so
   turning tooltips back on shows it without the program sending it again."
  [term-id]
  (when-let [{:keys [^js term ^js node ^js box hint hints?]} (get @instances term-id)]
    (some-> box (.remove))
    (swap! instances update term-id dissoc :box)
    (when (and hints? hint)
      (let [box (hint-box hint)]
        (.appendChild node box)
        (place-hint! term box hint)
        (swap! instances assoc-in [term-id :box] box)))))

(defn- set-hint! [term-id hint]
  (when (contains? @instances term-id)
    (swap! instances assoc-in [term-id :hint] hint)
    (render-hint! term-id)))

(defn- set-hints-on! [term-id on?]
  (when (and (contains? @instances term-id)
             (not= on? (get-in @instances [term-id :hints?])))
    (swap! instances assoc-in [term-id :hints?] on?)
    (render-hint! term-id)))

(defn- on-hint-osc [term-id]
  (fn [payload]
    (when-not (contains? @replaying term-id)
      (set-hint! term-id (osc-hint payload)))
    true))

(defn- ctrl-key?
  "A plain Ctrl+key the terminal should own outright: the program gets it,
   the browser's binding (Firefox's Ctrl+D bookmark, Ctrl+F find, …) never
   runs. Ctrl+V stays the browser's, it is how a paste reaches xterm."
  [^js e]
  (and (= "keydown" (.-type e))
       (.-ctrlKey e)
       (not (or (.-shiftKey e) (.-altKey e) (.-metaKey e)))
       (not= "v" (str/lower-case (.-key e)))))

(defn- mount! [^js node term-id hints?]
  (-> (load-lib!)
      (.then
       (fn [_]
         (when (and (.-isConnected node) (not (contains? @instances term-id)))
           (let [Terminal (.-Terminal js/globalThis)
                 FitAddon (.. js/globalThis -FitAddon -FitAddon)
                 ^js term (Terminal. #js {:cursorBlink true
                                          :fontFamily  (str (css-var node "--font-mono"
                                                                     "ui-monospace, Menlo, monospace")
                                                            ", 'Symbols Nerd Font Mono'")
                                          :fontSize    (font-size node)
                                          :scrollback  5000
                                          :theme       (theme node)})
                 fit      (FitAddon.)
                 observer (js/ResizeObserver. fit-all!)]
             @window-listeners
             (.loadAddon term fit)
             (.open term node)
             (fit! fit)
             (.attachCustomKeyEventHandler term (fn [e]
                                                  (cond
                                                    (@xi-key-ref e) false
                                                    (ctrl-key? e)   (do (.preventDefault e) true)
                                                    :else           true)))
             (.. term -parser (registerOscHandler osc-code (on-osc term-id)))
             (.. term -parser (registerOscHandler clipboard-osc (on-osc52 term-id)))
             (.. term -parser (registerOscHandler hint-osc (on-hint-osc term-id)))
             (.onData term #(send! {:type :terminal/input :term-id term-id :text %}))
             (.onResize term #(do (set-hint! term-id nil) (send-size! term-id term)))
             (.addEventListener (.-textarea term) "focus" #(send-size! term-id term))
             (.observe observer node)
             (swap! instances assoc term-id {:term term :fit fit :observer observer :node node
                                             :hints? hints?})
             (attach! term-id term)
             (when-not @skip-mount-focus? (.focus term))))))
      (.catch #(js/console.error "[terminal]" %))))

(defn- unmount! [term-id]
  (when-let [{:keys [^js term ^js observer]} (get @instances term-id)]
    (swap! instances dissoc term-id)
    (swap! replaying disj term-id)
    (send! {:type :terminal/detach :term-id term-id})
    (.disconnect observer)
    (.dispose term)))

(defn reattach!
  "Attach on-screen terminal `term-id` again (it was gone and restarted)."
  [term-id]
  (when-let [term (get-in @instances [term-id :term])]
    (attach! term-id term)))

(defn reattach-all!
  "After a reconnect: the server forgot this client's subscriptions."
  []
  (run! reattach! (keys @instances)))

(defn focus!
  "Focus the terminal on screen (the :terminal/focus key)."
  []
  (some (fn [{:keys [^js term ^js node]}]
          (when (.-isConnected node) (.focus term) true))
        (vals @instances)))

(defn write!
  "Type `text` into terminal `term-id` as if from the keyboard."
  [term-id text]
  (when (contains? @instances term-id)
    (send! {:type :terminal/input :term-id term-id :text text})
    (some-> (get-in @instances [term-id :term]) .focus)))

(defn intercept
  "Transport hook (xi.client.ws-transport :intercept): writes the server's
   output into its terminal and swallows it. `:reset?` marks a replay. An
   exit is swallowed too: the buffer's `:exit-code` carries it."
  [{:keys [type term-id text reset?]}]
  (case type
    :terminal/output (do (when-let [^js term (get-in @instances [term-id :term])]
                           (if reset?
                             (do (set-hint! term-id nil)
                                 (.reset term)
                                 (swap! replaying conj term-id)
                                 (.write term text #(swap! replaying disj term-id)))
                             (.write term text)))
                         true)
    :terminal/exit   true
    false))

;; ── Toolbar items ────────────────────────────────────────────────────────────

(defonce ^:private toolbar-providers (atom {}))

(defn register-toolbar!
  "Set extension `ext-id`'s `:terminal-toolbar-items` provider: (fn [buffer
   state] → [{:label :icon :title} + :send \"keys\" | :event {…}])."
  [ext-id f]
  (swap! toolbar-providers assoc ext-id f))

(defn toolbar-items [buffer state]
  (into [] (mapcat (fn [[_ f]] (try (f buffer state) (catch :default _ nil))))
        @toolbar-providers))

;; ── View ─────────────────────────────────────────────────────────────────────

(defn- toolbar [dispatch! {:keys [term-id] :as buffer} state]
  (when-let [items (seq (filter #(string? (:label %)) (toolbar-items buffer state)))]
    [:div {:class ["terminal-toolbar"]}
     [:div {:class ["fgroup"]}
      (for [[i {:keys [label icon title send event]}] (map-indexed vector items)]
        [:button {:class ["quick-cmd"] :replicant/key (str "tb-" i)
                  :title (or title label) :aria-label (or title label)
                  ;; keep the terminal focused (no soft-keyboard flicker)
                  :on {:mousedown (fn [^js e] (.preventDefault e))
                       :click (fn [_]
                                (cond
                                  (string? send) (write! term-id send)
                                  (map? event)   (dispatch! event)))}}
         (if icon
           (icon/icon {:icon-name icon :size :sm})
           [:span label])])]]))

(defn- ended-bar [dispatch! room-id buffer-id {:keys [exit-code error]} gone?]
  [:div {:class ["terminal-ended"]}
   [:span (cond
            error              error
            (some? exit-code)  (str "The program exited with code " exit-code ".")
            gone?              "This terminal ended when the server restarted.")]
   (when-not error
     (button/button {:variant :secondary :size :sm
                     :on-click (fn [_] (dispatch! {:type :terminal/restart
                                                   :room-id room-id :buffer-id buffer-id}))}
                    "Restart"))
   (button/button {:variant :ghost :size :sm
                   :on-click (fn [_] (dispatch! {:type :ui/buffer-close
                                                 :room-id room-id :buffer-id buffer-id}))}
                  "Close")])

(defn view
  "The `:terminal` buffer `buffer-id` of `room`. `gone?`: the server no longer
   runs it (xi.ext.terminal.web keeps that per term id)."
  [dispatch! state room buffer-id {:keys [term-id title error exit-code] :as buffer} gone?]
  (let [hints? (:terminal-hints? (appearance/effective-in state))]
    [:div {:class ["file-tab" "terminal-tab"]}
     [:div {:class ["file-tab-header"]}
      (icon/icon {:icon-name :terminal :size :sm})
      [:span {:class ["file-tab-path"]} title]
      (when-let [k (keys/shortcut (keymap/keymap state) [:mode/terminal] :terminal/leave)]
        [:span {:class ["terminal-hint"]} (str k " leaves the terminal")])]
     (when (or error (some? exit-code) gone?)
       (ended-bar dispatch! (:id room) buffer-id buffer gone?))
     (when-not error
       [:div {:class ["terminal-host"]
              :replicant/key (str "term-" term-id)
              ;; changes with the theme, so on-update runs and re-colours xterm
              :data-theme (str (hash [(:web/theme-mode state) (:web/themes state)
                                      (:web/theme-draft state)]))
              ;; and with the tooltip setting, so on-update applies it
              :data-hints (str hints?)
              :replicant/on-mount (fn [{:replicant/keys [node]}] (mount! node term-id hints?))
              :replicant/on-update (fn [{:replicant/keys [node]}]
                                     (retheme! term-id node)
                                     (set-hints-on! term-id hints?))
              :replicant/on-unmount (fn [_] (unmount! term-id))}])
     (toolbar dispatch! buffer state)]))
