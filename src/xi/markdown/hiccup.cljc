(ns xi.markdown.hiccup
  "Renders markdown AST tokens to Replicant-compatible hiccup."
  (:require [xi.markdown.parse :as parse]
            [xi.url :as url]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.theme-css :as theme]))

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

(defn- render-inline-token
  "Render a single inline token to hiccup."
  [token]
  (cond
    (string? token) token
    (vector? token)
    (let [[tag content] token]
      (case tag
        :bold (into [:strong] (map render-inline-token content))
        :italic (into [:em] (map render-inline-token content))
        :strike (into [:del] (map render-inline-token content))
        :code (into [:code] (linkify content))
        :link [:a {:href (:url content) :target "_blank" :rel "noopener noreferrer"} (:text content)]
        ;; fallback
        (str token)))
    :else (str token)))

(defn render-inline
  "Render inline tokens to hiccup nodes."
  [tokens]
  (mapv render-inline-token tokens))

;; ---------------------------------------------------------------------------
;; Block rendering
;; ---------------------------------------------------------------------------

(defn- render-block
  "Render a single block token to hiccup."
  [block]
  (when (vector? block)
    (let [[tag] block]
      (case tag
        :paragraph
        (let [[_ tokens] block]
          (into [:p] (render-inline tokens)))

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

        :ul
        (let [[_ items] block]
          (into [:ul]
                (for [item items]
                  (into [:li] (render-inline item)))))

        :ol
        (let [[_ items] block]
          (into [:ol]
                (for [item items]
                  (into [:li] (render-inline item)))))

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
          (into [:blockquote] (mapv render-block inner-blocks)))

        :table
        (let [[_ {:keys [align]} {:keys [header rows]}] block
              cell-attrs (fn [i]
                           (case (nth align i :none)
                             :right {:style {:text-align "right"}}
                             :center {:style {:text-align "center"}}
                             {}))]
          [:table {:class "md-table"}
           [:thead
            (into [:tr]
                  (map-indexed (fn [i c] (into [:th (cell-attrs i)] (render-inline c)))
                               header))]
           (into [:tbody]
                 (for [r rows]
                   (into [:tr]
                         (map-indexed (fn [i c] (into [:td (cell-attrs i)] (render-inline c)))
                                      r))))])

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
   Returns a vector of hiccup block elements."
  [text]
  (when text
    (let [blocks (parse/parse text)]
      (when (seq blocks)
        (into [:div {:class "markdown"}]
              (map-indexed with-block-key (keep render-block blocks)))))))
