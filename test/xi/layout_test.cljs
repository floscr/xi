(ns xi.layout-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.layout :as layout]))

(defn- views [l] (mapv :view (layout/panes l)))

(defn- sizes-of [l] (:sizes (:main l)))

(def chat (layout/single :chat))

(deftest single-and-default
  (is (layout/default? chat))
  (is (= :chat (layout/focused-view chat)))
  (is (layout/valid? chat))
  (is (not (layout/default? (layout/single "diff:git"))))
  (is (not (layout/default? (layout/open-view chat "sa-1" :right)))))

(deftest split-and-focus
  (let [l (layout/split chat "p1" :row "diff:git")]
    (is (= [:chat "diff:git"] (views l)))
    (is (= "p2" (:focus l)) "the new pane takes focus")
    (is (= [0.5 0.5] (sizes-of l)))
    (testing "a split in the parent's direction flattens into it"
      (let [l2 (layout/split l "p2" :row "file:a")]
        (is (= 3 (count (:children (:main l2)))))
        (is (= [:chat "diff:git" "file:a"] (views l2)))
        (is (= [0.5 0.25 0.25] (sizes-of l2)))))
    (testing "a split across nests"
      (let [l2 (layout/split l "p2" :col "file:a")]
        (is (= 2 (count (:children (:main l2)))))
        (is (= :col (:dir (second (:children (:main l2))))))
        (is (layout/valid? l2))))
    (testing "focus-next cycles"
      (is (= "p1" (:focus (layout/focus-next l))))
      (is (= "p2" (:focus (layout/focus-next (layout/focus-next l))))))))

(deftest pane-limit
  (let [l (reduce (fn [l i] (layout/split l (:focus l) :row (str "file:" i)))
                  chat (range 20))]
    (is (= layout/max-panes (layout/pane-count l)))
    (is (layout/valid? l))))

(deftest close-pane-cases
  (let [l (-> chat
              (layout/split "p1" :row "diff:git")
              (layout/split "p2" :row "file:a"))]
    (testing "closing a middle pane hands its space to the siblings and focus to the next"
      (let [l2 (layout/close-pane (layout/focus l "p2") "p2")]
        (is (= [:chat "file:a"] (views l2)))
        (is (= "p3" (:focus l2)))
        (is (< (abs (- 1 (reduce + (sizes-of l2)))) 1e-9))))
    (testing "closing the last pane focuses the previous"
      (is (= "p2" (:focus (layout/close-pane l "p3")))))
    (testing "focus elsewhere is kept"
      (is (= "p1" (:focus (layout/close-pane (layout/focus l "p1") "p3")))))
    (testing "a two-pane split collapses to a pane"
      (let [l2 (-> l (layout/close-pane "p3") (layout/close-pane "p2"))]
        (is (layout/pane? (:main l2)))
        (is (= :chat (:view (:main l2))))))
    (testing "the last main pane shows the chat instead of closing"
      (is (= [:chat] (views (layout/close-pane (layout/single "diff:git") "p1"))))
      (is (= chat (layout/close-pane chat "p1"))))
    (testing "an unknown id is a no-op"
      (is (= l (layout/close-pane l "nope"))))))

(deftest only-and-swap
  (let [l (-> chat (layout/split "p1" :row "diff:git") (layout/split "p2" :col "file:a"))]
    (is (= ["diff:git"] (views (layout/only l "p2"))))
    (is (= "p2" (:focus (layout/only l "p2"))))
    (let [s (layout/swap l "p1")]
      (is (= ["diff:git" "file:a" :chat] (views s)))
      (is (= (sizes-of l) (sizes-of s)) "sizes stay with the positions"))
    (is (= chat (layout/swap chat "p1")) "nothing to swap with")))

