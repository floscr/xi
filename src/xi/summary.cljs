(ns xi.summary
  "/summary — feed the current session to a cheap model and print a short
   description of what it's about. Handy after a /resume when the saved title
   is stale and you need to know what the session actually covers.

   Like xi.naming, the summary turn runs a cheap one-shot model against a
   throwaway CLAUDE_CONFIG_DIR, so it never creates a session file and never
   touches the ongoing provider thread. The transcript is built from the
   room's in-memory history, so it works even for a freshly resumed session.

   Flow:
     :summary/request   ─► status \"Summarizing…\" + [:summary/generate …]
     :summary/generate  ─► one-shot cheap-model turn on the transcript;
                           on end dispatch :summary/generated | :summary/failed
     :summary/generated ─► append the summary as a status entry
     :summary/failed    ─► append an error status"
  (:require [clojure.string :as str]
            [xi.commands :as commands]
            [xi.core.state :as state]))

(def ^:private SUMMARY_MODEL "claude-haiku-4-5-20251001")

(def ^:private SUMMARY_PROMPT_PREFIX
  (str "Below is a transcript of a coding session between a user and an AI "
       "assistant. In 2-4 sentences, describe what this session is about: the "
       "main goal or task, what has been done, and the current state. Be "
       "concrete — name the key files, features, or bugs involved. Reply with "
       "the description only, no preamble.\n\n"
       "=== TRANSCRIPT ===\n"))

(def ^:private MAX_INPUT 12000)
(def ^:private HEAD_KEEP 3000)

;; ── Transcript building (pure) ───────────────────────────────────────────────

(defn- entry->line
  "Render one in-memory history entry as a transcript line, or nil to skip."
  [{:keys [kind text tool]}]
  (case kind
    :user      (when (seq (str/trim (or text ""))) (str "USER: " text))
    :text      (when (seq (str/trim (or text ""))) (str "ASSISTANT: " text))
    :tool-call (when tool (str "[tool: " tool "]"))
    nil))

(defn- clip
  "Cap the transcript to MAX_INPUT chars, keeping the head (original task)
   and the tail (current state) when it's too long."
  [s]
  (if (<= (count s) MAX_INPUT)
    s
    (let [tail-keep (- MAX_INPUT HEAD_KEEP)]
      (str (subs s 0 HEAD_KEEP)
           "\n\n…[middle trimmed]…\n\n"
           (subs s (- (count s) tail-keep))))))

(defn build-transcript
  "Build a compact plain-text transcript from a room's in-memory history."
  [history]
  (->> history
       (keep entry->line)
       (str/join "\n\n")
       clip))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn- append-status [st room-id text]
  (update-in st [:rooms room-id :history] conj (commands/status-entry text)))

(defn- summary-request [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (cond
      (get-in room [:agent :summary-pending?])
      {:state (append-status st room-id "Already summarizing this session…")}

      (empty? (build-transcript (:history room)))
      {:state (append-status st room-id "Nothing to summarize yet.")}

      :else
      {:state    (-> st
                     (assoc-in [:rooms room-id :agent :summary-pending?] true)
                     (append-status room-id "Summarizing this session…"))
       :effects  [[:summary/generate {:room-id room-id}]]})))

(defn- summary-generated [st {:keys [room-id summary]}]
  (when (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id :agent :summary-pending?] false)
                (append-status room-id (str "Session summary:\n\n" summary)))}))

(defn- summary-failed [st {:keys [room-id error]}]
  (when (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id :agent :summary-pending?] false)
                (append-status room-id (str "Summary failed: " error)))}))

(def handlers
  {:summary/request   summary-request
   :summary/generated summary-generated
   :summary/failed    summary-failed})

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn create-fx
  "Summary-generation effect. Runs a throwaway one-shot turn against a cheap
   model through the claude provider, pointed at a temp CLAUDE_CONFIG_DIR so
   the CLI session it leaves behind never reaches the session list. The temp
   dir is created via `make-config-dir!` and removed via `remove-config-dir!`
   once the turn ends."
  [providers {:keys [make-config-dir! remove-config-dir!]}]
  {:summary/generate
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (if-let [provider (get providers :claude)]
       (let [room       (get-in state [:rooms room-id])
             transcript (build-transcript (:history room))
             cwd        (:cwd room)
             chunks     (atom [])
             config-dir (when make-config-dir! (make-config-dir!))
             cleanup!   (fn []
                          (when (and remove-config-dir! config-dir)
                            (remove-config-dir! config-dir)))
             {:keys [promise]}
             ((:start-turn! provider)
              (cond-> {:model   SUMMARY_MODEL
                       :prompt  (str SUMMARY_PROMPT_PREFIX transcript)
                       :cwd     cwd
                       :on-text (fn [t] (swap! chunks conj t))}
                config-dir (assoc :env {"CLAUDE_CONFIG_DIR" config-dir})))]
         (-> promise
             (.then
              (fn [result]
                (cleanup!)
                (let [summary (or (not-empty (str/trim (or (:result-text result) "")))
                                  (not-empty (str/trim (str/join @chunks))))]
                  (cond
                    (:aborted result)
                    (dispatch! {:type :summary/failed :room-id room-id :error "aborted"})

                    (nil? summary)
                    (dispatch! {:type :summary/failed :room-id room-id :error "empty summary"})

                    :else
                    (dispatch! {:type :summary/generated :room-id room-id :summary summary})))))
             (.catch
              (fn [err]
                (cleanup!)
                (dispatch! {:type :summary/failed :room-id room-id
                            :error (str (.-message err))})
                nil))))
       (dispatch! {:type :summary/failed :room-id room-id
                   :error "no claude provider"})))})
