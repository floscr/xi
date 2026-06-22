(ns xi.tui.completion
  "Generic fuzzy-filtering completion menu component.
   Renders a bordered list of items with a search input.
   Up/Down to navigate, type to filter, Enter to select, Escape to cancel."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]))

;; ── Fuzzy Matching ────────────────────────────────────────────────────────────

(defn- fuzzy-match?
  "Case-insensitive fuzzy match: every char of query appears in order in text."
  [query text]
  (if (empty? query)
    true
    (let [q (str/lower-case query)
          t (str/lower-case text)
          qlen (count q)
          tlen (count t)]
      (loop [qi 0 ti 0]
        (cond
          (>= qi qlen) true
          (>= ti tlen) false
          (= (.charAt q qi) (.charAt t ti)) (recur (inc qi) (inc ti))
          :else (recur qi (inc ti)))))))

(defn- fuzzy-score
  "Simple scoring: lower is better. Prefers prefix matches and tight grouping."
  [query text]
  (if (empty? query)
    0
    (let [q (str/lower-case query)
          t (str/lower-case text)
          qlen (count q)
          tlen (count t)]
      (loop [qi 0 ti 0 score 0 last-match -1]
        (cond
          (>= qi qlen)
          score

          (>= ti tlen)
          js/Infinity

          (= (.charAt q qi) (.charAt t ti))
          (let [gap (if (neg? last-match) ti (- ti last-match 1))
                ;; Bonus for first-char match at start
                pos-bonus (if (and (zero? qi) (zero? ti)) -10 0)]
            (recur (inc qi) (inc ti) (+ score gap pos-bonus) ti))

          :else
          (recur qi (inc ti) score last-match))))))

(defn- extract-snippet
  "Build a ±30-char snippet around the first match of query in text."
  [text query]
  (let [tl (str/lower-case text)
        ql (str/lower-case query)
        idx (str/index-of tl ql)]
    (when idx
      (let [start (max 0 (- idx 30))
            end   (min (count text) (+ idx (count query) 30))
            prefix (if (pos? start) "…" "")
            suffix (if (< end (count text)) "…" "")]
        (-> (str prefix (subs text start end) suffix)
            (str/replace #"[\n\r]+" " "))))))

(defn- filter-and-sort
  "Filter items by fuzzy query, sort by score.
   search-field — when non-nil, use substring match on that field instead of
   fuzzy match on :label. Matched items get a :snippet in :description."
  [items query search-field]
  (if (empty? query)
    items
    (if search-field
      (->> items
           (keep (fn [item]
                   (let [text (get item search-field)]
                     (when (and (seq text)
                                (str/includes? (str/lower-case text)
                                               (str/lower-case query)))
                       (if-let [snip (extract-snippet text query)]
                         (assoc item :description snip)
                         item)))))
           vec)
      (->> items
           (filter #(fuzzy-match? query (:label %)))
           (sort-by #(fuzzy-score query (:label %)))))))

;; ── Key Detection (subset — reuse from editor) ───────────────────────────────

(def ^:private ESC (str (char 27)))
(def ^:private DEL (str (char 127)))
(def ^:private BS (str (char 8)))

(defn- ctrl? [data ch]
  (let [legacy-code (- (.charCodeAt ch 0) 64)
        codepoint (.charCodeAt (.toLowerCase ch) 0)]
    (or (= data (str (char legacy-code)))
        (= data (str (char 27) "[" codepoint ";5u")))))

(defn- is-enter? [data]
  (or (= data "\r") (= data "\n")))

(defn- is-backspace? [data]
  (or (= data DEL) (= data BS)))

(defn- is-escape? [data]
  (or (= data ESC)
      (= data (str ESC "[27u"))))

(defn- is-arrow-up? [data]
  (= data (str ESC "[A")))

(defn- is-arrow-down? [data]
  (= data (str ESC "[B")))

(defn- is-tab? [data]
  (= data "\t"))

(defn- is-printable? [data]
  (let [code (.charCodeAt data 0)]
    (and (= (count data) 1)
         (>= code 32)
         (not= code 127))))

;; ── Completion Menu Component ─────────────────────────────────────────────────

(defn make-completion-menu
  "Create a completion menu component.

   opts:
     :items      — vec of {:label str :value any :description str (optional)}
     :prompt     — input prompt string (default: \"filter: \")
     :max-visible — max visible items (default: 10)
     :on-select  — (fn [item]) called when user confirms selection
     :on-cancel  — (fn []) called when user presses Escape
     :header-fn  — (fn []) returns header string to render above items (optional)
     :search-field — kw (e.g. :search-text). When set, Ctrl+S toggles between
                     fuzzy-on-:label and substring-on-this-field.
     :key-bindings — vec of {:key-fn (fn [data]) :handler (fn [state-atom])} for custom keys"
  [opts]
  (let [all-items (:items opts)
        prompt (or (:prompt opts) "filter: ")
        max-visible (or (:max-visible opts) 10)
        on-select (:on-select opts)
        on-cancel (:on-cancel opts)
        header-fn (:header-fn opts)
        search-field (:search-field opts)
        key-bindings (or (:key-bindings opts) [])

        state (atom {:query ""
                     :selected 0
                     :all-items all-items
                     :filtered all-items
                     :search-mode false})

        refilter! (fn []
                    (let [{:keys [query all-items search-mode]} @state
                          sf (when search-mode search-field)
                          filtered (filter-and-sort all-items query sf)]
                      (swap! state assoc
                             :filtered filtered
                             :selected (min (:selected @state)
                                            (max 0 (dec (count filtered)))))))

        update-items! (fn [new-items]
                        (swap! state assoc :all-items new-items)
                        (refilter!)
                        (tui/request-panel-render!))

        move-selection (fn [delta]
                         (let [{:keys [filtered selected]} @state
                               n (count filtered)]
                           (when (pos? n)
                             (swap! state assoc :selected
                                    (mod (+ selected delta n) n))))
                         (tui/request-panel-render!))

        insert-char (fn [ch]
                      (swap! state update :query str ch)
                      (refilter!)
                      (tui/request-panel-render!))

        delete-back (fn []
                      (let [q (:query @state)]
                        (when (pos? (count q))
                          (swap! state assoc :query (subs q 0 (dec (count q))))
                          (refilter!)
                          (tui/request-panel-render!))))

        confirm (fn []
                  (let [{:keys [filtered selected]} @state]
                    (when (and (seq filtered) on-select)
                      (on-select (nth filtered selected)))))

        cancel (fn []
                 (when on-cancel (on-cancel)))

        check-key-bindings
        (fn [data]
          (some (fn [{:keys [key-fn handler]}]
                  (when (key-fn data)
                    (handler state update-items!)
                    true))
                key-bindings))

        toggle-search! (fn []
                         (swap! state update :search-mode not)
                         (refilter!)
                         (tui/request-panel-render!))

        handle-input
        (fn [data]
          (cond
            ;; Custom key-bindings take priority
            (check-key-bindings data) nil
            (is-escape? data)          (cancel)
            (is-enter? data)           (confirm)
            (or (is-arrow-up? data)
                (ctrl? data "P"))      (move-selection -1)
            (or (is-arrow-down? data)
                (ctrl? data "N"))      (move-selection 1)
            (is-tab? data)             (move-selection 1)
            (is-backspace? data)       (delete-back)
            (ctrl? data "U")           (do (swap! state assoc :query "")
                                           (refilter!)
                                           (tui/request-panel-render!))
            (ctrl? data "C")           (cancel)
            ;; Ctrl+S toggles full-text search when search-field is configured
            (and search-field
                 (ctrl? data "S"))     (toggle-search!)
            (is-printable? data)       (insert-char data)

            ;; Multi-byte printable (unicode)
            (and (> (count data) 1)
                 (not (str/starts-with? data ESC)))
            (insert-char data)

            :else nil))]

    {:type :completion-menu
     :handle-input handle-input
     :invalidate (fn [])
     :render
     (fn [width]
       (let [{:keys [query selected filtered all-items search-mode]} @state
             border-top (ansi/fg :border (apply str (repeat width "─")))
             border-bot (ansi/fg :border (apply str (repeat width "─")))
             prompt-w (ansi/visible-width prompt)
             ;; Render the query input with cursor
             cursor-ch (str ansi/ESC "7m" " " ansi/ESC "27m")
             query-line (str (ansi/fg :accent prompt) query cursor-ch)

             ;; Optional header line
             header-line (when header-fn (header-fn))

             ;; Search mode indicator (only when search-field is configured)
             search-line (when search-field
                           (str "  "
                                (if search-mode
                                  (str (ansi/fg :dim "○ Title") (ansi/fg :dim " | ")
                                       (ansi/fg :accent "◉ Content"))
                                  (str (ansi/fg :accent "◉ Title") (ansi/fg :dim " | ")
                                       (ansi/fg :dim "○ Content")))
                                (ansi/fg :dim "  (Ctrl+S)")))

             ;; Visible window around selected item
             n (count filtered)
             visible-count (min max-visible n)
             ;; Scroll offset: keep selected item visible
             scroll-start (cond
                            (<= n max-visible) 0
                            (< selected (quot max-visible 2))
                            0
                            (> selected (- n (quot max-visible 2)))
                            (- n max-visible)
                            :else
                            (- selected (quot max-visible 2)))
             scroll-end (+ scroll-start visible-count)

             ;; Render visible items
             item-lines
             (into []
                   (map-indexed
                    (fn [vi idx]
                      (let [item (nth filtered idx)
                            is-selected (= idx selected)
                            label (str/replace (or (:label item) "") #"[\n\r]+" " ")
                            desc (:description item)
                            prefix (if is-selected
                                     (ansi/fg :accent "❯ ")
                                     "  ")
                            label-str (if is-selected
                                        (ansi/fg :bold label)
                                        label)
                            desc-str (when (seq desc)
                                       (str " " (ansi/fg :dim desc)))
                            line (str prefix label-str desc-str)]
                        (ansi/truncate-to-width line width)))
                    (range scroll-start scroll-end)))

             ;; Empty state
             item-lines (if (empty? filtered)
                          [(str "  " (ansi/fg :dim "(no matches)"))]
                          item-lines)

             ;; Count indicator
             count-line (ansi/fg :dim
                                 (str "  " (count filtered) "/" (count all-items)
                                      (when (> n max-visible)
                                        (str " (scroll ↑↓)"))))]

         (into []
               (concat
                [border-top]
                (when header-line [header-line])
                (when search-line [search-line])
                [""]
                item-lines
                [""]
                [count-line]
                [border-bot]
                [query-line]))))}))
