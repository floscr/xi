(ns docs-gen
  "`bb docs:gen`: rewrite the generated blocks of docs/**/*.md from the
   source. A block sits between `<!-- gen:NAME -->` and `<!-- /gen:NAME -->`
   (the site drops the markers, xisite.markdown); NAME picks one of
   `generators`. `--check` writes nothing and exits 1 when a block is stale
   (bb release runs it)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private keys-file (str (fs/path (fs/parent *file*) ".." "src" "xi" "keys.cljc")))
(load-file keys-file)
(alias 'keys 'xi.keys)

;; ── Markdown ─────────────────────────────────────────────────────────────────

(defn- code
  "Inline code for a table cell. A backslash or backtick goes into <code>
   with entities instead: the site's markdown-clj reads `Alt+\\` as an
   escaped fence and knows no double-backtick spans."
  [s]
  (if (re-find #"[\\`|]" s)
    (str "<code>" (str/escape s {\\ "&#92;" \` "&#96;" \| "&#124;" \& "&amp;" \< "&lt;" \> "&gt;"}) "</code>")
    (str "`" s "`")))

(defn- table [header rows]
  (str/join "\n" (concat [(str "| " (str/join " | " header) " |")
                          (str "|" (str/join "|" (repeat (count header) " --- ")) "|")]
                         (for [r rows] (str "| " (str/join " | " r) " |")))))

;; ── gen:keys — the default keymap ────────────────────────────────────────────

(def ^:private clients
  {#{:web :tui} "both" #{:web} "web" #{:tui} "terminal"})

(defn- surface-actions [surface]
  (into {} (filter (fn [[_ {s :surface}]] (or (nil? s) (contains? s surface))))
        keys/catalog))

(defn- catalog-order
  "Action id → its line in xi.keys/catalog's source: the catalog is a hash
   map, but written with related actions (next / previous) side by side."
  []
  (into {}
        (keep-indexed (fn [i line]
                        (when-let [[_ id] (re-find #"^\s+\{?:([\w.-]+/[\w.-]+)\s+\{:label" line)]
                          [(keyword id) i])))
        (str/split-lines (slurp keys-file))))

(defn- default-rows
  "xi.keys/listing of the defaults on each client (the rows its shortcut list
   shows), merged so a row both clients share appears once with both
   surfaces."
  []
  (let [rank  (into {} (map-indexed (fn [i [l _]] [l i])) keys/layer-labels)
        order (catalog-order)
        rows  (for [surface [:web :tui]
                    :let [km (keys/effective-keymap {:surface surface})]
                    {:keys [layer rows]} (keys/listing km km (surface-actions surface) [])
                    row rows]
                (assoc row :layer layer :surface surface))]
    (->> (group-by (juxt :layer :action :keys) rows)
         vals
         (map (fn [rs] (assoc (first rs) :surfaces (set (map :surface rs)))))
         (sort-by (juxt #(rank (:layer %) 999) #(order (:action %) 999) :display)))))

(defn- keys-tables []
  (str/join
   "\n\n"
   (for [rows (partition-by :layer (default-rows))
         :let [layer (:layer (first rows))]]
     (str "**" (keys/layer-label layer) "** · " (code (str layer)) "\n\n"
          (table ["Key" "Does" "Action" "Client"]
                 (for [{:keys [action label surfaces] ks :keys} rows]
                   [(str/join " / " (map (comp code keys/format-key) ks))
                    label
                    (code (str action))
                    (clients surfaces)]))))))

;; ── gen:keys-unbound — actions without a default key ───────────────────────

(defn- unbound-table []
  (let [bound (into {} (for [s keys/surfaces]
                         [s (set (keep val (mapcat val (keys/effective-keymap {:surface s}))))]))
        order (catalog-order)]
    (table ["Action" "Does" "Client"]
           (for [[id {:keys [label surface]}] (sort-by #(order (key %) 999) keys/catalog)
                 :let [unbound (set (remove #(contains? (bound %) id) (or surface keys/surfaces)))]
                 :when (seq unbound)]
             [(code (str id)) label (clients unbound)]))))

;; ── Blocks ───────────────────────────────────────────────────────────────────

(def generators
  "Block name → (fn [] markdown)."
  {"keys"         keys-tables
   "keys-unbound" unbound-table})

(def ^:private block-re #"(?s)(<!-- gen:([\w-]+) -->\n).*?(<!-- /gen:\2 -->)")

(defn- render [path text]
  (str/replace text block-re
               (fn [[_ open name close]]
                 (let [gen (or (generators name)
                               (throw (ex-info (str path ": no generator gen:" name) {})))]
                   (str open "\n" (gen) "\n\n" close)))))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        stale  (vec (for [f (sort (map str (fs/glob "docs" "**.md")))
                          :let [text (slurp f) text' (render f text)]
                          :when (not= text text')]
                      (do (when-not check? (spit f text'))
                          f)))]
    (cond
      (empty? stale) (println "✓ generated docs are up to date")
      check?         (do (binding [*out* *err*]
                           (println (str "✗ stale generated docs (run `bb docs:gen`):\n  "
                                         (str/join "\n  " stale))))
                         (System/exit 1))
      :else          (println (str "updated " (str/join ", " stale))))))
