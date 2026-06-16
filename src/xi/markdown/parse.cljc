(ns xi.markdown.parse
  "Markdown parser — produces an AST of block and inline tokens.
   Works in both Clojure and ClojureScript."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Inline parsing
;; ---------------------------------------------------------------------------

(defn- starts-with-at?
  "Check if string s has prefix starting at idx."
  [^String s idx ^String prefix]
  (let [plen (count prefix)]
    (when (<= (+ idx plen) (count s))
      (loop [i 0]
        (if (>= i plen)
          true
          (if (= (.charAt s (+ idx i)) (.charAt prefix i))
            (recur (inc i))
            false))))))

(defn- find-closing
  "Find closing delimiter in s starting from idx. Returns index or -1."
  [^String s idx ^String delim]
  (let [dlen (count delim)
        slen (count s)]
    (loop [i idx]
      (if (> (+ i dlen) slen)
        -1
        (if (starts-with-at? s i delim)
          i
          (recur (inc i)))))))

(declare parse-inline)

(defn- parse-code-span
  "Parse `code` span. No nesting."
  [^String s idx]
  (let [len (count s)]
    (when (and (< idx len) (= (.charAt s idx) \`))
      (let [close (find-closing s (inc idx) "`")]
        (when (and (not= close -1) (> close (inc idx)))
          [[:code (subs s (inc idx) close)] (inc close)])))))

(defn- parse-delimited
  "Parse delimited span (**bold**, *italic*, ~~strike~~).
   Content is recursively parsed."
  [^String s idx ^String delim tag]
  (let [dlen (count delim)
        len (count s)]
    (when (starts-with-at? s idx delim)
      (when (and (< (+ idx dlen) len)
                 (not= (.charAt s (+ idx dlen)) \space))
        (let [close (find-closing s (+ idx dlen) delim)]
          (when (and (not= close -1)
                     (> close (+ idx dlen))
                     (not= (.charAt s (dec close)) \space))
            [[tag (parse-inline (subs s (+ idx dlen) close))]
             (+ close dlen)]))))))

(defn- parse-link
  "Parse [text](url) link."
  [^String s idx]
  (let [len (count s)]
    (when (and (< idx len) (= (.charAt s idx) \[))
      (let [close-bracket (find-closing s (inc idx) "](")]
        (when (not= close-bracket -1)
          (let [close-paren (find-closing s (+ close-bracket 2) ")")]
            (when (not= close-paren -1)
              (let [text (subs s (inc idx) close-bracket)
                    url (subs s (+ close-bracket 2) close-paren)]
                [[:link {:url url :text text}] (inc close-paren)]))))))))

(defn- special-char? [ch]
  (or (= ch \`) (= ch \*) (= ch \_) (= ch \~) (= ch \[)))

(defn- word-char?
  "True if ch is alphanumeric or underscore — i.e. a word character."
  [ch]
  (or (<= (int \a) (int ch) (int \z))
      (<= (int \A) (int ch) (int \Z))
      (<= (int \0) (int ch) (int \9))
      (= ch \_)))

(defn- try-parse-at
  "Try to parse an inline token at position idx."
  [^String s idx]
  (let [len (count s)
        ch (.charAt s idx)]
    (case ch
      \` (parse-code-span s idx)
      \* (or (parse-delimited s idx "**" :bold)
             (parse-delimited s idx "*" :italic))
      ;; Per CommonMark: underscores don't open emphasis when preceded by
      ;; a word character (e.g. git_file_diff stays literal).
      \_ (when-not (and (pos? idx) (word-char? (.charAt s (dec idx))))
           (or (parse-delimited s idx "__" :bold)
               (parse-delimited s idx "_" :italic)))
      \~ (parse-delimited s idx "~~" :strike)
      \[ (parse-link s idx)
      nil)))

(defn parse-inline
  "Parse inline markdown text into tokens.
   Returns a vector of strings and tagged vectors like [:bold [...]]."
  [^String s]
  (let [len (count s)]
    (loop [idx 0
           acc (transient [])]
      (if (>= idx len)
        (persistent! acc)
        (let [result (try-parse-at s idx)]
          (if result
            (let [[token next-idx] result]
              (recur (long next-idx) (conj! acc token)))
            ;; Collect plain text until next special char
            (let [end (loop [i (inc idx)]
                        (if (>= i len)
                          i
                          (if (special-char? (.charAt s i))
                            i
                            (recur (inc i)))))]
              (recur end (conj! acc (subs s idx end))))))))))

;; ---------------------------------------------------------------------------
;; Block parsing
;; ---------------------------------------------------------------------------

(defn- heading-line?
  "Returns [level text] if line is a heading, nil otherwise."
  [line]
  (when-let [m (re-matches #"^(#{1,6})\s+(.*)" line)]
    [(count (nth m 1)) (nth m 2)]))

(defn- hr-line? [line]
  (boolean (re-matches #"^[-*_]{3,}\s*$" line)))

(defn- ul-item-line?
  "Returns item text if line is unordered list item."
  [line]
  (when-let [m (re-matches #"^[-*+]\s+(.*)" line)]
    (nth m 1)))

(defn- checkbox-item-line?
  "Returns [checked? text] if line is a checkbox item."
  [line]
  (or (when-let [m (re-matches #"^[-*+]\s+\[x\]\s+(.*)" line)]
        [true (nth m 1)])
      (when-let [m (re-matches #"^[-*+]\s+\[ \]\s+(.*)" line)]
        [false (nth m 1)])))

(defn- ol-item-line?
  "Returns item text if line is ordered list item."
  [line]
  (when-let [m (re-matches #"^\d+[.)]\s+(.*)" line)]
    (nth m 1)))

(defn- blockquote-line?
  "Returns content if line is a blockquote line."
  [line]
  (when-let [m (re-matches #"^>\s?(.*)" line)]
    (nth m 1)))

(defn- code-fence?
  "Returns language string (possibly empty) if line is a code fence."
  [line]
  (when-let [m (re-matches #"^```(.*)" (str/trim line))]
    (nth m 1)))

(defn- blank-line? [line]
  (str/blank? line))

(defn- consume-list-items
  "Consume consecutive list items of the same type from lines.
   Returns [items remaining-lines]."
  [lines item-fn]
  (loop [lines lines
         items []]
    (if (empty? lines)
      [items lines]
      (if-let [text (item-fn (first lines))]
        (recur (rest lines) (conj items text))
        [items lines]))))

(defn parse
  "Parse markdown text into block tokens.
   Returns a vector of block tokens."
  [text]
  (when (and text (not (str/blank? text)))
    (let [lines (str/split-lines text)]
      (loop [lines lines
             blocks (transient [])]
        (if (empty? lines)
          (persistent! blocks)
          (let [line (first lines)]
            (cond
              ;; Blank line — skip
              (blank-line? line)
              (recur (rest lines) blocks)

              ;; Code fence
              (code-fence? line)
              (let [lang (code-fence? line)
                    ;; Collect lines until closing fence
                    [code-lines remaining]
                    (loop [ls (rest lines)
                           code-acc []]
                      (if (empty? ls)
                        [code-acc ls]
                        (if (code-fence? (first ls))
                          [code-acc (rest ls)]
                          (recur (rest ls) (conj code-acc (first ls))))))]
                (recur remaining
                       (conj! blocks [:code-block
                                      {:lang (when (seq lang) lang)}
                                      (str/join "\n" code-lines)])))

              ;; Heading
              (heading-line? line)
              (let [[level text] (heading-line? line)]
                (recur (rest lines)
                       (conj! blocks [:heading {:level level} (parse-inline text)])))

              ;; Horizontal rule
              (hr-line? line)
              (recur (rest lines) (conj! blocks [:hr]))

              ;; Checkbox items (before regular ul to take priority)
              (checkbox-item-line? line)
              (let [[items remaining]
                    (loop [ls lines
                           items []]
                      (if (empty? ls)
                        [items ls]
                        (if-let [[checked? text] (checkbox-item-line? (first ls))]
                          (recur (rest ls)
                                 (conj items {:checked? checked?
                                              :content (parse-inline text)}))
                          [items ls])))]
                (recur remaining
                       (conj! blocks [:checkbox-list items])))

              ;; Unordered list
              (ul-item-line? line)
              (let [[items remaining] (consume-list-items lines ul-item-line?)]
                (recur remaining
                       (conj! blocks [:ul (mapv parse-inline items)])))

              ;; Ordered list
              (ol-item-line? line)
              (let [[items remaining] (consume-list-items lines ol-item-line?)]
                (recur remaining
                       (conj! blocks [:ol (mapv parse-inline items)])))

              ;; Blockquote
              (blockquote-line? line)
              (let [[quote-lines remaining]
                    (loop [ls lines
                           acc []]
                      (if (empty? ls)
                        [acc ls]
                        (if-let [content (blockquote-line? (first ls))]
                          (recur (rest ls) (conj acc content))
                          [acc ls])))]
                (recur remaining
                       (conj! blocks [:blockquote
                                      (parse (str/join "\n" quote-lines))])))

              ;; Paragraph — collect consecutive non-special lines
              :else
              (let [[para-lines remaining]
                    (loop [ls lines
                           acc []]
                      (if (empty? ls)
                        [acc ls]
                        (let [l (first ls)]
                          (if (or (blank-line? l)
                                  (heading-line? l)
                                  (hr-line? l)
                                  (code-fence? l)
                                  (and (ul-item-line? l) (seq acc))
                                  (and (ol-item-line? l) (seq acc))
                                  (and (blockquote-line? l) (seq acc)))
                            [acc ls]
                            (recur (rest ls) (conj acc l))))))]
                (recur remaining
                       (conj! blocks [:paragraph
                                      (parse-inline (str/join "\n" para-lines))]))))))))))