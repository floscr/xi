(ns xi.session.recorder
  "Session transcripts for sessionless providers (OpenCode, Ollama, Codex):
   `wrap` records each main turn from the provider's own callbacks into a
   Claude CLI-format transcript under the Claude projects dir, and reports
   its id as the provider session id. xi.cli applies it to every provider
   that keeps no transcript of its own (`with-transcripts`), so a new
   provider gets sessions without opting in. Xi's session machinery — saving, the
   sidebar, resume, `/tree` rewinds (xi.session.claude-store) — then works
   as for the Claude provider. The model's context still comes from the
   room's replayed :history; the transcript is what a resume rebuilds that
   history from.

   The prompt is written when the turn starts and the rest when it ends, as
   the CLI does, so a killed turn leaves a cut-off transcript
   (xi.session/turn-completed?). Side turns (`:no-tools?`) are not recorded."
  (:require [clojure.string :as str]
            [xi.session :as session]
            [xi.session.claude-store :as claude-store]
            [xi.session.sync :as sync]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

;; ── Events → transcript messages (pure) ──────────────────────────────────────
;;
;; A turn is a stream of callback events: [:text s] [:tool-start call]
;; [:tool-args call] [:tool-result result] [:error message]. Claude's format
;; groups them as assistant messages (text + tool_use blocks) alternating with
;; user messages carrying the tool_result blocks.

(def empty-turn {:messages [] :assistant [] :results []})

(defn- flush-results [t]
  (if (seq (:results t))
    (-> t
        (update :messages conj {:role "user" :content (:results t)})
        (assoc :results []))
    t))

(defn- flush-assistant [t extra]
  (if (or (seq (:assistant t)) extra)
    (-> t
        (update :messages conj (merge {:role "assistant" :content (:assistant t)} extra))
        (assoc :assistant []))
    t))

(defn- update-tool-use [blocks id f]
  (mapv #(if (and (= "tool_use" (:type %)) (= id (:id %))) (f %) %) blocks))

(defn step
  "Fold one callback event into turn state `t`."
  [t [kind x]]
  (case kind
    :text
    (let [t (flush-results t)
          bs (:assistant t)]
      (assoc t :assistant
             (if (= "text" (:type (peek bs)))
               (conj (pop bs) (update (peek bs) :text str x))
               (conj bs {:type "text" :text x}))))

    :tool-start
    (-> (flush-results t)
        (update :assistant conj {:type "tool_use" :id (:id x) :name (:name x)
                                 :input (or (:arguments x) {})}))

    :tool-args
    (update t :assistant update-tool-use (:id x) #(assoc % :input (or (:arguments x) {})))

    :tool-result
    (-> (flush-assistant t nil)
        (update :results conj {:type "tool_result" :tool_use_id (:id x)
                               :content (:content x)
                               :is_error (boolean (:is-error x))}))

    ;; Stored like the CLI's synthetic error message, so a resumed chat
    ;; shows the error card (xi.session/read-claude-session-messages).
    :error
    (-> (flush-results t)
        (flush-assistant nil)
        (update :messages conj {:role "assistant" :api-error? true
                                :content [{:type "text" :text (str x)}]}))

    t))

(defn finish
  "The turn's messages once it ended: a turn that finished normally closes
   with an assistant message stopped at end_turn, the marker of a completed
   turn; an aborted or failed one stays cut off."
  [t completed?]
  (:messages (-> (flush-results t)
                 (flush-assistant (when completed? {:stop_reason "end_turn"})))))

(defn completed?
  "True when the provider's turn result is a normal end."
  [{:keys [aborted stop-reason resume-failed cwd-missing]}]
  (not (or aborted resume-failed cwd-missing
           (contains? #{"error" "aborted"} stop-reason))))

(defn prompt-message
  "The user message for the turn's prompt; inline images and PDFs as
   base64 blocks, as the CLI stores them."
  [prompt images]
  (let [inline (filter (fn [{:keys [media-type data]}]
                         (and data (or (= media-type "application/pdf")
                                       (str/starts-with? (or media-type "") "image/"))))
                       images)]
    {:role "user"
     :content (if (seq inline)
                (into [{:type "text" :text prompt}]
                      (map (fn [{:keys [media-type data]}]
                             {:type (if (= media-type "application/pdf") "document" "image")
                              :source {:type "base64" :media_type media-type :data data}}))
                      inline)
                prompt)}))

;; ── Transcript file ──────────────────────────────────────────────────────────

(defn- transcript-file
  "Under the Claude projects dir, or with `config-dir` (a turn's
   CLAUDE_CONFIG_DIR, as sub-agents run with) under that dir's projects/,
   like the CLI."
  [config-dir cwd sid]
  (node-path/join (if config-dir
                    (node-path/join config-dir "projects")
                    (session/claude-projects-dir))
                  (sync/encode-cwd-claude cwd)
                  (str sid ".jsonl")))

(defn entry
  "One transcript line's data for message `m`."
  [sid cwd now-iso {:keys [role content stop_reason api-error?]}]
  (cond-> {:type      role
           :sessionId sid
           :cwd       cwd
           :timestamp now-iso
           :message   (cond-> {:role role :content content}
                        stop_reason (assoc :stop_reason stop_reason))}
    api-error? (assoc :isApiErrorMessage true)))

(defn- append! [file sid cwd messages]
  (when (seq messages)
    (try
      (fs/mkdirSync (node-path/dirname file) #js {:recursive true})
      (let [now (.toISOString (js/Date.))]
        (fs/appendFileSync file
                           (apply str (map #(str (js/JSON.stringify (clj->js (entry sid cwd now %))) "\n")
                                           messages))))
      (catch :default e
        (.write js/process.stderr (str "[xi] transcript write failed: " (.-message e) "\n"))))))

;; ── Wrapper ──────────────────────────────────────────────────────────────────

(defn- recorded-start-turn [start-turn!]
  (fn [{:keys [no-tools? resume-session-id cwd prompt images] :as opts}]
    (if no-tools?
      (start-turn! opts)
      (let [cwd    (or cwd (.cwd js/process))
            sid    (or resume-session-id (str (random-uuid)))
            file   (transcript-file (get-in opts [:env "CLAUDE_CONFIG_DIR"]) cwd sid)
            !turn  (atom empty-turn)
            record (fn [k cb]
                     (fn [x]
                       (swap! !turn step [k (if (= k :error) (:message x) x)])
                       (when cb (cb x))))
            _      (append! file sid cwd [(prompt-message prompt images)])
            _      (when-let [f (:on-session opts)] (f sid))
            handle (start-turn!
                    (-> opts
                        (assoc :on-text        (record :text (:on-text opts))
                               :on-tool-start  (record :tool-start (:on-tool-start opts))
                               :on-tool-args   (record :tool-args (:on-tool-args opts))
                               :on-tool-result (record :tool-result (:on-tool-result opts))
                               :on-error       (record :error (:on-error opts)))))
            settle (fn [completed?]
                     (append! file sid cwd (finish @!turn completed?))
                     (reset! !turn empty-turn))
            promise (-> (:promise handle)
                        (.then (fn [result]
                                 (settle (completed? result))
                                 (assoc result :session-id sid)))
                        (.catch (fn [err]
                                  (settle false)
                                  (throw err))))]
        (assoc handle
               :promise promise
               ;; the transcript is complete once the promise settles
               :exited promise)))))

(defn wrap
  "`provider` with its main turns recorded as Claude-format transcripts and
   the Claude session store for rewinds."
  [provider]
  (-> provider
      (update :start-turn! recorded-start-turn)
      (assoc :store claude-store/store)))

(defn with-transcripts
  "`provider`, recorded unless it keeps its conversation itself — a provider
   with a `:store` does (Claude's CLI writes the transcript it resumes from)."
  [provider]
  (if (:store provider) provider (wrap provider)))
