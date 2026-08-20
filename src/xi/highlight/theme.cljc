(ns xi.highlight.theme
  "Token type → ANSI color mapping for syntax highlighting, plus the
   mode-varying \"chrome\" colors (block backgrounds, diff backgrounds, default
   foreground) used to paint tool/code blocks.

   Two palettes — a dark one (Nord-inspired, for dark terminals) and a light
   one — are selected by a process-local mode atom. The mode is pushed in from
   the node side via `set-mode!` (see `xi.tui.theme-mode`), so the pure
   highlighting functions stay parameter-free and every caller reflects the
   current terminal theme automatically.")

;; ── Syntax palettes ───────────────────────────────────────────────────────────

;; Nord-inspired palette on a dark background.
(def ^:private dark-colors
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

;; The same hues darkened for readability on a light background.
(def ^:private light-colors
  {:comment       "\033[38;2;124;134;154m"   ;; muted gray-blue
   :string        "\033[38;2;60;110;60m"      ;; green
   :string-char   "\033[38;2;60;110;60m"      ;; green
   :string-symbol "\033[38;2;140;80;140m"     ;; purple
   :number        "\033[38;2;140;80;140m"     ;; purple
   :keyword       "\033[38;2;40;90;150m"      ;; blue
   :keyword-decl  "\033[38;2;40;90;150m"      ;; blue
   :keyword-special "\033[38;2;40;90;150m"    ;; blue
   :name-builtin  "\033[38;2;20;110;120m"     ;; teal
   :name-fn       "\033[38;2;20;110;120m"     ;; teal
   :name-var      "\033[38;2;59;66;82m"       ;; dark gray (default fg)
   :operator      "\033[38;2;40;90;150m"      ;; blue
   :punctuation   "\033[38;2;59;66;82m"       ;; dark gray
   :text          nil})                        ;; no color (inherit)

;; ── Chrome (block / diff backgrounds, default fg) ─────────────────────────────

(def ^:private dark-chrome
  {:block-bg        "\033[48;2;38;44;55m"     ;; navy code/tool block
   :code-default-fg "\033[38;2;216;222;233m"  ;; light gray for untokenized text
   :diff-add-bg     "\033[48;2;35;60;45m"     ;; dark green
   :diff-del-bg     "\033[48;2;65;40;42m"     ;; dark red
   :diff-header-bg  "\033[48;2;50;55;70m"})   ;; file header row

(def ^:private light-chrome
  {:block-bg        "\033[48;2;229;233;240m"  ;; light gray code/tool block
   :code-default-fg "\033[38;2;59;66;82m"     ;; dark gray for untokenized text
   :diff-add-bg     "\033[48;2;209;231;204m"  ;; light green
   :diff-del-bg     "\033[48;2;245;210;210m"  ;; light red
   :diff-header-bg  "\033[48;2;216;222;233m"}) ;; file header row

;; ── Mode selection ────────────────────────────────────────────────────────────

(defonce ^:private mode-state (atom :dark))

(defn set-mode!
  "Set the active theme mode (`:light` or `:dark`). Anything other than
   `:light` is treated as `:dark`."
  [m]
  (reset! mode-state (if (= :light m) :light :dark)))

(defn mode [] @mode-state)

(defn- palette [] (if (= :light @mode-state) light-colors dark-colors))
(defn- chrome [k] (get (if (= :light @mode-state) light-chrome dark-chrome) k))

(defn block-bg        [] (chrome :block-bg))
(defn code-default-fg [] (chrome :code-default-fg))
(defn diff-add-bg     [] (chrome :diff-add-bg))
(defn diff-del-bg     [] (chrome :diff-del-bg))
(defn diff-header-bg  [] (chrome :diff-header-bg))

;; ── Highlighting ──────────────────────────────────────────────────────────────

(def ^:private reset "\033[0m")

(defn token-color
  "Return the ANSI escape code for a token type, or nil for plain text."
  [token-type]
  (get (palette) token-type))

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
