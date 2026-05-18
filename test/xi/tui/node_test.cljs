(ns xi.tui.node-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.node :as node]))

(deftest children-filters-nils
  (let [a {:type :text}
        b {:type :spacer}]
    (is (= [a b] (node/children [a nil b nil])))))

(deftest children-flattens-nested-vectors
  (let [a {:type :text}
        b {:type :spacer}
        c {:type :text}]
    (is (= [a b c] (node/children [a [b c]])))))

(deftest children-flattens-deeply-nested
  (let [a {:type :text}
        b {:type :spacer}]
    (is (= [a b] (node/children [[[[a]] [b]]])))))

(deftest children-handles-lazy-seqs
  (let [items [{:type :text :id 1} {:type :text :id 2}]]
    (is (= items (node/children (map identity items))))))

(deftest children-handles-for-with-nils
  (let [items [1 2 3]
        result (node/children
                 (for [x items]
                   (when (odd? x)
                     {:type :text :val x})))]
    (is (= [{:type :text :val 1}
             {:type :text :val 3}]
           result))))

(deftest children-mixed-nils-vectors-and-seqs
  (let [a {:type :text}
        b {:type :spacer}
        c {:type :text}]
    (is (= [a b c]
           (node/children
             [a
              nil
              (when true [b c])
              (when false {:type :box})])))))

(deftest children-empty-input
  (is (= [] (node/children [])))
  (is (= [] (node/children nil)))
  (is (= [] (node/children [nil nil]))))

(deftest text-returns-component-map
  (let [t (node/text "hello")]
    (is (= :text (:type t)))
    (is (fn? (:render t)))))

(deftest spacer-returns-component-map
  (let [s (node/spacer)]
    (is (= :spacer (:type s)))
    (is (fn? (:render s)))))

(deftest spacer-with-n-returns-component-map
  (let [s (node/spacer 3)]
    (is (= :spacer (:type s)))
    (is (fn? (:render s)))))

(deftest append-children!-adds-to-container
  (let [added (atom [])
        container {:add-child (fn [node] (swap! added conj node))}
        a {:type :text}
        b {:type :spacer}]
    (node/append-children! container [a nil [b] nil])
    (is (= [a b] @added))))

(deftest append-children!-with-empty-vector
  (let [added (atom [])
        container {:add-child (fn [node] (swap! added conj node))}]
    (node/append-children! container [])
    (is (= [] @added))))
