(ns xi.tui.ansi
  "Low-level ANSI escape code helpers — colors, cursor, visible-width."
  (:require [clojure.string :as str]))

;; ── Escape Codes ──────────────────────────────────────────────────────────────

(def ^:private ^:const ESC "\033[")

;; ── Colors ────────────────────────────────────────────────────────────────────

(def reset     (str ESC "0m"))
(def bold      (str ESC "1m"))
(def dim       (str ESC "2m"))
(def black     (str ESC "30m"))
(def red       (str ESC "31m"))
(def green     (str ESC "32m"))
(def yellow    (str ESC "33m"))
(def blue      (str ESC "34m"))
(def magenta   (str ESC "35m"))
(def cyan      (str ESC "36m"))
(def white     (str ESC "37m"))
(def default   (str ESC "39m"))
(def dim-white (str ESC "90m"))

(def bg-dark   (str ESC "48;5;236m"))

(defn fg
  "Wrap text in a foreground color and reset.
   Color can be a keyword or a raw escape code string.
   `(fg :red \"hello\")` or `(fg red \"hello\")`"
  [color text]
  (let [code (if (keyword? color)
               (case color
                 :dim     dim-white
                 :accent  cyan
                 :error   red
                 :success green
                 :warning yellow
                 :bold    bold
                 :red     red
                 :green   green
                 :yellow  yellow
                 :blue    blue
                 :magenta magenta
                 :cyan    cyan
                 :white   white
                 :reset   reset
                 nil)
               color)]
    (if code
      (str code text reset)
      text)))

(defn bg
  "Wrap text in a background color and reset."
  [color text]
  (let [code (if (keyword? color)
               (case color
                 :dark bg-dark
                 :tool bg-dark
                 nil)
               color)]
    (if code
      (str code text reset)
      text)))

;; ── Cursor Control ────────────────────────────────────────────────────────────

(defn cursor-up [n]
  (when (pos? n) (str ESC n "A")))

(defn cursor-down [n]
  (when (pos? n) (str ESC n "B")))

(defn cursor-to-col [col]
  (str ESC col "G"))

(defn cursor-home []
  (str ESC "H"))

(defn cursor-to
  "Move cursor to absolute position (1-based row, column 1)."
  [row]
  (str ESC row ";1H"))

(def HIDE_CURSOR (str ESC "?25l"))
(def SHOW_CURSOR (str ESC "?25h"))
(def CLEAR_LINE (str ESC "2K"))
(def CLEAR_FROM_CURSOR (str ESC "0J"))
(def CLEAR_SCREEN (str ESC "2J"))

;; Synchronized output — CSI ?2026h/l
(def SYNC_START (str ESC "?2026h"))
(def SYNC_END (str ESC "?2026l"))

;; ── Visible Width ─────────────────────────────────────────────────────────────

(def ^:private ansi-regex #"\033\[[^m]*m|\033\][^\007]*\007|\033_[^\007]*\007")

(defn strip-ansi
  "Strip ANSI escape sequences from text."
  [text]
  (str/replace text ansi-regex ""))

(defn visible-width
  "Calculate visible terminal width of a string (stripping ANSI codes).
   Handles basic ASCII correctly. CJK/emoji widths are approximated."
  [text]
  (if (empty? text)
    0
    (count (strip-ansi text))))

;; ── Line Utilities ────────────────────────────────────────────────────────────

(defn pad-to-width
  "Pad a line with spaces to fill terminal width."
  [line width]
  (let [vis (visible-width line)
        padding (max 0 (- width vis))]
    (str line (apply str (repeat padding " ")))))

(defn apply-bg-to-line
  "Apply background color to a full-width line.
   bg-code is the ANSI escape sequence to set the background (e.g. \"\\033[48;5;236m\").
   Handles internal resets by re-applying the bg after each \\033[0m."
  [line width bg-code]
  (let [padded (pad-to-width line width)]
    (str bg-code
         (str/replace padded (str ESC "0m") (str ESC "0m" bg-code))
         ESC "0m")))

(defn wrap-text
  "Word-wrap text to fit within max-width columns.
   Returns vector of lines."
  [text max-width]
  (if (or (empty? text) (<= max-width 0))
    [""]
    (let [lines (str/split-lines text)]
      (into []
            (mapcat
             (fn [line]
               (if (<= (visible-width line) max-width)
                 [line]
                 ;; Simple word-wrap: split on spaces
                 (let [words (str/split line #" ")]
                   (loop [result []
                          current ""
                          [w & more] words]
                     (if-not w
                       (if (seq current)
                         (conj result current)
                         result)
                       (let [candidate (if (seq current)
                                         (str current " " w)
                                         w)]
                         (if (> (visible-width candidate) max-width)
                           (if (seq current)
                             (recur (conj result current) w more)
                             ;; Single word longer than width — force it on its own line
                             (recur (conj result w) "" more))
                           (recur result candidate more))))))))
            lines)))))
