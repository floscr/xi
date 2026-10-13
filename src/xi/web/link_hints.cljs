(ns xi.web.link-hints
  "Vimium-style link hints: `show!` labels every clickable element on screen
   with a short home-row string; typing a label clicks its element (focuses
   it when it is a text field), Shift on the last letter opens a link in a
   new tab, Backspace takes a letter back, Escape cancels.

   Pure DOM, like xi.web.key-hints: candidates are the semantic clickables
   plus the sidebar's `[data-nav]` rows, kept when they are visible and win a
   hit-test (so nothing behind a dialog or scrolled out of a pane). The
   badges live in a top-layer popover outside the Replicant root, drawn by
   style.css. While they show, keys are taken in the capture phase, so the
   keymap (xi.web.keymap) never sees them. Bound to :hints/show and
   :hints/sidebar in xi.web.core."
  (:require [clojure.string :as str]))

(def alphabet "sadfjklewcmpgh")

(defn labels
  "`n` prefix-free labels over `alphabet`, as short as possible (Vimium's
   scheme): single letters while they suffice, then pairs on a letter the
   singles leave free, and so on."
  [n]
  (when (pos? n)
    (loop [hints [""] offset 0]
      (if (or (= 1 (count hints)) (< (- (count hints) offset) n))
        (recur (into hints (map #(str % (hints offset))) alphabet) (inc offset))
        (->> (subvec hints offset (+ offset n)) sort (mapv str/reverse))))))

;; ── Candidates ───────────────────────────────────────────────────────────────

(def ^:private selector
  (str/join ", " ["a[href]" "button:not([disabled])" "summary"
                  "input:not([type=hidden]):not([disabled])"
                  "textarea:not([disabled])" "select:not([disabled])"
                  "[contenteditable]:not([contenteditable=false])"
                  "[role=button]" "[role=link]" "[role=tab]" "[role=option]"
                  "[role=menuitem]" "[role=menuitemradio]" "[role=menuitemcheckbox]"
                  "[role=checkbox]" "[role=radio]" "[role=switch]"
                  ;; sidebar rows (session cards are divs with a click handler)
                  "[data-nav]"]))

(defn- rect [^js el]
  (let [r (.getBoundingClientRect el)]
    [(.-left r) (.-top r) (.-right r) (.-bottom r)]))

(defn- viewport [] [0 0 js/innerWidth js/innerHeight])

(defn- intersect [[l t r b] [l2 t2 r2 b2]]
  [(max l l2) (max t t2) (min r r2) (min b b2)])

(defn- empty-rect? [[l t r b]]
  (or (< (- r l) 2) (< (- b t) 2)))

(defn- fixed? [^js el]
  (= "fixed" (.-position (js/getComputedStyle el))))

(defn- clip
  "The viewport area `el`'s descendants show in: the rects of the
   overflow-clipping ancestors (`el` included) intersected; a fixed element
   starts afresh from the viewport. Memoised per element in `cache`."
  [^js el ^js cache]
  (cond
    (nil? el)       (viewport)
    (.has cache el) (.get cache el)
    :else
    (let [st   (js/getComputedStyle el)
          base (if (= "fixed" (.-position st))
                 (viewport)
                 (clip (.-parentElement el) cache))
          c    (if (and (= "visible" (.-overflowX st)) (= "visible" (.-overflowY st)))
                 base
                 (intersect base (rect el)))]
      (.set cache el c)
      c)))

(defn- visible-area [^js el cache]
  (let [base (if (fixed? el) (viewport) (clip (.-parentElement el) cache))
        area (intersect base (rect el))]
    (when-not (empty-rect? area) area)))

(defn- painted?
  "Not hidden by display, visibility or opacity anywhere up the tree (a
   hover-only button is not offered)."
  [^js el]
  (or (not (.-checkVisibility el))
      (.checkVisibility el #js {:opacityProperty true :visibilityProperty true})))

(defn- on-top?
  "True when a point of `area` hits `el` (or a child): nothing covers it."
  [^js el [l t r b]]
  (some (fn [[x y]]
          (when-let [hit (.elementFromPoint js/document x y)]
            (.contains el hit)))
        [[(/ (+ l r) 2) (/ (+ t b) 2)] [(+ l 2) (+ t 2)] [(- r 2) (- b 2)]]))

(defn- candidates
  "[el area] of every clickable under `root` a click could reach right now."
  [^js root]
  (let [cache (js/Map.)]
    (->> (array-seq (.querySelectorAll root selector))
         (keep (fn [el]
                 (when (painted? el)
                   (when-let [area (visible-area el cache)]
                     (when (on-top? el area) [el area])))))
         vec)))

;; ── Activation ───────────────────────────────────────────────────────────────

(def ^:private button-inputs
  #{"checkbox" "radio" "button" "submit" "reset" "range" "color" "file" "image"})

(defn- text-field? [^js el]
  (let [tag (.-tagName el)]
    (or (.-isContentEditable el)
        (= "TEXTAREA" tag)
        (= "SELECT" tag)
        (and (= "INPUT" tag) (not (button-inputs (.-type el)))))))

(defn- activate! [^js el new-tab?]
  (cond
    (and new-tab? (= "A" (.-tagName el)) (seq (.-href el)))
    (.open js/window (.-href el) "_blank" "noopener")

    (text-field? el) (.focus el)
    :else            (.click el)))

;; ── Overlay ──────────────────────────────────────────────────────────────────

(defonce ^:private session (atom nil))

(defn- span [class text]
  (doto (.createElement js/document "span")
    (-> .-className (set! class))
    (-> .-textContent (set! text))))

(defn- hint-node [label [l t _ _]]
  (let [typed (span "link-hint-typed" "")
        rest  (span "link-hint-rest" label)
        node  (span "link-hint" "")]
    (.append node typed rest)
    (set! (.. node -style -left) (str l "px"))
    (set! (.. node -style -top) (str t "px"))
    {:node node :typed typed :rest rest}))

(defn- render! [{:keys [hints typed]}]
  (doseq [{:keys [label node] :as h} hints]
    (if (str/starts-with? label typed)
      (do (.removeAttribute node "hidden")
          (set! (.-textContent (:typed h)) typed)
          (set! (.-textContent (:rest h)) (subs label (count typed))))
      (.setAttribute node "hidden" ""))))

(def ^:private modifier-keys
  #{"Shift" "Control" "Alt" "Meta" "AltGraph" "CapsLock" "Dead"})

(defn- hide! []
  (when-let [{:keys [overlay listeners]} @session]
    (reset! session nil)
    (doseq [[target type f] listeners]
      (.removeEventListener target type f true))
    (.remove overlay)))

(defn- cancel! []
  (when-let [{:keys [on-cancel]} @session]
    (hide!)
    (when on-cancel (on-cancel))))

(defn- type-letter!
  "Add `letter` to the typed prefix; a letter no label continues is ignored."
  [letter new-tab?]
  (let [{:keys [hints typed]} @session
        typed (str typed letter)]
    (if-let [hit (some #(when (= typed (:label %)) %) hints)]
      (do (hide!) (activate! (:el hit) new-tab?))
      (when (some #(str/starts-with? (:label %) typed) hints)
        (render! (swap! session assoc :typed typed))))))

(defn- on-keydown [^js e]
  (let [k (.-key e)]
    (cond
      (contains? modifier-keys k) nil
      ;; a shortcut: end hinting and let it through
      (or (.-ctrlKey e) (.-metaKey e) (.-altKey e)) (cancel!)
      :else
      (do (.preventDefault e)
          (.stopImmediatePropagation e)
          (let [letter (str/lower-case k)]
            (cond
              (= "Escape" k)    (cancel!)
              (= "Backspace" k) (render! (swap! session update :typed
                                                #(subs % 0 (max 0 (dec (count %))))))
              (and (= 1 (count letter)) (str/includes? alphabet letter))
              (type-letter! letter (.-shiftKey e))))))))

(defn- listen! []
  (let [ls [[js/window "keydown" on-keydown]
            ;; a pointer or the wheel takes over: the badges would be stale
            [js/window "pointerdown" cancel!]
            [js/window "wheel" cancel!]
            [js/window "resize" cancel!]
            [js/window "blur" cancel!]]]
    (doseq [[target type f] ls]
      (.addEventListener target type f true))
    ls))

(defn show!
  "Label the clickables inside the element at `scope` (a CSS selector;
   default the whole page). `on-cancel` runs when hinting ends without a
   click: Escape, a pointer, the wheel, leaving the window."
  ([] (show! nil))
  ([{:keys [scope on-cancel]}]
   (cancel!)
   (let [root (if scope (.querySelector js/document scope) js/document)
         found (some-> root candidates)]
     (if (empty? found)
       (when on-cancel (on-cancel))
       (let [overlay (doto (.createElement js/document "div")
                       (-> .-className (set! "link-hints"))
                       (.setAttribute "popover" "manual"))
             hints   (mapv (fn [[el area] label]
                             (assoc (hint-node label area) :el el :label label))
                           found (labels (count found)))]
         (doseq [{:keys [node]} hints] (.append overlay node))
         (.append (.-body js/document) overlay)
         ;; the top layer: above an open modal <dialog> too
         (when (.-showPopover overlay) (.showPopover overlay))
         (reset! session {:hints hints :typed "" :overlay overlay
                          :on-cancel on-cancel :listeners (listen!)}))))))

(defn- rect-on-screen? [[l t r b]]
  (and (> r l) (> b t) (> r 0) (> b 0) (< l js/innerWidth) (< t js/innerHeight)))

(defn- when-settled
  "Run `f` once the element at `selector` is on screen and has stopped
   moving (a drawer sliding in), or after ~40 frames."
  [selector f]
  (letfn [(step [prev n]
            (js/requestAnimationFrame
             (fn []
               (let [r (some-> (.querySelector js/document selector) rect)]
                 (if (or (zero? n) (and r (= r prev) (rect-on-screen? r)))
                   (f)
                   (step r (dec n)))))))]
    (step nil 40)))

(defn- on-screen? [selector]
  (boolean (some-> (.querySelector js/document selector) rect rect-on-screen?)))

(def ^:private sidebar "aside.sidebar")

(defn show-sidebar!
  "Hints on the sidebar only. A closed drawer is opened for them (`open!`)
   and closed again (`close!`) when they are cancelled."
  [open! close!]
  (if (on-screen? sidebar)
    (show! {:scope sidebar})
    (do (open!)
        (when-settled sidebar #(show! {:scope sidebar :on-cancel close!})))))
