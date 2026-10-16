(ns xi.session.claude-store
  "The Claude CLI's transcripts (`~/.claude/projects/<cwd>/<id>.jsonl`) as a
   provider session store: how a conversation edit in Xi translates to the
   JSONL the CLI resumes from (design: tasks/session-store.md). A provider
   declares it as its `:store`; xi.agent's `:session/rewind` effect calls it.
   The fake provider writes the same format and shares it."
  (:require [clojure.string :as str]
            [xi.session :as session]
            [xi.util :as util]
            ["node:fs" :as fs]))

;; ── Cut point (pure) ─────────────────────────────────────────────────────────

(defn- entry-texts [entry]
  (let [content (get-in entry [:message :content])]
    (cond
      (string? content)     [content]
      (sequential? content) (keep #(when (= "text" (:type %)) (:text %)) content)
      :else                 nil)))

(defn prompt-text
  "The typed text of a transcript entry that is a user prompt, else nil. Tool
   results, CLI-written meta lines (`isMeta`: image notes, skill bodies),
   sidechains, compaction summaries and interrupt markers are not prompts."
  [entry]
  (when (and (= "user" (:type entry))
             (not (:isMeta entry))
             (not (:isSidechain entry))
             (not (:isCompactSummary entry)))
    (let [texts (entry-texts entry)
          text  (str/join "\n" texts)]
      (when (and (seq texts)
                 (not-any? util/cli-interrupt-marker? texts)
                 (not (str/starts-with? text "The conversation history")))
        (str/trim (util/transcript-prompt-text text))))))

(defn- before-queue-bookkeeping
  "Step `i` back over the queue-operation lines the CLI writes just before a
   prompt: they belong to the dropped prompt."
  [entries i]
  (if (and (pos? i) (= "queue-operation" (:type (nth entries (dec i)))))
    (recur entries (dec i))
    i))

(defn rewind-cut
  "Index of the first entry to drop so `entries` keep exactly the prompts
   `kept` (typed texts, in order) and their turns: the first prompt after the
   last kept one. (count entries) keeps everything. nil when `kept` isn't an
   in-order subsequence of the transcript's prompts — a prompt that never
   reached the CLI, or a different conversation — so it can't be cut safely.
   Transcript prompts missing from `kept` (e.g. an auto-resume \"continue\")
   are skipped over."
  [entries kept]
  (loop [prompts (keep-indexed (fn [i e] (when-let [t (prompt-text e)] [i t])) entries)
         kept    (map str/trim kept)]
    (cond
      (empty? kept)    (before-queue-bookkeeping
                        entries
                        (if-let [[i] (first prompts)] i (count entries)))
      (empty? prompts) nil
      (= (second (first prompts)) (first kept)) (recur (rest prompts) (rest kept))
      :else            (recur (rest prompts) kept))))

;; ── Operations ───────────────────────────────────────────────────────────────

(defn- parse-line [line]
  (try (js->clj (js/JSON.parse line) :keywordize-keys true)
       (catch :default _e nil)))

(defn- write-atomic! [path content]
  (let [tmp (str path ".rewind-tmp")]
    (fs/writeFileSync tmp content "utf8")
    (fs/renameSync tmp path)))

(defn rewind!
  "Cut the transcript of `ref` ({:cwd :session-id}) in place so it keeps the
   prompts in :keep-prompts (typed texts, in order) and their turns; the
   session id stays. Keeping none removes the transcript. Promise of
   {:status :ok | :unchanged | :emptied | :missing | :mismatch | :error}.
   The CLI must not be writing it: xi.agent awaits the turn's exit first."
  [{:keys [cwd session-id]} {:keys [keep-prompts]}]
  (js/Promise.resolve
   (try
     (let [path (when session-id (session/transcript-path cwd session-id))]
       (if-not (and path (fs/existsSync path))
         {:status :missing}
         (let [lines   (vec (remove str/blank? (str/split-lines (fs/readFileSync path "utf8"))))
               entries (mapv parse-line lines)
               cut     (rewind-cut entries keep-prompts)]
           (cond
             (nil? cut)            {:status :mismatch}
             (empty? keep-prompts) (do (fs/unlinkSync path) {:status :emptied})
             (= cut (count lines)) {:status :unchanged}
             :else
             (do (write-atomic! path (str (str/join "\n" (subvec lines 0 cut)) "\n"))
                 {:status :ok :dropped (- (count lines) cut)})))))
     (catch :default e
       {:status :error :message (.-message e)}))))

(def store
  {:rewind! rewind!})
