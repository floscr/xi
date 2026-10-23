(ns xi.web.threads
  "The browser side of question threads on chat blocks
   (xi.ext.subagent.threads): which block the composer and the touch menu
   are open on, and the delegated listeners behind the floating Ask button
   (desktop hover) and that menu (touch tap or long-press). The threads
   themselves are the room's mirrored sub-agents; xi.web.views renders them
   under their block. A question goes to the server as :subagent/ask, whose
   spawn comes back as a broadcast."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.subagent.handlers :as sa]
            [xi.ext.subagent.threads :as t]
            [xi.web.views :as views]))

;; ── State ────────────────────────────────────────────────────────────────────

(defn node-of
  "The node a post names (its data-ask-* attributes: `index`, `sub-id`) in
   room `room-id`, or nil when that block is gone."
  [st room-id {:keys [index sub-id]}]
  (cond
    (and sub-id (number? index))
    (t/entry-node sub-id (:history (sa/find-child st room-id sub-id)) index)

    sub-id
    (when-let [child (sa/find-child st room-id sub-id)]
      (t/agent-node sub-id (:label child)))

    (number? index)
    (t/entry-node (:history (state/get-room st room-id)) index)))

(defn- compose-open
  "Open the composer on the block `ev` names; `:selection` is text the user
   selected in it, which the question is then about."
  [st {:keys [room-id selection] :as ev}]
  (when-let [node (node-of st room-id ev)]
    (let [sel (some-> selection str/trim not-empty)]
      {:state (-> st
                  (dissoc :web/thread-menu :web/code-menu :web/bubble-menu)
                  (assoc :web/thread-compose
                         (cond-> {:room-id room-id :node node}
                           sel (assoc :selection sel))))})))

(defn- ask [room-id m]
  [:ws/send (merge {:type :subagent/ask :room-id room-id} m)])

(defn- compose-submit [st {:keys [text]}]
  (let [{:keys [room-id node selection]} (:web/thread-compose st)
        q (some-> text str/trim not-empty)]
    (when (and room-id q)
      {:state   (dissoc st :web/thread-compose)
       :effects [(ask room-id (cond-> {:node node :question q}
                                selection (assoc :selection selection)))]})))

(defn- reply [_st {:keys [room-id thread text]}]
  (when-let [q (some-> text str/trim not-empty)]
    {:effects [(ask room-id {:thread thread :question q})]}))

