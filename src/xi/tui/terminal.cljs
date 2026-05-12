(ns xi.tui.terminal
  "Raw mode terminal abstraction.
   Handles raw mode, cursor visibility, dimensions, input routing.
   Provides stdout/stderr interception to capture stray external writes."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]))

;; ── Stdout Interception ───────────────────────────────────────────────────────
;;
;; When the TUI is running, external code (shadow-cljs hot-reload, console.log
;; from libraries) can write to stdout/stderr and corrupt the display.
;; We intercept these writes and route them to a callback (typically a buffer).
;; TUI's own writes go through `write!` which sets `tui-writing?` so the
;; interceptor lets them pass through to the real stdout.

(def ^:private tui-writing? (atom false))
(def ^:private restore-fn (atom nil))

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
  "Write a string to stdout (TUI-safe — marks write as internal)."
  [s]
  (reset! tui-writing? true)
  (js/process.stdout.write s)
  (reset! tui-writing? false))

(defn writeln!
  "Write a string + newline to stdout."
  [s]
  (write! (str s "\n")))

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

(defn cursor-to!
  "Move cursor to absolute position (1-based row and col)."
  [row col]
  (write! (str "\033[" row ";" col "H")))

(defn enter-alt-screen!
  "Enter alternate screen buffer."
  []
  (write! "\033[?1049h"))

(defn exit-alt-screen!
  "Exit alternate screen buffer (restores previous screen)."
  []
  (write! "\033[?1049l"))

(defn enable-mouse-tracking!
  "Enable button-event mouse tracking with SGR encoding.
   Tracks press, release, motion-while-pressed, and scroll wheel.
   Text selection is handled by the TUI (not the terminal)."
  []
  (write! "\033[?1002h")
  (write! "\033[?1006h"))

(defn disable-mouse-tracking!
  "Disable button-event mouse tracking."
  []
  (write! "\033[?1006l")
  (write! "\033[?1002l"))

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
        ;; Paste buffering — assemble multi-chunk bracketed pastes
        paste-buf (atom nil)
        paste-start (str (char ESC-CODE) "[200~")
        paste-end (str (char ESC-CODE) "[201~")

        flush-paste! (fn [complete-data]
                       (reset! paste-buf nil)
                       (on-input complete-data))

        ;; Wrap on-input to split batched input and buffer pastes
        split-handler (fn [data]
                        (if-let [buf @paste-buf]
                          ;; Currently buffering a paste
                          (let [combined (str buf data)]
                            (if-let [end-idx (str/index-of combined paste-end)]
                              ;; Found paste-end — flush complete paste, process remainder
                              (let [paste-data (subs combined 0 (+ end-idx (count paste-end)))
                                    remainder (subs combined (+ end-idx (count paste-end)))]
                                (flush-paste! paste-data)
                                (when (seq remainder)
                                  (doseq [s (split-input remainder)]
                                    (on-input s))))
                              ;; No end marker yet — keep buffering
                              (reset! paste-buf combined)))
                          ;; Not buffering — check if this chunk starts a paste
                          (if (str/starts-with? data paste-start)
                            (if (str/includes? data paste-end)
                              ;; Complete paste in one chunk
                              (on-input data)
                              ;; Incomplete paste — start buffering
                              (reset! paste-buf data))
                            ;; Regular input — split as before
                            (doseq [s (split-input data)]
                              (on-input s)))))]
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

    ;; Enter alternate screen buffer
    (enter-alt-screen!)

    ;; Enable bracketed paste mode
    (write! "\033[?2004h")

    ;; Enable kitty keyboard protocol (flag 1: disambiguate escape codes)
    ;; Unmodified keys keep legacy encoding (Enter→\r, Tab→\t, etc.)
    ;; Modified keys (Shift+Enter, etc.) get CSI u sequences
    (write! "\033[>1u")

    ;; Hide cursor during rendering
    (hide-cursor!)

    ;; Enable mouse tracking (for scroll wheel)
    (enable-mouse-tracking!)

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

    ;; Disable mouse tracking
    (disable-mouse-tracking!)

    ;; Disable kitty keyboard protocol
    (write! "\033[<u")

    ;; Disable bracketed paste mode
    (write! "\033[?2004l")

    ;; Show cursor
    (show-cursor!)

    ;; Exit alternate screen buffer (restores main screen)
    (exit-alt-screen!)

    ;; Remove listeners
    (when on-input (.removeListener stdin "data" on-input))
    (when on-resize (.removeListener stdout "resize" on-resize))

    ;; Restore raw mode state
    (when (.-setRawMode stdin)
      (.setRawMode stdin was-raw))

    (swap! terminal assoc :started false)))

