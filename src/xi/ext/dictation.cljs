(ns xi.ext.dictation
  "Voice dictation — client-only, process-local.

   Alt+R toggles recording: the first press spawns whisper-stream and starts
   live transcription, a second press (or Ctrl+C while recording) pauses it.
   Shows a `● REC` badge while recording. The whisper process is kept alive
   between sessions (SIGSTOP/SIGCONT) for instant resume; :on-shutdown kills
   it on process exit.

   The child process + diff bookkeeping are runtime resources living in the
   `create` closure — not in app state. App state holds only the mirror-free
   process-local flag [:ext :dictation] {:recording? bool}, which drives the
   badge and the Ctrl+C guard. Transcript text reaches the editor purely as
   data: the read loop dispatches :ext.dictation/transcript events whose
   handler emits :editor/delete-before-cursor + :editor/insert-text effects."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def ^:private ext-id :dictation)

(def ^:private models-dir
  (str (aget js/process.env "HOME") "/.local/share/whisper-models"))

(def ^:private default-model "base.en")

(defn- model-path []
  (str models-dir "/ggml-" default-model ".bin"))

(defn- strip-ansi [s]
  (-> s
      (str/replace #"\x1b\[[0-9;]*[a-zA-Z]" "")
      (str/replace #"\r" "")))

(defn- parse-stream-chunk
  "Extract meaningful text from a whisper-stream stdout chunk, or nil."
  [raw]
  (let [text (-> raw strip-ansi str/trim
                 (str/replace #"\.{2,}" "")
                 str/trim)]
    (when (and (seq text)
               (not (str/includes? text "[BLANK_AUDIO]"))
               (not (str/includes? text "[Start speaking]"))
               (not (re-matches #"^\[.*\]$" text)))
      text)))

;; ── State (pure) ──────────────────────────────────────────────────────────────

(defn- recording? [st]
  (boolean (:recording? (state/process-ext st ext-id))))

(defn- toggle
  "Alt+R / Ctrl+C → flip the process-local flag and emit start/stop fx."
  [st _]
  (let [on? (recording? st)]
    {:state   (assoc-in st [:ext ext-id :recording?] (not on?))
     :effects [[(if on? :dictation/stop :dictation/start) {}]]}))

(defn- ended
  "Read loop reported the process died — clear the flag."
  [st _]
  {:state (assoc-in st [:ext ext-id :recording?] false)})

(defn- transcript
  "Apply a transcript delta: optionally rewind, then insert."
  [_ {:keys [delete text]}]
  {:effects (cond-> []
              (and delete (pos? delete)) (conj [:editor/delete-before-cursor {:n delete}])
              (seq text)                 (conj [:editor/insert-text {:text text}]))})

(defn- prompt-badge [state]
  (when (recording? state) " ● REC"))

;; ── Factory (closure holds the runtime resources) ─────────────────────────────

(defn create
  "Build the dictation extension. The whisper process + diff state live in
   this closure; app state only carries the :recording? badge flag."
  []
  (let [proc*    (atom nil)        ;; the whisper child process (or nil)
        active?  (atom false)      ;; insert transcript output while true
        prev*    (atom "")         ;; last whisper chunk (append vs rewrite)
        ins-len* (atom 0)]         ;; chars inserted for the current chunk
    (letfn [(spawn! [dispatch!]
              (let [proc (js/Bun.spawn
                          #js ["whisper-stream"
                               "--model" (model-path)
                               "--step" "3000" "--length" "8000"
                               "--keep" "200" "--language" "en"]
                          #js {:stdout "pipe" :stderr "ignore"})
                    reader (.getReader (.-stdout proc))
                    decoder (js/TextDecoder.)]
                (reset! proc* proc)
                (letfn [(read-loop []
                          (-> (.read reader)
                              (.then
                               (fn [result]
                                 (if (.-done result)
                                   (do (reset! proc* nil)
                                       (reset! active? false)
                                       (dispatch! {:type :ext.dictation/ended}))
                                   (do
                                     (when @active?
                                       (when-let [text (parse-stream-chunk
                                                        (.decode decoder (.-value result)))]
                                         (let [prev @prev*]
                                           (if (str/starts-with? text prev)
                                             ;; append — whisper extended the chunk
                                             (let [delta (str/trim (subs text (count prev)))]
                                               (when (seq delta)
                                                 (let [insertion (str delta " ")]
                                                   (swap! ins-len* + (count insertion))
                                                   (reset! prev* text)
                                                   (dispatch! {:type :ext.dictation/transcript
                                                               :text insertion}))))
                                             ;; rewrite — whisper revised the chunk
                                             (let [insertion (str (str/trim text) " ")]
                                               (dispatch! {:type :ext.dictation/transcript
                                                           :delete @ins-len* :text insertion})
                                               (reset! ins-len* (count insertion))
                                               (reset! prev* text))))))
                                     (when @proc* (read-loop))))))))]
                  (read-loop))))]
      (let [start! (fn [{:keys [dispatch!]} _]
                     (reset! prev* "")
                     (reset! ins-len* 0)
                     (reset! active? true)
                     (if-let [^js proc @proc*]
                       (.kill proc "SIGCONT")   ;; resume — no model reload
                       (spawn! dispatch!)))
            stop!  (fn [_ _]
                     (reset! active? false)
                     (when-let [^js proc @proc*]
                       (.kill proc "SIGSTOP")))
            kill!  (fn []
                     (reset! active? false)
                     (when-let [^js proc @proc*]
                       (reset! proc* nil)
                       (.kill proc "SIGTERM")))]
        {:id           ext-id
         :init         {:process {:recording? false}}
         :handlers     {:ext.dictation/toggle     toggle
                        :ext.dictation/ended      ended
                        :ext.dictation/transcript transcript}
         :fx           {:dictation/start start!
                        :dictation/stop  stop!}
         :keybindings  [{:key "alt+r" :event {:type :ext.dictation/toggle}}
                        {:key "ctrl+c" :event {:type :ext.dictation/toggle}
                         :when recording?}]
         :prompt-badge prompt-badge
         :on-shutdown  kill!}))))
