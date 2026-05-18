(ns xi.highlight.theme
  "Token type → ANSI color mapping for syntax highlighting.
   Colors are true-color (24-bit) escape codes for a dark background.")

;; Nord-inspired palette on dark background
(def ^:private colors
  {:comment       "\033[38;2;106;115;141m"   ;; muted gray-blue
   :string        "\033[38;2;163;190;140m"   ;; green
   :string-char   "\033[38;2;163;190;140m"   ;; green
   :string-symbol "\033[38;2;180;142;173m"   ;; purple
   :number        "\033[38;2;180;142;173m"   ;; purple
   :keyword       "\033[38;2;129;161;193m"   ;; blue
   :keyword-decl  "\033[38;2;129;161;193m"   ;; blue
   :keyword-special "\033[38;2;129;161;193m" ;; blue
   :name-builtin  "\033[38;2;136;192;208m"   ;; teal
   :name-fn       "\033[38;2;136;192;208m"   ;; teal
   :name-var      "\033[38;2;216;222;233m"   ;; light gray (default fg)
   :operator      "\033[38;2;129;161;193m"   ;; blue
   :punctuation   "\033[38;2;216;222;233m"   ;; light gray
   :text          nil})                       ;; no color (inherit)

(def ^:private reset "\033[0m")

(defn token-color
  "Return the ANSI escape code for a token type, or nil for plain text."
  [token-type]
  (get colors token-type))

(defn colorize-token
  "Wrap a token's value in its ANSI color. Returns plain value for :text tokens."
  [{:keys [type value]}]
  (if-let [color (token-color type)]
    (str color value reset)
    value))

(defn colorize
  "Turn a seq of tokens into a single ANSI-colored string."
  [tokens]
  (apply str (map colorize-token tokens)))
