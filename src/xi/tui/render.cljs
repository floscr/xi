(ns xi.tui.render
  "Terminal renderer — ANSI color helpers, spinner, and Pi-style tool boxes."
  (:require [clojure.string :as str]))

;; ── ANSI Escape Codes ─────────────────────────────────────────────────────────

(def ^:private ESC "\033[")

(defn fg
  "Apply foreground color."
  [color text]
  (case color
    :dim     (str ESC "90m" text ESC "0m")
    :accent  (str ESC "36m" text ESC "0m")  ;; cyan/teal
    :error   (str ESC "31m" text ESC "0m")  ;; red
    :success (str ESC "32m" text ESC "0m")  ;; green
    :warning (str ESC "33m" text ESC "0m")  ;; yellow
    :bold    (str ESC "1m" text ESC "0m")
    :blue    (str ESC "34m" text ESC "0m")
    :magenta (str ESC "35m" text ESC "0m")
    :reset   (str ESC "0m" text)
    text))

(defn bg
  "Apply background color."
  [color text]
  (case color
    :dark (str ESC "48;5;236m" text ESC "0m")  ;; subtle dark bg
    text))

(defn clear-line []
  (js/process.stdout.write (str ESC "2K\r")))

(defn move-up [n]
  (when (pos? n)
    (js/process.stdout.write (str ESC n "A"))))

(defn set-title [title]
  (js/process.stdout.write (str "\033]0;" title "\007")))

;; ── Tool Box ──────────────────────────────────────────────────────────────────
;; Pi-style tool call rendering with background color, no unicode borders.

(def ^:private TOOL_BG (str ESC "48;5;236m"))  ;; dark gray background
(def ^:private RESET   (str ESC "0m"))

(defn- tool-line
  "Print a line with tool box background."
  [text]
  (let [cols (or (aget (aget js/process "stdout") "columns") 80)
        visible-len (count (str/replace text #"\033\[[^m]*m" ""))
        padding (max 0 (- cols visible-len))]
    (println (str TOOL_BG text (apply str (repeat padding " ")) RESET))))

(defn tool-header
  "Print tool call header. Returns start time for duration."
  [tool-name args-summary]
  (println)
  (tool-line (str (fg :accent (str "$ " tool-name))
                  (when (seq args-summary)
                    (str " " (fg :dim args-summary)))))
  (tool-line "")
  (js/Date.now))

(defn tool-output-line
  "Print tool output with background."
  [text]
  (doseq [line (str/split-lines text)]
    (tool-line line)))

(defn tool-footer
  "Print tool call footer with duration."
  [start-time & [{:keys [is-error]}]]
  (let [elapsed (- (js/Date.now) start-time)
        duration (str (.toFixed (/ elapsed 1000) 1) "s")
        color (if is-error :error :success)]
    (tool-line "")
    (tool-line (fg color (str "Took " duration)))
    (println)))

;; ── Spinner ───────────────────────────────────────────────────────────────────

(def ^:private SPINNER_FRAMES ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defonce ^:private spinner-state (atom {:timer nil :frame 0 :label ""}))

(defn start-spinner [label]
  (when-let [t (:timer @spinner-state)]
    (js/clearInterval t))
  (let [timer (js/setInterval
               (fn []
                 (let [{:keys [frame]} (swap! spinner-state update :frame
                                              #(mod (inc %) (count SPINNER_FRAMES)))
                       ch (nth SPINNER_FRAMES frame)]
                   (js/process.stdout.write
                    (str "\r" (fg :accent ch) " " (fg :dim label)))))
               80)]
    (reset! spinner-state {:timer timer :frame 0 :label label})))

(defn stop-spinner []
  (when-let [t (:timer @spinner-state)]
    (js/clearInterval t)
    (clear-line)
    (reset! spinner-state {:timer nil :frame 0 :label ""})))

;; ── Status Line ───────────────────────────────────────────────────────────────

(defonce ^:private status-state (atom {:text "" :visible false}))

(defn show-status [text]
  (reset! status-state {:text text :visible true})
  (js/process.stdout.write (str "\r" (fg :dim text))))

(defn hide-status []
  (when (:visible @status-state)
    (clear-line)
    (reset! status-state {:text "" :visible false})))
