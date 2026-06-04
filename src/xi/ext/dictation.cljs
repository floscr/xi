(ns xi.ext.dictation
  "Voice dictation extension.
   Alt+R toggles recording — first press starts live streaming transcription,
   second press pauses. Shows 🎤 badge while recording.
   Uses whisper-stream for real-time speech-to-text.
   Process is kept alive after first use (SIGSTOP/SIGCONT) for instant resume."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]))

(def ^:private models-dir
  (str (aget js/process.env "HOME") "/.local/share/whisper-models"))

(def ^:private default-model "base.en")

(defn- model-path []
  (str models-dir "/ggml-" default-model ".bin"))

;; Process lifecycle: nil → {:proc} (spawned once, lives until session end)
;; Active state is separate — controls whether output is inserted
(defonce ^:private process (atom nil))
(defonce ^:private active? (atom false))
;; Tracks the last whisper chunk so we can detect rewrites vs appends
(def ^:private prev-text (atom ""))
;; Tracks how many chars we've inserted for the current chunk (for delete-on-rewrite)
(def ^:private inserted-len (atom 0))

(defn- strip-ansi
  "Remove ANSI escape sequences and control chars."
  [s]
  (-> s
      (str/replace #"\x1b\[[0-9;]*[a-zA-Z]" "")
      (str/replace #"\r" "")))

(defn- parse-stream-chunk
  "Extract meaningful text from a whisper-stream stdout chunk.
   Returns text or nil if blank/noise."
  [raw]
  (let [text (-> raw strip-ansi str/trim
                 (str/replace #"\.{2,}" "")
                 str/trim)]
    (when (and (seq text)
               (not (str/includes? text "[BLANK_AUDIO]"))
               (not (str/includes? text "[Start speaking]"))
               (not (re-matches #"^\[.*\]$" text)))
      text)))

(defn- spawn-process!
  "Spawn whisper-stream and start the read loop.
   The process and loop live until session shutdown."
  []
  (let [proc (js/Bun.spawn
               #js ["whisper-stream"
                    "--model" (model-path)
                    "--step" "3000"
                    "--length" "8000"
                    "--keep" "200"
                    "--language" "en"]
               #js {:stdout "pipe" :stderr "ignore"})
        reader (.getReader (.-stdout proc))
        decoder (js/TextDecoder.)]
    (reset! process {:proc proc})
    ;; Read loop lives as long as the process
    (letfn [(read-loop []
              (-> (.read reader)
                  (.then (fn [result]
                           (if (.-done result)
                             ;; Process died — clean up
                             (do (reset! process nil)
                                 (reset! active? false)
                                 (ext/request-render!))
                             (do
                               (when @active?
                                 (let [raw (.decode decoder (.-value result))
                                       text (parse-stream-chunk raw)]
                                   (when text
                                     (let [prev @prev-text
                                           trimmed (str/trim text)]
                                       (if (str/starts-with? text prev)
                                         ;; Append — whisper extended the previous chunk
                                         (let [delta (str/trim (subs text (count prev)))]
                                           (when (seq delta)
                                             (let [insertion (str delta " ")]
                                               (ext/insert-text! insertion)
                                               (swap! inserted-len + (count insertion))
                                               (reset! prev-text text))))
                                         ;; Rewrite — whisper revised the chunk, replace it
                                         (let [insertion (str trimmed " ")]
                                           (ext/delete-chars-before-cursor! @inserted-len)
                                           (ext/insert-text! insertion)
                                           (reset! inserted-len (count insertion))
                                           (reset! prev-text text)))))))
                               (when @process
                                 (read-loop))))))))]
      (read-loop))
    proc))

(defn- start-recording! []
  (if-let [{:keys [proc]} @process]
    ;; Resume existing process — no model load delay
    (do
      (.kill proc "SIGCONT")
      (reset! prev-text "")
      (reset! inserted-len 0)
      (reset! active? true)
      (ext/request-render!))
    ;; First time — spawn process
    (do
      (spawn-process!)
      (reset! prev-text "")
      (reset! inserted-len 0)
      (reset! active? true)
      (ext/request-render!))))

(defn- stop-recording!
  "Pause recording via SIGSTOP. Process stays alive for fast resume."
  []
  (when @active?
    (reset! active? false)
    (when-let [{:keys [proc]} @process]
      (.kill proc "SIGSTOP"))
    (ext/request-render!)))

(defn- kill-process!
  "Terminate the process. Used on session shutdown."
  []
  (reset! active? false)
  (when-let [{:keys [proc]} @process]
    (reset! process nil)
    (.kill proc "SIGTERM"))
  (ext/request-render!))

(defn- toggle! []
  (if @active?
    (stop-recording!)
    (start-recording!)))

(defn- prompt-badge [_state]
  (when @active? "🎤"))

(defn- on-input [value _state]
  (when @active?
    (stop-recording!))
  value)

(defn- on-shutdown [value _state]
  (kill-process!)
  value)

(def extension
  {:name "dictation"
   :hooks {:prompt-badge prompt-badge
           :input on-input
           :session-shutdown on-shutdown}
   :commands [{:name "dictate"
               :description "Toggle voice dictation (Alt+R)"
               :handler (fn [_ctx] (toggle!) nil)}]
   :keybindings [{:key "alt+r"
                  :handler (fn [] (toggle!))}
                 {:key "ctrl+c"
                  :handler (fn []
                             (when @active?
                               (stop-recording!)))
                  :when-fn (fn [] @active?)}]})
