(ns xi.layout
  "The chat page's window layout (web client): a tree of splits whose leaves
   are panes, each showing one *view* — the chat (`:chat`), a buffer id
   (xi.buffers), a sub-agent id, or `:empty` (a pane waiting for a pick) —
   plus one dockable sidebar per side and the focused pane. Pure data; the
   focused pane's view is the room's `[:ui :active-buffer]` (xi.web.layout
   keeps the two in step).

     {:main    {:type :split :dir :row :sizes [0.6 0.4]
                :children [{:type :pane :id \"p1\" :view :chat}
                           {:type :pane :id \"p2\" :view \"diff:git\"}]}
      :left    nil
      :right   {:pane {:type :pane :id \"p3\" :view \"sa-1\"} :size 380 :open? true}
      :focus   \"p1\"
      :next-id 4}

   `:sizes` are fractions of the parent split summing to 1; a sidebar's
   `:size` is px. Every mutation ends in `simplify` (same-direction splits
   merged, one-child splits dropped), so one arrangement has one shape.
   `:at` (ms) marks the last change for the per-user store's LRU.")

(def max-panes 8)
(def max-depth 4)
(def min-fraction 0.1)
(def side-min 200)
(def side-max 1200)
(def side-default 380)
(def sides [:left :right])

;; ── Nodes ────────────────────────────────────────────────────────────────────

(defn pane? [node] (= :pane (:type node)))
(defn split? [node] (= :split (:type node)))

(defn- pane-node [id view] {:type :pane :id id :view view})

(defn- new-id
  "[id layout'] with the counter advanced."
  [layout]
  (let [n (or (:next-id layout) 1)]
    [(str "p" n) (assoc layout :next-id (inc n))]))

(defn single
  "The layout of every session until it is split: one pane showing `view`."
  [view]
  {:main (pane-node "p1" view) :focus "p1" :next-id 2})

(defn- node-panes [node]
  (if (pane? node) [node] (mapcat node-panes (:children node))))

(defn main-panes [layout] (node-panes (:main layout)))

(defn side-pane [layout side] (get-in layout [side :pane]))

(defn panes
  "Every pane, left sidebar first, then the main tree depth-first, then the
   right sidebar."
  [layout]
  (concat (some-> (side-pane layout :left) vector)
          (main-panes layout)
          (some-> (side-pane layout :right) vector)))

(defn pane-count [layout] (count (panes layout)))

(defn find-pane [layout id]
  (some #(when (= id (:id %)) %) (panes layout)))

(defn side-of
  "The sidebar pane `id` sits in, nil for a main pane."
  [layout id]
  (some (fn [s] (when (= id (:id (side-pane layout s))) s)) sides))

(defn pane-of-view
  "The id of the first pane showing `view`."
  [layout view]
  (:id (some #(when (= view (:view %)) %) (panes layout))))

(defn focused [layout]
  (or (find-pane layout (:focus layout)) (first (main-panes layout))))

(defn focused-view [layout] (:view (focused layout)))

(defn default?
  "A single chat pane and no sidebars: the layout a session has without ever
   being split; such layouts are not stored."
  [layout]
  (and (pane? (:main layout))
       (= :chat (:view (:main layout)))
       (nil? (:left layout))
       (nil? (:right layout))))

;; ── Tree helpers ─────────────────────────────────────────────────────────────

(defn- path-of
  "The child indices from the main root down to pane `id`, [] for the root
   itself, nil when absent."
  ([node id] (path-of node id []))
  ([node id path]
   (if (pane? node)
     (when (= id (:id node)) path)
     (some (fn [[i c]] (path-of c id (conj path i)))
           (map-indexed vector (:children node))))))

(defn- node-path [path] (vec (mapcat (fn [i] [:children i]) path)))

(defn- update-at [node path f & args]
  (if (empty? path)
    (apply f node args)
    (apply update-in node (node-path path) f args)))

(defn- node-at [node path]
  (if (empty? path) node (get-in node (node-path path))))

(defn normalize-sizes [sizes]
  (let [n     (count sizes)
        total (reduce + 0 sizes)]
    (if (and (pos? n) (pos? total))
      (mapv #(/ % total) sizes)
      (vec (repeat n (/ 1 (max n 1)))))))

(defn simplify
  "Merge same-direction nested splits, drop one-child splits, re-normalise
   sizes. nil for an empty tree."
  [node]
  (if (pane? node)
    node
    (let [pairs (keep (fn [[c s]] (when-let [c (simplify c)] [c s]))
                      (map vector (:children node) (:sizes node)))
          flat  (reduce (fn [acc [c s]]
                          (if (and (split? c) (= (:dir c) (:dir node)))
                            (into acc (map (fn [gc gs] [gc (* s gs)]) (:children c) (:sizes c)))
                            (conj acc [c s])))
                        [] pairs)]
      (case (count flat)
        0 nil
        1 (ffirst flat)
        {:type :split :dir (:dir node)
         :children (mapv first flat)
         :sizes (normalize-sizes (mapv second flat))}))))

(defn- depth [node]
  (if (pane? node) 0 (inc (reduce max 0 (map depth (:children node))))))

(defn- remove-child [split i]
  (let [c (:children split) s (:sizes split)]
    (assoc split
           :children (vec (concat (subvec c 0 i) (subvec c (inc i))))
           :sizes (normalize-sizes (vec (concat (subvec s 0 i) (subvec s (inc i))))))))

(defn- sibling-index [parent i]
  (if (< (inc i) (count (:children parent))) (inc i) (dec i)))

(defn- refocus
  "After pane `removed` left: focus the first main pane when it was focused."
  [layout removed]
  (cond-> layout
    (= removed (:focus layout)) (assoc :focus (:id (first (main-panes layout))))))

;; ── Ops ──────────────────────────────────────────────────────────────────────

(defn focus [layout id]
  (if (find-pane layout id) (assoc layout :focus id) layout))

(defn focus-next
  "Focus the pane after the focused one in `panes` order, wrapping."
  [layout]
  (let [ids (mapv :id (panes layout))
        i   (.indexOf ids (:id (focused layout)))]
    (if (<= (count ids) 1)
      layout
      (assoc layout :focus (nth ids (mod (inc i) (count ids)))))))

(defn set-view [layout id view]
  (if-let [side (side-of layout id)]
    (assoc-in layout [side :pane :view] view)
    (if-let [path (path-of (:main layout) id)]
      (update layout :main update-at path assoc :view view)
      layout)))

(defn split
  "Split pane `id` along `dir` (:row side by side, :col stacked); the new
   pane shows `view` and takes focus. A sidebar pane splits the first main
   pane instead. Unchanged at the pane or depth limit."
  [layout id dir view]
  (let [id   (if (side-of layout id) (:id (first (main-panes layout))) id)
        path (path-of (:main layout) id)]
    (if (or (nil? path) (>= (pane-count layout) max-panes))
      layout
      (let [[nid layout'] (new-id layout)
            main (-> (:main layout')
                     (update-at path (fn [p] {:type :split :dir dir :sizes [0.5 0.5]
                                              :children [p (pane-node nid view)]}))
                     simplify)]
        (if (> (depth main) max-depth)
          layout
          (assoc layout' :main main :focus nid))))))

(defn close-pane
  "Remove pane `id`; its space goes to its siblings, focus to the one that
   took its place. The last main pane is not removed: it shows the chat
   instead."
  [layout id]
  (if-let [side (side-of layout id)]
    (-> layout (assoc side nil) (refocus id))
    (let [path (path-of (:main layout) id)]
      (cond
        (nil? path) layout
        (empty? path) (set-view layout id :chat)
        :else
        (let [pp      (pop path)
              i       (peek path)
              parent  (node-at (:main layout) pp)
              next-id (:id (first (node-panes (nth (:children parent) (sibling-index parent i)))))]
          (-> layout
              (assoc :main (simplify (update-at (:main layout) pp remove-child i)))
              (cond-> (= id (:focus layout)) (assoc :focus next-id))))))))

(defn only
  "Zoom: main pane `id` becomes the whole main area; sidebars stay."
  [layout id]
  (if (and (nil? (side-of layout id)) (find-pane layout id))
    (assoc layout :main (find-pane layout id) :focus id)
    layout))

(defn swap
  "Exchange main pane `id` with its next sibling (the previous for the last
   one); the sizes stay with the positions."
  [layout id]
  (let [path (path-of (:main layout) id)]
    (if (or (nil? path) (empty? path))
      layout
      (let [pp (pop path) i (peek path)
            j  (sibling-index (node-at (:main layout) pp) i)]
        (update layout :main update-at pp
                (fn [s] (let [c (:children s)]
                          (assoc s :children (-> c (assoc i (nth c j)) (assoc j (nth c i)))))))))))

(defn resize
  "Set the sizes of the split at `path` (child indices from the root), as
   the DOM reports them; normalised and kept off the edges."
  [layout path sizes]
  (let [node (node-at (:main layout) path)]
    (if (and (split? node) (= (count sizes) (count (:children node))))
      (update layout :main update-at path assoc :sizes
              (->> sizes normalize-sizes (mapv #(-> % (max 0.02) (min 0.98))) normalize-sizes))
      layout)))

(defn- ancestor-split
  "The path of the nearest ancestor split of `path` with direction `dir`
   and the index of the child holding `path`, or nil."
  [main path dir]
  (loop [p path]
    (when (seq p)
      (let [pp (pop p)]
        (if (= dir (:dir (node-at main pp)))
          [pp (peek p)]
          (recur pp))))))

(defn resize-by
  "Grow (positive `delta`, a fraction of the parent) or shrink main pane
   `id` along `axis` (:width / :height), trading with its sibling in the
   nearest split of that direction; both stay at least `min-fraction`."
  [layout id axis delta]
  (let [main (:main layout)
        dir  (if (= axis :width) :row :col)]
    (if-let [[pp i] (some-> (path-of main id) (as-> p (ancestor-split main p dir)))]
      (let [parent (node-at main pp)
            sizes  (:sizes parent)
            j      (sibling-index parent i)
            d      (-> delta
                       (min (- (nth sizes j) min-fraction))
                       (max (- min-fraction (nth sizes i))))]
        (update layout :main update-at pp assoc :sizes
                (-> sizes (update i + d) (update j - d))))
      layout)))

(defn balance
  "Equal sizes in the split holding main pane `id`."
  [layout id]
  (let [path (path-of (:main layout) id)]
    (if (or (nil? path) (empty? path))
      layout
      (update layout :main update-at (pop path)
              (fn [s] (assoc s :sizes (normalize-sizes (vec (repeat (count (:children s)) 1)))))))))

(defn- side-slot [pane existing]
  {:pane pane :size (or (:size existing) side-default) :open? true})

(defn dock
  "Move pane `id` into sidebar `side`. A pane already there takes the moved
   pane's place (in the main tree, or the other sidebar), so no view is
   lost; the last main pane leaves a chat pane behind."
  [layout id side]
  (let [pane     (find-pane layout id)
        from     (side-of layout id)
        existing (side-pane layout side)]
    (cond
      (or (nil? pane) (= from side)) layout

      from
      (-> layout
          (assoc side (side-slot pane existing))
          (assoc from (when existing (side-slot existing (get layout from)))))

      :else
      (let [path (path-of (:main layout) id)
            main (:main layout)
            [nid layout'] (new-id layout)
            main' (cond
                    existing     (update-at main path (constantly existing))
                    (empty? path) (pane-node nid :chat)
                    :else        (simplify (update-at main (pop path) remove-child (peek path))))]
        (-> (if (and (nil? existing) (empty? path)) layout' layout)
            (assoc :main main')
            (assoc side (side-slot pane existing)))))))

(defn undock
  "Move sidebar `side`'s pane into the main tree, at that side of the root."
  [layout side]
  (if-let [pane (side-pane layout side)]
    (let [main (:main layout)]
      (-> layout
          (assoc side nil)
          (assoc :main (simplify (if (= side :left)
                                   {:type :split :dir :row :sizes [0.3 0.7] :children [pane main]}
                                   {:type :split :dir :row :sizes [0.7 0.3] :children [main pane]})))))
    layout))

(defn toggle-side
  "Collapse / expand sidebar `side` (no-op when empty)."
  ([layout side] (toggle-side layout side (not (get-in layout [side :open?]))))
  ([layout side open?]
   (cond-> layout (side-pane layout side) (assoc-in [side :open?] (boolean open?)))))

(defn set-side-size [layout side px]
  (cond-> layout
    (side-pane layout side)
    (assoc-in [side :size] (-> px (max side-min) (min side-max)))))

(defn resize-side
  "Grow (positive `delta-px`) or shrink sidebar `side`."
  [layout side delta-px]
  (if (side-pane layout side)
    (set-side-size layout side (+ (get-in layout [side :size] side-default) delta-px))
    layout))

(defn prune
  "Close every pane whose view fails `open?` (a closed buffer, a dismissed
   sub-agent)."
  [layout open?]
  (reduce (fn [l p] (close-pane l (:id p)))
          layout
          (remove #(open? (:view %)) (panes layout))))

(defn open-view
  "Show `view`: in the focused pane (`:focus`), in a new split of it
   (`:split-right` / `:split-down`), or in sidebar `:left` / `:right`
   (replacing its view). A pane already showing the view is focused
   instead. Focus moves only for `:focus` and the splits."
  [layout view where]
  (if-let [id (pane-of-view layout view)]
    (focus layout id)
    (let [fid (:id (focused layout))]
      (case where
        :focus       (set-view layout fid view)
        :split-right (split layout fid :row view)
        :split-down  (split layout fid :col view)
        (:left :right)
        (if (side-pane layout where)
          (-> layout (assoc-in [where :pane :view] view) (assoc-in [where :open?] true))
          (let [[nid layout'] (new-id layout)]
            (if (>= (pane-count layout) max-panes)
              layout
              (assoc layout' where (side-slot (pane-node nid view) nil)))))
        layout))))

;; ── Geometry (directional focus) ─────────────────────────────────────────────

(defn- node-rects [node [x y w h] acc]
  (if (pane? node)
    (assoc acc (:id node) [x y w h])
    (let [{:keys [dir children sizes]} node]
      (loop [cs children ss sizes off 0 acc acc]
        (if (empty? cs)
          acc
          (let [s (first ss)
                r (if (= dir :row)
                    [(+ x (* off w)) y (* s w) h]
                    [x (+ y (* off h)) w (* s h)])]
            (recur (rest cs) (rest ss) (+ off s) (node-rects (first cs) r acc))))))))

(defn rects
  "Pane id → [x y w h] with the main area the unit square, the left sidebar
   at x -1..0 and the right at 1..2."
  [layout]
  (cond-> (node-rects (:main layout) [0 0 1 1] {})
    (side-pane layout :left)  (assoc (:id (side-pane layout :left)) [-1 0 1 1])
    (side-pane layout :right) (assoc (:id (side-pane layout :right)) [1 0 1 1])))

(defn- overlaps? [a1 a2 b1 b2] (and (< a1 b2) (< b1 a2)))

(defn focus-dir
  "Focus the nearest pane in direction `dir` (:left :right :up :down) that
   shares an edge span with the focused one; unchanged when there is none."
  [layout dir]
  (let [fid (:id (focused layout))
        rs  (rects layout)]
    (if-let [[x y w h] (get rs fid)]
      (let [eps  1e-6
            cx   (+ x (/ w 2))
            cy   (+ y (/ h 2))
            ok?  (fn [[x2 y2 w2 h2]]
                   (case dir
                     :right (and (>= x2 (- (+ x w) eps)) (overlaps? y (+ y h) y2 (+ y2 h2)))
                     :left  (and (<= (+ x2 w2) (+ x eps)) (overlaps? y (+ y h) y2 (+ y2 h2)))
                     :down  (and (>= y2 (- (+ y h) eps)) (overlaps? x (+ x w) x2 (+ x2 w2)))
                     :up    (and (<= (+ y2 h2) (+ y eps)) (overlaps? x (+ x w) x2 (+ x2 w2)))))
            ;; nearest edge, then the closest centre, then the first in
            ;; reading order
            rank (fn [[x2 y2 w2 h2]]
                   (case dir
                     :right [x2 (abs (- (+ y2 (/ h2 2)) cy)) y2]
                     :left  [(- (+ x2 w2)) (abs (- (+ y2 (/ h2 2)) cy)) y2]
                     :down  [y2 (abs (- (+ x2 (/ w2 2)) cx)) x2]
                     :up    [(- (+ y2 h2)) (abs (- (+ x2 (/ w2 2)) cx)) x2]))
            best (some->> (dissoc rs fid)
                          (filter (comp ok? val))
                          (sort-by (comp rank val))
                          first
                          key)]
        (if best (assoc layout :focus best) layout))
      layout)))

;; ── Validation (the per-user store, xi.user-state) ───────────────────────────

(defn valid-view? [v]
  (or (#{:chat :empty :prompt :canvas :subagents} v)
      (and (string? v) (<= 1 (count v) 300))))

(defn- valid-node? [n depth]
  (and (map? n)
       (<= depth max-depth)
       (case (:type n)
         :pane  (and (string? (:id n)) (<= 1 (count (:id n)) 12) (valid-view? (:view n)))
         :split (let [{:keys [children sizes dir]} n]
                  (and (#{:row :col} dir)
                       (vector? children) (vector? sizes)
                       (<= 2 (count children) max-panes)
                       (= (count children) (count sizes))
                       (every? #(and (number? %) (< 0 % 1)) sizes)
                       (< (abs (- 1 (reduce + sizes))) 0.01)
                       (every? #(valid-node? % (inc depth)) children)))
         false)))

(defn- valid-side? [s]
  (or (nil? s)
      (and (map? s)
           (pane? (:pane s)) (valid-node? (:pane s) 0)
           (number? (:size s)) (<= side-min (:size s) side-max)
           (boolean? (:open? s)))))

(defn valid?
  [l]
  (and (map? l)
       (valid-node? (:main l) 0)
       (valid-side? (:left l))
       (valid-side? (:right l))
       (string? (:focus l))
       (some? (find-pane l (:focus l)))
       (<= (pane-count l) max-panes)
       (let [ids (map :id (panes l))] (= (count ids) (count (distinct ids))))
       (integer? (:next-id l))
       (or (nil? (:at l)) (number? (:at l)))))

(def max-stored
  "Sessions whose layout the per-user store keeps (most recently changed)."
  50)

(defn valid-store?
  "The per-user `:layouts` value: {session-id layout}."
  [m]
  (and (map? m)
       (<= (count m) max-stored)
       (every? (fn [[k v]] (and (string? k) (<= 1 (count k) 200) (valid? v))) m)))

(defn trim-store
  "The layouts worth storing: non-default ones of real sessions, the
   `max-stored` most recently changed."
  [layouts]
  (->> layouts
       (filter (fn [[k v]] (and (string? k) (not (default? v)))))
       (sort-by (fn [[_ v]] (or (:at v) 0)) >)
       (take max-stored)
       (into {})))
