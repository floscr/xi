(ns xi.web.appearance
  "Web appearance settings — how the chat timeline renders its collapsible
   blocks. Three layers, later wins:

     built-in `defaults`
       ← `xi.config/appearance` (compile-time, config.cljc; xi.web.core
          seeds it into state as `:web/appearance-config` — this ns must not
          require xi.config, which reaches xi.web.views via the extension
          web halves and would form a cycle)
       ← the browser's own overrides (`:web/appearance`, set from the
          Appearance dialog and persisted to localStorage by xi.web.cache)

   Views read the merged map via `effective-in`; the dialog edits only the
   override layer, so \"Reset to defaults\" always lands back on the
   configured values."
  (:require [clojure.string :as str]))

(def defaults
  "Built-in values, one per setting. Runs of consecutive tool / thinking
   posts always fold into one grouped box of header rows (viewer mode); the
   settings decide how far each group folds:

     :super-collapsed? fold every group whose blocks are all collapsed into
                       one summary row (step count + latest block); click it
                       to reveal the header rows
     :tool-blocks      :open | :collapsed — whether a tool call's details
                       start expanded
     :tool-blocks-except
                       tool names that start the other way: with
                       :collapsed the tools that stay open, with :open the
                       only ones that collapse
     :thinking-blocks  :open | :collapsed — same for thinking blocks
     :tool-args        :summary | :all — what a tool call's header shows
                       next to the tool name: a per-tool one-liner (the
                       command, the path, the pattern) or every argument
                       as `k=v`
     :terminal-hints?  show the tooltips a terminal program sends (OSC
                       7475, xi.web.terminal)"
  {:super-collapsed? true
   :tool-blocks      :collapsed
   :tool-blocks-except []
   :thinking-blocks  :collapsed
   :tool-args        :summary
   :terminal-hints?  true})

