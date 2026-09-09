(ns xi.server.files
  "Filesystem helpers for the web file browser + viewer (roomless requests).
   list-dir backs the drill-down browser; read-file backs the file tab. Both
   work on absolute paths (the browser navigates by absolute path, seeded from
   the room's cwd) and fail soft with {:error ...} so the ws reply can surface a
   status instead of throwing."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn list-dir
  "List a directory's immediate children for the browser. Returns
   {:path abs :parent abs|nil :entries [{:name :dir?} ...]} — directories first,
   then files, each alphabetical. :parent is nil at the filesystem root. On
   failure returns {:path abs :error message}."
  [path cwd]
  (let [abs (.resolve node-path (or path cwd (.cwd js/process)))]
    (try
      (let [dirents (fs/readdirSync abs #js {:withFileTypes true})
            entries (->> dirents
                         (mapv (fn [^js d]
                                 {:name (.-name d)
                                  :dir? (or (.isDirectory d)
                                            ;; follow symlinks pointing at dirs
                                            (and (.isSymbolicLink d)
                                                 (try (.isDirectory (fs/statSync
                                                                     (.join node-path abs (.-name d))))
                                                      (catch :default _ false))))}))
                         (sort-by (juxt (complement :dir?) #(str/lower-case (:name %))))
                         vec)
            parent  (let [p (.dirname node-path abs)]
                      (when (not= p abs) p))]
        {:path abs :parent parent :entries entries})
      (catch :default e
        {:path abs :error (.-message e)}))))

(def ^:private max-files
  "Cap the flat file list so a huge monorepo can't wedge the fuzzy finder."
  20000)

(def ^:private walk-skip-dirs
  "Directory names pruned from the fallback recursive walk (git ls-files already
   honours .gitignore, so this only applies outside a git repo)."
  #{".git" "node_modules" ".shadow-cljs" "target" ".cache" "dist" "build"
    ".demo-home" ".next" ".cljs-cache"})

(defn- git-list-files
  "Tracked + untracked-not-ignored files under `abs`, relative to it, via
   `git ls-files`. Returns a vector of paths, or nil when `abs` isn't a git
   working tree (or git is unavailable)."
  [abs]
  (try
    (let [proc (js/Bun.spawnSync
                #js ["git" "-C" abs "ls-files" "--cached" "--others"
                     "--exclude-standard"]
                #js {:stdout "pipe" :stderr "pipe"})]
      (when (zero? (.-exitCode proc))
        (->> (str/split-lines (str (.toString (.-stdout proc))))
             (remove str/blank?)
             vec)))
    (catch :default _ nil)))

(defn- walk-list-files
  "Recursive fallback for non-git directories: all files under `abs` (relative),
   skipping `walk-skip-dirs`. Stops once `max-files` is reached."
  [abs]
  (let [out (volatile! [])]
    (letfn [(walk [dir rel]
              (when (< (count @out) max-files)
                (doseq [^js d (try (fs/readdirSync dir #js {:withFileTypes true})
                                   (catch :default _ #js []))
                        :while (< (count @out) max-files)]
                  (let [name (.-name d)
                        child-rel (if (str/blank? rel) name (str rel "/" name))]
                    (cond
                      (.isDirectory d)
                      (when-not (contains? walk-skip-dirs name)
                        (walk (.join node-path dir name) child-rel))
                      (.isFile d)
                      (vswap! out conj child-rel))))))]
      (walk abs "")
      @out)))

(defn list-files
  "Flat list of files under `cwd` for the fuzzy file finder, relative to it.
   Prefers `git ls-files` (so .gitignore is honoured); falls back to a recursive
   walk for non-git directories. Returns {:cwd abs :files [rel ...]} (capped at
   `max-files`, sorted), or {:cwd abs :error message} on failure."
  [cwd]
  (let [abs (.resolve node-path (or cwd (.cwd js/process)))]
    (try
      (let [files (or (git-list-files abs) (walk-list-files abs))]
        {:cwd abs :files (->> files sort (take max-files) vec)})
      (catch :default e
        {:cwd abs :error (.-message e)}))))

(def ^:private max-file-bytes
  "Cap the viewer at 2 MB so a stray huge/binary file can't wedge the client."
  (* 2 1024 1024))

(defn read-file
  "Read a file's UTF-8 contents for the viewer. Returns {:path abs :text ...} or
   {:path abs :error message} (missing, a directory, too large, or unreadable)."
  [path cwd]
  ;; path.resolve joins right-to-left; when path is absolute cwd is ignored,
  ;; which is what the browser always sends.
  (let [abs (.resolve node-path (or cwd (.cwd js/process)) (or path ""))]
    (try
      (let [st (fs/statSync abs)]
        (cond
          (.isDirectory st) {:path abs :error "Is a directory"}
          (> (.-size st) max-file-bytes) {:path abs :error "File too large to view (> 2 MB)"}
          :else {:path abs :text (fs/readFileSync abs "utf8")}))
      (catch :default e
        {:path abs :error (.-message e)}))))
