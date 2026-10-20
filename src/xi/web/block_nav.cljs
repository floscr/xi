(ns xi.web.block-nav
  "Keyboard moves over the agent blocks (xi.ext.blocks.web) in the chat
   timeline. :block/next / :block/prev jump to the next / previous block and
   focus its first control; inside a block the arrow keys, Home and End move
   between its controls (`on-keydown`, which the blocks' web half attaches to
   each block), Enter and Space activate them as usual, and Esc leaves the
   block so the navigate-mode keys work again. Text fields and selects keep
   their own arrows. Everything is read from the DOM on each press; the pure
   `step` is tested in xi.web.block-nav-test."
  (:require [xi.web.answer-nav :as answer-nav]))

;; ── Pure ─────────────────────────────────────────────────────────────────────

(defn step
  "Index of the control to focus among `n` after `key`, from index `i` (nil:
   none of them has focus); nil when the key moves nothing."
  [n i key]
  (when (pos? n)
    (case key
      ("ArrowDown" "ArrowRight") (if (nil? i) 0 (min (dec n) (inc i)))
      ("ArrowUp" "ArrowLeft")    (if (nil? i) (dec n) (max 0 (dec i)))
      "Home"                     0
      "End"                      (dec n)
      nil)))

;; ── DOM ──────────────────────────────────────────────────────────────────────

(def ^:private control-selector
  (str "button:not([disabled]), a[href], input:not([disabled]), textarea:not([disabled]), "
       "[role=combobox], iframe, [tabindex]:not([tabindex='-1'])"))

(def ^:private own-arrows-selector
  "Elements whose arrow keys move a caret or a selection."
  (str "textarea, [role=combobox], [role=listbox], [role=listbox] *, "
       "input:not([type=checkbox]):not([type=radio]):not([type=button])"))

(defn- visible? [^js el]
  (pos? (.-length (.getClientRects el))))

(defn- controls [^js block]
  (when-let [body (.querySelector block ".agent-block-body")]
    (filterv visible? (array-seq (.querySelectorAll body control-selector)))))

(defn- focus! [^js el]
  (.focus el #js {:preventScroll true}))

(defn on-keydown
  "Roving focus inside one block: the keydown handler of `.agent-block`."
  [^js e]
  (let [^js target (.-target e)
        key        (.-key e)]
    (when-not (or (.-altKey e) (.-ctrlKey e) (.-metaKey e) (.-shiftKey e)
                  (.matches target own-arrows-selector))
      (if (= "Escape" key)
        (when (.closest target ".agent-block")
          (.preventDefault e)
          (.blur target))
        (when-let [^js block (.closest target ".agent-block")]
          (let [cs (controls block)
                i  (first (keep-indexed (fn [j ^js c] (when (.contains c target) j)) cs))]
            (when-let [j (step (count cs) i key)]
              (.preventDefault e)
              (.stopPropagation e)
              (focus! (nth cs j)))))))))

(defn- top [^js el]
  (.-top (.getBoundingClientRect el)))

(defn- landing-y [^js view]
  (+ (top view) (js/parseFloat (.-paddingTop (js/getComputedStyle view))) 8))

(defn jump!
  "Focus the next / previous (`dir`) block: from the one holding focus, else
   from the top of the view. `scroll-to!` (fn [view el y]) eases the view
   until el's top sits at viewport y. True when there was one."
  [dir scroll-to!]
  (when-let [^js view (.querySelector js/document ".timeline:not(.timeline--sub)")]
    (let [blocks  (vec (array-seq (.querySelectorAll view ".agent-block")))
          landing (landing-y view)
          current (some-> js/document.activeElement (.closest ".agent-block"))
          at      (when current (first (keep-indexed (fn [i b] (when (identical? b current) i)) blocks)))
          i       (if at
                    (let [k (if (= dir :next) (inc at) (dec at))]
                      (when (< -1 k (count blocks)) k))
                    (answer-nav/target (mapv #(- (top %) landing) blocks) dir))]
      (when-let [^js block (some->> i (nth blocks))]
        (focus! (or (first (controls block)) block))
        (scroll-to! view block landing)
        true))))
