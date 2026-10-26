(ns xi.rules.migrate
  "Rules files written for an older xi: the match values and keys a release
   renamed, found (`changes`) and rewritten (`migrate-text`, run by
   `xi migrate`). xi.rules.store refuses a file that still has them, since an
   unknown match key is ignored and would make its rule match more.

   0.5.0: the bb tool became run_task, so `:tool :bb` is `:tool :task`,
   `:tool-name \"bb\"` is `\"run_task\"` and `:bb-trusted` is
   `:task-file-trusted`."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- rename-in
  "`v` (a value or a set of them) with `from` as `to`."
  [v from to]
  (cond
    (= from v)                       to
    (and (set? v) (contains? v from)) (-> v (disj from) (conj to))
    :else                            v))

(defn- migrate-match
  "→ [match descriptions-of-what-changed]."
  [m]
  (let [renames [[:tool :bb :task] [:tool-name "bb" "run_task"]]
        m'      (reduce (fn [m [k from to]]
                          (cond-> m (contains? m k) (update k rename-in from to)))
                        m renames)
        m'      (cond-> m'
                  (contains? m' :bb-trusted)
                  (-> (assoc :task-file-trusted (:bb-trusted m')) (dissoc :bb-trusted)))]
    [m' (cond-> []
          (not= (:tool m) (:tool m'))           (conj ":tool :bb → :task")
          (not= (:tool-name m) (:tool-name m')) (conj ":tool-name \"bb\" → \"run_task\"")
          (contains? m :bb-trusted)             (conj ":bb-trusted → :task-file-trusted"))]))

(defn- migrate-rule
  "→ [rule descriptions]; aliases (keywords under :defaults) pass through."
  [rule]
  (let [k (if (and (map? rule) (contains? rule :on-block)) :on-block :match)]
    (if (and (map? rule) (map? (get rule k)))
      (let [[m changes] (migrate-match (get rule k))]
        [(assoc rule k m) changes])
      [rule []])))

(defn- migrate-section
  "→ [rules descriptions] for the rule vector under `section`."
  [data section]
  (let [results (map migrate-rule (get data section))]
    [(mapv first results)
     (into [] (comp (map-indexed (fn [i [_ cs]]
                                   (map #(str (name section) " #" (inc i) ": " %) cs)))
                    cat)
           results)]))

(defn migrate-data
  "Parsed rules-file `data` with the renamed values and keys replaced →
   {:data :changes}; :changes describes each, empty when it was current."
  [data]
  (if-not (map? data)
    {:data data :changes []}
    (reduce (fn [{:keys [data changes]} section]
              (if (sequential? (get data section))
                (let [[rules cs] (migrate-section data section)]
                  {:data (assoc data section rules) :changes (into changes cs)})
                {:data data :changes changes}))
            {:data data :changes []}
            [:rules :defaults])))

(defn changes
  "What `migrate-data` would change in parsed `data`; empty when current."
  [data]
  (:changes (migrate-data data)))

;; ── Text ─────────────────────────────────────────────────────────────────────
;; The file is rewritten as text, so its comments and layout survive; the
;; result must read back as exactly the migrated data, else nothing is written.

(defn- rename-in-set-body
  "A set literal's body with the token `from` as `to`, or dropped when `to`
   is already in it (a duplicate would be a reader error)."
  [body from-re to]
  (if (re-find (re-pattern (str "(?:^|[\\s,])" (str/replace to #"[.*+?^${}()|\[\]\\]" "\\$&")
                                "(?![\\w-])"))
               body)
    (str/replace body from-re "")
    (str/replace body from-re to)))

(defn rewrite-text
  "`text` with the renamed tokens replaced where they appear as match values
   or keys. Syntactic only; `migrate-text` checks the result."
  [text]
  (-> text
      (str/replace #":bb-trusted(?![\w-])" ":task-file-trusted")
      (str/replace #"(:tool\s+):bb(?![\w-])" "$1:task")
      (str/replace #"(:tool\s+#\{)([^}]*)\}"
                   (fn [[_ open body]]
                     (str open (rename-in-set-body body #":bb(?![\w-])" ":task") "}")))
      (str/replace #"(:tool-name\s+)\"bb\"" "$1\"run_task\"")
      (str/replace #"(:tool-name\s+#\{)([^}]*)\}"
                   (fn [[_ open body]]
                     (str open (rename-in-set-body body #"\"bb\"" "\"run_task\"") "}")))))

(defn- comparable
  "Regex literals compared by their source (JS regexes are equal only to
   themselves)."
  [data]
  (walk/postwalk #(if (regexp? %) [::re (.-source %)] %) data))

(defn migrate-text
  "Migrate the rules file `text`, read with `read` (EDN → data, nil on
   failure) → nil when it is current or unreadable, {:text :changes} when
   rewritten, or {:error :changes} when the rewrite didn't come out as the
   migrated data (an unusual layout): those changes are left to the user."
  [text read]
  (let [{migrated :data :keys [changes]} (migrate-data (read text))]
    (when (seq changes)
      (let [text' (rewrite-text text)]
        (if (= (comparable migrated) (comparable (read text')))
          {:text text' :changes changes}
          {:error   "couldn't rewrite the file safely; make these changes by hand"
           :changes changes})))))
