(ns xi.tui.render
  "Minimal terminal renderer — ANSI color helpers and screen control."
  (:require [clojure.string :as str]))

;; ── ANSI Escape Codes ─────────────────────────────────────────────────────────

(def ^:private ESC "\033[")

(defn fg
  "Apply foreground color. Named colors: dim, accent, error, bold, reset."
  [color text]
  (case color
    :dim     (str ESC "90m" text ESC "0m")
    :accent  (str ESC "36m" text ESC "0m")  ;; cyan
    :error   (str ESC "31m" text ESC "0m")  ;; red
    :success (str ESC "32m" text ESC "0m")  ;; green
    :warning (str ESC "33m" text ESC "0m")  ;; yellow
    :bold    (str ESC "1m" text ESC "0m")
    :reset   (str ESC "0m" text)
    text))

(defn clear-line []
  (js/process.stdout.write (str ESC "2K\r")))

(defn move-up [n]
  (when (pos? n)
    (js/process.stdout.write (str ESC n "A"))))

(defn set-title [title]
  (js/process.stdout.write (str "\033]0;" title "\007")))

;; ── Status Line ───────────────────────────────────────────────────────────────

(defonce ^:private status-state (atom {:text "" :visible false}))

(defn show-status [text]
  (reset! status-state {:text text :visible true})
  (js/process.stdout.write (str "\r" (fg :dim text))))

(defn hide-status []
  (when (:visible @status-state)
    (clear-line)
    (reset! status-state {:text "" :visible false})))

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
