(ns xi.tui.node
  "Declarative helpers for building TUI node trees.
   Returns flat vectors of components, which can be appended
   to containers via append-children!."
  (:require [xi.tui.components :as comp]))

(defn text
  "Create a text node. Wraps comp/make-text."
  ([s] (comp/make-text s))
  ([s opts] (comp/make-text s opts)))

(defn spacer
  "Create a spacer node. Wraps comp/make-spacer."
  ([] (comp/make-spacer 1))
  ([n] (comp/make-spacer n)))

(defn children
  "Flatten a nested structure of nodes into a flat vector.
   Removes nils, flattens nested seqs/vectors (but not component maps)."
  [nodes]
  (let [result (transient [])]
    (letfn [(walk [x]
              (cond
                (nil? x) nil
                (map? x) (conj! result x)
                (sequential? x) (run! walk x)
                :else (conj! result x)))]
      (walk nodes))
    (persistent! result)))

(defn append-children!
  "Append a flat or nested vector of nodes to a container.
   Filters nils and flattens nested seqs."
  [container nodes]
  (doseq [node (children nodes)]
    ((:add-child container) node)))