(defn suspend!
  "Suspend terminal for fullscreen subprocess.
   Exits alt screen, raw mode, removes listeners so child process can own stdio."
  [terminal]
  (disable-mouse-tracking!)
  (write! "\033[<u")           ;; disable kitty keyboard protocol
  (write! "\033[?2004l")       ;; disable bracketed paste
  (show-cursor!)
  (exit-alt-screen!)
  (let [{:keys [on-input on-resize]} @terminal
        stdin js/process.stdin
        stdout js/process.stdout]
    (when on-input
      (.removeListener stdin "data" on-input))
    (when on-resize
      (.removeListener stdout "resize" on-resize))
    (when (.-setRawMode stdin)
      (.setRawMode stdin false))
    (.pause stdin)))

(defn resume!
  "Resume terminal after fullscreen subprocess."
  [terminal]
  (let [{:keys [on-input on-resize]} @terminal
        stdin js/process.stdin
        stdout js/process.stdout]
    (when (.-setRawMode stdin)
      (.setRawMode stdin true))
    (.setEncoding stdin "utf8")
    (.resume stdin)
    (enter-alt-screen!)
    (write! "\033[?2004h")       ;; enable bracketed paste
    (write! "\033[>1u")          ;; enable kitty keyboard protocol
    (hide-cursor!)
    (enable-mouse-tracking!)
    (when on-input
      (.on stdin "data" on-input))
    (when on-resize
      (.on stdout "resize" on-resize))))

;; ── Stdout/Stderr Interception ───────────────────────────────────────────────────
;;
;; Bun's console.log/warn/error bypass process.stdout.write entirely (native I/O),
;; so we must intercept both the console methods AND process.stdout/stderr.write.

(defn- stringify-args
  "Convert console-style arguments to a single string."
  [args]
  (.join (to-array (map str args)) " "))

(defn intercept-stdout!
  "Intercept external stdout/stderr writes and console.log/warn/error.
   on-capture: (fn [stream-keyword text]) where stream is :stdout or :stderr.
   TUI writes (via write!/writeln!) pass through unaffected.
   Call restore-stdout! to undo."
  [on-capture]
  (when-not @restore-fn
    (let [stdout-orig (.-write js/process.stdout)
          stderr-orig (.-write js/process.stderr)
          log-orig   (.-log js/console)
          warn-orig  (.-warn js/console)
          error-orig (.-error js/console)]

      ;; Intercept process.stdout.write (for CLJS println, direct writes)
      (set! (.-write js/process.stdout)
            (fn [& args]
              (if @tui-writing?
                (.apply stdout-orig js/process.stdout (to-array args))
                (do (on-capture :stdout (str (first args)))
                    true))))

      ;; Intercept process.stderr.write
      (set! (.-write js/process.stderr)
            (fn [& args]
              (on-capture :stderr (str (first args)))
              true))

      ;; Intercept console.log/warn/error (Bun bypasses process.stdout for these)
      (set! (.-log js/console)
            (fn [& args]
              (on-capture :stdout (stringify-args args))))

      (set! (.-warn js/console)
            (fn [& args]
              (on-capture :stderr (stringify-args args))))

      (set! (.-error js/console)
            (fn [& args]
              (on-capture :stderr (stringify-args args))))

      (reset! restore-fn
              (fn []
                (set! (.-write js/process.stdout) stdout-orig)
                (set! (.-write js/process.stderr) stderr-orig)
                (set! (.-log js/console) log-orig)
                (set! (.-warn js/console) warn-orig)
                (set! (.-error js/console) error-orig))))))

(defn restore-stdout!
  "Restore original stdout/stderr write functions and console methods."
  []
  (when-let [f @restore-fn]
    (f)
    (reset! restore-fn nil)))
