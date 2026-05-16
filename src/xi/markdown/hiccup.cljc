(ns xi.markdown.hiccup
  "Renders markdown AST tokens to Replicant-compatible hiccup."
  (:require [xi.markdown.parse :as parse]))

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
        :code [:code content]
        :link [:a {:href (:url content)} (:text content)]
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
        (let [[_ {:keys [lang]} code] block]
          [:pre (if lang
                  [:code {:class (str "language-" lang)} code]
                  [:code code])])

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

        :hr
        [:hr]

        ;; fallback
        nil))))

(defn render
  "Render a full markdown string to hiccup nodes.
   Returns a vector of hiccup block elements."
  [text]
  (when text
    (let [blocks (parse/parse text)]
      (when (seq blocks)
        (into [:div {:class "markdown"}]
              (keep render-block blocks))))))
