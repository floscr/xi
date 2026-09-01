(ns xi.ext.treesitter.skeleton
  "Format extracted entries into a compact outline (maki-style).

   Entries are maps:
     {:section :imports|:bindings|:mods|:consts|:rules|:types|:traits|:impls
               |:fns|:classes|:macros
      :text    \"function greet(name: string): string\"
      :name    \"greet\"            ; optional — used for symbol lookup
      :start 13 :end 15            ; 1-based line range
      :children [{:text … :start … :end … :name …} | \"plain string\" …]}

   Output:
     imports: [1-2]
       { foo, bar } from './foo'

     fns:
       function greet(name: string): string [13-15]"
  (:require [clojure.string :as str]))

(def ^:private section-order
  [[:imports  "imports:"]
   [:bindings "bindings:"]
   [:mods     "mods:"]
   [:consts   "consts:"]
   [:rules    "rules:"]
   [:types    "types:"]
   [:traits   "traits:"]
   [:impls    "impls:"]
   [:fns      "fns:"]
   [:classes  "classes:"]
   [:macros   "macros:"]])

(defn format-range [s e]
  (if (= s e) (str "[" s "]") (str "[" s "-" e "]")))

(defn- entries-range [entries]
  (format-range (reduce min (map :start entries))
                (reduce max (map :end entries))))

(defn- emit-child [out child]
  (if (string? child)
    (conj out (str "    " child))
    (conj out (str "    " (:text child)
                   (when (:start child)
                     (str " " (format-range (:start child) (:end child))))))))

(defn- emit-entry [out {:keys [text start end children]}]
  (let [out (conj out (str "  " text " " (format-range start end)))]
    (reduce emit-child out children)))

(defn format-skeleton
  "Entries → outline string. Returns nil when there are no entries."
  [entries]
  (let [grouped (group-by :section entries)
        blocks
        (for [[section header] section-order
              :let [items (get grouped section)]
              :when (seq items)]
          (if (= section :imports)
            ;; imports collapse into one block: header carries the range,
            ;; entries are listed without per-line ranges.
            (into [(str header " " (entries-range items))]
                  (map #(str "  " (:text %)) items))
            (reduce emit-entry [header] items)))]
    (when (seq blocks)
      (str/join "\n\n" (map #(str/join "\n" %) blocks)))))

(defn symbols
  "Flatten entries into {name → {:start :end :text}} for symbol lookup.
   Children with names are addressable both bare and as Parent.child."
  [entries]
  (reduce
   (fn [acc {:keys [name text start end children]}]
     (let [acc (if (and name start)
                 (assoc acc name {:start start :end end :text text})
                 acc)]
       (reduce
        (fn [acc child]
          (if (and (map? child) (:name child) (:start child))
            (let [v (select-keys child [:start :end :text])]
              (cond-> acc
                name (assoc (str name "." (:name child)) v)
                (not (contains? acc (:name child))) (assoc (:name child) v)))
            acc))
        acc children)))
   {}
   entries))
