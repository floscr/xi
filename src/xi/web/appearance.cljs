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
   configured values.")

(def defaults
  "Built-in values, one per setting:

     :viewer-mode?    fold each run of consecutive tool / thinking posts into
                      one grouped box of header rows (viewer mode)
     :tool-blocks     :open | :collapsed — whether a tool call's details
                      start expanded
     :thinking-blocks :open | :collapsed — same for thinking blocks"
  {:viewer-mode?    true
   :tool-blocks     :collapsed
   :thinking-blocks :collapsed})

(def choices
  "Allowed values per setting; anything else is ignored by `normalize`."
  {:viewer-mode?    #{true false}
   :tool-blocks     #{:open :collapsed}
   :thinking-blocks #{:open :collapsed}})

(defn normalize
  "Keep only the known settings of `m` whose values are allowed (`choices`).
   Tolerates nil / non-map input (a stale or hand-edited localStorage entry,
   an invalid config value) by dropping it."
  [m]
  (into {}
        (filter (fn [[k v]] (contains? (get choices k) v)))
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
  "Does a history entry of `kind` (:tool-call / :thinking) start collapsed
   under the effective settings `app`? Other kinds are never collapsible."
  [app kind]
  (case kind
    :tool-call (= :collapsed (:tool-blocks app))
    :thinking  (= :collapsed (:thinking-blocks app))
    false))

(defn overridden?
  "True when the browser holds at least one override — i.e. \"Reset to
   defaults\" would change something."
  [overrides]
  (boolean (seq (normalize overrides))))
