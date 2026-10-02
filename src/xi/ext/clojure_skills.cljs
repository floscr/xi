(ns xi.ext.clojure-skills
  "Skills extension — detects project type and injects tool knowledge into
   the system prompt. Also provides on-demand skills loaded from
   ~/.config/xi/skills/*/SKILL.md via /skill list|load commands."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

;; ── Skill Registry ────────────────────────────────────────────────────────────

(defn- file-exists-in-cwd?
  "Check if any of the given filenames exist in cwd."
  [cwd filenames]
  (some #(fs/existsSync (.join node-path cwd %)) filenames))

(def ^:private CLOJURE_SKILL
  "# Clojure Structural Operations (clj-surgeon)

You have `clj-surgeon` available — a babashka CLI for structural Clojure refactoring via AST (rewrite-clj). ~5ms startup, returns EDN.

## Proactive Rules

- **Before reading any .clj/.cljs/.cljc file over 200 lines**, run `clj_outline` first (~50 tokens vs thousands)
- **When you see or add a `(declare ...)`**, run `clj_fix_declares` to eliminate it
- **Before manually moving forms between files**, use `clj_extract`

## Available Tools

- `clj_outline` — form boundaries, types, arglists. Use BEFORE reading large files.
- `clj_tree` — map entire project namespace tree
- `clj_deps` — transitive dependency tree for a form
- `clj_extract` — extract forms to a new namespace (dry-run by default)
- `clj_fix_declares` — eliminate forward declares by reordering (dry-run by default)
- `clj_mv` — reorder a form within a file
- `clj_rename_ns` — structural namespace rename across all files
- `clj_topo` — topological sort showing optimal form ordering

## Workflow: Split Large File

1. `clj_outline` on the file to see structure
2. `clj_deps` on target form to check dependencies
3. `clj_extract` with execute=false (dry-run) to see the plan
4. `clj_extract` with execute=true to write changes
5. Run tests — compiler catches bare refs instantly

## Notes

- All read ops are pure. Only execute=true variants write.
- After extraction, always run tests — the compiler catches bare references.")

(def ^:private skill-registry
  [{:name "clojure"
    :markers #{"bb.edn" "deps.edn" "project.clj" "squint.edn"}
    :content CLOJURE_SKILL}])

(defn- should-load?
  "Check if a skill should be loaded for the given cwd."
  [skill cwd]
  (if-let [custom-pred (:load-when skill)]
    (custom-pred cwd)
    (file-exists-in-cwd? cwd (:markers skill))))

(defn- load-skill-prompts
  "Load and concatenate all active skill prompts for cwd.
   Returns nil if no skills match."
  [cwd]
  (let [active (->> skill-registry
                    (filter #(should-load? % cwd))
                    (map :content))]
    (when (seq active)
      (str/join "\n\n---\n\n" active))))

;; ── On-Demand Skills (~/.config/xi/skills/*/SKILL.md) ─────────────────────────

(def ^:private SKILLS_DIR
  (.join node-path (os/homedir) ".config" "xi" "skills"))

(defn- parse-frontmatter
  "Parse simple YAML frontmatter from markdown content.
   Returns {:frontmatter {...} :body ...}."
  [content]
  (if (str/starts-with? content "---")
    (let [end-idx (str/index-of content "---" 3)]
      (if end-idx
        (let [fm-str (subs content 3 end-idx)
              body (str/trim (subs content (+ end-idx 3)))
              pairs (->> (str/split-lines fm-str)
                         (map str/trim)
                         (remove empty?)
                         (map #(let [colon-idx (str/index-of % ":")
                                     k (when colon-idx (str/trim (subs % 0 colon-idx)))
                                     v (when colon-idx (str/trim (subs % (inc colon-idx))))]
                                 (when (and k v) [k v])))
                         (remove nil?)
                         (into {}))]
          {:frontmatter pairs :body body})
        {:frontmatter {} :body content}))
    {:frontmatter {} :body content}))