(def handlers
  {:thread/compose-open   compose-open
   :thread/compose-close  (fn [st _] {:state (dissoc st :web/thread-compose)})
   :thread/compose-submit compose-submit
   :thread/reply          reply
   :thread/toggle         (fn [st {:keys [thread]}]
                            {:state (update st :web/thread-collapsed
                                            (fn [s] (let [s (or s #{})]
                                                      (if (s thread) (disj s thread) (conj s thread)))))})
   ;; the file change its card shows (xi.web.views/thread-changes)
   :thread/rev            (fn [st {:keys [thread rev]}]
                            {:state (assoc-in st [:web/thread-rev thread] rev)})
   :thread/menu-open      (fn [st ev]
                            {:state (assoc st :web/thread-menu
                                           (select-keys ev [:index :sub-id :text :x :y]))})
   :thread/menu-close     (fn [st _] {:state (dissoc st :web/thread-menu)})})

;; ── DOM listeners ────────────────────────────────────────────────────────────

(def ^:private block-selector "[data-ask-index],[data-ask-sub]")

(def ^:private tap-skip
  "What a tap means something else on: links, controls, code (its own menu,
   xi.web.core), user bubbles (theirs, with Ask in it), thread cards."
  (str "a, button, summary, input, textarea, select, label, [role=button], img, "
       "pre, code, .md-diff, .post--user, .thread-card, .bubble-menu, .ask-hover"))

(def ^:private long-press-skip
  "A long-press also asks on a tool or thinking header (summary), unless the
   row names a file: its file menu (xi.web.core) offers Ask itself."
  (str "a, input, textarea, select, img, pre, code, .md-diff, .post--user, "
       ".thread-card, .bubble-menu, .file-path"))

(defn- block-ref [^js el]
  {:index  (some-> (.getAttribute el "data-ask-index") not-empty parse-long)
   :sub-id (not-empty (.getAttribute el "data-ask-sub"))})

(defn- first-line-center
  "Viewport y of the middle of block `el`'s first line: its header row
   (a tool or thinking <summary>) or else its first line of text."
  [^js el]
  (if-let [head (.querySelector el ":scope > summary, :scope > details > summary")]
    (let [r (.getBoundingClientRect head)]
      (+ (.-top r) (/ (.-height r) 2)))
    (let [cs (js/getComputedStyle el)
          lh (js/parseFloat (.-lineHeight cs))]
      (+ (.-top (.getBoundingClientRect el))
         (js/parseFloat (.-borderTopWidth cs))
         (js/parseFloat (.-paddingTop cs))
         (/ (if (js/isNaN lh) 24 lh) 2)))))

(defn- show-hover-button!
  "Move the timeline's Ask button (views/ask-hover-button) to block `el`,
   centred on its first line (or at viewport `y`: the end of a selection),
   and name the block in its data attributes, with the `selection` text the
   question is about. Its top is relative to the .timeline-content holding
   both, so it scrolls with the block."
  ([el] (show-hover-button! el nil))
  ([^js el {:keys [y selection]}]
   (when-let [content (.closest el ".timeline-content")]
     (when-let [btn (.querySelector content ":scope > .ask-hover")]
       (let [top (- (or y (first-line-center el))
                    (/ (.-offsetHeight btn) 2)
                    (.-top (.getBoundingClientRect content)))
             ds  (.-dataset btn)]
         (set! (.. btn -style -top) (str (max 0 top) "px"))
         (set! (.-askIndex ds) (or (.getAttribute el "data-ask-index") ""))
         (set! (.-askSub ds) (or (.getAttribute el "data-ask-sub") ""))
         (set! (.-askSelection ds) (or selection ""))
         (.add (.-classList btn) "is-visible"))))))

;; ── Selections ───────────────────────────────────────────────────────────────

(defn- block-selection
  "What the user has selected inside one block of the timeline: {:el the
   block :text :y the viewport y of the selection's last line}. nil when
   nothing is selected, the selection spans blocks, or it is in a user
   bubble."
  []
  (when-let [sel (.getSelection js/window)]
    (when (and (not (.-isCollapsed sel)) (pos? (.-rangeCount sel)))
      (let [text  (str/trim (.toString sel))
            el-of (fn [^js n]
                    (some-> (if (instance? js/Element n) n (some-> n .-parentElement))
                            (.closest block-selector)))
            a     (el-of (.-anchorNode sel))
            f     (el-of (.-focusNode sel))]
        (when (and (seq text) a (identical? a f)
                   (.closest a ".timeline-content")
                   (not (.closest a ".post--user")))
          (let [rects (.getClientRects (.getRangeAt sel 0))
                n     (.-length rects)
                last  (when (pos? n) (.item rects (dec n)))]
            {:el a :text text
             :y  (when last (+ (.-top last) (/ (.-height last) 2)))}))))))

(defn- selection-pending?
  "Is the Ask button on a selection (show-hover-button! with one)?"
  []
  (boolean (some-> (.querySelector js/document ".ask-hover") .-dataset .-askSelection not-empty)))

(defn- hide-hover-buttons! []
  (doseq [btn (array-seq (.querySelectorAll js/document ".ask-hover.is-visible"))]
    (.remove (.-classList btn) "is-visible")))

;; The button sits beside its block, past a gap that is no block: hiding waits
;; a moment so the pointer can cross it; reaching the button or a block cancels.
(def ^:private hide-grace-ms 300)

(defonce ^:private hide-timer (atom nil))

(defn- cancel-hide! []
  (some-> @hide-timer js/clearTimeout)
  (reset! hide-timer nil))

(defn- hide-soon! []
  (when-not @hide-timer
    (reset! hide-timer (js/setTimeout (fn []
                                        (reset! hide-timer nil)
                                        (hide-hover-buttons!))
                                      hide-grace-ms))))

(defn- drop-selection!
  "The selection is gone: the Ask button stops asking about it and hides."
  []
  (when-let [btn (.querySelector js/document ".ask-hover")]
    (set! (.-askSelection (.-dataset btn)) ""))
  (hide-soon!))

(defn- open-menu! [dispatch! ^js e ^js el]
  (let [{:keys [index sub-id]} (block-ref el)]
    (dispatch! {:type    :thread/menu-open
                :index   index
                :sub-id  sub-id
                :text    (some-> (.-innerText el) str/trim)
                :x       (.-clientX e)
                :y       (.-clientY e)})))

(defonce ^:private attached? (atom false))

(defn attach-listeners!
  "Delegated document listeners, once: a mouse pointer over a block shows
   the Ask button there; text selected with the mouse inside a block moves
   it to the selection's end, where it asks about that text, and keeps it
   there until the selection is gone; a touch tap, or a touch long-press
   (contextmenu), on a block opens its menu. Right-click with a mouse keeps
   the browser's menu. `dispatch-ref` holds the app's dispatch!."
  [dispatch-ref]
  (when-not @attached?
    (reset! attached? true)
    (.addEventListener
     js/document "pointerover"
     (fn [^js e]
       (let [t (.-target e)]
         (when (and (= "mouse" (.-pointerType e)) (instance? js/Element t))
           (let [block (when (.closest t ".timeline-content")
                         (.closest t block-selector))]
             (cond
               (.closest t ".ask-hover") (cancel-hide!)
               ;; the Ask stays on the selection while there is one
               (and (selection-pending?) (block-selection)) (cancel-hide!)
               block (do (cancel-hide!) (show-hover-button! block))
               :else (hide-soon!)))))))
    (.addEventListener
     js/document "pointerup"
     (fn [^js e]
       (when (and (= "mouse" (.-pointerType e))
                  (not (some-> (.-target e) (.closest ".ask-hover"))))
         (if-let [{:keys [el text y]} (block-selection)]
           (do (cancel-hide!) (show-hover-button! el {:y y :selection text}))
           (when (selection-pending?) (drop-selection!))))))
    (let [handle (fn [^js e skip]
                   (let [t (.-target e)]
                     (when (and (views/tap-opens-context-menu?)
                                (instance? js/Element t)
                                (not (.closest t skip)))
                       (when-let [el (.closest t block-selector)]
                         (when (.closest el ".timeline-content")
                           (when-let [d @dispatch-ref]
                             (open-menu! d e el)
                             true))))))]
      (.addEventListener js/document "click" (fn [e] (handle e tap-skip)))
      (.addEventListener js/document "contextmenu"
                         (fn [^js e] (when (handle e long-press-skip)
                                       (.preventDefault e)))))))
