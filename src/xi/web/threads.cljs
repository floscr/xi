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

(defn- compose-open [st {:keys [room-id] :as ev}]
  (when-let [node (node-of st room-id ev)]
    {:state (-> st
                (dissoc :web/thread-menu :web/code-menu :web/bubble-menu)
                (assoc :web/thread-compose {:room-id room-id :node node}))}))

(defn- ask [room-id m]
  [:ws/send (merge {:type :subagent/ask :room-id room-id} m)])

(defn- compose-submit [st {:keys [text]}]
  (let [{:keys [room-id node]} (:web/thread-compose st)
        q (some-> text str/trim not-empty)]
    (when (and room-id q)
      {:state   (dissoc st :web/thread-compose)
       :effects [(ask room-id {:node node :question q})]})))

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
  "A long-press also asks on a tool or thinking header (summary), but not on
   a file path in it (its file menu, xi.web.core)."
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
   centred on its first line, and name the block in its data attributes. Its
   top is relative to the .timeline-content holding both, so it scrolls with
   the block."
  [^js el]
  (when-let [content (.closest el ".timeline-content")]
    (when-let [btn (.querySelector content ":scope > .ask-hover")]
      (let [top (- (first-line-center el)
                   (/ (.-offsetHeight btn) 2)
                   (.-top (.getBoundingClientRect content)))
            ds  (.-dataset btn)]
        (set! (.. btn -style -top) (str (max 0 top) "px"))
        (set! (.-askIndex ds) (or (.getAttribute el "data-ask-index") ""))
        (set! (.-askSub ds) (or (.getAttribute el "data-ask-sub") ""))
        (.add (.-classList btn) "is-visible")))))

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
   the Ask button there; a touch tap, or a touch long-press (contextmenu), on a
   block opens its menu. Right-click with a mouse keeps the browser's menu.
   `dispatch-ref` holds the app's dispatch!."
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
               block (do (cancel-hide!) (show-hover-button! block))
               :else (hide-soon!)))))))
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