(defn- parse-inputs
  "Scan a skill body for self-closing dynamic-input placeholder tags like
   <description /> or <image-upload />. Returns an ordered, deduped vec of
   {:name \"description\" :type :text}; <image-upload /> gets :type :image.
   Skills with inputs open a form in the web client before loading."
  [body]
  (->> (re-seq #"<([a-z][a-z0-9-]*)\s*/>" (or body ""))
       (map second)
       (distinct)
       (mapv (fn [n] {:name n
                      :type (if (= n "image-upload") :image :text)}))))

(defn- scan-skills
  "Scan SKILLS_DIR for available skill directories (symlinks included —
   anything with a SKILL.md). Returns vec of {:name :description :path :inputs}."
  []
  (if (fs/existsSync SKILLS_DIR)
    (->> (fs/readdirSync SKILLS_DIR)
         (keep (fn [dir-name]
                 (let [skill-path (.join node-path SKILLS_DIR dir-name "SKILL.md")]
                   (when (fs/existsSync skill-path)
                     (let [content (.toString (fs/readFileSync skill-path "utf-8"))
                           {:keys [frontmatter body]} (parse-frontmatter content)]
                       {:name (or (get frontmatter "name") dir-name)
                        :description (or (get frontmatter "description") "")
                        :inputs (parse-inputs body)
                        :path skill-path})))))
         (sort-by :name)
         vec)
    []))

(defn- find-skill
  "Find a skill by name and read its body. Returns the scan-skills map with
   :body added, or nil. Substitutes `$SKILL` in the body with the skill's own
   directory so skills can reference bundled helper scripts by absolute path
   (e.g. `bb $SKILL/cli.clj`) regardless of where the dir is symlinked from."
  [skill-name]
  (when-let [skill (some #(when (= (:name %) skill-name) %) (scan-skills))]
    (let [content (.toString (fs/readFileSync (:path skill) "utf-8"))
          {:keys [body]} (parse-frontmatter content)
          skill-dir (.dirname node-path (:path skill))
          body (str/replace body "$SKILL" skill-dir)]
      (assoc skill :body body))))

(defn- load-skill-by-name
  "Load a skill by name. Returns the full content (body after frontmatter) or nil."
  [skill-name]
  (:body (find-skill skill-name)))

(defn- substitute-inputs
  "Replace each <name /> placeholder in `body` with its value from `values`
   (a map of input name → text). :image inputs are blanked — the TUI has no
   attachment support in dialogs."
  [body inputs values]
  (reduce (fn [t {:keys [name type]}]
            (let [re (js/RegExp. (str "<" name "\\s*/>") "g")
                  v  (if (= type :image)
                       ""
                       (str/trim (or (get values name) "")))]
              (.replace t re v)))
          body inputs))

;; ── Web skill menu (roomless) ──────────────────────────────────────────────────

(defn- skill-web-list
  "Roomless handler: forward to the effect that scans skills for the client."
  [_st {:keys [client-id]}]
  {:effects [[:skill/web-list-reply {:client-id client-id}]]})

(defn- skill-web-get
  "Roomless handler: forward to the effect that reads one skill's body."
  [_st {:keys [client-id name]}]
  {:effects [[:skill/web-get-reply {:client-id client-id :name name}]]})

(defn- server-fx
  "WS-server fx: scan on-demand skills and reply to the requesting client."
  [{:keys [send!]}]
  {:skill/web-list-reply
   (fn [_ {:keys [client-id]}]
     (send! client-id {:type   :skill/web-list-result
                       :skills (mapv #(select-keys % [:name :description :inputs])
                                     (scan-skills))}))
   :skill/web-get-reply
   (fn [_ {:keys [client-id name]}]
     (send! client-id {:type :skill/web-get-result
                       :name name
                       :body (load-skill-by-name name)}))})

;; ── Commands ──────────────────────────────────────────────────────────────────

(defn- skill-command
  "/skill [list|load <name>] — list defers to an effect (fs scan); load
   defers to an effect (fs read + prompt submit)."
  [_st {:keys [room-id args]}]
  (let [[sub rest-args] (str/split (str/trim (or args "")) #"\s+" 2)]
    (case sub
      "load" {:effects [[:skill/load {:room-id room-id :name (or rest-args "")}]]}
      ;; default: list
      {:effects [[:skill/list {:room-id room-id}]]})))

(defn- skill-select-handler
  "Menu-selected a skill \u2192 load and auto-post it."
  [_st {:keys [room-id name]}]
  {:effects [[:skill/load {:room-id room-id :name name}]]})

(defn- skill-list-fx
  "Scan on-demand skills and open a picker menu; Enter loads the skill."
  [{:keys [dispatch!]} {:keys [room-id]}]
  (let [skills (scan-skills)]
    (if (seq skills)
      (let [items (mapv (fn [{:keys [name description]}]
                          {:label name
                           :description description
                           :event {:type :skill/select
                                   :room-id room-id
                                   :name name}})
                        skills)]
        (dispatch! {:type :ui/menu-push :room-id room-id
                    :menu {:id :skills :prompt "skill> " :items items}}))
      (dispatch! {:type :history/append :room-id room-id
                  :entry {:kind :status
                          :text (str "No skills found in " SKILLS_DIR)}}))))

(defn- skill-load-fx
  "Load a skill's content and submit it as a prompt. Skills with dynamic
   <input /> placeholders first open a :form dialog (rendered generically by
   the TUI and web from its :fields data); the entered values are substituted
   into the body before submitting. Cancelling the dialog aborts the load."
  [ask! {:keys [dispatch!] :as fx-ctx} {:keys [room-id name]}]
  (if (str/blank? name)
    (dispatch! {:type :history/append :room-id room-id
                :entry {:kind :status
                        :text "Usage: /skill load <name>\nUse /skill list to see available skills."}})
    (if-let [{:keys [body inputs] :as skill} (find-skill (str/trim name))]
      (let [text-inputs (filterv #(not= :image (:type %)) inputs)]
        (if (and ask! (seq text-inputs))
          (-> (ask! fx-ctx
                    {:room-id room-id
                     :dialog {:type    :form
                              :message (str "Skill: " (:name skill))
                              :fields  (mapv #(select-keys % [:name]) text-inputs)}})
              (.then (fn [values]
                       (when values
                         (dispatch! {:type :prompt/submit :room-id room-id
                                     :text (substitute-inputs body inputs values)
                                     :collapsed-label (str "Skill: " (:name skill))})))))
          (dispatch! {:type :prompt/submit :room-id room-id
                      :text (substitute-inputs body inputs {})
                      :collapsed-label (str "Skill: " (:name skill))})))
      (dispatch! {:type :history/append :room-id room-id
                  :entry {:kind :status
                          :text (str "Skill '" (str/trim name) "' not found. Use /skill list to see available skills.")}}))))

;; ── Extension ─────────────────────────────────────────────────────────────────

(defn create
  "Factory: the skills extension. Takes the per-surface ctx for :ask! (the
   dialog opener from ext/create-dialogs) — used to raise the :form dialog
   for skills with <input /> placeholders. Without ask! (headless) inputs
   are blanked and the skill loads directly."
  [{:keys [ask!]}]
  {:id            :skills
   :system-prompt load-skill-prompts
   :commands      [{:name "skill"
                    :description "List or load on-demand skills"
                    :handler skill-command
                    :subcommands [{:name "list" :description "List available skills"}
                                  {:name "load" :description "Load a skill and auto-post it"}]}]
   :handlers        {:skill/web-list skill-web-list
                     :skill/web-get skill-web-get
                     :skill/select  skill-select-handler}
   :server-fx       server-fx
   :roomless-events #{:skill/web-list :skill/web-get}
   :no-broadcast    #{:skill/select}
   :fx            {:skill/list skill-list-fx
                   :skill/load (partial skill-load-fx ask!)}})