(deftest resizing
  (let [l (layout/split chat "p1" :row "diff:git")]
    (testing "resize sets a split's sizes, normalised"
      (is (= [0.75 0.25] (sizes-of (layout/resize l [] [3 1])))))
    (testing "a wrong count is ignored"
      (is (= l (layout/resize l [] [1 1 1]))))
    (testing "resize-by trades with the sibling"
      (let [l2 (layout/resize-by l "p1" :width 0.1)]
        (is (= [0.6 0.4] (mapv #(/ (js/Math.round (* 100 %)) 100) (sizes-of l2))))))
    (testing "resize-by keeps the minimum"
      (let [l2 (layout/resize-by l "p1" :width 5)]
        (is (= [0.9 0.1] (mapv #(/ (js/Math.round (* 100 %)) 100) (sizes-of l2))))))
    (testing "resize-by on the other axis finds no split"
      (is (= l (layout/resize-by l "p1" :height 0.1))))
    (testing "nested: the nearest ancestor of the right direction"
      (let [l2 (layout/split l "p2" :col "file:a")
            l3 (layout/resize-by l2 "p3" :width 0.2)]
        (is (= [0.3 0.7] (mapv #(/ (js/Math.round (* 100 %)) 100) (sizes-of l3))))))
    (testing "balance"
      (is (= [0.5 0.5] (sizes-of (layout/balance (layout/resize l [] [3 1]) "p1")))))))

(deftest sidebars
  (let [l (layout/split chat "p1" :row "diff:git")]
    (testing "dock moves a main pane into a side"
      (let [d (layout/dock l "p2" :right)]
        (is (= [:chat "diff:git"] (views d)))
        (is (= "p2" (:id (layout/side-pane d :right))))
        (is (layout/pane? (:main d)))
        (is (= :right (layout/side-of d "p2")))
        (is (= layout/side-default (get-in d [:right :size])))
        (is (true? (get-in d [:right :open?])))
        (is (layout/valid? d))
        (testing "docking the last main pane leaves a chat pane"
          (let [d2 (layout/dock d "p1" :left)]
            (is (= [:chat :chat "diff:git"] (views d2)))
            (is (layout/valid? d2))))
        (testing "a pane already on that side swaps places"
          (let [d2 (layout/dock (layout/split d "p1" :row "file:a") "p3" :right)]
            (is (= "p3" (:id (layout/side-pane d2 :right))))
            (is (= [:chat "diff:git" "file:a"] (views d2)))))
        (testing "moving across sides"
          (let [d2 (layout/dock d "p2" :left)]
            (is (= :left (layout/side-of d2 "p2")))
            (is (nil? (layout/side-pane d2 :right)))))
        (testing "undock puts it back at that side of the root"
          (let [u (layout/undock d :right)]
            (is (nil? (layout/side-pane u :right)))
            (is (= [:chat "diff:git"] (views u)))
            (is (= :row (:dir (:main u))))))
        (testing "toggle and resize"
          (is (false? (get-in (layout/toggle-side d :right) [:right :open?])))
          (is (= layout/side-max (get-in (layout/resize-side d :right 5000) [:right :size])))
          (is (= layout/side-min (get-in (layout/set-side-size d :right 10) [:right :size])))
          (is (= d (layout/toggle-side d :left)) "no-op without a pane"))
        (testing "closing a side pane"
          (let [c (layout/close-pane (layout/focus d "p2") "p2")]
            (is (nil? (layout/side-pane c :right)))
            (is (= "p1" (:focus c)))))))))

(deftest open-view-cases
  (let [l (layout/split chat "p1" :row "diff:git")]
    (is (= "p2" (:focus (layout/open-view (layout/focus l "p1") "diff:git" :focus)))
        "a view already shown is focused, not opened twice")
    (is (= [:chat "file:a"] (views (layout/open-view (layout/focus l "p2") "file:a" :focus))))
    (is (= [:chat "diff:git" "file:a"] (views (layout/open-view (layout/focus l "p2") "file:a" :split-right))))
    (let [r (layout/open-view (layout/focus l "p1") "sa-1" :right)]
      (is (= "sa-1" (:view (layout/side-pane r :right))))
      (is (= "p1" (:focus r)) "a sidebar open keeps the focus")
      (is (= "sa-2" (:view (layout/side-pane (layout/open-view r "sa-2" :right) :right)))
          "a second one replaces the sidebar's view"))))

(deftest prune-closes-gone-views
  (let [l (-> chat
              (layout/split "p1" :row "diff:git")
              (layout/open-view "sa-1" :right))
        open? #{:chat "diff:git"}]
    (let [p (layout/prune l open?)]
      (is (= [:chat "diff:git"] (views p)))
      (is (nil? (layout/side-pane p :right))))
    (is (= [:chat] (views (layout/prune l #{:chat}))))
    (is (= [:chat] (views (layout/prune (layout/single "diff:git") #{:chat})))
        "the last pane falls back to the chat")))

(deftest directional-focus
  (let [l (-> chat
              (layout/split "p1" :row "diff:git")     ; p1 | p2
              (layout/split "p2" :col "file:a")       ; p1 | (p2 / p3)
              (layout/open-view "sa-1" :right))       ; … | p4 (sidebar)
        focus-after (fn [id dir] (:focus (layout/focus-dir (layout/focus l id) dir)))]
    (is (= "p2" (focus-after "p1" :right)) "the upper of two neighbours (closest centre)")
    (is (= "p1" (focus-after "p2" :left)))
    (is (= "p3" (focus-after "p2" :down)))
    (is (= "p2" (focus-after "p3" :up)))
    (is (= "p4" (focus-after "p2" :right)) "into the right sidebar")
    (is (= "p2" (focus-after "p4" :left)))
    (is (= "p1" (focus-after "p1" :left)) "nothing there: unchanged")
    (is (= "p1" (focus-after "p1" :up)))))

(deftest validation
  (is (layout/valid? (-> chat (layout/split "p1" :row "diff:git") (layout/open-view "sa-1" :left))))
  (is (not (layout/valid? (assoc chat :focus "nope"))))
  (is (not (layout/valid? (assoc-in chat [:main :view] 42))))
  (is (not (layout/valid? (assoc chat :main {:type :split :dir :row :sizes [1] :children [(:main chat)]}))))
  (is (not (layout/valid? (assoc chat :right {:pane (:main chat) :size 10 :open? true}))))
  (is (not (layout/valid? {})))
  (testing "the store"
    (is (layout/valid-store? {}))
    (is (layout/valid-store? {"s1" (layout/split chat "p1" :row "diff:git")}))
    (is (not (layout/valid-store? {:s1 chat})))
    (is (not (layout/valid-store? {"s1" {}})))))

(deftest trim-store-keeps-recent-non-default
  (let [split (layout/split chat "p1" :row "diff:git")
        m (into {"new-room" split "plain" chat}
                (map (fn [i] [(str "s" i) (assoc split :at i)]) (range 60)))
        t (layout/trim-store m)]
    (is (= layout/max-stored (count t)))
    (is (not (contains? t "plain")) "a default layout is not stored")
    (is (contains? t "s59"))
    (is (not (contains? t "s0")) "the oldest are dropped")
    (is (not (contains? t "new-room")) "no timestamp sorts last")))
