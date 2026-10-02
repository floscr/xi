(ns xi.highlight.embedded
  "Highlight code embedded in string literals of clj source: the content arg of
   `(spit \"file.clj\" \"…\")` (language from the path's extension) and the
   script arg of an interpreter eval flag, e.g. `(sh \"bb\" \"-e\" \"(…)\")`.

   The string is unescaped, tokenized with the embedded language's grammar and
   the tokens are mapped back onto the original source text, so every token
   value is still an exact slice of what was written (`\\\"` stays `\\\"`) and
   the concatenated values equal the input — consumers colour them as usual."
  (:require [clojure.string :as str]
            [xi.highlight.core :as hl]))

(def ^:private interpreters
  "Interpreter basename → [grammar lang, eval flags taking an inline script]."
  {"bb"      ["clj"        #{"-e" "--eval"}]
   "nbb"     ["clj"        #{"-e" "--eval"}]
   "clj"     ["clj"        #{"-e" "--eval"}]
   "clojure" ["clj"        #{"-e" "--eval"}]
   "node"    ["javascript" #{"-e" "--eval" "-p" "--print"}]
   "bun"     ["javascript" #{"-e" "--eval" "-p" "--print"}]
   "python"  ["python"     #{"-c"}]
   "python3" ["python"     #{"-c"}]
   "ruby"    ["ruby"       #{"-e"}]
   "bash"    ["bash"       #{"-c"}]
   "sh"      ["bash"       #{"-c"}]
   "zsh"     ["bash"       #{"-c"}]})

(def ^:private max-depth
  "How many levels of clj-in-clj-in-clj we keep re-highlighting."
  3)

(def ^:private lookback
  "Significant tokens kept behind the current one — enough for
   `\"node\" \"--input-type=module\" \"-e\" \"…\"`."
  8)

(def ^:private simple-escapes {"\\" "\\", "\"" "\"", "n" "\n", "t" "\t"})

(defn- string-token? [{:keys [type value]}]
  (and (= :string type) (>= (count value) 2)))

(defn- string-content
  "The text between a :string token's quotes, as written (still escaped)."
  [{:keys [value]}]
  (subs value 1 (dec (count value))))

(defn- unescape
  "Escaped string `content` → {:text unescaped, :offsets v} where (nth v i) is
   the index in `content` at which the i-th unescaped char starts; one extra
   trailing entry holds (count content) so a token range [a b) in the unescaped
   text maps to the slice [(v a) (v b)) of the original."
  [content]
  (let [n (count content)]
    (loop [i 0, out (transient []), offs (transient [])]
      (if (>= i n)
        {:text    (apply str (persistent! out))
         :offsets (persistent! (conj! offs n))}
        (let [c (nth content i)
              r (when (and (= c "\\") (< (inc i) n))
                  (get simple-escapes (nth content (inc i))))]
          (recur (+ i (if r 2 1))
                 (conj! out (or r c))
                 (conj! offs i)))))))

(defn- basename [path]
  (last (str/split path #"/")))

(defn- file-ext [path]
  (let [b (basename path)
        i (str/last-index-of b ".")]
    (when (and i (pos? i)) (str/lower-case (subs b (inc i))))))

(defn- symbol-name [{:keys [value]}]
  (basename (str/trim value)))

(defn- embedded-lang
  "Grammar name for the string literal that follows `recent` (the preceding
   significant tokens, oldest first), or nil when it isn't embedded code."
  [recent]
  (let [n    (count recent)
        prev (peek recent)]
    (or
     ;; (spit "path.ext" "<content>")
     (when (and (>= n 2)
                (string-token? prev)
                (= "spit" (symbol-name (nth recent (- n 2)))))
       (file-ext (string-content prev)))
     ;; … "interpreter" … "-e" "<script>"
     (let [args (->> (rseq recent)
                     (take-while string-token?)
                     (map string-content)
                     reverse
                     vec)
           flag (peek args)]
       (some (fn [arg]
               (let [[lang flags] (get interpreters (basename arg))]
                 (when (and lang (contains? flags flag)) lang)))
             (butlast args))))))

(declare tokenize-with)

(defn- embed-string
  "Replace a :string token with quote / nested-code / quote tokens, or nil when
   `lang` has no grammar."
  [get-grammar depth tok lang]
  (when-let [grammar (get-grammar lang)]
    (let [content          (string-content tok)
          {:keys [text offsets]} (unescape content)
          nested           (if (and (= "clj" lang) (< depth max-depth))
                             (tokenize-with get-grammar grammar (inc depth) text)
                             (hl/tokenize grammar text))
          q                {:type :string :value "\""}]
      (-> [q]
          (into (loop [toks nested, a 0, out []]
                  (if-let [{:keys [type value]} (first toks)]
                    (let [b (+ a (count value))]
                      (recur (rest toks) b
                             (conj out {:type  type
                                        :value (subs content (nth offsets a) (nth offsets b))})))
                    out)))
          (conj q)))))

(defn- tokenize-with [get-grammar grammar depth source]
  (loop [toks (hl/tokenize grammar source), recent [], out []]
    (if-let [tok (first toks)]
      (let [embedded (when (string-token? tok)
                       (when-let [lang (embedded-lang recent)]
                         (embed-string get-grammar depth tok lang)))
            sig?     (not (contains? #{:text :comment} (:type tok)))]
        (recur (rest toks)
               (if sig?
                 (let [r (conj recent tok)]
                   (if (> (count r) lookback) (subvec r 1) r))
                 recent)
               (if embedded (into out embedded) (conj out tok))))
      (hl/merge-adjacent out))))

(defn tokenize-clj
  "Tokenize clj `source` like `hl/tokenize` + `hl/merge-adjacent`, but with
   code embedded in string literals highlighted in its own language.
   `get-grammar` resolves a language name to a grammar (or nil); returns nil
   when there is no clj grammar."
  [get-grammar source]
  (when-let [grammar (get-grammar "clj")]
    (tokenize-with get-grammar grammar 0 source)))
