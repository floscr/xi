(ns xi.projects
  "Built-in project directories — the list behind `/project`, alt+p and the web
   projects page. Where the projects come from:

     :repos     single directories listed as-is (config.edn `:projects`)
     :browse    directories scanned for repos down to a depth
                (`{:dir \"~/Code\" :depth 2}`)
     remembered git repos a session ran in that neither of the above covers,
                newest first, capped by `:remember-limit`. Nothing to
                configure or run: a room created or `/cd`'d anywhere inside
                a repo records that repo's root (`visit!`), in the state
                file — config.edn is typically generated and read-only.

   The result is deduplicated, existing directories only, and ordered by last
   visit (never-visited ones keep the order above). Visits are also recorded
   for directories that aren't repos, but only to order configured projects —
   a non-repo directory is never remembered into the list.

   Config shape (validated by `parse-spec`, wired into xi.user-config):

     :projects {:browse [\"~/Code/Projects\"                ; = {:dir … :depth 1}
                         {:dir \"~/Code/Work\" :depth 3 :git? true}]
                :repos  [\"~/.config/dotfiles\"]
                :remember-limit 50
                :settings {\"~/Code/Projects/xi\"       ; per-project, by exact dir
                           {:agents-prompt \"docs/agents.md\"
                            :agents-replace true
                            :agents-ignore false
                            :snippets [{:label \"Run checks\" :text \"…\"}]}}}

   Scanning and ordering are pure over an injected `ops` map so they test
   without a filesystem; the `!` fns bind the real one."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [xi.paths :as paths]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def max-depth
  "Deepest `:depth` a browse entry may ask for — scans are synchronous."
  6)

(def default-remember-limit 50)

(def default-spec
  {:browse [] :repos [] :remember-limit default-remember-limit :settings {}})

;; ── Config spec ─────────────────────────────────────────────────────────────

(def ^:private spec-keys #{:browse :repos :remember-limit :settings})

(def ^:private setting-keys #{:agents-prompt :agents-replace :agents-ignore :snippets})

(def ^:private spec-shape
  (str "{:browse [dir | {:dir d :depth n :git? bool}] :repos [dir] :remember-limit n"
       " :settings {dir {:agents-prompt str :agents-replace bool :agents-ignore bool"
       " :snippets [{:label :text}]}}}"))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- parse-browse-entry
  "A browse entry — a dir string or `{:dir :depth :git?}` — normalized to the
   map form, or nil when invalid."
  [entry]
  (let [m (if (string? entry) {:dir entry} entry)]
    (when (and (map? m)
               (non-blank-string? (:dir m))
               (every? #{:dir :depth :git?} (keys m))
               (let [d (get m :depth 1)] (and (integer? d) (<= 1 d max-depth)))
               (boolean? (get m :git? true)))
      {:dir   (:dir m)
       :depth (get m :depth 1)
       :git?  (get m :git? true)})))

(defn- valid-settings?
  "`:settings`: a map of non-blank dir string → `{:agents-prompt str
   :agents-replace bool :agents-ignore bool :snippets [map …]}` (every key
   optional)."
  [settings]
  (and (map? settings)
       (every? (fn [[dir s]]
                 (and (non-blank-string? dir)
                      (map? s)
                      (every? setting-keys (keys s))
                      (string? (get s :agents-prompt ""))
                      (boolean? (get s :agents-replace false))
                      (boolean? (get s :agents-ignore false))
                      (let [sn (get s :snippets [])]
                        (and (sequential? sn) (every? map? sn)))))
               settings)))

(defn parse-spec
  "Validate the config's `:projects` value (nil = absent) → the normalized
   spec `{:browse [{:dir :depth :git?}] :repos [dir] :remember-limit n
   :settings {dir {…}}}` or `{:error msg}`."
  [data]
  (cond
    (nil? data)
    default-spec

    (not (map? data))
    {:error (str ":projects must be a map " spec-shape)}

    (seq (remove spec-keys (keys data)))
    {:error (str "unknown :projects key(s) "
                 (str/join " " (map pr-str (remove spec-keys (keys data))))
                 " — expected " spec-shape)}

    (not (sequential? (:browse data [])))
    {:error ":projects :browse must be a vector of dirs or {:dir :depth :git?} maps"}

    (some nil? (map parse-browse-entry (:browse data [])))
    {:error (str ":projects :browse entries must be a dir string or {:dir d :depth 1.."
                 max-depth " :git? bool}")}

    (not (and (sequential? (:repos data []))
              (every? non-blank-string? (:repos data []))))
    {:error ":projects :repos must be a vector of directory strings"}

    (not (nat-int? (get data :remember-limit default-remember-limit)))
    {:error ":projects :remember-limit must be a non-negative integer"}

    (not (valid-settings? (get data :settings {})))
    {:error (str ":projects :settings must map a directory to {:agents-prompt str "
                 ":agents-replace bool :agents-ignore bool :snippets [{:label :text}]}")}

    :else
    {:browse         (mapv parse-browse-entry (:browse data []))
     :repos          (vec (:repos data []))
     :remember-limit (get data :remember-limit default-remember-limit)
     :settings       (or (:settings data) {})}))

;; ── Pure core ───────────────────────────────────────────────────────────────

(defn scan-browse
  "Project dirs under `dir` down to `depth` levels. `ops`: `{:child-dirs
   (fn [path] [name …]) :repo? (fn [path] bool)}`.

   `:git? true` — a directory is a project when it is a repo (`:repo?`); a repo
   is never descended into, other directories are until `depth` is used up.
   `:git? false` — every directory down to `depth` is a project."
  [{:keys [child-dirs repo?]} {:keys [dir depth git?]}]
  (letfn [(walk [path level]
            (mapcat (fn [n]
                      (let [p    (str path "/" n)
                            down #(when (< level depth) (walk p (inc level)))]
                        (cond
                          (and git? (repo? p)) [p]
                          git?                 (down)
                          :else                (cons p (down)))))
                    (child-dirs path)))]
    (walk dir 1)))

(defn repo-root
  "The nearest directory at or above `cwd` that is a repo (`:repo?`), or nil.
   The search stops before `:home` and `/`, so a stray `.git` there can't turn
   every directory into one project. `ops` needs `:repo?`, `:expand`, `:home`."
  [{:keys [repo? expand home]} cwd]
  (loop [dir (expand cwd)]
    (when-not (or (= dir "/") (= dir home))
      (if (repo? dir)
        dir
        (let [i (str/last-index-of dir "/")]
          (recur (if (pos? i) (subs dir 0 i) "/")))))))

(defn list-projects
  "The ordered, distinct project directories (see the ns doc). `ops` adds
   `:dir?` (path → existing directory) and `:expand` (`~`/trailing-slash
   normalizer) to scan-browse's; `state` is `{:visits {path ms}}`."
  [{:keys [dir? repo? expand] :as ops} spec {:keys [visits]}]
  (let [existing   (fn [coll] (->> coll (map expand) (filter dir?)))
        scanned    (->> (:browse spec)
                        (map #(update % :dir expand))
                        (filter (comp dir? :dir))
                        (mapcat #(scan-browse ops %)))
        configured (vec (distinct (concat (existing (:repos spec)) scanned)))
        known      (set configured)
        remembered (->> visits
                        (remove (comp known key))
                        (sort-by val >)
                        (map key)
                        (filter repo?)
                        (take (:remember-limit spec)))]
    (->> (concat configured remembered)
         distinct
         (sort-by #(get visits % 0) >)
         vec)))

(defn settings-for
  "The `:settings` entry for the project whose directory is exactly `cwd`
   (both `~`-expanded), or nil. `ops` needs `:expand`."
  [{:keys [expand]} spec cwd]
  (let [dir (expand cwd)]
    (some (fn [[k v]] (when (= (expand k) dir) v)) (:settings spec))))

(defn resolve-prompt
  "The text of an `:agents-prompt` value: the file `prompt` names — relative
   to the project `dir`, else absolute — or `prompt` itself when no such file
   exists. `ops` needs `:read-file` (path → string, nil when not a file)."
  [{:keys [read-file]} dir prompt]
  (or (read-file (str dir "/" prompt))
      (read-file prompt)
      prompt))

(defn agents-prompt
  "`{:prompt str :replace bool}` configured for the project at `cwd`, or nil.
   `:replace` means the prompt stands in for the project's own AGENTS.md."
  [{:keys [expand] :as ops} spec cwd]
  (when-let [s (settings-for ops spec cwd)]
    (when (non-blank-string? (:agents-prompt s))
      {:prompt  (resolve-prompt ops (expand cwd) (:agents-prompt s))
       :replace (boolean (:agents-replace s))})))

(defn agents-ignore?
  "True when the project at `cwd` sets `:agents-ignore true`: the repo's own
   AGENTS.md / CLAUDE.md files are not loaded or listed."
  [ops spec cwd]
  (boolean (:agents-ignore (settings-for ops spec cwd))))

(defn snippets
  "The `:snippets` configured for the project at `cwd` (a vec, maybe empty)."
  [ops spec cwd]
  (vec (:snippets (settings-for ops spec cwd))))

(def visit-cap
  "Visit timestamps kept in the state file (the newest ones)."
  500)

(defn- prune-visits [visits]
  (if (<= (count visits) visit-cap)
    visits
    (into {} (take visit-cap) (sort-by val > visits))))

(defn visit-state
  "`state` with `path` visited at `now` (ms)."
  [state path now]
  (-> state
      (assoc-in [:visits path] now)
      (update :visits prune-visits)))

;; ── State file ──────────────────────────────────────────────────────────────

(defonce ^:private state-file-override
  ;; Test seam, like xi.user-config/set-config-file!.
  (atom nil))

(defn set-state-file!
  "Override the state file path (nil restores the default)."
  [file]
  (reset! state-file-override file))

(defn state-file []
  (or @state-file-override
      (node-path/join (os/homedir) ".config" "xi" "state" "projects.edn")))

(defn- sanitize-state [data]
  (let [data (when (map? data) data)]
    {:visits (if (map? (:visits data))
               (into {} (filter (fn [[k v]] (and (string? k) (number? v)))) (:visits data))
               {})}))

(defn read-state
  "`{:visits {path ms}}` — empty when the file is missing or unreadable (a
   `:pinned` key written by an older xi is ignored)."
  []
  (let [file (state-file)]
    (sanitize-state
     (when (fs/existsSync file)
       (try (edn/read-string (fs/readFileSync file "utf8"))
            (catch :default _ nil))))))

(defn- write-state! [state]
  (let [file (state-file)
        tmp  (str file ".tmp")]
    (fs/mkdirSync (node-path/dirname file) #js {:recursive true})
    (fs/writeFileSync tmp (str (pr-str (assoc state :version 1)) "\n"))
    (fs/renameSync tmp file)))

;; ── Real filesystem ─────────────────────────────────────────────────────────

(defn- dir? [p]
  (try (.isDirectory (fs/statSync p)) (catch :default _ false)))

(defn- repo? [p]
  (fs/existsSync (node-path/join p ".git")))

(defn- child-dirs
  "Non-hidden subdirectory names of `path` (symlinks to directories included),
   sorted. node_modules is never worth descending into."
  [path]
  (try
    (->> (.readdirSync fs path #js {:withFileTypes true})
         (keep (fn [^js d]
                 (let [n (.-name d)]
                   (when (and (not (str/starts-with? n "."))
                              (not= n "node_modules")
                              (or (.isDirectory d)
                                  (and (.isSymbolicLink d)
                                       (dir? (node-path/join path n)))))
                     n))))
         sort
         vec)
    (catch :default _ [])))

(defn- expand
  "`~`-expanded absolute path with no trailing slash."
  [p]
  (node-path/resolve (paths/expand-home p)))

(defn- read-file
  "A file's text, nil when `p` isn't a readable file (an inline prompt string
   lands here too, so any error means \"not a path\")."
  [p]
  (try (when (.isFile (fs/statSync p)) (fs/readFileSync p "utf8"))
       (catch :default _ nil)))

(def ^:private home (node-path/resolve (os/homedir)))

(def ^:private real-ops
  {:child-dirs child-dirs :repo? repo? :dir? dir? :expand expand
   :read-file read-file :home home})

;; ── Public API ──────────────────────────────────────────────────────────────

(defn list-projects!
  "The project directories for `spec` (a `parse-spec` result) on this machine."
  [spec]
  (list-projects real-ops spec (read-state)))

(defn agents-prompt!
  "`agents-prompt` on the real filesystem."
  [spec cwd]
  (agents-prompt real-ops spec cwd))

(defn agents-ignore?!
  "`agents-ignore?` on the real filesystem."
  [spec cwd]
  (agents-ignore? real-ops spec cwd))

(defn snippets!
  "`snippets` on the real filesystem."
  [spec cwd]
  (snippets real-ops spec cwd))

(defn visit!
  "Remember that a session just ran in `cwd` (room created, `/cd`). Records
   the root of the git repo that contains `cwd`, so working in a sub-directory
   counts for its repo; outside any repo it records `cwd` itself, which only
   orders configured projects (see the ns doc). Returns the recorded path, or
   nil when `cwd` isn't an existing directory other than home or `/`."
  [cwd]
  (when (non-blank-string? cwd)
    (let [p (or (repo-root real-ops cwd) (expand cwd))]
      (when (and (not (contains? #{"/" home} p)) (dir? p))
        (write-state! (visit-state (read-state) p (js/Date.now)))
        p))))
