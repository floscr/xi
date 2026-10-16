(ns xi.ext.clj-fs
  "The `babashka.fs` SCI namespace of the clj sandbox (aliased `fs` by the
   prelude, xi.ext.clj): the part of babashka.fs scripts reach for, on path
   strings instead of java.nio Paths. Every file access goes through the
   `read` / `write` resolvers of xi.ext.clj, the gates the builtin helpers
   use; the pure path fns touch nothing."
  (:require [clojure.string :as str]
            [xi.paths :as paths]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- stat-of [resolved nofollow?]
  (try (if nofollow? (fs/lstatSync resolved) (fs/statSync resolved))
       (catch :default _ nil)))

(defn- existing-dir? [resolved nofollow?]
  (boolean (some-> (stat-of resolved nofollow?) .isDirectory)))

(defn- join-path
  "babashka.fs/path: later absolute segments replace the ones before, like
   java.nio's Path.resolve."
  [parts]
  (reduce (fn [acc p]
            (let [p (str p)]
              (if (or (nil? acc) (node-path/isAbsolute p)) p (node-path/join acc p))))
          nil parts))

(defn- parent [p]
  (let [p (str/replace (str p) #"(?<=.)/+$" "")]
    (when (and (str/includes? p "/") (not= p "/"))
      (node-path/dirname p))))

(defn- extension [p]
  (let [e (node-path/extname (str p))]
    (when (seq e) (subs e 1))))

(defn- strip-ext [p]
  (let [p (str p) e (node-path/extname p)]
    (subs p 0 (- (count p) (count e)))))

(defn- glob-matcher [pattern]
  (if (exists? js/Bun)
    (let [g (js/Bun.Glob. pattern)] #(.match g %))
    #(node-path/matchesGlob % pattern)))

(defn- glob-scan
  "Paths under the resolved dir `root` matching `pattern`, relative to it;
   directories included, dotfiles only with `hidden?` (babashka.fs/glob).
   Java's `**` also crosses directories mid-segment (`**.clj`), Bun's and
   Node's only as a whole segment: `**x` becomes `**/*x`."
  [root pattern hidden?]
  (let [pattern (str/replace pattern #"\*\*(?!/)" "**/*")]
    (if (exists? js/Bun)
      (js/Array.from (.scanSync (js/Bun.Glob. pattern)
                                #js {:cwd root :dot hidden? :onlyFiles false}))
      (cond->> (js/Array.from (fs/globSync pattern #js {:cwd root}))
        (not hidden?) (remove #(some (fn [seg] (str/starts-with? seg "."))
                                     (str/split % #"/")))))))

(defn- access? [resolved mode]
  (try (fs/accessSync resolved mode) true (catch :default _ false)))

(defn- move!
  "rename, falling back to copy + delete across filesystems (/tmp is often
   its own mount)."
  [from to]
  (try (fs/renameSync from to)
       (catch :default e
         (if (= "EXDEV" (.-code e))
           (do (fs/cpSync from to #js {:recursive true :errorOnExist true :force false})
               (fs/rmSync from #js {:recursive true :force true}))
           (throw e)))))

(defn- into-dir
  "A copy / move target: inside `dest` when it is an existing directory (a
   symlink to one only unless `nofollow?`)."
  [src dest nofollow?]
  (if (existing-dir? dest nofollow?) (node-path/join dest (node-path/basename src)) dest))

(defn sci-namespace
  "`read` / `write` resolve and gate a path for helper `op`, as
   xi.ext.clj/resolve-read and resolve-write (`write` takes {:nofollow? true}
   for entry-level ops); `cwd` is the room's working dir."
  [{:keys [read write cwd]}]
  (let [entry  (fn [p op]
                 ;; the directory entry itself: its parent dir is what is read
                 (let [abs (node-path/resolve (cwd) (paths/expand-home (str p)))]
                   (if (= abs (node-path/dirname abs))
                     (read abs op)
                     (node-path/join (read (node-path/dirname abs) op)
                                     (node-path/basename abs)))))
        stat   (fn [p op & [{:keys [nofollow-links]}]]
                 (stat-of (if nofollow-links (entry p op) (read p op)) nofollow-links))]
    {;; ── Paths (no file access) ──
     'path        (fn [& parts] (join-path parts))
     'file        (fn [& parts] (join-path parts))
     'file-name   (fn [p] (node-path/basename (str p)))
     'parent      parent
     'extension   extension
     'strip-ext   strip-ext
     'split-ext   (fn [p] [(strip-ext p) (extension p)])
     'absolute?   (fn [p] (node-path/isAbsolute (str p)))
     'relative?   (fn [p] (not (node-path/isAbsolute (str p))))
     'absolutize  (fn [p] (node-path/resolve (cwd) (paths/expand-home (str p))))
     'normalize   (fn [p] (node-path/normalize (str p)))
     'relativize  (fn [base other] (node-path/relative (str base) (str other)))
     'components  (fn [p] (vec (remove empty? (str/split (str p) #"/"))))
     'expand-home (fn [p] (paths/expand-home (str p)))
     'hidden?     (fn [p] (str/starts-with? (node-path/basename (str p)) "."))
     'home        (fn [] (os/homedir))
     'cwd         (fn [] (cwd))
     'temp-dir    (fn [] (os/tmpdir))
     'windows?    (fn [] false)

     ;; ── Reads ──
     'exists?       (fn [p & [o]] (some? (stat p 'fs/exists? o)))
     'directory?    (fn [p & [o]] (boolean (some-> (stat p 'fs/directory? o) .isDirectory)))
     'regular-file? (fn [p & [o]] (boolean (some-> (stat p 'fs/regular-file? o) .isFile)))
     'sym-link?     (fn [p] (boolean (some-> (stat-of (entry p 'fs/sym-link?) true)
                                             .isSymbolicLink)))
     'readable?     (fn [p] (access? (read p 'fs/readable?) (.-R_OK fs/constants)))
     'writable?     (fn [p] (access? (read p 'fs/writable?) (.-W_OK fs/constants)))
     'executable?   (fn [p] (access? (read p 'fs/executable?) (.-X_OK fs/constants)))
     'size          (fn [p] (.-size (fs/statSync (read p 'fs/size))))
     'canonicalize  (fn [p] (read p 'fs/canonicalize))
     'read-all-lines (fn [p] (str/split-lines (fs/readFileSync (read p 'fs/read-all-lines) "utf8")))
     'list-dir      (fn [dir & [filt]]
                      (let [names (sort (fs/readdirSync (read dir 'fs/list-dir)))
                            keep? (cond (string? filt) (glob-matcher filt)
                                        (fn? filt)     nil
                                        :else          (constantly true))
                            paths (->> names
                                       (filter #(or (nil? keep?) (keep? %)))
                                       (mapv #(node-path/join (str dir) %)))]
                        (if (fn? filt) (filterv filt paths) paths)))
     'glob          (fn [root pattern & [{:keys [hidden]}]]
                      (->> (glob-scan (read root 'fs/glob) (str pattern) (boolean hidden))
                           (map #(node-path/join (str root) %))
                           sort vec))
     'which         (fn [cmd]
                      (if (exists? js/Bun)
                        (js/Bun.which (str cmd))
                        (some (fn [dir]
                                (let [p (node-path/join dir (str cmd))]
                                  (when (access? p (.-X_OK fs/constants)) p)))
                              (str/split (or (aget js/process.env "PATH") "") #":"))))

     ;; ── Writes ──
     'create-dirs (fn [p] (fs/mkdirSync (write p 'fs/create-dirs) #js {:recursive true}) (str p))
     'create-dir  (fn [p] (fs/mkdirSync (write p 'fs/create-dir)) (str p))
     'create-file (fn [p] (fs/writeFileSync (write p 'fs/create-file) "" #js {:flag "wx"}) (str p))
     'create-temp-dir (fn [& [{:keys [prefix]}]]
                        (fs/mkdtempSync (node-path/join (os/tmpdir) (or prefix "xi-clj-"))))
     'write-lines (fn [p lines & [{:keys [append]}]]
                    (fs/writeFileSync (write p 'fs/write-lines)
                                      (apply str (map #(str % "\n") lines))
                                      #js {:flag (if append "a" "w")})
                    (str p))
     ;; delete / move act on the directory entry: a final-component symlink is
     ;; removed or renamed as a link, never followed (like the rm / mv helpers).
     'delete      (fn [p]
                    (let [f (write p 'fs/delete {:nofollow? true})]
                      (if (some-> (stat-of f true) .isDirectory)
                        (fs/rmdirSync f)
                        (fs/unlinkSync f))
                      nil))
     'delete-if-exists (fn [p]
                         (let [f (write p 'fs/delete-if-exists {:nofollow? true})
                               s (stat-of f true)]
                           (cond (nil? s)         false
                                 (.isDirectory s) (do (fs/rmdirSync f) true)
                                 :else            (do (fs/unlinkSync f) true))))
     'delete-tree (fn [p]
                    (fs/rmSync (write p 'fs/delete-tree {:nofollow? true})
                               #js {:recursive true :force true})
                    nil)
     'copy        (fn [src dest & [{:keys [replace-existing]}]]
                    (let [from (read src 'fs/copy)
                          to   (into-dir from (write dest 'fs/copy) false)]
                      (fs/copyFileSync from to (if replace-existing 0 (.-COPYFILE_EXCL fs/constants)))
                      to))
     ;; .git is never copied: its hooks and config would run.
     'copy-tree   (fn [src dest & [{:keys [replace-existing]}]]
                    (let [from (read src 'fs/copy-tree)
                          to   (write dest 'fs/copy-tree)]
                      (fs/mkdirSync to #js {:recursive true})
                      (fs/cpSync from to
                                 #js {:recursive true
                                      :force (boolean replace-existing)
                                      :errorOnExist (not replace-existing)
                                      :filter (fn [s _] (not= ".git" (node-path/basename s)))})
                      (str dest)))
     'move        (fn [src dest & [{:keys [replace-existing]}]]
                    (let [from (write src 'fs/move {:nofollow? true})
                          to   (into-dir from (write dest 'fs/move {:nofollow? true}) true)]
                      (when (and (not replace-existing) (stat-of to true))
                        (throw (ex-info (str "fs/move: target exists: " to
                                             " (pass {:replace-existing true})") {})))
                      (move! from to)
                      to))}))
