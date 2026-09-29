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
       ─► maybe-generate-title sets :agent :title-pending?, sets a provisional
          [:session :name] from the prompt text, + emits
          [:session/generate-title …]
     :session/generate-title (effect)
       ─► one-shot turn in a temp config dir; on end, remove the temp dir +
          dispatch :session/title-generated
     :session/title-generated
       ─► replaces the nil/provisional [:session :name] with the model title."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.util :as util]))

(def ^:private TITLE_MODEL "claude-haiku-4-5-20251001")

(def ^:private TITLE_PROMPT_PREFIX
  (str "Write a concise title (3-6 words, Title Case, no surrounding quotes, "
       "no trailing punctuation) that summarizes the following request for a "
       "coding-session list. Reply with the title only — no preamble.\n\n"))

(def ^:private MAX_INPUT 2000)

(defn clean-title
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
   against a queued second message firing a second turn.

   Immediately sets a *provisional* name derived from the first prompt (via
   `util/session-title`) so the UI shows a snippet of the request instead of
   the New-session placeholder while the model title is still generating.
   :title-provisional? marks it so `title-generated` may overwrite it once the
   real title lands."
  [st {:keys [room-id text]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (string? text)
               (seq (str/trim text))
               (nil? (get-in room [:session :name]))
               (not (get-in room [:agent :title-pending?]))
               ;; prompt-submit ran first (chain), so the message is in
               ;; history — confirms this wasn't merely queued while busy.
               (some #(= :user (:kind %)) (:history room)))
      (let [provisional (util/session-title text)
            st (assoc-in st [:rooms room-id :agent :title-pending?] true)
            st (if provisional
                 (-> st
                     (assoc-in [:rooms room-id :session :name] provisional)
                     (assoc-in [:rooms room-id :agent :title-provisional?] true))
                 st)]
        {:state   st
         :effects [[:session/generate-title {:room-id room-id :text text}]]}))))

(defn- title-generated
  "Apply the model title over the nil/provisional name, so the prompt-derived
   placeholder is replaced once generation finishes. A /resume or compaction
   that set a real (non-provisional) name always wins, so we never clobber it.

   Persist the fresh title immediately via :session/sync. The title turn
   resolves out of band and often *after* the turn-end that first wrote the
   session file (e.g. a short turn whose title turn is still running, or one
   with a large first message like the element picker's), so without this the
   provisional name would linger on disk until some later turn-end synced the
   room — which for a one-shot picker session may never happen. :session/sync
   no-ops until the session has a provider-session-id, so an early landing is
   still safely persisted by the following turn-end."
  [st {:keys [room-id title]}]
  (when-let [room (state/get-room st room-id)]
    (when (and title
               (or (nil? (get-in room [:session :name]))
                   (get-in room [:agent :title-provisional?])))
      {:state   (-> st
                    (assoc-in [:rooms room-id :session :name] title)
                    (update-in [:rooms room-id :agent] dissoc :title-provisional?))
       :effects [[:session/sync {:room-id room-id}]]})))

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
     (when-let [provider (get providers :anthropic)]
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
                       ;; Title generation is a throwaway text-only turn: skip
                       ;; the MCP tool bridge so this concurrent CLI subprocess
                       ;; doesn't race the first user turn's tool handshake
                       ;; (which surfaced as "No such tool available").
                       :no-tools? true
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
