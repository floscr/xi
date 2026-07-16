(ns xi.compaction
  "Context compaction — summarize the current provider session, then start
   a fresh session seeded with the summary.

   Flow:
     :compact/request ─► busy on + status + [:compact/start …]
     :compact/start   ─► provider turn (resume + summary prompt)
     :compact/done    ─► busy off + [:session/new {:after-prompt summary}]
                         (xi.fx creates the session; :session/created
                          re-submits the summary through :prompt/submit)
     :compact/failed  ─► busy off + status

   Unlike master (which talked to the SDK directly), the summarization run
   goes through the provider layer — same .close()/abort handling as any
   other turn. Pure handlers here; the provider call lives in create-fx."
  (:require [clojure.string :as str]
            [xi.commands :as commands]
            [xi.core.state :as state]))

(def ^:private COMPACT_MODEL "claude-sonnet-4-6")

(def ^:private COMPACT_PROMPT
  "Summarize this conversation for continuity. Produce a concise summary preserving:
1. All file paths read, written, or edited
2. Key decisions and their rationale
3. Current task state (done vs pending)
4. Errors encountered and resolutions
5. Important context needed to continue

Be thorough but concise. Output only the summary, no preamble.")

(defn- append-status [st room-id text]
  (update-in st [:rooms room-id :history] conj (commands/status-entry text)))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn- compact-request [st {:keys [room-id focus]}]
  (when-let [room (state/get-room st room-id)]
    (let [session-id (get-in room [:session :provider-session-id])]
      (cond
        (get-in room [:agent :busy?])
        {:state (append-status st room-id "Cannot compact while the agent is busy.")}

        (nil? session-id)
        {:state (append-status st room-id "No active session to compact.")}

        :else
        {:state (-> st
                    (assoc-in [:rooms room-id :agent :busy?] true)
                    (append-status room-id "Compacting conversation..."))
         :effects [[:compact/start {:room-id room-id
                                    :session-id session-id
                                    :focus focus}]]}))))

(defn- compact-done [st {:keys [room-id summary]}]
  (when (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id :agent :busy?] false)
                (append-status room-id
                               "Session compacted. Summary preserved as context."))
     :effects [[:session/new
                {:room-id room-id
                 :after-prompt
                 (str "<conversation-summary>\n" summary "\n</conversation-summary>\n\n"
                      "Acknowledge this summary briefly and wait for my next instruction.")}]]}))

(defn- compact-failed [st {:keys [room-id error]}]
  (when (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id :agent :busy?] false)
                (append-status room-id (str "Compaction failed: " error)))}))

(def handlers
  {:compact/request compact-request
   :compact/done    compact-done
   :compact/failed  compact-failed})

(defn abort-handler
  "Chained onto :agent/abort so Escape also stops an in-flight compaction
   (no-op when none is running — the fx handle map decides)."
  [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :agent :busy?])
    {:effects [[:compact/abort {:room-id room-id}]]}))

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn create-fx
  "Compaction effects. Runs the summary turn through the claude provider,
   resuming the room's provider session."
  [providers]
  (let [inflight (js/Map.)]
    {:compact/start
     (fn [{:keys [dispatch! state]} {:keys [room-id session-id]}]
       (let [provider (get providers :claude)
             chunks (atom [])
             {:keys [promise abort!]}
             ((:start-turn! provider)
              {:model COMPACT_MODEL
               :prompt COMPACT_PROMPT
               :cwd (get-in state [:rooms room-id :cwd])
               :resume-session-id session-id
               :on-text (fn [text] (swap! chunks conj text))})]
         (.set inflight room-id abort!)
         (-> promise
             (.then
              (fn [result]
                (.delete inflight room-id)
                (let [summary (or (not-empty (:result-text result))
                                  (not-empty (str/join @chunks)))]
                  (cond
                    (:aborted result)
                    (dispatch! {:type :compact/failed :room-id room-id
                                :error "aborted"})

                    ;; Turn errored (e.g. bad model) — don't let the provider's
                    ;; error text masquerade as the summary.
                    (:is-error result)
                    (dispatch! {:type :compact/failed :room-id room-id
                                :error (or (not-empty summary) "provider error")})

                    (nil? summary)
                    (dispatch! {:type :compact/failed :room-id room-id
                                :error "empty summary"})

                    :else
                    (dispatch! {:type :compact/done :room-id room-id
                                :summary summary})))))
             (.catch
              (fn [err]
                (.delete inflight room-id)
                (dispatch! {:type :compact/failed :room-id room-id
                            :error (str (.-message err))}))))))

     :compact/abort
     (fn [_ {:keys [room-id]}]
       (when-let [abort! (.get inflight room-id)]
         (abort!)))}))
