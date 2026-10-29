(ns xi.server.files
  "Filesystem helpers for the web file browser + viewer (roomless requests).
   list-dir backs the drill-down browser; read-file backs the file tab. Both
   work on absolute paths (the browser navigates by absolute path, seeded from
   the room's cwd) and fail soft with {:error ...} so the ws reply can surface a
   status instead of throwing."
  (:require [clojure.string :as str]
            [xi.paths :as paths]
            [xi.tools.util :as tools-util]
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
   working tree (or git is unavailable).

   Uses -z (NUL-delimited) so paths with special characters (non-ASCII,
   control chars, …) come through verbatim instead of git's default quoted +
   backslash-octal form (e.g. a literal `→chroma-alias.ch` rather than the
   escaped form git prints without -z)."
  [abs]
  (try
    (let [proc (js/Bun.spawnSync
                #js ["git" "-C" abs "ls-files" "-z" "--cached" "--others"
                     "--exclude-standard"]
                #js {:stdout "pipe" :stderr "pipe"})]
      (when (zero? (.-exitCode proc))
        (->> (str/split (str (.toString (.-stdout proc))) #"\u0000")
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

(defn- git-recency-order
  "Relative paths ordered by recent git activity — working-tree changes first,
   then files touched by the last 300 commits — deduped, most-recent first.
   Used to float the files you're actually editing to the top of the finder
   (an empty query preserves this order). Returns nil outside a git tree.
   All git invocations use -z so special-char paths match the -z `git ls-files`
   listing verbatim."
  [abs]
  (letfn [(run [args]
            (try
              (let [proc (js/Bun.spawnSync
                          (into-array (concat ["git" "-C" abs] args))
                          #js {:stdout "pipe" :stderr "pipe"})]
                (when (zero? (.-exitCode proc))
                  (str/split (str (.toString (.-stdout proc))) #"\u0000")))
              (catch :default _ nil)))]
    (let [changed (run ["diff" "-z" "--name-only" "HEAD"])
          recent  (run ["log" "-z" "--name-only" "--pretty=format:"
                        "-n" "300"])]
      (when (or changed recent)
        (->> (concat changed recent)
             (remove str/blank?)
             distinct
             vec)))))

(defn list-files
  "Flat list of files under `cwd` for the fuzzy file finder, relative to it.
   Prefers `git ls-files` (so .gitignore is honoured); falls back to a recursive
   walk for non-git directories. Ordered by recent git activity (see
   `git-recency-order`) so the files you're actively editing surface first when
   the finder opens with an empty query; the rest fall back to alphabetical.
   Returns {:cwd abs :files [rel ...]} (capped at `max-files`), or
   {:cwd abs :error message} on failure."
  [cwd]
  (let [abs (.resolve node-path (or cwd (.cwd js/process)))]
    (try
      (let [files (or (git-list-files abs) (walk-list-files abs))
            rank  (into {} (map-indexed (fn [i f] [f i])
                                        (git-recency-order abs)))
            n     (count rank)
            ordered (sort-by (fn [f] [(get rank f n) f]) files)]
        {:cwd abs :files (->> ordered (take max-files) vec)})
      (catch :default e
        {:cwd abs :error (.-message e)}))))

(def ^:private max-file-bytes
  "Cap the viewer at 2 MB so a stray huge/binary file can't wedge the client."
  (* 2 1024 1024))

(def ^:private max-image-bytes
  "Images travel as base64 in the buffer (every client gets it), so they get
   a cap of their own: screenshots and renders, not raw camera dumps."
  (* 10 1024 1024))

(def ^:private image-media-types
  {"png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "webp" "image/webp"
   "svg"  "image/svg+xml"
   "bmp"  "image/bmp"
   "avif" "image/avif"
   "ico"  "image/x-icon"})

(defn image-media-type
  "The media type of `path` when its extension names an image the browser
   can show, else nil."
  [path]
  (let [ext (-> (.extname node-path (or path "")) (subs 1) str/lower-case)]
    (get image-media-types ext)))

(defn- size-label [bytes]
  (cond
    (< bytes 1024)    (str bytes " B")
    (< bytes 1048576) (str (js/Math.round (/ bytes 1024)) " KB")
    :else             (str (.toFixed (/ bytes 1048576) 1) " MB")))

(defn read-contents
  "What the viewer shows for the file at `abs`: {:text s} for a text file,
   {:data base64 :media-type mime :text note} for an image (the web client
   renders it from a data: URL like a prompt's attachment, the TUI shows the
   note), or {:error message} (a directory, too large, unreadable)."
  [abs]
  (try
    (let [st    (fs/statSync abs)
          size  (.-size st)
          image (image-media-type abs)]
      (cond
        (.isDirectory st)
        {:error "Is a directory"}

        (and image (> size max-image-bytes))
        {:error "Image too large to view (> 10 MB)"}

        image
        {:data       (.toString (fs/readFileSync abs) "base64")
         :media-type image
         :text       (str "Image · " image " · " (size-label size))}

        (> size max-file-bytes)
        {:error "File too large to view (> 2 MB)"}

        :else
        {:text (fs/readFileSync abs "utf8")}))
    (catch :default e
      {:error (.-message e)})))

(defn read-file
  "Read a file for the viewer. Returns {:path abs} merged with read-contents:
   :text, an image's :data + :media-type, or :error (missing, a directory,
   too large, or unreadable)."
  [path cwd]
  ;; path.resolve joins right-to-left; when path is absolute cwd is ignored,
  ;; which is what the browser always sends.
  (let [abs (.resolve node-path (or cwd (.cwd js/process)) (or path ""))]
    (assoc (read-contents abs) :path abs)))

(defn exists
  "Whether `path` (as inline code names it) is a file: `~` expanded, then
   relative to `cwd`, else to cwd's git root (the paths git prints), the way
   the file viewer resolves it (xi.ext.file-view). Returns {:path :exists?}
   with the path as asked, so the client can match the reply to its question."
  [path cwd]
  (let [cwd   (or cwd (.cwd js/process))
        p     (paths/expand-home (or path ""))
        file? (fn [base]
                (try (.isFile (fs/statSync (.resolve node-path base p)))
                     (catch :default _ false)))]
    {:path    path
     :exists? (boolean (or (file? cwd)
                           (some-> (tools-util/git-root cwd) file?)))}))
