(ns xi.tui.grid
  "Cell-level grid for TUI rendering.
   Parses ANSI-formatted line strings into a 2D grid of cells,
   then diffs against the previous grid to emit minimal terminal writes.

   Cell: a JS array [char-string, style-string]
     char-string: single visible character (or \" \")
     style-string: accumulated ANSI SGR prefix (e.g. \"\\033[31m\\033[1m\") or \"\"

   Grid: {:width W :height H :cells js/Array-of-rows}
     Each row is a js/Array of cells, length = width."
  (:require [xi.tui.terminal :as term]))

;; ── Cell Constructors ─────────────────────────────────────────────────────────

(defn- make-cell
  "Create a cell: [char, style]."
  [ch style]
  (let [a (js/Array. 2)]
    (aset a 0 ch)
    (aset a 1 style)
    a))

(def ^:private SPACE_CELL (make-cell " " ""))

(defn- cell-eq?
  "Fast cell equality — compare char and style strings."
  [^js a ^js b]
  (and (= (aget a 0) (aget b 0))
       (= (aget a 1) (aget b 1))))

;; ── Grid ──────────────────────────────────────────────────────────────────────

(defn make-grid
  "Create an empty WxH grid filled with space cells."
  [width height]
  (let [rows (js/Array. height)]
    (dotimes [r height]
      (let [row (js/Array. width)]
        (dotimes [c width]
          (aset row c (make-cell " " "")))
        (aset rows r row)))
    {:width width :height height :cells rows}))

;; ── ANSI Line Parsing ─────────────────────────────────────────────────────────

(defn- skip-csi
  "Given string s starting at index i (after ESC[), find the end of the CSI sequence.
   Returns the index after the final byte (64-126)."
  [^js s i len]
  (loop [j i]
    (if (>= j len)
      j
      (let [c (.charCodeAt s j)]
        (if (and (>= c 64) (<= c 126))
          (inc j)
          (recur (inc j)))))))

(defn line->row
  "Parse an ANSI-formatted line string into a js/Array of cells.
   The row is padded/truncated to exactly `width` cells."
  [^js line width]
  (let [row (js/Array. width)
        len (.-length line)]
    ;; Fill with spaces first
    (dotimes [c width]
      (aset row c (make-cell " " "")))
    (loop [i 0      ;; index into line string
           col 0    ;; current visible column
           sgr ""]  ;; accumulated SGR state
      (if (or (>= i len) (>= col width))
        row
        (let [ch (.charAt line i)]
          (if (= ch "\033")
            ;; Start of escape sequence
            (if (and (< (inc i) len) (= (.charAt line (inc i)) "["))
              ;; CSI sequence: \033[ params final-byte
              (let [seq-end (skip-csi line (+ i 2) len)
                    seq-str (.substring line i seq-end)
                    ;; Check if this is an SGR sequence (ends with 'm')
                    final (.charAt line (dec seq-end))]
                (if (= final "m")
                  ;; SGR — extract params to update state
                  (let [params (.substring line (+ i 2) (dec seq-end))
                        new-sgr (if (or (= params "0") (= params ""))
                                  ""  ;; reset
                                  (str sgr seq-str))]
                    (recur seq-end col new-sgr))
                  ;; Non-SGR CSI — skip it, don't change style
                  (recur seq-end col sgr)))
              ;; Other ESC sequence (2 chars) — skip
              (recur (min len (+ i 2)) col sgr))
            ;; Visible character
            (do
              (aset row col (make-cell ch sgr))
              (recur (inc i) (inc col) sgr))))))))

(defn frame->grid
  "Convert a vector of ANSI line strings into a cell grid."
  [lines width height]
  (let [rows (js/Array. height)
        n (count lines)]
    (dotimes [r height]
      (if (< r n)
        (aset rows r (line->row (nth lines r "") width))
        ;; Pad missing rows with spaces
        (let [row (js/Array. width)]
          (dotimes [c width]
            (aset row c (make-cell " " "")))
          (aset rows r row))))
    {:width width :height height :cells rows}))

;; ── Diffing & Output ──────────────────────────────────────────────────────────

(defn emit-diff!
  "Compare new-grid against old-grid, write only changed cells to terminal.
   Uses cursor positioning to skip unchanged regions.
   Assumes sync-start! has been called."
  [new-grid old-grid]
  (let [width (:width new-grid)
        height (:height new-grid)
        new-cells (:cells new-grid)
        old-cells (:cells old-grid)
        old-height (:height old-grid)
        old-width (:width old-grid)
        buf (js/Array.)]
    (dotimes [r height]
      (let [new-row (aget new-cells r)
            old-row (when (< r old-height) (aget old-cells r))
            ;; Find runs of changed cells on this row
            ;; Emit: cursor-to + style + chars for each contiguous run
            ]
        (loop [c 0
               run-start -1
               run-style nil
               run-chars nil]
          (if (>= c width)
            ;; Flush final run
            (when (>= run-start 0)
              (.push buf (str "\033[" (inc r) ";" (inc run-start) "H"))
              (when (seq run-style) (.push buf run-style))
              (.push buf (.join run-chars ""))
              (.push buf "\033[0m"))
            (let [new-cell (aget new-row c)
                  old-cell (when (and old-row (< c old-width)) (aget old-row c))
                  changed? (or (nil? old-cell) (not (cell-eq? new-cell old-cell)))
                  new-ch (aget new-cell 0)
                  new-style (aget new-cell 1)]
              (if changed?
                ;; Cell changed — extend or start a run
                (if (and (>= run-start 0) (= new-style run-style))
                  ;; Same style — extend run
                  (do (.push run-chars new-ch)
                      (recur (inc c) run-start run-style run-chars))
                  ;; Different style or new run — flush previous, start new
                  (do
                    (when (>= run-start 0)
                      (.push buf (str "\033[" (inc r) ";" (inc run-start) "H"))
                      (when (seq run-style) (.push buf run-style))
                      (.push buf (.join run-chars ""))
                      (.push buf "\033[0m"))
                    (let [chars (js/Array.)]
                      (.push chars new-ch)
                      (recur (inc c) c new-style chars))))
                ;; Cell unchanged — flush any pending run, continue
                (do
                  (when (>= run-start 0)
                    (.push buf (str "\033[" (inc r) ";" (inc run-start) "H"))
                    (when (seq run-style) (.push buf run-style))
                    (.push buf (.join run-chars ""))
                    (.push buf "\033[0m"))
                  (recur (inc c) -1 nil nil))))))))
    ;; Write all at once
    (when (pos? (.-length buf))
      (term/write! (.join buf "")))))