(def choices
  "Allowed values per setting; anything else is ignored by `normalize`
   (including the retired :viewer-mode? key a stored override may still
   carry)."
  {:super-collapsed? #{true false}
   :tool-blocks      #{:open :collapsed}
   :thinking-blocks  #{:open :collapsed}
   :tool-args        #{:summary :all}
   :terminal-hints?  #{true false}})

(def max-tool-names 64)

;; The Claude provider advertises every Xi tool as mcp__xi-tools__<name>
;; (xi.providers.anthropic); the other providers use the bare name.
(def ^:private xi-tools-prefix "mcp__xi-tools__")

(defn tool-key
  "The name a tool is listed under in :tool-blocks-except: lowercased, without
   the Claude provider's prefix, so `read`, `Read` and `mcp__xi-tools__read`
   are one key. An MCP server's tool keeps its mcp__<server>__ part."
  [tool]
  (some-> tool str/trim str/lower-case
          (as-> k (if (str/starts-with? k xi-tools-prefix)
                    (subs k (count xi-tools-prefix))
                    k))))

(defn- tool-names
  "`v` as a sorted, distinct vector of tool keys, or nil when it isn't a
   bounded list of short non-blank strings."
  [v]
  (when (and (sequential? v)
             (<= (count v) max-tool-names)
             (every? #(and (string? %) (<= 1 (count (str/trim %)) 128)) v))
    (vec (sort (distinct (map tool-key v))))))

(defn- tool-entry
  "A tool from the server (`{:name :source :ext}`, xi.cli/tool-sources) or a
   listed key as {:key :label :source :ext}, `default-source` when it carries
   none. An MCP tool is labelled without its mcp__<server>__ part."
  [default-source t]
  (let [{:keys [name source ext]} (if (map? t) t {:name t})]
    (when-let [k (tool-key name)]
      (if-let [[_ server tool] (re-matches #"mcp__(.+?)__(.+)" k)]
        {:key k :label tool :source :mcp :ext server}
        {:key k :label k :source (or source default-source) :ext ext}))))

(def ^:private source-order [:builtin :extension :user :mcp :other :unavailable])

(defn- group-title [{:keys [source ext]}]
  (case source
    :builtin     "Built-in"
    :extension   "Extensions"
    :user        (str ext " · user extension")
    :mcp         (str ext " · MCP")
    :other       "Tools"
    "Not available"))

(defn tool-groups
  "The server's `tools` plus the `listed` keys (:tool-blocks-except: a listed
   tool that is gone can still be unchecked) as [[{:source :ext} [entry …]] …]:
   built-in tools, the bundled extensions' tools, then one group per user
   extension and per MCP server, each sorted. A tool the server sent without
   a source (an older server: bare names) is :other; a listed one it didn't
   send at all (an extension since disabled) is :unavailable."
  [tools listed]
  (->> (concat (keep (partial tool-entry :other) tools)
               (keep (partial tool-entry :unavailable) listed))
       (reduce (fn [m t] (cond-> m (not (contains? m (:key t))) (assoc (:key t) t))) {})
       vals
       (group-by (fn [{:keys [source ext]}]
                   {:source source :ext (when (#{:user :mcp} source) ext)}))
       (sort-by (fn [[{:keys [source ext]}]]
                  [(.indexOf source-order source) (or ext "")]))
       (mapv (fn [[group ts]] [group (vec (sort-by :label ts))]))))

(def tool-filters
  "The tool list's filter chips: [id label]; the source ones match :source."
  [[:all "All"] [:selected "Selected"] [:builtin "Built-in"]
   [:extension "Extensions"] [:user "User extensions"] [:mcp "MCP"]])

(defn tool-filter-counts
  "`tool-filters` as [id label count] for `tools` and `listed`; a source
   filter without tools is left out."
  [tools listed]
  (let [entries (mapcat second (tool-groups tools listed))
        on?     (set listed)]
    (into []
          (keep (fn [[id label]]
                  (let [n (count (case id
                                   :all      entries
                                   :selected (filter (comp on? :key) entries)
                                   (filter #(= id (:source %)) entries)))]
                    (when (or (#{:all :selected} id) (pos? n))
                      [id label n]))))
          tool-filters)))

(defn- hint
  "The muted note beside a tool's name: which extension a bundled tool comes
   from; in Selected, where every tool comes from."
  [selected? {:keys [source ext]}]
  (if selected?
    (case source
      :builtin     "built-in"
      :unavailable "not available"
      ext)
    (when (= :extension source) ext)))

(defn tool-sections
  "The Appearance dialog's tool list as [[title [entry …]] …] for `opts`
   {:query :filter}: with :filter :all (the default) the `listed` tools under
   \"Selected\", then every tool per `tool-groups` group; :selected keeps
   only the listed tools, a source filter (:builtin, :extension, :user, :mcp)
   only that source's groups. `query` is a substring of the tool key. A
   listed tool shows in Selected and in its group, so checking one never
   moves a row; each entry's :id is unique across the sections (a render
   key) and :hint is the note beside its name. Empty sections are left out."
  [tools listed {:keys [query] kind :filter}]
  (let [kind     (or kind :all)
        q        (some-> query str/trim str/lower-case not-empty)
        match?   #(or (nil? q) (str/includes? (:key %) q))
        on?      (set listed)
        shown?   (case kind
                   :all      (constantly true)
                   :selected (comp on? :key)
                   #(= kind (:source %)))
        groups   (tool-groups tools listed)
        selected (when (= :all kind)
                   (into []
                         (comp (mapcat second) (filter (comp on? :key)) (filter match?)
                               (map #(assoc % :id (str "selected/" (:key %))
                                              :hint (hint true %))))
                         groups))]
    (into (if (seq selected) [["Selected" selected]] [])
          (keep (fn [[group ts]]
                  (when-let [ts (seq (filter #(and (match? %) (shown? %)) ts))]
                    [(group-title group)
                     (mapv #(assoc % :id (:key %) :hint (hint false %)) ts)])))
          groups)))

(defn tool-summary
  "The listed tools in one line for the Chat pane's row, e.g. \"edit,
   git_commit +1\"; nil when none are listed."
  [listed]
  (when (seq listed)
    (let [labels (keep (comp :label (partial tool-entry nil)) listed)]
      (cond-> (str/join ", " (take 2 labels))
        (> (count labels) 2) (str " +" (- (count labels) 2))))))

(defn toggle-tool
  "`listed` (:tool-blocks-except) with tool `key` added or removed."
  [listed key]
  (if (some #{key} listed)
    (vec (remove #{key} listed))
    (conj (vec listed) key)))

(defn normalize
  "Keep only the known settings of `m` whose values are allowed (`choices`,
   `tool-names` for :tool-blocks-except). Tolerates nil / non-map input (a
   stale or hand-edited localStorage entry, an invalid config value) by
   dropping it."
  [m]
  (into {}
        (keep (fn [[k v]]
                (if (= :tool-blocks-except k)
                  (some->> (tool-names v) (vector k))
                  (when (contains? (get choices k) v) [k v]))))
        (when (map? m) m)))

(defn effective
  "The merged settings map: `defaults` ← `configured` (xi.config/appearance,
   seeded into state as :web/appearance-config by xi.web.core so this ns
   never requires xi.config) ← `overrides` (the browser's `:web/appearance`).
   Always has every key of `defaults`."
  ([overrides] (effective nil overrides))
  ([configured overrides]
   (merge defaults (normalize configured) (normalize overrides))))

(defn effective-in
  "`effective` for an app `state`: :web/appearance-config under
   :web/appearance."
  [state]
  (effective (:web/appearance-config state) (:web/appearance state)))

(defn block-collapsed?
  "Does history `entry` start collapsed under the effective settings `app`?
   Only :tool-call and :thinking entries are collapsible."
  [app {:keys [kind tool]}]
  (case kind
    :tool-call (let [collapsed? (= :collapsed (:tool-blocks app))]
                 (if (some #{(tool-key tool)} (:tool-blocks-except app))
                   (not collapsed?)
                   collapsed?))
    :thinking  (= :collapsed (:thinking-blocks app))
    false))

(defn overridden?
  "True when the browser holds at least one override — i.e. \"Reset to
   defaults\" would change something."
  [overrides]
  (boolean (seq (normalize overrides))))
