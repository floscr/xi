(ns xi.naming
  "Auto-titling — generate a short session name from the first user message
   so the UI never lingers on \"New session\".

   The title turn runs a cheap model through the claude provider. The SDK/CLI
   always persists that turn as its own session file, which Xi would otherwise
   surface in the session list — so we run it against a throwaway
   CLAUDE_CONFIG_DIR (a temp mirror of the real config). The CLI writes the
   turn's session JSONL under that temp dir instead of ~/.claude/projects, so
   it never reaches the session list, and the temp dir is removed once the turn
   ends. Nothing about the title turn is ever kept.

   Flow:
     :prompt/submit (first msg, unnamed)
       ─► maybe-generate-title sets :agent :title-pending? + emits
          [:session/generate-title …]
     :session/generate-title (effect)
       ─► one-shot turn in a temp config dir; on end, remove the temp dir +
          dispatch :session/title-generated
     :session/title-generated
       ─► sets [:session :name] when still unnamed."
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
  "Apply the title — only while the session is still unnamed, so a /resume
   that landed first always wins."
  [st {:keys [room-id title]}]
  (when-let [room (state/get-room st room-id)]
    (when (and title (nil? (get-in room [:session :name])))
      {:state (assoc-in st [:rooms room-id :session :name] title)})))

(def handlers
  {:session/title-generated title-generated})

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn create-fx
  "Title-generation effect. Runs a throwaway one-shot turn against a cheap
   model through the claude provider, pointed at a temp CLAUDE_CONFIG_DIR so the
   CLI session it leaves behind lands in a temp dir (never ~/.claude/projects).
   The temp dir is created via `make-config-dir!` and torn down via
   `remove-config-dir!` once the turn ends."
  [providers {:keys [make-config-dir! remove-config-dir!]}]
  {:session/generate-title
   (fn [{:keys [dispatch! state]} {:keys [room-id text]}]
     (when-let [provider (get providers :claude)]
       (let [cwd        (get-in state [:rooms room-id :cwd])
             chunks     (atom [])
             input      (subs text 0 (min MAX_INPUT (count text)))
             config-dir (when make-config-dir! (make-config-dir!))
             cleanup!   (fn []
                          (when (and remove-config-dir! config-dir)
                            (remove-config-dir! config-dir)))
             {:keys [promise]}
             ((:start-turn! provider)
              (cond-> {:model   TITLE_MODEL
                       :prompt  (str TITLE_PROMPT_PREFIX input)
                       :cwd     cwd
                       :on-text (fn [t] (swap! chunks conj t))}
                config-dir (assoc :env {"CLAUDE_CONFIG_DIR" config-dir})))]
         (-> promise
             (.then
              (fn [result]
                (cleanup!)
                (when-not (:aborted result)
                  (let [raw   (or (not-empty (:result-text result))
                                  (not-empty (str/join @chunks)))
                        title (clean-title raw)]
                    (when title
                      (dispatch! {:type :session/title-generated
                                  :room-id room-id :title title}))))))
             (.catch (fn [_err]
                       (cleanup!)
                       (dispatch! {:type :session/title-generated
                                   :room-id room-id :title nil})
                       nil))))))})
