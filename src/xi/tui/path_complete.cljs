(ns xi.tui.path-complete
  "Filesystem path tab-completion for the editor. Given a partial path token
   and a base directory (cwd), computes either an inline insertion (a single
   match or a shared common-prefix extension) or a menu of candidates.

   Directories are suffixed with '/' so completing a dir lets you keep typing
   deeper. Dotfiles are hidden unless the typed base starts with '.'."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- longest-common-prefix
  [strs]
  (if (empty? strs)
    ""
    (reduce (fn [acc s]
              (let [n (min (count acc) (count s))]
                (loop [i 0]
                  (if (and (< i n) (= (nth acc i) (nth s i)))
                    (recur (inc i))
                    (subs acc 0 i)))))
            (first strs)
            (rest strs))))

(defn- expand-home
  [p]
  (let [home (aget js/process.env "HOME")]
    (cond
      (not home) p
      (= p "~") home
      (str/starts-with? p "~/") (str home (subs p 1))
      :else p)))

(defn- list-dir
  "Vec of entry names in `dir`, directories suffixed with '/'. nil if unreadable."
  [dir]
  (try
    (mapv (fn [e]
            (let [n (.-name e)]
              (if ^boolean (.isDirectory ^js e) (str n "/") n)))
          (fs/readdirSync dir #js {:withFileTypes true}))
    (catch :default _ nil)))

(defn complete
  "Compute a path completion for `token` relative to `cwd`.
   Returns nil when there is nothing to complete, or:
     {:action :insert :text <suffix>}                — extend the token inline
     {:action :menu   :items [{:label :insert} ...]} — multiple candidates"
  [token cwd]
  (when (and (string? token) (seq token))
    (let [slash (str/last-index-of token "/")
          [dir-part base] (if slash
                            [(subs token 0 (inc slash)) (subs token (inc slash))]
                            ["" token])
          expanded (expand-home dir-part)
          dir (if (str/blank? expanded) cwd (path/resolve cwd expanded))
          entries (list-dir dir)]
      (when (seq entries)
        (let [hide-dots? (not (str/starts-with? base "."))
              matches (->> entries
                           (filter #(str/starts-with? % base))
                           (remove #(and hide-dots? (str/starts-with? % ".")))
                           sort
                           vec)]
          (cond
            (empty? matches) nil

            (= 1 (count matches))
            {:action :insert :text (subs (first matches) (count base))}

            :else
            (let [lcp (longest-common-prefix matches)]
              (if (> (count lcp) (count base))
                {:action :insert :text (subs lcp (count base))}
                {:action :menu
                 :items (mapv (fn [m] {:label m :insert (subs m (count base))})
                              matches)}))))))))
