(ns xi.ext.dictation
  "Voice dictation extension.
   Alt+R toggles recording — first press starts live streaming transcription,
   second press stops. Shows 🎤 badge while recording.
   Uses whisper-stream for real-time speech-to-text."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]))

(def ^:private models-dir
  (str (aget js/process.env "HOME") "/.local/share/whisper-models"))

(def ^:private default-model "base.en")

(defn- model-path []
  (str models-dir "/ggml-" default-model ".bin"))

;; State: nil when idle, {:proc} when recording
(defonce ^:private recording (atom nil))

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

(defn- start-recording! []
  (let [proc (js/Bun.spawn
               #js ["whisper-stream"
                    "--model" (model-path)
                    "--step" "3000"
                    "--length" "8000"
                    "--keep" "200"
                    "--language" "en"]
               #js {:stdout "pipe" :stderr "ignore"})
        reader (.getReader (.-stdout proc))
        decoder (js/TextDecoder.)
        ;; Track what we've inserted so we can replace on refinement
        prev-text (atom "")]
    (reset! recording {:proc proc})
    (ext/request-render!)
    ;; Read stdout stream in a loop
    (letfn [(read-loop []
              (-> (.read reader)
                  (.then (fn [result]
                           (when (and (not (.-done result)) @recording)
                             (let [raw (.decode decoder (.-value result))
                                   text (parse-stream-chunk raw)]
                               (when text
                                 (let [prev @prev-text]
                                   (if (str/starts-with? text prev)
                                     (let [delta (subs text (count prev))]
                                       (when (seq (str/trim delta))
                                         (ext/insert-text! (str/trim delta))
                                         (ext/insert-text! " ")
                                         (reset! prev-text text)))
                                     (do
                                       (ext/insert-text! (str text " "))
                                       (reset! prev-text text))))))
                           (when @recording
                             (read-loop)))))))]
      (read-loop))))

(defn- stop-recording! []
  (when-let [{:keys [proc]} @recording]
    (reset! recording nil)
    (.kill ^js proc "SIGTERM")
    (ext/request-render!)))

(defn- toggle! []
  (if @recording
    (stop-recording!)
    (start-recording!)))

(defn- prompt-badge [_state]
  (when @recording "🎤"))

(defn- auto-stop [value _state]
  (when @recording
    (stop-recording!))
  value)

(def extension
  {:name "dictation"
   :hooks {:prompt-badge prompt-badge
           :input auto-stop
           :session-shutdown auto-stop}
   :commands [{:name "dictate"
               :description "Toggle voice dictation (Alt+R)"
               :handler (fn [_ctx] (toggle!) nil)}]
   :keybindings [{:key "alt+r"
                  :handler (fn [] (toggle!))}
                 {:key "ctrl+c"
                  :handler (fn []
                             (when @recording
                               (stop-recording!)))
                  :when-fn (fn [] @recording)}]})
