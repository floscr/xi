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
(def dim-white (str ESC "38;2;131;144;169m"))

(def border-gray (str ESC "90m"))

(def bg-dark   (str ESC "48;5;236m"))

(defn fg
  "Wrap text in a foreground color and reset.
   Color can be a keyword or a raw escape code string.
   `(fg :red \"hello\")` or `(fg red \"hello\")`"
  [color text]
  (let [code (if (keyword? color)
               (case color
                 :dim     dim-white
                 :border  border-gray
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
   Handles basic ASCII correctly. Tabs expand to 4-column stops."
  [text]
  (if (empty? text)
    0
    (let [stripped (strip-ansi text)
          len (count stripped)]
      (loop [i 0 col 0]
        (if (>= i len)
          col
          (if (= (.charAt stripped i) "\t")
            (recur (inc i) (* 4 (inc (quot col 4))))
            (recur (inc i) (inc col))))))))

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

(defn- skip-ansi-seq
  "Return the end index of an ANSI escape sequence starting at i."
  [line i len]
  (if (and (< (inc i) len) (= (.charAt line (inc i)) "["))
    ;; CSI: \033[ ... <final byte 64-126>
    (loop [j (+ i 2)]
      (if (>= j len)
        j
        (let [c (.charCodeAt line j)]
          (if (and (>= c 64) (<= c 126))
            (inc j)
            (recur (inc j))))))
    ;; Other esc: \033 + one char
    (min len (+ i 2))))

(def ^:private REVERSE_ON  (str ESC "7m"))
(def ^:private REVERSE_OFF (str ESC "27m"))

(defn truncate-to-width
  "Truncate an ANSI-formatted string to max visible columns.
   Adds ellipsis if truncated. ANSI sequences are preserved/closed."
  [line max-width]
  (if (or (empty? line) (<= (visible-width line) max-width))
    line
    (let [ellipsis "…"
          target (dec max-width)
          len (count line)
          out (js/Array.)]
      (loop [i 0, vcol 0]
        (cond
          (>= i len)
          (.join out "")

          (= (.charAt line i) "\033")
          (let [end (skip-ansi-seq line i len)]
            (.push out (.substring line i end))
            (recur end vcol))

          (>= vcol target)
          (do (.push out ellipsis)
              (.push out (str "\033[" "0m"))
              (.join out ""))

          ;; Tab — expand to spaces
          (= (.charAt line i) "\t")
          (let [tab-w 4
                next-stop (* tab-w (inc (quot vcol tab-w)))
                spaces (- next-stop vcol)]
            (if (> next-stop target)
              ;; Tab pushes past target — truncate
              (do (dotimes [_ (- target vcol)] (.push out " "))
                  (.push out ellipsis)
                  (.push out (str "\033[" "0m"))
                  (.join out ""))
              (do (dotimes [_ spaces] (.push out " "))
                  (recur (inc i) next-stop))))

          :else
          (do (.push out (.charAt line i))
              (recur (inc i) (inc vcol))))))))

(defn highlight-range
  "Apply reverse-video highlight to visible columns [from, to) in a line.
   ANSI escape sequences are preserved and skipped when counting columns."
  [line from to]
  (if (or (>= from to) (empty? line))
    line
    (let [len (count line)
          out (js/Array.)]
      (loop [i 0, vcol 0, in-hl false]
        (cond
          ;; Done — close highlight if still open
          (>= i len)
          (do (when in-hl (.push out REVERSE_OFF))
              (.join out ""))

          ;; ANSI escape — copy verbatim, don't count as visible
          (= (.charAt line i) "\033")
          (let [end (skip-ansi-seq line i len)]
            (.push out (.substring line i end))
            (recur end vcol in-hl))

          ;; Visible character
          :else
          (do
            ;; Start highlight at `from`
            (when (and (= vcol from) (not in-hl))
              (.push out REVERSE_ON))
            (.push out (.charAt line i))
            (let [nv (inc vcol)]
              (if (and (or in-hl (= vcol from)) (= nv to))
                (do (.push out REVERSE_OFF)
                    (recur (inc i) nv false))
                (recur (inc i) nv (or in-hl (= vcol from)))))))))))

(defn- trailing-sgr
  "Extract active SGR state at end of a string.
   Returns a string of ANSI codes to prepend to the next line,
   or empty string if state is reset/default."
  [s]
  (let [matches (re-seq #"\033\[([^m]*)m" s)]
    (if (empty? matches)
      ""
      (loop [active []
             [[_ params] & more] matches]
        (if (nil? params)
          (if (empty? active) "" (str/join active))
          (if (or (= params "0") (= params ""))
            (recur [] more)
            (recur (conj active (str "\033[" params "m")) more)))))))

(defn- propagate-sgr
  "Ensure each line carries forward the ANSI SGR state from previous lines.
   This makes each line self-contained so viewport-based renderers can
   display any subset of lines correctly."
  [lines]
  (loop [result []
         sgr ""
         [line & more] lines]
    (if (nil? line)
      result
      (let [prefixed (if (seq sgr) (str sgr line) line)
            new-sgr (trailing-sgr prefixed)]
        (recur (conj result prefixed) new-sgr more)))))

(defn- break-long-word
  "Break a single word into chunks of at most max-width visible characters.
   ANSI escape sequences are preserved and don't count toward width."
  [word max-width]
  (let [len (count word)]
    (loop [i 0, vcol 0, start 0, result []]
      (if (>= i len)
        (if (> i start)
          (conj result (.substring word start i))
          result)
        (if (= (.charAt word i) "\033")
          (let [end (skip-ansi-seq word i len)]
            (recur end vcol start result))
          (let [vcol' (inc vcol)]
            (if (>= vcol' max-width)
              (recur (inc i) 0 (inc i) (conj result (.substring word start (inc i))))
              (recur (inc i) vcol' start result))))))))

(defn wrap-text
  "Word-wrap text to fit within max-width columns.
   Returns vector of lines. ANSI SGR state is propagated across line
   boundaries so each output line is independently renderable."
  [text max-width]
  (if (or (empty? text) (<= max-width 0))
    [""]
    (let [lines (str/split-lines text)
          raw (into []
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
                                     ;; Single word longer than width — hard-break it
                                     (recur (into result (break-long-word w max-width)) "" more))
                                   (recur result candidate more))))))))
                    lines))]
      (propagate-sgr raw))))
