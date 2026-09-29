(ns xi.quick-replies
  "Dynamic quick-reply chips — after a turn ends, run a cheap model over the
   final assistant message to detect a decision point (yes/no, pick-one) and
   surface one-tap reply buttons below the response.

   The assistant text is NEVER modified: chips are additive UI rendered
   separately (web: a row above the composer; see xi.web.views), and tapping
   one just dispatches a normal :prompt/submit with a predefined message. So
   there is nothing to validate against content-drift — the response is shown
   verbatim exactly as before.

   Like xi.naming / xi.summary, the detection turn runs a cheap one-shot model
   against a throwaway CLAUDE_CONFIG_DIR so it never creates a session file and
   never touches the ongoing provider thread. It runs the provider directly
   (not through app dispatch), so it does not re-fire :agent/turn-end — no
   recursion.

   A cheap regex gate (`worth-suggesting?`) runs first, so most turns never
   call the model at all.

   Flow:
     :agent/turn-end (chained maybe-suggest)
       ─► always clears stale chips; if the last assistant text looks like a
          decision, sets [:rooms rid :quick-replies :pending?] + emits
          [:quick-replies/generate …]
     :quick-replies/generate (effect)
       ─► one-shot cheap-model turn → parse JSON → dispatch :quick-replies/suggested
     :quick-replies/suggested
       ─► store [:rooms rid :quick-replies {:chips […]}] (mirrors to clients),
          or clear when there are none — but drop the result if a newer turn or
          submit has since bumped :gen (a slow detection from a past turn must
          not re-add chips under the current response)
     :prompt/submit (chained clear-on-submit)
       ─► the user sent something, so any lingering chips are stale — clear."
  (:require [clojure.string :as str]
            [xi.config :as config]
            [xi.core.state :as state]))

(def ^:private MODEL "claude-haiku-4-5-20251001")

(def ^:private MAX_INPUT 4000)
(def ^:private MAX_CHIPS 4)
(def ^:private MAX_LABEL 60)
(def ^:private MAX_SEND 400)

(def ^:private PROMPT_PREFIX
  (str "You are a UI affordance detector for a coding assistant. You are given "
       "the final message the assistant sent to the user. Decide whether the "
       "user would benefit from one-tap reply buttons, and if so produce them.\n\n"
       "Output ONLY a JSON object — no prose, no markdown, no code fences.\n"
       "Schema: {\"kind\": \"yes-no\" | \"choice\" | \"none\", "
       "\"chips\": [{\"label\": \"...\", \"send\": \"...\"}]}\n\n"
       "- \"label\": the short button text shown to the user (<= 60 chars).\n"
       "- \"send\": the exact message sent on the user's behalf when tapped.\n"
       "- \"yes-no\": the assistant asks a yes/no question. Typical chips: "
       "{\"label\":\"Yes\",\"send\":\"yes\"}, {\"label\":\"No\",\"send\":\"no\"}.\n"
       "- \"choice\": the assistant offers a small set of distinct options or "
       "alternatives (2-4). One chip per option, in the order presented.\n"
       "- \"none\" with empty chips: no clear decision for the user to make. "
       "When in doubt, use \"none\".\n"
       "- Never invent options the assistant did not offer. Never change "
       "meaning. Maximum 4 chips.\n\n"
       "Assistant message:\n<<<\n"))

;; ── Pure helpers ─────────────────────────────────────────────────────────────

