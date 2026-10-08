(ns xi.web.flip
  "FLIP animation for the sidebar: when a render moves rows (a session was
   deleted or hidden, a group collapsed, the order changed) they glide to
   their new position instead of snapping.

   Rows opt in with a `data-flip` attribute holding a stable key (a session
   row's id, a group's id, …). `snapshot` runs before a render and records
   where each row is on screen; `play!` runs after it and animates every row
   that moved from its old spot to its new one. Rows the render created have
   no old position and just appear.")

(def ^:private duration-ms 250)

(def ^:private easing "cubic-bezier(0.2, 0.8, 0.2, 1)")

(def ^:private flip-id "xi-flip")

(defn- rows
  "[key el] for every flip row in the sidebar, in DOM order. A key can occur
   more than once (a session listed in Favorites and Recent), so repeats get
   a #n suffix."
  []
  (let [seen (volatile! {})]
    (for [^js el (array-seq (.querySelectorAll js/document ".sidebar [data-flip]"))
          :let [k  (.getAttribute el "data-flip")
                n  (get @seen k 0)]]
      (do (vswap! seen assoc k (inc n))
          [(str k "#" n) el]))))

(defn- reduced-motion? []
  (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))

(defn snapshot
  "Where every flip row is on screen right now, as {key top}. Includes a
   running flip's transform, so an interrupted glide continues from where
   the row visually is."
  []
  (when-not (reduced-motion?)
    (into {} (map (fn [[k ^js el]] [k (.-top (.getBoundingClientRect el))])) (rows))))

(defn- leaving?
  "Rows being deleted collapse on their own (:sidebar/animate-leave)."
  [^js el]
  (.contains (.-classList el) "project-card-trigger--leaving"))

(defn play!
  "Animate every row that moved since `snap` (see `snapshot`) from its old
   position to the new one."
  [snap]
  (when (seq snap)
    (let [moved (doall
                 (for [[k ^js el] (rows)
                       :when (not (leaving? el))
                       :let [_   (doseq [^js a (.getAnimations el)]
                                   (when (= flip-id (.-id a)) (.cancel a)))
                             old (get snap k)
                             dy  (when old (- old (.-top (.getBoundingClientRect el))))]
                       :when (and dy (> (js/Math.abs dy) 1))]
                   [el dy]))]
      (doseq [[^js el dy] moved]
        (let [a (.animate el
                          #js [#js {:transform (str "translateY(" dy "px)")}
                               #js {:transform "none"}]
                          #js {:duration duration-ms :easing easing})]
          (set! (.-id a) flip-id))))))
