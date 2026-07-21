(ns xi.tui.container-test
  "Correctness tests for make-container's incremental identity-diff flatten cache.
   The cache reuses the flattened prefix of unchanged children across renders;
   these tests prove its output always matches a naive flatten and that unchanged
   prefixes are returned as identical? vectors (which is what makes it fast)."
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.core :as tui]))

(defn- leaf
  "A minimal leaf component that caches its output vector, so an unchanged leaf
   returns the SAME (identical?) vector across renders — like the real text/box
   components. Call (set! new-lines) to change it."
  [initial-lines]
  (let [lines (atom initial-lines)
        cache (atom nil)]
    {:type :text
     :set (fn [new-lines] (reset! lines new-lines) (reset! cache nil))
     :render (fn [_width]
               (let [ls @lines
                     c @cache]
                 (if (and c (= (:lines c) ls))
                   (:result c)
                   (let [result (vec ls)]
                     (reset! cache {:lines ls :result result})
                     result))))}))

(defn- naive-flatten [container width]
  (into [] (mapcat (fn [c] ((:render c) width))) @(:children container)))

(deftest matches-naive-flatten-across-mutations
  (let [c (tui/make-container)
        a (leaf ["a1" "a2"])
        b (leaf ["b1"])
        d (leaf ["d1" "d2" "d3"])]
    (let [render (fn [] ((:render c) 80))]
      ((:add-child c) a)
      ((:add-child c) b)
      (is (= ["a1" "a2" "b1"] (render)))
      (is (= (naive-flatten c 80) (render)))

      ;; append a child
      ((:add-child c) d)
      (is (= ["a1" "a2" "b1" "d1" "d2" "d3"] (render)))
      (is (= (naive-flatten c 80) (render)))

      ;; mutate the LAST child — prefix [a b] unchanged
      ((:set d) ["D1"])
      (is (= ["a1" "a2" "b1" "D1"] (render)))
      (is (= (naive-flatten c 80) (render)))

      ;; mutate a MIDDLE child — prefix [a] unchanged, tail re-flattened
      ((:set b) ["B1" "B2"])
      (is (= ["a1" "a2" "B1" "B2" "D1"] (render)))
      (is (= (naive-flatten c 80) (render)))

      ;; mutate the FIRST child — no stable prefix
      ((:set a) ["A"])
      (is (= ["A" "B1" "B2" "D1"] (render)))
      (is (= (naive-flatten c 80) (render)))

      ;; remove a child
      ((:remove-child c) b)
      (is (= ["A" "D1"] (render)))
      (is (= (naive-flatten c 80) (render))))))

(deftest reuses-result-when-nothing-changed
  (let [c (tui/make-container)
        a (leaf ["a1"])
        b (leaf ["b1"])
        render (fn [] ((:render c) 80))]
    ((:add-child c) a)
    ((:add-child c) b)
    (let [r1 (render)
          r2 (render)]
      (is (identical? r1 r2)
          "unchanged children + width returns the exact same result vector"))))

(deftest width-change-reflattens
  (let [c (tui/make-container)
        a (leaf ["a1"])
        render (fn [w] ((:render c) w))]
    ((:add-child c) a)
    (let [r80 (render 80)
          r40 (render 40)]
      (is (= ["a1"] r80))
      (is (= ["a1"] r40))
      ;; different widths must not collide on the cached result
      (is (= (naive-flatten c 40) r40)))))

(deftest invalidate-clears-cache
  (let [c (tui/make-container)
        a (leaf ["a1"])
        render (fn [] ((:render c) 80))]
    ((:add-child c) a)
    (render)
    ((:invalidate c))
    (is (= ["a1"] (render)))))
