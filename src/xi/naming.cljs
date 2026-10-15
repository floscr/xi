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
       ─► replaces the nil/provisional [:session :name] with the model title.

   A rename (:session/name-set) sets a real name; :session/suggest-name
   titles an existing session from an excerpt of its conversation."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.util :as util]))

(def ^:private TITLE_MODEL "claude-haiku-4-5-20251001")

(def ^:private TITLE_PROMPT_PREFIX
  (str "Write a concise title (3-6 words, Title Case, no surrounding quotes, "
       "no trailing punctuation) that summarizes the following request for a "
       "coding-session list. Reply with the title only — no preamble.\n\n"))

(def ^:private MAX_INPUT 2000)

(def suggestion-styles
  "The kinds of name :session/suggest-name asks for, one per line, in order."
  [:short :descriptive :topic])

(def ^:private SUGGEST_PROMPT
  (str "Suggest three titles for the coding session excerpted below, for a "
       "session list. Title Case, no surrounding quotes, no trailing "
       "punctuation. Exactly three lines, in this order:\n"
       "1. short: 2-4 words\n"
       "2. descriptive: 5-8 words\n"
       "3. topic: the project area, a colon, then the specifics, 3-6 words "
       "(e.g. Auth: Flaky Test Fix)\n"
       "Reply with the three titles only, one per line, without the numbers "
       "or labels."))

(defn suggest-prompt
  "The :session/suggest-name prompt for an excerpt; `exclude` lists names
   already shown, so \"More\" brings new ones."
  [input exclude]
  (str SUGGEST_PROMPT
       (when (seq exclude)
         (str "\nDon't repeat any of these: " (str/join "; " exclude) "."))
       "\n\n" input))

(def ^:private SUGGEST_HEAD 1500)
(def ^:private SUGGEST_TAIL 2500)

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

(defn parse-suggestions
  "The titles in a :session/suggest-name reply, numbering and style labels
   stripped, as [{:name :style}] (styles from `suggestion-styles` by line)."
  [s]
  (->> (str/split-lines (or s ""))
       (map #(-> %
                 (str/replace #"^\s*(?:[-*•]|\d+[.)])\s*" "")
                 (str/replace #"(?i)^(?:short|descriptive|topic)\s*[:—-]\s*" "")
                 clean-title))
       (remove nil?)
       distinct
       (take (count suggestion-styles))
       (map (fn [style n] {:name n :style style}) suggestion-styles)
       vec))

(defn suggestion-input
  "A bounded excerpt of a session's messages (xi.session/conversation shape)
   for :session/suggest-name: the opening message plus as much of the latest
   exchange as fits, so a long session costs no more than a short one. nil
   without messages."
  [messages]
  (when (seq messages)
    (let [clip (fn [s n]
                 (let [s (str/trim (str s))]
                   (if (> (count s) n) (str (subs s 0 n) "…") s)))
          line (fn [{:keys [role text]} n] (str role ": " (clip text n)))
          tail (loop [ms (reverse (rest messages)) acc () n 0]
                 (let [l (some-> (first ms) (line 600))]
                   (if (and l (<= (+ n (count l)) SUGGEST_TAIL))
                     (recur (rest ms) (cons l acc) (+ n (count l)))
                     acc)))]
      (str/join "\n\n" (concat [(line (first messages) SUGGEST_HEAD)]
                               (when (< (count tail) (dec (count messages))) ["…"])
                               tail)))))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn maybe-generate-title
  "Chained onto :prompt/submit. On the first user message of an unnamed (or
   only provisionally named) session, kick off out-of-band title generation.
   :title-pending? guards against a queued second message firing a second
   turn. A provisional name counts as unnamed, so after a failed title turn
   (which clears :title-pending?, see `title-generated`) or a first-message
   retry (see `rearm-titling`) the next prompt regenerates.

   Immediately sets a *provisional* name derived from the first prompt (via
   `util/session-title`) so the UI shows a snippet of the request instead of
   the New-session placeholder while the model title is still generating.
   :title-provisional? marks it so `title-generated` may overwrite it once the
   real title lands."
  [st {:keys [room-id text]}]
  (when-let [room (state/get-room st room-id)]
    (when (and (string? text)
               (seq (str/trim text))
               (or (nil? (get-in room [:session :name]))
                   (get-in room [:agent :title-provisional?]))
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
   with a large first message), so without this the
   provisional name would linger on disk until some later turn-end synced the
   room — which for a one-shot session may never happen. :session/sync
   no-ops until the session has a provider-session-id, so an early landing is
   still safely persisted by the following turn-end."
  [st {:keys [room-id title]}]
  (when-let [room (state/get-room st room-id)]
    (if (and title
             (or (nil? (get-in room [:session :name]))
                 (get-in room [:agent :title-provisional?])))
      {:state   (-> st
                    (assoc-in [:rooms room-id :session :name] title)
                    (update-in [:rooms room-id :agent] dissoc
                               :title-provisional? :title-pending?))
       :effects [[:session/sync {:room-id room-id}]]}
      ;; Failed/empty title turn (title nil) or a real name landed meanwhile:
      ;; drop the pending guard so a later prompt may regenerate while the
      ;; name is still provisional (see maybe-generate-title).
      {:state (update-in st [:rooms room-id :agent] dissoc :title-pending?)})))

(defn rearm-titling
  "Make a room's session title regenerable again: clear the :title-pending?
   guard and mark any existing name provisional, so the next submitted first
   message kicks off a fresh title turn whose result overwrites it. Used when
   history is truncated to before the first user message (bubble retry / edit
   / delete of the first message) — the conversation restarts, so the title
   should too."
  [room]
  (cond-> (update room :agent dissoc :title-pending?)
    (get-in room [:session :name])
    (assoc-in [:agent :title-provisional?] true)))

(defn- name-set
  "A name the user chose (rename, xi.server.room-manager/session-rename): a
   real name, so a title turn still in flight never overwrites it."
  [st {:keys [room-id name]}]
  (when (and (state/get-room st room-id) (string? name))
    {:state (-> st
                (assoc-in [:rooms room-id :session :name] name)
                (update-in [:rooms room-id :agent] dissoc :title-provisional?))}))

(def handlers
  {:session/title-generated title-generated
   :session/name-set        name-set})

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn turn-stats
  "What a finished side turn cost, for the rename dialog's status line:
   {:model :input-tokens :output-tokens :cost :ms}. Input counts cached
   prompt tokens too."
  [result ms]
  (let [{:keys [input_tokens output_tokens cache_read_input_tokens
                cache_creation_input_tokens]} (:usage result)]
    {:model         TITLE_MODEL
     :input-tokens  (+ (or input_tokens 0) (or cache_read_input_tokens 0)
                       (or cache_creation_input_tokens 0))
     :output-tokens (or output_tokens 0)
     :cost          (:cost result)
     :ms            ms}))