(defn last-assistant-text
  "The final assistant text block of the last turn: the text of the last
   `:kind :text` history entry. nil when there is none."
  [history]
  (->> history
       (filter #(= :text (:kind %)))
       last
       :text
       not-empty))

(defn worth-suggesting?
  "Cheap gate: only call the model when the text plausibly poses a decision —
   it contains a question mark, or a decision phrase. Skips the common case
   (statements with no choice) without spending a model call."
  [text]
  (boolean
   (when (and (string? text) (seq (str/trim text)))
     (or (str/includes? text "?")
         (re-find #"(?i)\b(should i|shall i|would you like|do you want|which option|which one|proceed|continue|yes or no|prefer)\b"
                  text)))))

(defn- clean-chip
  "Validate + tidy one raw chip map. Returns {:label :send} or nil."
  [{:strs [label send]}]
  (let [label (some-> label str str/trim)
        send  (some-> send str str/trim)]
    (when (and (seq label) (seq send)
               (<= (count label) MAX_LABEL)
               (<= (count send) MAX_SEND))
      {:label label :send send})))

(defn parse-chips
  "Parse the model's JSON output into a sanitized chip vector (possibly empty).
   Tolerates surrounding whitespace / accidental code fences. Any parse or
   schema problem yields []."
  [raw]
  (let [s (some-> raw
                  str/trim
                  (str/replace #"^```(?:json)?\s*" "")
                  (str/replace #"\s*```$" ""))]
    (if (str/blank? s)
      []
      (try
        (let [obj   (js/JSON.parse s)
              chips (aget obj "chips")]
          (if (array? chips)
            (->> (array-seq chips)
                 (map #(js->clj %))
                 (keep clean-chip)
                 (distinct)
                 (take MAX_CHIPS)
                 vec)
            []))
        (catch :default _ [])))))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn- next-gen
  "Monotonic per-room detection id. Bumped on every turn-end and submit so a
   slow detection from an earlier turn can be recognised as stale when it lands
   (see `suggested`). Survives clears because it is written back, not dissoc'd."
  [st room-id]
  (inc (get-in st [:rooms room-id :quick-replies :gen] 0)))

(defn maybe-suggest
  "Chained onto :agent/turn-end. Always clears the previous turn's chips (they
   are stale once a new turn finishes) and bumps the generation id. If the
   feature is on, the turn was not aborted, and the final assistant text looks
   like a decision, kick off out-of-band chip detection tagged with that gen."
  [st {:keys [room-id aborted?]}]
  (when-let [room (state/get-room st room-id)]
    (let [gen  (next-gen st room-id)
          st'  (assoc-in st [:rooms room-id :quick-replies] {:gen gen})
          text (last-assistant-text (:history room))]
      (if (and config/quick-replies?
               (not aborted?)
               text
               (worth-suggesting? text))
        {:state   (assoc-in st' [:rooms room-id :quick-replies :pending?] true)
         :effects [[:quick-replies/generate
                    {:room-id room-id
                     :gen     gen
                     :text    (subs text 0 (min MAX_INPUT (count text)))}]]}
        {:state st'}))))

(defn- suggested
  "Store detected chips on the room (mirrors to clients), or clear when there
   are none. Ignores a stale result: if a newer turn-end or submit has since
   bumped :gen, this detection was for an earlier response and must not re-add
   chips under the current one (the slow-detection race)."
  [st {:keys [room-id chips gen]}]
  (when-let [room (state/get-room st room-id)]
    (when (= gen (get-in room [:quick-replies :gen]))
      (if (seq chips)
        {:state (assoc-in st [:rooms room-id :quick-replies] {:gen gen :chips (vec chips)})}
        {:state (assoc-in st [:rooms room-id :quick-replies] {:gen gen})}))))

(defn clear-on-submit
  "Chained onto :prompt/submit. Once the user sends anything, lingering chips
   are stale — drop them and bump the generation so an in-flight detection from
   the prior turn is discarded when it lands."
  [st {:keys [room-id]}]
  (when-let [qr (get-in st [:rooms room-id :quick-replies])]
    {:state (assoc-in st [:rooms room-id :quick-replies] {:gen (inc (:gen qr 0))})}))

(def handlers
  {:quick-replies/suggested suggested})

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn create-fx
  "Chip-detection effect. Runs a throwaway one-shot turn against a cheap model
   through the claude provider, pointed at a temp CLAUDE_CONFIG_DIR so the CLI
   session it leaves behind never reaches the session list. The temp dir is
   created via `make-config-dir!` and removed via `remove-config-dir!` once the
   turn ends."
  [providers {:keys [make-config-dir! remove-config-dir!]}]
  {:quick-replies/generate
   (fn [{:keys [dispatch! state]} {:keys [room-id gen text]}]
     (when-let [provider (get providers :anthropic)]
       (let [cwd        (get-in state [:rooms room-id :cwd])
             chunks     (atom [])
             config-dir (when make-config-dir! (make-config-dir!))
             cleanup!   (fn []
                          (when (and remove-config-dir! config-dir)
                            (remove-config-dir! config-dir)))
             finish!    (fn [chips]
                          (dispatch! {:type :quick-replies/suggested
                                      :room-id room-id :gen gen :chips chips}))
             {:keys [promise]}
             ((:start-turn! provider)
              (cond-> {:model   MODEL
                       :prompt  (str PROMPT_PREFIX text "\n>>>")
                       :cwd     cwd
                       ;; Text-only throwaway turn: without this the request
                       ;; carries every tool definition (~25k tokens) plus the
                       ;; Claude Code preset prompt, after every single turn.
                       :no-tools? true
                       :on-text (fn [t] (swap! chunks conj t))}
                config-dir (assoc :env {"CLAUDE_CONFIG_DIR" config-dir})))]
         (-> promise
             (.then
              (fn [result]
                (cleanup!)
                (if (:aborted result)
                  (finish! [])
                  (let [raw (or (not-empty (:result-text result))
                                (not-empty (str/join @chunks)))]
                    (finish! (parse-chips raw))))))
             (.catch
              (fn [_err]
                (cleanup!)
                (finish! [])
                nil))))))})
