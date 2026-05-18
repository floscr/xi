(ns xi.markdown.ansi
  "Renders markdown AST tokens to ANSI-formatted lines for TUI display."
  (:require [clojure.string :as str]
            [xi.highlight.core :as hl]
            [xi.highlight.grammars :as grammars]
            [xi.highlight.theme :as hl-theme]
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
              text (render-inline tokens)
              wrapped (ansi/wrap-text (ansi/fg :bold text) width)]
          (mapv (fn [line] {:text line :code? false}) wrapped))

        :code-block
        (let [[_ {:keys [lang]} code] block
              code (or code "")
              grammar (grammars/get-grammar lang)
              lines (str/split-lines code)
              highlighted-lines
              (if grammar
                (mapv (fn [line]
                        (let [tokens (-> (hl/tokenize grammar line) hl/merge-adjacent)]
                          (hl-theme/colorize tokens)))
                      lines)
                lines)]
          (into [{:text "" :code? true}]
                (concat
                 (mapv (fn [line] {:text (ansi/truncate-to-width (str "  " line) width) :code? true})
                       highlighted-lines)
                 [{:text "" :code? true}])))

        :ul
        (let [[_ items] block
              prefix "  • "
              prefix-w (count prefix)
              cont-pad (apply str (repeat prefix-w " "))
              item-w (max 1 (- width prefix-w))]
          (into []
                (mapcat
                 (fn [tokens]
                   (let [text (render-inline tokens)
                         wrapped (ansi/wrap-text text item-w)]
                     (map-indexed
                      (fn [i line]
                        {:text (str (if (zero? i) prefix cont-pad) line) :code? false})
                      wrapped))))
                items))

        :ol
        (let [[_ items] block
              max-num (count items)]
          (into []
                (mapcat
                 (fn [[i tokens]]
                   (let [prefix (str "  " (inc i) ". ")
                         prefix-w (ansi/visible-width prefix)
                         cont-pad (apply str (repeat prefix-w " "))
                         item-w (max 1 (- width prefix-w))
                         text (render-inline tokens)
                         wrapped (ansi/wrap-text text item-w)]
                     (map-indexed
                      (fn [vi line]
                        {:text (str (if (zero? vi) prefix cont-pad) line) :code? false})
                      wrapped))))
                (map-indexed vector items)))

        :checkbox-list
        (let [[_ items] block
              ;; Checkbox prefix: "✓ " or "○ " = 2 visible chars
              prefix-w 2
              cont-pad (apply str (repeat prefix-w " "))
              item-w (max 1 (- width prefix-w))]
          (into []
                (mapcat
                 (fn [{:keys [checked? content]}]
                   (let [prefix (if checked?
                                  (str (ansi/fg :success "✓") " ")
                                  (str (ansi/fg :dim "○") " "))
                         text (render-inline content)
                         wrapped (ansi/wrap-text text item-w)]
                     (map-indexed
                      (fn [i line]
                        {:text (str (if (zero? i) prefix cont-pad) line) :code? false})
                      wrapped))))
                items))

        :blockquote
        (let [[_ inner-blocks] block
              prefix-w 2  ;; "│ " = 2 visible chars
              inner-w (max 1 (- width prefix-w))]
          (into []
                (mapcat (fn [b]
                          (let [lines (render-block b inner-w)]
                            (mapv (fn [{:keys [text code?]}]
                                    {:text (str (ansi/fg :dim "│ ") text) :code? code?})
                                  lines))))
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