(defn- side-turn!
  "One throwaway turn on `provider` (TITLE_MODEL, no tools, no thinking, in a
   temp CLAUDE_CONFIG_DIR). Resolves to {:text :stats}, or nil when the turn
   failed, errored or was aborted — an errored turn's result text is the
   failure notice (auth expired, billing, …), never a title."
  [provider {:keys [make-config-dir! remove-config-dir!]} cwd prompt]
  (let [chunks     (atom [])
        started    (js/Date.now)
        config-dir (when make-config-dir! (make-config-dir!))
        cleanup!   (fn []
                     (when (and remove-config-dir! config-dir)
                       (remove-config-dir! config-dir)))
        {:keys [promise]}
        ((:start-turn! provider)
         (cond-> {:model   TITLE_MODEL
                  :prompt  prompt
                  :cwd     cwd
                  ;; Title generation is a throwaway text-only turn: skip
                  ;; the MCP tool bridge so this concurrent CLI subprocess
                  ;; doesn't race the first user turn's tool handshake
                  ;; (which surfaced as "No such tool available").
                  :no-tools? true
                  :no-thinking? true
                  :on-text (fn [t] (swap! chunks conj t))}
           config-dir (assoc :env {"CLAUDE_CONFIG_DIR" config-dir})))]
    (-> promise
        (.then (fn [result]
                 (cleanup!)
                 (when-not (or (:aborted result) (:is-error result) (:fatal-error result))
                   {:text  (or (not-empty (:result-text result))
                               (not-empty (str/join @chunks)))
                    :stats (turn-stats result (- (js/Date.now) started))})))
        (.catch (fn [_err] (cleanup!) nil)))))

(defn create-fx
  "Title effects, each a throwaway one-shot turn against a cheap model through
   the claude provider (`side-turn!`). The temp CLAUDE_CONFIG_DIR is created
   via `make-config-dir!` and torn down via `remove-config-dir!`.
   :session/suggest-name reads a saved session through `session-conversation`
   (session-id → {:cwd :messages}, xi.session/conversation shape); without it
   there are never suggestions."
  [providers {:keys [session-conversation] :as opts}]
  {:session/generate-title
   (fn [{:keys [dispatch! state]} {:keys [room-id text]}]
     (when-let [provider (get providers :anthropic)]
       (-> (side-turn! provider opts (get-in state [:rooms room-id :cwd])
                       (str TITLE_PROMPT_PREFIX (subs text 0 (min MAX_INPUT (count text)))))
           ;; A nil title clears the pending guard so a retry can regenerate.
           (.then (fn [res]
                    (dispatch! {:type :session/title-generated
                                :room-id room-id :title (clean-title (:text res))}))))))

   :session/suggest-name
   (fn [{:keys [dispatch!]} {:keys [client-id session-id exclude]}]
     (let [reply!   (fn [res]
                      (dispatch! {:type :session/name-suggested :client-id client-id
                                  :session-id session-id
                                  :names (parse-suggestions (:text res))
                                  :stats (:stats res)}))
           provider (get providers :anthropic)
           {:keys [cwd messages]} (when session-conversation
                                    (session-conversation session-id))
           input    (suggestion-input messages)]
       (if (and provider input)
         (.then (side-turn! provider opts cwd (suggest-prompt input exclude)) reply!)
         (reply! nil))))})
