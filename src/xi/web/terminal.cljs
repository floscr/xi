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
   theme's oklch()); a canvas does the conversion."
  [css]
  (let [^js ctx (or @color-ctx
                    (reset! color-ctx (.getContext (.createElement js/document "canvas") "2d"
                                                   #js {:willReadFrequently true})))]
    (.clearRect ctx 0 0 1 1)
    (set! (.-fillStyle ctx) css)
    (.fillRect ctx 0 0 1 1)
    (let [d (.-data (.getImageData ctx 0 0 1 1))]
      (str "#" (apply str (map #(.padStart (.toString (aget d %) 16) 2 "0") [0 1 2]))))))

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

(defn- theme [node]
  (clj->js (update-vals palette (fn [[k fallback]] (hex-color (css-var node k fallback))))))

(defn- retheme!
  "Follow a theme change: re-read the colours once its CSS vars are applied."
  [term-id ^js node]
  (js/requestAnimationFrame
   (fn []
     (when-let [^js term (get-in @instances [term-id :term])]
       (set! (.. term -options -theme) (theme node))))))

(defn- attach! [term-id ^js term]
  (send! {:type :terminal/attach :term-id term-id
          :cols (.-cols term) :rows (.-rows term)}))

(defn- fit! [^js fit]
  (try (.fit fit) (catch :default _ nil)))

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

(defn- ctrl-key?
  "A plain Ctrl+key the terminal should own outright: the program gets it,
   the browser's binding (Firefox's Ctrl+D bookmark, Ctrl+F find, …) never
   runs. Ctrl+V stays the browser's, it is how a paste reaches xterm."
  [^js e]
  (and (= "keydown" (.-type e))
       (.-ctrlKey e)
       (not (or (.-shiftKey e) (.-altKey e) (.-metaKey e)))
       (not= "v" (str/lower-case (.-key e)))))

(defn- mount! [^js node term-id]
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
                                          :fontSize    13
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
             (.onData term #(send! {:type :terminal/input :term-id term-id :text %}))
             (.onResize term #(send-size! term-id term))
             (.addEventListener (.-textarea term) "focus" #(send-size! term-id term))
             (.observe observer node)
             (swap! instances assoc term-id {:term term :fit fit :observer observer :node node})
             (attach! term-id term)
             (.focus term)))))
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
                             (do (.reset term)
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
            :replicant/on-mount (fn [{:replicant/keys [node]}] (mount! node term-id))
            :replicant/on-update (fn [{:replicant/keys [node]}] (retheme! term-id node))
            :replicant/on-unmount (fn [_] (unmount! term-id))}])
   (toolbar dispatch! buffer state)])
