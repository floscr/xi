(ns xi.markdown.hiccup
  "Renders markdown AST tokens to Replicant-compatible hiccup."
  (:require [clojure.string :as str]
            [xi.markdown.parse :as parse]
            [xi.url :as url]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.theme-css :as theme]
            [ui.button-group :as bg]))

;; ---------------------------------------------------------------------------
;; Bare-URL linkification (for pre-formatted / code contexts)
;; ---------------------------------------------------------------------------

(defn linkify
  "Split a plain string into a seq of hiccup nodes, wrapping bare http(s)://
   URLs in anchor tags. Unlike the inline markdown parser this does NOT treat
   any other character specially, so it's safe to run over literal code — it's
   how links inside code blocks and inline code stay clickable."
  [^String s]
  (if-not (string? s)
    [s]
    (let [len (count s)]
      (loop [idx   0
             start 0
             acc   []]
        (if (>= idx len)
          (if (< start len) (conj acc (subs s start)) acc)
          (if-let [[u end] (and (url/scheme-at s idx) (url/url-at s idx))]
            (recur (long end) (long end)
                   (cond-> acc
                     (< start idx) (conj (subs s start idx))
                     :always       (conj [:a {:href u :target "_blank"
                                              :rel "noopener noreferrer"} u])))
            (recur (inc idx) start acc)))))))

;; ---------------------------------------------------------------------------
;; Inline rendering
;; ---------------------------------------------------------------------------

