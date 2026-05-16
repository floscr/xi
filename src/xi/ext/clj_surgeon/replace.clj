#!/usr/bin/env bb

(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as node]
         '[clojure.string :as str])

(defn parse-safely [code-str label]
  (try
    {:ok true :result (z/of-string code-str {:track-position? true})}
    (catch Exception e
      {:ok false :error (str label " parse error: " (.getMessage e))})))

(defn sexpr-equal? [a b]
  (try
    (= a b)
    (catch Exception _
      false)))

(defn find-location [z]
  (try
    (let [[line col] (z/position z)]
      {:line line :column col})
    (catch Exception _
      {:line "?" :column "?"})))

(defn walk-and-find [z target-sexpr]
  "Walk the zipper and collect all nodes matching target-sexpr"
  (loop [z z
         matches []]
    (if (z/end? z)
      matches
      (let [node-sexpr (try (z/sexpr z) (catch Exception _ ::unparseable))]
        (if (sexpr-equal? node-sexpr target-sexpr)
          (let [loc (find-location z)]
            (recur (z/next z)
                   (conj matches {:z z :loc loc})))
          (recur (z/next z) matches))))))

(defn get-node-string [z]
  (try
    (z/string z)
    (catch Exception _
      (str (z/node z)))))

(defn -main [& args]
  (let [[filename old-string new-string] args]
    (if (or (nil? filename) (nil? old-string) (nil? new-string))
      (do
        (println "Usage: clj-replace <filename> <old-string> <new-string>")
        (System/exit 1))

      (let [file (java.io.File. filename)]
        (if (not (.exists file))
          (do
            (println (str "Error: File not found: " filename))
            (System/exit 1))

          (let [file-content (slurp filename)
                old-parse (parse-safely old-string "old-string")
                new-parse (parse-safely new-string "new-string")]

            (if (not (:ok old-parse))
              (do
                (println (str (:error old-parse)))
                (System/exit 1))

              (if (not (:ok new-parse))
                (do
                  (println (str (:error new-parse)))
                  (System/exit 1))

                (let [file-zip (z/of-string file-content {:track-position? true})
                      target-sexpr (z/sexpr (:result old-parse))
                      new-node (z/node (:result new-parse))
                      matches (walk-and-find file-zip target-sexpr)]

                  (cond
                    (empty? matches)
                    (do
                      (println (str "No matching S-expression found in " filename))
                      (System/exit 2))

                    (> (count matches) 1)
                    (do
                      (println (str "Found " (count matches) " matching S-expressions in " filename " (ambiguous)"))
                      (doseq [[i match] (map-indexed vector matches)]
                        (let [loc (:loc match)
                              node-str (get-node-string (:z match))]
                          (println (str "  " (inc i) ". line " (:line loc) ", column " (:column loc) ": " node-str))))
                      (println "Provide a more specific old-string (include surrounding context)")
                      (System/exit 3))

                    :else
                    (let [match (first matches)
                          match-z (:z match)
                          loc (:loc match)
                          updated-z (z/replace match-z new-node)
                          result (z/root-string updated-z)]
                      (spit filename result)
                      (println (str "Replaced in " filename " at line " (:line loc) ", col " (:column loc)))
                      (System/exit 0))))))))))))

(apply -main *command-line-args*)
