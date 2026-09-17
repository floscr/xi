(ns xi.highlight.theme-css
  "Token type → CSS class mapping for browser syntax highlighting.")

(def ^:private class-map
  {:comment        "hl-comment"
   :string         "hl-string"
   :string-char    "hl-string"
   :string-symbol  "hl-symbol"
   :number         "hl-number"
   :keyword        "hl-keyword"
   :keyword-decl   "hl-keyword"
   :keyword-special "hl-keyword"
   :name-builtin   "hl-builtin"
   :name-fn        "hl-fn"
   :name-var       "hl-var"
   :operator       "hl-operator"
   :reader         "hl-reader"
   :punctuation    "hl-punct"
   :text           nil})

(defn token-class
  "Return CSS class for a token type, or nil for plain text."
  [token-type]
  (get class-map token-type))
