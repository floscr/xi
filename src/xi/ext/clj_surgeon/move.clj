(ns clj-surgeon.move
  "Move forms within a file using rewrite-clj zipper surgery.
   Forms are moved as AST nodes, not text — parens always balanced,
   comments travel with their form, whitespace is preserved."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [clojure.string :as str]))

(defn- find-form
  "Walk top-level forms to find one whose second child matches `form-name`."
  [zloc form-name]
  (let [target (str form-name)]
    (loop [zloc zloc]
      (when zloc
        (let [first-child (some-> zloc z/down z/string)]
          (if (and (z/list? zloc)
                   (not= "declare" first-child)
                   (let [second-child (some-> zloc z/down z/right z/string)]
                     (and second-child
                          (or (= second-child target)
                              (let [third (some-> zloc z/down z/right z/right z/string)]
                                (= third target))))))
            zloc
            (recur (z/right zloc))))))))

(defn- preceding-comment-nodes
  "Collect comment/newline nodes immediately preceding a form."
  [zloc]
  ;; Walk left collecting whitespace and comment nodes
  ;; Stop at the first non-whitespace, non-comment node
  (loop [left (z/left zloc), comments []]
    (if (nil? left)
      comments
      (let [node (z/node left)
            tag (n/tag node)]
        (if (#{:comment :newline} tag)
          (recur (z/left left) (conj comments node))
          comments)))))

(defn move-form
  "Move a named form before another named form in the same file.
   Returns {:ok true :result <new-source>} or {:error <message>}.
   With :dry-run true, returns the plan without modifying anything."
  [{:keys [file form before dry-run]}]
  (let [source (slurp file)
        zloc (z/of-string source {:track-position? true})
        src-zloc (find-form zloc (str form))
        dst-zloc (find-form zloc (str before))]
    (cond
      (nil? src-zloc)
      {:error (str "Form not found: " form)}

      (nil? dst-zloc)
      {:error (str "Destination form not found: " before)}

      :else
      (let [src-meta (meta (z/node src-zloc))
            dst-meta (meta (z/node dst-zloc))
            src-line (:row src-meta)
            dst-line (:row dst-meta)]
        (if dry-run
          {:ok true
           :plan {:form (str form)
                  :from-line src-line
                  :to-before (str before)
                  :to-line dst-line
                  :direction (if (< src-line dst-line) :down :up)}}
          (let [lines (vec (str/split-lines source))
                src-start (loop [i (dec (dec src-line))] ;; 0-indexed, line above
                            (if (neg? i) 0
                                (if (str/starts-with? (str/trim (nth lines i "")) ";")
                                  (recur (dec i))
                                  (inc i))))
                src-end (:end-row src-meta)
                form-lines (subvec lines src-start src-end)
                remaining (into (subvec lines 0 src-start)
                                (subvec lines src-end))
                adj-dst (if (< src-start (dec dst-line))
                          (- dst-line (- src-end src-start))
                          dst-line)
                insert-at (dec adj-dst) ;; 0-indexed, insert before this line
                result (str/join "\n"
                                 (concat (subvec remaining 0 insert-at)
                                         [""]
                                         form-lines
                                         [""]
                                         (subvec remaining insert-at)))]
            (spit file result)
            {:ok true
             :file file
             :form (str form)
             :moved-from src-line
             :moved-to adj-dst
             :lines-moved (count form-lines)}))))))
