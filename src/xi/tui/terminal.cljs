(ns xi.tui.terminal
  "Raw mode terminal abstraction.
   Handles raw mode, cursor visibility, dimensions, input routing."
  (:require [xi.tui.ansi :as ansi]))

(defn create-terminal
  "Create a terminal state map. Call `start!` to enter raw mode."
  []
  (atom {:started false
         :was-raw false
         :on-input nil
         :on-resize nil}))

(defn columns
  "Get terminal width in columns."
  []
  (or (aget js/process.stdout "columns") 80))

(defn rows
  "Get terminal height in rows."
  []
  (or (aget js/process.stdout "rows") 24))

(defn write!
  "Write a string to stdout."
  [s]
  (js/process.stdout.write s))

(defn writeln!
  "Write a string + newline to stdout."
  [s]
  (js/process.stdout.write (str s "\n")))

(defn move-by!
  "Move cursor up (negative) or down (positive) by n lines."
  [n]
  (cond
    (neg? n) (write! (ansi/cursor-up (- n)))
    (pos? n) (write! (ansi/cursor-down n))))

(defn hide-cursor! []
  (write! ansi/HIDE_CURSOR))

(defn show-cursor! []
  (write! ansi/SHOW_CURSOR))

(defn clear-line! []
  (write! (str "\r" ansi/CLEAR_LINE)))

(defn clear-from-cursor! []
  (write! ansi/CLEAR_FROM_CURSOR))

(defn clear-screen! []
  (write! (str ansi/CLEAR_SCREEN (ansi/cursor-home))))

(defn set-title! [title]
  (write! (str "\033]0;" title "\007")))

(defn sync-start!
  "Begin synchronized output — terminal buffers until sync-end."
  []
  (write! ansi/SYNC_START))

(defn sync-end!
  "End synchronized output — terminal flushes buffer atomically."
  []
  (write! ansi/SYNC_END))

;; ── Input Splitting ───────────────────────────────────────────────────────────

(def ^:private ESC-CODE 27)

(defn- find-csi-end
  "Find end index of a CSI sequence starting at i (ESC [ already matched)."
  [data i len]
  (loop [j (+ i 2)]
    (if (>= j len)
      j
      (let [c (.charCodeAt data j)]
        (if (and (>= c 64) (<= c 126))
          (inc j)
          (recur (inc j)))))))

(defn- split-input
  "Split a raw input string into individual key sequences."
  [data]
  (let [len (count data)]
    (cond
      (<= len 1)
      [data]

      ;; Bracketed paste — return whole thing
      (and (>= len 6)
           (= (.charCodeAt data 0) ESC-CODE)
           (= (.charAt data 1) "[")
           (= (subs data 2 6) "200~"))
      [data]

      :else
      (loop [i 0
             result []]
        (if (>= i len)
          result
          (if (= (.charCodeAt data i) ESC-CODE)
            ;; Escape sequence
            (if (and (< (inc i) len)
                     (= (.charAt data (inc i)) "["))
              ;; CSI sequence
              (let [end (find-csi-end data i len)]
                (recur end (conj result (subs data i end))))
              ;; Other ESC seq (2 chars) or lone ESC
              (let [end (min len (+ i 2))]
                (recur end (conj result (subs data i end)))))
            ;; Regular char
            (recur (inc i) (conj result (subs data i (inc i))))))))))

;; ── Raw Mode ──────────────────────────────────────────────────────────────────

(defn tty?
  "Check if stdin is a TTY."
  []
  (boolean (.-isTTY js/process.stdin)))

(defn start!
  "Enter raw mode, set up input and resize handlers.
   on-input: (fn [data-string])
   on-resize: (fn [])"
  [terminal on-input on-resize]
  (let [stdin js/process.stdin
        stdout js/process.stdout
        was-raw (boolean (.-isRaw stdin))
        ;; Wrap on-input to split batched input
        split-handler (fn [data]
                        (doseq [seq (split-input data)]
                          (on-input seq)))]
    (swap! terminal assoc
           :started true
           :was-raw was-raw
           :on-input split-handler
           :on-resize on-resize)

    ;; Enter raw mode (only for TTY)
    (when (.-setRawMode stdin)
      (.setRawMode stdin true))
    (.setEncoding stdin "utf8")
    (.resume stdin)

    ;; Enable bracketed paste mode
    (write! "\033[?2004h")

    ;; Hide cursor during rendering
    (hide-cursor!)

    ;; Listen for input
    (.on stdin "data" split-handler)

    ;; Keep process alive when stdin is not a TTY
    (when-not (.-isTTY stdin)
      (.ref stdin))

    ;; Listen for resize
    (.on stdout "resize" on-resize)))

(defn stop!
  "Leave raw mode, restore terminal state."
  [terminal]
  (let [{:keys [on-input on-resize was-raw]} @terminal
        stdin js/process.stdin
        stdout js/process.stdout]

    ;; Disable bracketed paste mode
    (write! "\033[?2004l")

    ;; Show cursor
    (show-cursor!)

    ;; Move to start of next line
    (write! "\n")

    ;; Remove listeners
    (when on-input (.removeListener stdin "data" on-input))
    (when on-resize (.removeListener stdout "resize" on-resize))

    ;; Restore raw mode state
    (when (.-setRawMode stdin)
      (.setRawMode stdin was-raw))

    (swap! terminal assoc :started false)))
