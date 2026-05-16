(ns xi.markdown.ansi
  "Renders markdown AST tokens to ANSI-formatted lines for TUI display."
  (:require [clojure.string :as str]
            [xi.markdown.parse :as parse]
            [xi.tui.ansi :as ansi]))

;; ---------------------------------------------------------------------------
;; Inline rendering → ANSI string
;; ---------------------------------------------------------------------------

(defn- render-inline-token
  "Render a single inline token to an ANSI string."
  [token]
  (cond
    (string? token) token
    (vector? token)
    (let [[tag content] token]
      (case tag
        :bold (ansi/fg :bold (apply str (map render-inline-token content)))
        :italic (apply str (map render-inline-token content))
        :strike (apply str (map render-inline-token content))
        :code (ansi/fg :accent content)
        :link (let [{:keys [text url]} content]
                (str text " " (ansi/fg :dim url)))
        (str token)))
    :else (str token)))

(defn render-inline
  "Render inline tokens to a single ANSI string."
  [tokens]
  (apply str (map render-inline-token tokens)))

;; ---------------------------------------------------------------------------
;; Block rendering → lines
;; ---------------------------------------------------------------------------

(def ^:private code-bg "\033[48;2;67;76;94m")
(def ^:private code-fg "\033[38;2;255;255;255m")

(defn- render-block
  "Render a block token to a seq of {:text :code?} maps."
  [block width]
  (when (vector? block)
    (let [[tag] block]
      (case tag
        :paragraph
        (let [[_ tokens] block
              text (render-inline tokens)
              wrapped (ansi/wrap-text text width)]
          (mapv (fn [line] {:text line :code? false}) wrapped))

        :heading
        (let [[_ _ tokens] block
              text (render-inline tokens)]
          [{:text (ansi/fg :bold text) :code? false}])

        :code-block
        (let [[_ _ code] block
              lines (str/split-lines (or code ""))]
          (into [{:text "" :code? true}]
                (concat
                 (mapv (fn [line] {:text (str "  " line) :code? true}) lines)
                 [{:text "" :code? true}])))

        :ul
        (let [[_ items] block]
          (mapv (fn [tokens]
                  {:text (str "  • " (render-inline tokens)) :code? false})
                items))

        :ol
        (let [[_ items] block]
          (vec (map-indexed
                (fn [i tokens]
                  {:text (str "  " (inc i) ". " (render-inline tokens)) :code? false})
                items)))

        :checkbox-list
        (let [[_ items] block]
          (mapv (fn [{:keys [checked? content]}]
                  {:text (str (if checked?
                                (str (ansi/fg :success "✓") " ")
                                (str (ansi/fg :dim "○") " "))
                              (render-inline content))
                   :code? false})
                items))

        :blockquote
        (let [[_ inner-blocks] block]
          (mapv (fn [b]
                  (let [lines (render-block b width)]
                    (mapv (fn [{:keys [text code?]}]
                            {:text (str (ansi/fg :dim "│ ") text) :code? code?})
                          lines)))
                inner-blocks))

        :hr
        [{:text (ansi/fg :dim (apply str (repeat (min width 40) "─"))) :code? false}]

        []))))

(defn render
  "Render markdown text to ANSI-formatted lines.
   Returns a vector of {:text :code?} maps."
  [text width]
  (when (and text (not (str/blank? text)))
    (let [blocks (parse/parse text)]
      (reduce
       (fn [acc block]
         (let [lines (render-block block width)]
           (if (seq acc)
             ;; Add blank line between blocks
             (into (conj acc {:text "" :code? false}) (flatten lines))
             (into acc (flatten lines)))))
       []
       blocks))))
