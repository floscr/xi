(ns xi.markdown.code-kind
  "Classifies inline code by the shape of its text alone (it carries no
   language), so xi.markdown.hiccup can tint each kind and dim the
   directory / namespace prefix that precedes the part that matters."
  (:require [clojure.string :as str]))

(def ^:private literal-re
  #"[\"'`].*[\"'`]|\[.*\]|[-+/*]?\d[\d.,_]*(?:%|px|ms|s|em|rem|deg)?|[^\w\s]{1,3}|true|false|nil|null|undefined")

(def ^:private file-re
  #"(?:^|/)[\w.-]*\.[A-Za-z]{1,5}(?::\d+(?:-\d+)?)?$|/$")

(def ^:private sigil-re
  #"[:@$#]\S+|--?[A-Za-z][\w-]*")

(defn classify
  "One of :literal (quoted, number, operator, [...], true/nil/…), :path (a
   file extension, optionally :line, or a trailing /), :sigil (:kw @x $x #x
   --flag) or :ident. Ambiguous text (`a/b`) is an :ident, the plainest."
  [s]
  (cond
    (re-matches literal-re s) :literal
    (and (not (re-find #"\s" s))
         (not (re-matches #"[:@$#].*" s))
         (re-find file-re s)) :path
    (re-matches sigil-re s) :sigil
    :else :ident))

(defn split-prefix
  "[prefix leaf]: everything through the last / (else the last . of a dotted
   name that isn't a file extension, else a leading sigil) is the prefix.
   Text with whitespace isn't split."
  [s]
  (if (re-find #"\s" s)
    ["" s]
    (let [t (str/replace s #"/$" "")
          i (or (str/last-index-of t "/")
                (when-not (re-find #"\.[A-Za-z]{1,5}(?::\d+)?$" t)
                  (let [j (str/last-index-of t ".")]
                    (when (and j (re-find #"^[A-Za-z_]" (subs t (inc j)))) j))))]
      (cond
        (and i (pos? i) (< (inc i) (count s))) [(subs s 0 (inc i)) (subs s (inc i))]
        (re-matches #"[:@$#].+" s) [(subs s 0 1) (subs s 1)]
        :else ["" s]))))
