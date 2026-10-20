(ns xi.web.answer-nav
  "Keyboard jumps between the agent's answers in the chat timeline
   (:answer/next, :answer/prev). An answer is the final text post of a turn:
   assistant text with no tool call or thinking after it before the next user
   message or the end, so the text the agent writes between tool calls is
   skipped. The posts are read from the rendered timeline on every press.

   A jump lands the answer's top under the floating chat header and marks it.
   While the mark is on screen the next jump steps on from it rather than
   from the top of the view, so answers near the bottom, which can never
   reach the top, are walked too. Going back past the oldest rendered answer
   shows earlier messages (the timeline's window, xi.web.views) first.

   The pure `turn-ends` and `target` are tested in xi.web.answer-nav-test.")

;; ── Pure ─────────────────────────────────────────────────────────────────────

(defn turn-ends
  "Indices of the answers among `kinds` (:user / :text / :work, document
   order): each :text followed by a :user, or by nothing unless the turn is
   still `running?`."
  [kinds running?]
  (let [kinds (vec kinds)
        n     (count kinds)]
    (filterv (fn [i] (and (= :text (kinds i))
                          (if (= i (dec n))
                            (not running?)
                            (= :user (kinds (inc i))))))
             (range n))))

(def ^:private slack-px
  "A post this close to the reference line counts as on it."
  4)

(defn target
  "Index of the post to jump to, given each post's top relative to the
   reference line (document order): the first below it for :next, the last
   above it for :prev; nil when there is none."
  [tops dir]
  (if (= dir :next)
    (first (keep-indexed (fn [i top] (when (> top slack-px) i)) tops))
    (last (keep-indexed (fn [i top] (when (< top (- slack-px)) i)) tops))))

;; ── DOM ──────────────────────────────────────────────────────────────────────

(def ^:private text-post
  ".post--assistant:has(> .post-body > .markdown)")

(def ^:private user-post
  ".post--user:not(.post--pending-command)")

(def ^:private work-post
  ".post--tool, .post--thinking")

(def ^:private mark-class
  "post--answer-target")

(def ^:private landing-gap-px
  8)

(defonce ^:private mark (atom nil))

(defn clear-mark! []
  (when-let [^js el @mark]
    (.remove (.-classList el) mark-class))
  (reset! mark nil))

(defn- top [^js el]
  (.-top (.getBoundingClientRect el)))

(defn- on-screen? [^js el ^js view]
  (let [r (.getBoundingClientRect el)
        v (.getBoundingClientRect view)]
    (and (.-isConnected el)
         (< (.-top r) (.-bottom v))
         (> (.-bottom r) (.-top v)))))

(defn- landing-y
  "Where a jumped-to answer's top lands: below the timeline's top padding,
   which reserves the floating chat header's height."
  [^js view]
  (+ (top view)
     (js/parseFloat (.-paddingTop (js/getComputedStyle view)))
     landing-gap-px))

(defn- kind [^js post]
  (cond
    (.matches post user-post) :user
    (.matches post text-post) :text
    :else                     :work))

(defn- answers [^js view running?]
  (let [posts (vec (array-seq (.querySelectorAll view (str user-post ", " text-post ", " work-post))))]
    (mapv posts (turn-ends (map kind posts) running?))))

(defn- at-bottom? [^js view]
  (>= (+ (.-scrollTop view) (.-clientHeight view)) (- (.-scrollHeight view) 2)))

(defn- reference-y
  "The line a jump measures from: `ref-el`, the oldest post before earlier
   messages were shown; the mark while it is on screen; else, going back from
   the bottom of the chat, the view's bottom, so the answers already on
   screen are not skipped; else the landing line."
  [^js view dir landing ^js ref-el]
  (let [^js m @mark]
    (cond
      (and ref-el (.-isConnected ref-el))   (top ref-el)
      (and m (on-screen? m view))           (top m)
      (and (= dir :prev) (at-bottom? view)) (.-bottom (.getBoundingClientRect view))
      :else                                 landing)))

(def ^:private max-earlier-loads
  "How many windows of earlier messages one jump back may show while looking
   for an answer."
  10)

(defn- after-render [f]
  (js/requestAnimationFrame #(js/requestAnimationFrame f)))

(defn- jump-in! [dir running? scroll-to! loads ref-el]
  (when-let [^js view (.querySelector js/document ".timeline:not(.timeline--sub)")]
    (let [posts   (answers view running?)
          landing (landing-y view)
          ref     (reference-y view dir landing ref-el)]
      (if-let [i (target (mapv #(- (top %) ref) posts) dir)]
        (let [^js el (nth posts i)]
          (clear-mark!)
          (.add (.-classList el) mark-class)
          (reset! mark el)
          (scroll-to! view el landing)
          true)
        (when-let [^js earlier (and (= dir :prev)
                                    (< loads max-earlier-loads)
                                    (.querySelector view ".load-earlier button"))]
          ;; Earlier messages render above the oldest post, wherever the
          ;; browser leaves the scroll position.
          (let [oldest (or ref-el (.querySelector view ".post"))]
            (.click earlier)
            (after-render #(jump-in! dir running? scroll-to! (inc loads) oldest)))
          true)))))

(defn jump!
  "Jump to the next / previous (`dir`) answer. `running?`: the chat's turn
   is still going. `scroll-to!` (fn [view el y]) eases the view until el's
   top sits at viewport y. True when there was one to jump to, or earlier
   messages to look in."
  [dir running? scroll-to!]
  (jump-in! dir running? scroll-to! 0 nil))
