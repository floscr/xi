(ns xi.naming
  "Auto-titling — generate a short session name from the first user message
   so the UI never lingers on \"New session\".

   Flow:
     :prompt/submit (first msg, unnamed)
       ─► maybe-generate-title chains on, sets :agent :title-pending? and
          emits [:session/generate-title …]
     :session/generate-title
       ─► one-shot turn against a cheap model, fire-and-forget; on success
          dispatches :session/title-generated
     :session/title-generated
       ─► sets [:session :name] when still unnamed (broadcast to clients;
          :session/sync later persists it to disk)

   The title turn is NOT an Xi session — it resumes nothing and is never
   saved. It's a throwaway provider call, like compaction's summary turn."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def ^:private TITLE_MODEL "claude-haiku-4-5-20251001")

(def ^:private TITLE_PROMPT_PREFIX
  (str "Write a concise title (3-6 words, Title Case, no surrounding quotes, "
       "no trailing punctuation) that summarizes the following request for a "
       "coding-session list. Reply with the title only — no preamble.\n\n"))

(def ^:private MAX_INPUT 2000)

(defn- clean-title
  "Tidy a model-produced title: first non-blank line, strip wrapping quotes
   and trailing punctuation, cap length. Returns nil when nothing usable."
  [s]
  (when s
    (let [line (->> (str/split-lines s)
                    (map str/trim)
                    (some not-empty))]
      (when line
        (-> line
            (str/replace #"^[\"'`]+|[\"'`]+$" "")
            (str/replace #"[.!?,;:]+$" "")
            str/trim
            (as-> t (when (seq t) (subs t 0 (min 60 (count t))))))))))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn maybe-generate-title
  "Chained onto :prompt/submit. On the first user message of an unnamed
   session, kick off out-of-band title generation. :title-pending? guards
   against a queued second message firing a second turn."
  [st {:keys [room-id text]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (string? text)
               (seq (str/trim text))
               (nil? (get-in room [:session :name]))
               (not (get-in room [:agent :title-pending?]))
               ;; prompt-submit ran first (chain), so the message is in
               ;; history — confirms this wasn't merely queued while busy.
               (some #(= :user (:kind %)) (:history room)))
      {:state    (assoc-in st [:rooms room-id :agent :title-pending?] true)
       :effects  [[:session/generate-title {:room-id room-id :text text}]]})))

(defn- title-generated
  "Apply a generated title — only while the session is still unnamed, so a
   /resume that landed first always wins."
  [st {:keys [room-id title]}]
  (when-let [room (state/get-room st room-id)]
    (when (and title (nil? (get-in room [:session :name])))
      {:state (assoc-in st [:rooms room-id :session :name] title)})))

(def handlers
  {:session/title-generated title-generated})

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn create-fx
  "Title-generation effect. Runs a throwaway one-shot turn against a cheap
   model through the claude provider — no resume, never persisted."
  [providers]
  {:session/generate-title
   (fn [{:keys [dispatch! state]} {:keys [room-id text]}]
     (when-let [provider (get providers :claude)]
       (let [chunks (atom [])
             input  (subs text 0 (min MAX_INPUT (count text)))
             {:keys [promise]}
             ((:start-turn! provider)
              {:model   TITLE_MODEL
               :prompt  (str TITLE_PROMPT_PREFIX input)
               :cwd     (get-in state [:rooms room-id :cwd])
               :on-text (fn [t] (swap! chunks conj t))})]
         (-> promise
             (.then
              (fn [result]
                (when-not (:aborted result)
                  (let [raw   (or (not-empty (:result-text result))
                                  (not-empty (str/join @chunks)))
                        title (clean-title raw)]
                    (when title
                      (dispatch! {:type :session/title-generated
                                  :room-id room-id :title title}))))))
             (.catch (fn [_err] nil))))))})
