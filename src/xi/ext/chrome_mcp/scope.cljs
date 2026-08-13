(ns xi.ext.chrome-mcp.scope
  "Pure classification for chrome-mcp workspace scoping.

   Given what chrome-devtools-mcp reports (`list_pages`), what CDP reports
   (page targets grouped by window), and what the window manager reports
   (X11 windows with their xmonad workspace), decide which mcp pages live on the
   currently-viewed workspace.

   Everything is keyed by workspace *name*, never index — xmonad workspaces are
   dynamic (they grow and shrink), so an index is unstable while a name is not.

   Correlation chain (see docs/chrome-mcp.md):

     mcp page --URL--> CDP target --windowId--> CDP window
              --tab title--> X11 window (wm) --> workspace

   Geometry can't bridge CDP<->X11 (HiDPI scale + origin differences + tiling
   collisions), so the bridge is the tab *title*; mcp<->CDP is the *URL*. When a
   window's workspace can't be determined the page is treated as NOT on the
   current workspace (fail-safe: never touch what we can't place)."
  (:require [clojure.string :as str]))

(def ^:private title-suffix-re #"\s+-\s+(Google Chrome|Chromium)$")

(defn strip-chrome-suffix
  "X11 chrome titles are `<active tab title> - Google Chrome`; drop the suffix."
  [s]
  (some-> s (str/replace title-suffix-re "") str/trim))

(defn parse-pages
  "Parse a `list_pages` result body into [{:id :title :url :selected?}].
   chrome-devtools-mcp prints `<id>: <title> (<url>)[ [selected]]` lines under a
   `## Pages` header."
  [text]
  (->> (str/split-lines (or text ""))
       (keep (fn [line]
               (when-let [[_ id rest] (re-matches #"\s*(\d+):\s*(.*)" line)]
                 (let [selected? (str/includes? rest "[selected]")
                       url       (last (map second (re-seq #"\(([a-z][a-z0-9+.-]*://[^)]*)\)" rest)))
                       title     (-> rest
                                     (str/replace #"\s*\[selected\]" "")
                                     (str/replace #"\s*isolatedContext=\S+" "")
                                     (cond-> url (str/replace (str "(" url ")") ""))
                                     str/trim)]
                   {:id (js/parseInt id 10) :title title :url url :selected? selected?}))))
       vec))

(defn- titles-loosely-equal?
  "Titles from two sources for the same tab; exact, else one contains the other
   (guards truncation / trailing whitespace / minor decoration differences)."
  [a b]
  (let [a (some-> a str/trim) b (some-> b str/trim)]
    (boolean
     (and (seq a) (seq b)
          (or (= a b)
              (str/includes? a b)
              (str/includes? b a))))))

(defn- pages-aligned?
  "True if mcp `pages` and `cdp-targets` line up positionally: same length and,
   wherever both sides report a concrete URL, they agree. about:blank / nil URLs
   are treated as wildcards — that's exactly where positional correlation earns
   its keep, since URL matching can't tell two blank pages apart. mcp list_pages
   order matches CDP getTargets(page) order (both derive from the same target
   list), so equal length + no URL contradiction means index i <-> target i."
  [pages cdp-targets]
  (and (= (count pages) (count cdp-targets))
       (seq pages)
       (every? (fn [[p t]]
                 (let [pu (some-> (:url p) str/trim)
                       tu (some-> (:url t) str/trim)]
                   (or (str/blank? pu) (str/blank? tu) (= pu tu))))
               (map vector pages cdp-targets))))

(defn- correlate-pages->wid
  "Map each mcp page id → CDP window-id. Prefer positional correlation (robust
   for about:blank and duplicate URLs); fall back to unique-URL matching when
   the two lists don't line up (different length or a URL contradiction)."
  [pages cdp-targets]
  (if (pages-aligned? pages cdp-targets)
    (into {} (map (fn [p t] [(:id p) (:window-id t)]) pages cdp-targets))
    (let [url->wid (reduce (fn [m {:keys [url window-id]}]
                            (cond-> m (and url (not (contains? m url)))
                                    (assoc url window-id)))
                          {} cdp-targets)]
      (into {} (map (fn [{:keys [id url]}] [id (get url->wid url)]) pages)))))

(defn- window->workspace
  "Map each CDP windowId → xmonad workspace *name* by matching any of the
   window's tab titles to an X11 (wm) window title. Unmatched windows are
   omitted."
  [cdp-targets wm-windows]
  (let [wm* (map (fn [w] (assoc w :title* (strip-chrome-suffix (:title w)))) wm-windows)]
    (->> (group-by :window-id cdp-targets)
         (keep (fn [[wid targets]]
                 (when wid
                   (when-let [ws (some (fn [w]
                                         (when (some #(titles-loosely-equal? (:title %) (:title* w))
                                                     targets)
                                           (:workspace w)))
                                       wm*)]
                     [wid ws]))))
         (into {}))))

(defn classify
  "Decide which mcp pages are on the current workspace.

   Inputs:
     :pages                  [{:id :title :url :selected?}]  (from parse-pages)
     :cdp-targets            [{:url :title :window-id}]       (page targets)
     :wm-windows             [{:workspace :title}]            (X11 chrome windows)
     :launch-workspace       string  the workspace name we consider ours (the
                             currently-viewed workspace, read live by the caller
                             — not cached)
     :owned-window-workspaces {cdp-window-id workspace-name}  windows this agent
                             created and placed itself. A freshly self-healed
                             about:blank window has no title to correlate against
                             an X11 window, so title matching can't place it —
                             but we know the workspace we moved it to, so we
                             record it here. Merged over the title-derived
                             workspace map, this keeps such a window scoped to
                             *its* workspace (not ours-everywhere).

   → {:in-workspace #{page-id …}
      :in-workspace-window-ids #{cdp-window-id …}
      :selected-id  page-id|nil
      :selected-in? bool
      :page->workspace {page-id workspace-name|nil}}

   `:in-workspace-window-ids` is every CDP window on the launch workspace
   (derived from the window→workspace map, independent of page correlation) —
   the guard opens a new tab in one of these to reuse an existing window instead
   of spawning a fresh one."
  [{:keys [pages cdp-targets wm-windows launch-workspace owned-window-workspaces]}]
  (let [page->wid (correlate-pages->wid pages cdp-targets)
        wid->ws (merge (window->workspace cdp-targets wm-windows)
                       owned-window-workspaces)
        page->ws (into {} (map (fn [{:keys [id]}]
                                 [id (some-> (get page->wid id) wid->ws)]))
                       pages)
        in-ws (into #{}
                    (keep (fn [{:keys [id]}]
                            (let [ws (get page->ws id)]
                              (when (and (some? ws) (= ws launch-workspace)) id))))
                    pages)
        in-ws-wids (into #{}
                        (keep (fn [[wid ws]]
                                (when (and (some? wid) (= ws launch-workspace)) wid)))
                        wid->ws)
        selected (some #(when (:selected? %) (:id %)) pages)]
    {:in-workspace            in-ws
     :in-workspace-window-ids in-ws-wids
     :selected-id             selected
     :selected-in?            (contains? in-ws selected)
     :page->workspace         page->ws}))

(defn filter-list-pages-text
  "Rewrite a `list_pages` result body to only the lines for `keep-ids`
   (plus the `## Pages` header and any non-page lines). Preserves the mcp
   page ids the model uses to address pages."
  [text keep-ids]
  (let [keep? (set keep-ids)]
    (->> (str/split-lines (or text ""))
         (keep (fn [line]
                 (if-let [[_ id] (re-matches #"\s*(\d+):.*" line)]
                   (when (keep? (js/parseInt id 10)) line)
                   line)))
         (str/join "\n"))))
