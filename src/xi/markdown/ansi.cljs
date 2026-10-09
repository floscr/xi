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
                ;; Bare URL (text == url): render once in accent so terminals
                ;; that auto-detect URLs can make it clickable.
                (if (= text url)
                  (ansi/fg :accent url)
                  (str text " " (ansi/fg :dim url))))
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

(declare render-block)

(defn- render-list-item
  "Lines of one list item: `prefix` + wrapped inline text, then its nested
   child blocks indented under the text."
  [prefix tokens child-blocks width]
  (let [prefix-w (ansi/visible-width prefix)
        cont-pad (apply str (repeat prefix-w " "))
        item-w (max 1 (- width prefix-w))
        wrapped (ansi/wrap-text (render-inline tokens) item-w)]
    (into (vec (map-indexed
                (fn [i line]
                  {:text (str (if (zero? i) prefix cont-pad) line) :code? false})
                wrapped))
          (mapcat (fn [b]
                    (map (fn [l] (update l :text #(str cont-pad %)))
                         (render-block b item-w))))
          child-blocks)))

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
                (let [tokens (-> (hl/tokenize grammar code) hl/merge-adjacent)]
                  (mapv hl-theme/colorize (hl/split-tokens-by-line tokens)))
                lines)]
          (let [fence-open (str "  " (ansi/fg :dim (str "```" (or lang ""))))
                fence-close (str "  " (ansi/fg :dim "```"))]
            (into [{:text "" :code? true}
                   {:text (ansi/truncate-to-width fence-open width) :code? true}]
                  (concat
                   (mapv (fn [line] {:text (ansi/truncate-to-width (str "  " line) width) :code? true})
                         highlighted-lines)
                   [{:text (ansi/truncate-to-width fence-close width) :code? true}
                    {:text "" :code? true}]))))

        :ul
        (let [[_ items children] block]
          (into []
                (comp (map-indexed (fn [i tokens]
                                     (render-list-item "  • " tokens (nth children i nil) width)))
                      cat)
                items))

        :ol
        (let [[_ items children] block]
          (into []
                (comp (map-indexed (fn [i tokens]
                                     (render-list-item (str "  " (inc i) ". ") tokens
                                                       (nth children i nil) width)))
                      cat)
                items))

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

        :table
        (let [[_ {:keys [align]} {:keys [header rows]}] block
              ncols (apply max (count header) (map count rows))
              norm (fn [r] (vec (concat (mapv render-inline r)
                                        (repeat (- ncols (count r)) ""))))
              hdr (mapv #(ansi/fg :bold %) (norm header))
              body (mapv norm rows)
              col-w (mapv (fn [i]
                            (apply max 0 (map #(ansi/visible-width (nth % i ""))
                                              (cons hdr body))))
                          (range ncols))
              align-of (fn [i] (nth align i :none))
              pad-cell (fn [i s]
                         (let [w (nth col-w i)
                               deficit (max 0 (- w (ansi/visible-width s)))
                               sp #(apply str (repeat % " "))]
                           (case (align-of i)
                             :right (str (sp deficit) s)
                             :center (let [l (quot deficit 2)]
                                       (str (sp l) s (sp (- deficit l))))
                             (str s (sp deficit)))))
              ;; Two spaces between columns; no box-drawing borders (they copy
              ;; badly) — cells are aligned with spaces only.
              render-row (fn [r] (str/trimr (str/join "  " (map-indexed pad-cell r))))
              sep (str/trimr
                   (str/join "  "
                             (map #(ansi/fg :dim (apply str (repeat % "-"))) col-w)))
              lines (into [(render-row hdr) sep] (map render-row body))]
          (mapv (fn [l] {:text (ansi/truncate-to-width l width) :code? false}) lines))

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
