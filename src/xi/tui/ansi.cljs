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

;; ── Character Width ───────────────────────────────────────────────────────────

(def VS16 0xFE0F)

(defn zero-width?
  "True if code point occupies no terminal column (combining marks,
   variation selectors, zero-width joiners/spaces)."
  [cp]
  (or (<= 0x0300 cp 0x036F)   ;; combining diacritical marks
      (<= 0x1AB0 cp 0x1AFF)   ;; combining diacritical marks extended
      (<= 0x1DC0 cp 0x1DFF)   ;; combining diacritical marks supplement
      (<= 0x20D0 cp 0x20FF)   ;; combining marks for symbols
      (<= 0xFE00 cp 0xFE0F)   ;; variation selectors
      (<= 0xFE20 cp 0xFE2F)   ;; combining half marks
      (= cp 0x200B)           ;; zero-width space
      (= cp 0x200C)           ;; zero-width non-joiner
      (= cp 0x200D)           ;; zero-width joiner
      (= cp 0xFEFF)))         ;; zero-width no-break space

(defn- emoji-presentation?
  "True if code point renders as a wide emoji by default."
  [cp]
  (or (<= 0x231A cp 0x231B)   ;; watch, hourglass
      (<= 0x23E9 cp 0x23EC)   ;; fast-forward etc.
      (= cp 0x23F0)
      (= cp 0x23F3)
      (<= 0x25FD cp 0x25FE)
      (<= 0x2614 cp 0x2615)
      (<= 0x2648 cp 0x2653)
      (= cp 0x267F)
      (= cp 0x2693)
      (= cp 0x26A1)
      (<= 0x26AA cp 0x26AB)
      (<= 0x26BD cp 0x26BE)
      (<= 0x26C4 cp 0x26C5)
      (= cp 0x26CE)
      (= cp 0x26D4)
      (= cp 0x26EA)
      (<= 0x26F2 cp 0x26F3)
      (= cp 0x26F5)
      (= cp 0x26FA)
      (= cp 0x26FD)
      (= cp 0x2705)
      (<= 0x270A cp 0x270B)
      (= cp 0x2728)
      (= cp 0x274C)
      (= cp 0x274E)
      (<= 0x2753 cp 0x2755)
      (= cp 0x2757)
      (<= 0x2795 cp 0x2797)
      (= cp 0x27B0)
      (= cp 0x27BF)
      (<= 0x2B1B cp 0x2B1C)
      (= cp 0x2B50)
      (= cp 0x2B55)))

(defn- wide?
  "True if code point occupies two terminal columns (East Asian Wide/Fullwidth
   ranges plus default-emoji-presentation symbols)."
  [cp]
  (or (<= 0x1100 cp 0x115F)    ;; hangul jamo
      (<= 0x2E80 cp 0x303E)    ;; CJK radicals, kana punctuation
      (<= 0x3041 cp 0x33FF)    ;; hiragana, katakana, CJK symbols
      (<= 0x3400 cp 0x4DBF)    ;; CJK ext A
      (<= 0x4E00 cp 0x9FFF)    ;; CJK unified
      (<= 0xA000 cp 0xA4CF)    ;; yi
      (<= 0xAC00 cp 0xD7A3)    ;; hangul syllables
      (<= 0xF900 cp 0xFAFF)    ;; CJK compat ideographs
      (<= 0xFE30 cp 0xFE4F)    ;; CJK compat forms
      (<= 0xFF00 cp 0xFF60)    ;; fullwidth forms
      (<= 0xFFE0 cp 0xFFE6)    ;; fullwidth signs
      (<= 0x1F300 cp 0x1F5FF)  ;; misc symbols & pictographs
      (<= 0x1F600 cp 0x1F64F)  ;; emoticons
      (<= 0x1F680 cp 0x1F6FF)  ;; transport
      (<= 0x1F900 cp 0x1F9FF)  ;; supplemental symbols
      (<= 0x1FA70 cp 0x1FAFF)  ;; symbols ext A
      (<= 0x20000 cp 0x2FFFD)  ;; CJK ext B+
      (<= 0x30000 cp 0x3FFFD)
      (emoji-presentation? cp)))

(defn char-width
  "Terminal column width of a code point: 0, 1 or 2."
  [cp]
  (cond
    ;; Fast path: everything below U+0300 (ASCII/Latin-1) is narrow.
    (< cp 0x0300) 1
    (zero-width? cp) 0
    (wide? cp) 2
    :else 1))