(defn- hard-breaks
  "Split a plain-text token on newlines, interleaving [:br] nodes, so a
   CommonMark soft break (a single newline inside a paragraph) renders as a
   visible line break instead of collapsing to a space."
  [^String s]
  (interpose [:br] (str/split s #"\n" -1)))

(defn- render-inline-token
  "Render a single inline token to a seq of hiccup nodes (a string may expand
   to several under :hard-breaks?). `opts` is the render opts map (see
   `render`)."
  [opts token]
  (let [nodes (fn [content] (mapcat #(render-inline-token opts %) content))]
    (cond
      (string? token) (if (:hard-breaks? opts) (hard-breaks token) [token])
      (vector? token)
      (let [[tag content] token]
        [(case tag
           :bold (into [:strong] (nodes content))
           :italic (into [:em] (nodes content))
           :strike (into [:del] (nodes content))
           :code (into [:code] (linkify content))
           :link [:a {:href (:url content) :target "_blank" :rel "noopener noreferrer"} (:text content)]
           ;; fallback
           (str token))])
      :else [(str token)])))

(defn render-inline
  "Render inline tokens to hiccup nodes. `opts` as for `render`."
  ([tokens] (render-inline tokens nil))
  ([tokens opts]
   (into [] (mapcat #(render-inline-token opts %)) tokens)))

(defn- inline-text
  "Flatten inline tokens to their visible plain text (markup dropped)."
  [tokens]
  (apply str
         (for [t tokens]
           (cond
             (string? t) t
             (vector? t) (let [[tag content] t]
                           (case tag
                             (:bold :italic :strike) (inline-text content)
                             :code content
                             :link (:text content)
                             ""))
             :else ""))))

(defn- table-mode-toggle
  "Table ⇄ List segmented control. The mode is pure DOM state: the .as-text
   class on the enclosing .md-table-wrap, which also drives which segment
   looks active (see style.css), so there's no second flag to keep in sync."
  []
  (let [set-mode (fn [text?]
                   (fn [e]
                     (.. e -currentTarget -parentNode -parentNode -classList
                         (toggle "as-text" text?))))]
    (bg/button-group {:variant :boxed :class "md-table-toggle"}
                     (bg/button-group-item {:icon :grid :class "md-table-mode-grid"
                                            :attrs {:type "button" :title "Show as table"}
                                            :on-click (set-mode false)}
                                           "Table")
                     (bg/button-group-item {:icon :list :class "md-table-mode-list"
                                            :attrs {:type "button" :title "Show as list"}
                                            :on-click (set-mode true)}
                                           "List"))))

;; ---------------------------------------------------------------------------
;; Block rendering
;; ---------------------------------------------------------------------------

(defn render-block
  "Render a single block token to hiccup. `opts` as for `render`; it only
   affects paragraph text (and paragraphs nested in blockquotes). Public for
   the rendered markdown diff (xi.markdown.diff), which renders blocks one by
   one."
  [block opts]
  (when (vector? block)
    (let [[tag] block]
      (case tag
        :paragraph
        (let [[_ tokens] block]
          (into [:p] (render-inline tokens opts)))

        :heading
        (let [[_ {:keys [level]} tokens] block
              htag (keyword (str "h" (max 1 (min 6 level))))]
          (into [htag] (render-inline tokens)))

        :code-block
        (let [[_ {:keys [lang]} code] block
              grammar (when lang (grammars/get-grammar lang))
              tokens (when grammar
                       (hl/merge-adjacent (hl/tokenize grammar code)))]
          [:pre {:class (str "md-code-block" (when lang (str " language-" lang)))}
           (if tokens
             (into [:code]
                   (mapcat (fn [{:keys [type value]}]
                             (if-let [cls (theme/token-class type)]
                               [(into [:span {:class cls}] (linkify value))]
                               (linkify value)))
                           tokens))
             (into [:code] (linkify code)))])

        (:ul :ol)
        (let [[_ items children] block]
          (into [tag]
                (map-indexed
                 (fn [i item]
                   (into [:li] (concat (render-inline item)
                                       (map #(render-block % opts) (nth children i nil)))))
                 items)))

        :checkbox-list
        (let [[_ items] block]
          (into [:ul {:class "checkbox-list"}]
                (for [{:keys [checked? content]} items]
                  [:li {:class (if checked? "checked" "unchecked")}
                   [:span {:class "checkbox"}
                    (if checked? "✓" "○")]
                   (into [:span] (render-inline content))])))

        :blockquote
        (let [[_ inner-blocks] block]
          (into [:blockquote] (mapv #(render-block % opts) inner-blocks)))

        :table
        (let [[_ {:keys [align]} {:keys [header rows]}] block
              cell-attrs (fn [i]
                           (case (nth align i :none)
                             :right {:style {:text-align "right"}}
                             :center {:style {:text-align "center"}}
                             {}))
              labels (mapv inline-text header)]
          [:div {:class "md-table-wrap"}
           (table-mode-toggle)
           [:table {:class "md-table"}
            [:thead
             (into [:tr]
                   (map-indexed (fn [i c] (into [:th (cell-attrs i)] (render-inline c)))
                                header))]
            (into [:tbody]
                  (for [r rows]
                    (into [:tr]
                          ;; data-label carries the column name into list
                          ;; mode, where the thead is hidden.
                          (map-indexed (fn [i c]
                                         (into [:td (assoc (cell-attrs i) :data-label (nth labels i ""))]
                                               (render-inline c)))
                                       r))))]])

        :hr
        [:hr]

        ;; fallback
        nil))))

(defn- with-block-key
  "Attach a positional :replicant/key to a rendered markdown block. Streaming
   re-parses the whole message every frame, so block boundaries shift and merge
   — an unkeyed sequence then makes Replicant reconcile positionally and, when a
   middle block drops, call removeChild on a stale node (\"Argument 1 is not an
   object\"), throwing mid-render. A stable per-index key makes it reconcile
   position-for-position and trim the tail cleanly instead."
  [i block]
  (if (map? (second block))
    (assoc-in block [1 :replicant/key] i)
    (into [(first block) {:replicant/key i}] (rest block))))

(defn render
  "Render a full markdown string to hiccup nodes.
   Returns a vector of hiccup block elements.

   `opts`:
   - `:hard-breaks?` — render a single newline inside a paragraph as a
     visible line break ([:br]) rather than a CommonMark soft break that
     collapses to a space. For user-typed text, where a newline in the
     composer is meant literally."
  ([text] (render text nil))
  ([text opts]
   (when text
     (let [blocks (parse/parse text)]
       (when (seq blocks)
         (into [:div {:class "markdown"}]
               (map-indexed with-block-key
                            (keep #(render-block % opts) blocks))))))))