(defn visible-width
  "Calculate visible terminal width of a string (stripping ANSI codes).
   Code-point aware: wide emoji/CJK count as 2 columns, combining marks
   and variation selectors as 0. A VS16 after a narrow char upgrades it
   to emoji presentation (width 2). Tabs expand to 4-column stops."
  [text]
  (if (empty? text)
    0
    (let [stripped (strip-ansi text)
          len (count stripped)]
      (loop [i 0 col 0 prev-w 0]
        (if (>= i len)
          col
          (let [cu (.charCodeAt stripped i)]
            (cond
              ;; Fast path: plain narrow char — no code-point machinery needed
              (and (< cu 0x0300) (not= cu 9))
              (recur (inc i) (inc col) 1)

              (= cu 9)
              (recur (inc i) (* 4 (inc (quot col 4))) 0)

              :else
              (let [cp (.codePointAt stripped i)
                    n (if (> cp 0xFFFF) 2 1)]
                (cond
                  (= cp VS16)
                  (if (= prev-w 1)
                    (recur (+ i n) (inc col) 2)
                    (recur (+ i n) col prev-w))

                  (zero-width? cp)
                  (recur (+ i n) col prev-w)

                  :else
                  (let [w (char-width cp)]
                    (recur (+ i n) (+ col w) w)))))))))))

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
      (loop [i 0, vcol 0, prev-w 0]
        (cond
          (>= i len)
          (.join out "")

          (= (.charAt line i) "\033")
          (let [end (skip-ansi-seq line i len)]
            (.push out (.substring line i end))
            (recur end vcol prev-w))

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
                  (recur (inc i) next-stop 0))))

          :else
          (let [cu (.charCodeAt line i)]
            (if (< cu 0x0300)
              ;; Fast path: plain narrow char
              (do (.push out (.charAt line i))
                  (recur (inc i) (inc vcol) 1))
              (let [cp (.codePointAt line i)
                    n (if (> cp 0xFFFF) 2 1)]
                (cond
                  ;; VS16 — upgrades a preceding narrow char to wide
                  (= cp VS16)
                  (if (and (= prev-w 1) (< vcol target))
                    (do (.push out (.substring line i (+ i n)))
                        (recur (+ i n) (inc vcol) 2))
                    ;; would overflow or nothing to upgrade — drop it
                    (recur (+ i n) vcol prev-w))

                  (zero-width? cp)
                  (do (.push out (.substring line i (+ i n)))
                      (recur (+ i n) vcol prev-w))

                  :else
                  (let [w (char-width cp)]
                    (if (> (+ vcol w) target)
                      ;; wide char won't fit before the ellipsis
                      (do (.push out ellipsis)
                          (.push out (str "\033[" "0m"))
                          (.join out ""))
                      (do (.push out (.substring line i (+ i n)))
                          (recur (+ i n) (+ vcol w) w)))))))))))))

(defn highlight-range
  "Apply reverse-video highlight to visible columns [from, to) in a line.
   ANSI escape sequences are preserved and skipped when counting columns."
  [line from to]
  (if (or (>= from to) (empty? line))
    line
    (let [len (count line)
          out (js/Array.)]
      (loop [i 0, vcol 0, in-hl false, prev-w 0]
        (cond
          ;; Done — close highlight if still open
          (>= i len)
          (do (when in-hl (.push out REVERSE_OFF))
              (.join out ""))

          ;; ANSI escape — copy verbatim, don't count as visible
          (= (.charAt line i) "\033")
          (let [end (skip-ansi-seq line i len)]
            (.push out (.substring line i end))
            (recur end vcol in-hl prev-w))

          ;; Visible character
          :else
          (let [cu (.charCodeAt line i)
                fast? (< cu 0x0300)
                cp (if fast? cu (.codePointAt line i))
                n (if (> cp 0xFFFF) 2 1)
                w (cond
                    fast? 1
                    (= cp VS16) (if (= prev-w 1) 1 0)
                    :else (char-width cp))
                next-prev (cond
                            fast? 1
                            (= cp VS16) (if (= prev-w 1) 2 prev-w)
                            (zero? w) prev-w
                            :else w)
                starting? (and (>= vcol from) (< vcol to) (not in-hl) (pos? w))]
            (when starting?
              (.push out REVERSE_ON))
            (.push out (.substring line i (+ i n)))
            (let [nv (+ vcol w)
                  hl (or in-hl starting?)]
              (if (and hl (>= nv to))
                (do (.push out REVERSE_OFF)
                    (recur (+ i n) nv false next-prev))
                (recur (+ i n) nv hl next-prev)))))))))

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
          (let [cu (.charCodeAt word i)
                fast? (< cu 0x0300)
                cp (if fast? cu (.codePointAt word i))
                n (if (> cp 0xFFFF) 2 1)
                w (if fast? 1 (char-width cp))
                vcol' (+ vcol w)]
            (cond
              ;; Wide char would overflow this chunk — break before it
              (and (> vcol' max-width) (> i start))
              (recur i 0 i (conj result (.substring word start i)))

              (>= vcol' max-width)
              (recur (+ i n) 0 (+ i n) (conj result (.substring word start (+ i n))))

              :else
              (recur (+ i n) vcol' start result))))))))

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
