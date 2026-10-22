(ns xi.providers.config
  "Which providers load, from config.edn's `:providers`
   (xi.user-config/providers) — each entry a bundled provider's id, or a map
   naming one (:id), a directory (:dir) or an npm package (:npm); the entry's
   other keys are the provider's options. Without the key every bundled
   provider loads, in resources/providers.edn's order.

   Bundled providers live in the providers/ directory next to the bundle
   (an xi-providers checkout: packages/<name>), or in $XI_PROVIDERS_DIR. An
   :npm package is installed once with `bun add` into
   ~/.cache/xi/providers/<name>@<version>/; a bundled provider whose
   dependencies don't resolve (the npm package ships none) into
   ~/.cache/xi/providers/bundled/ (see Dependencies).

   Read once at startup (xi.cli); adding or removing a provider takes a
   restart, an edit to a provider's program applies on its next request."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["node:child_process" :as child-process]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            [xi.paths :as paths]
            [xi.providers.manifest :as manifest]
            [xi.providers.process :as process]
            [xi.user-config :as user-config]))

(defn- bundle-dir
  "The directory holding the compiled main.js (target/), when known."
  []
  (when (exists? js/__dirname) js/__dirname))

(defn- read-pin []
  (try
    (when-let [d (bundle-dir)]
      (let [f (node-path/resolve d ".." "resources" "providers.edn")]
        (when (fs/existsSync f)
          (reader/read-string (fs/readFileSync f "utf8")))))
    (catch :default _ nil)))

(defn bundled-root
  "Where the bundled providers are: $XI_PROVIDERS_DIR, else providers/ next
   to the bundle."
  []
  (or (not-empty (aget js/process.env "XI_PROVIDERS_DIR"))
      (some-> (bundle-dir) (node-path/resolve ".." "providers"))))

(defn bundled-names
  "The bundled package directories in picker order: resources/providers.edn's
   :bundled, else every package under the root."
  []
  (or (seq (:bundled (read-pin)))
      (when-let [root (bundled-root)]
        (let [pkgs (node-path/join root "packages")]
          (when (fs/existsSync pkgs)
            (sort (vec (fs/readdirSync pkgs))))))))

(defn- bundled-manifests
  "{:manifests [m …] :errors [msg …]} of every bundled provider present."
  []
  (let [root (bundled-root)]
    (reduce (fn [acc n]
              (let [dir (node-path/join root "packages" n)
                    {m :manifest e :error} (manifest/read-dir dir)]
                (cond m (update acc :manifests conj m)
                      ;; a package without a manifest (the provider kit) is no provider
                      (str/includes? (str e) "no provider manifest") acc
                      :else (update acc :errors conj e))))
            {:manifests [] :errors []}
            (when root (bundled-names)))))

;; ── npm ──────────────────────────────────────────────────────────────────────

(defn- npm-name
  "\"@scope/pkg@1.2.0\" → \"@scope/pkg\"."
  [spec]
  (subs spec 0 (str/last-index-of spec "@")))

(defn npm-dir
  "Where an :npm entry's package is installed."
  [spec]
  (node-path/join (or (not-empty (aget js/process.env "XDG_CACHE_HOME"))
                      (node-path/join (os/homedir) ".cache"))
                  "xi" "providers" (str/replace spec "/" "+")))

(defn- npm-install!
  "Install `spec` into its cache directory unless it is there → the package's
   directory, or {:error msg}."
  [spec]
  (let [dir (npm-dir spec)
        pkg (node-path/join dir "node_modules" (npm-name spec))]
    (if (fs/existsSync (node-path/join pkg "package.json"))
      pkg
      (do
        (js/console.error (str "[providers] installing " spec " …"))
        (fs/mkdirSync dir #js {:recursive true})
        (when-not (fs/existsSync (node-path/join dir "package.json"))
          (fs/writeFileSync (node-path/join dir "package.json") "{\"private\": true}\n"))
        (let [^js r (child-process/spawnSync (.-execPath js/process) #js ["add" "--exact" spec]
                                             #js {:cwd dir :stdio #js ["ignore" 2 "inherit"]})]
          (if (and (zero? (.-status r)) (fs/existsSync (node-path/join pkg "package.json")))
            pkg
            {:error (str "could not install " spec " (bun add exited " (.-status r) ")")}))))))

;; ── Dependencies ───────────────────────────────────────────────────────────────
;; Xi depends on no provider's libraries. A provider's npm dependencies
;; (the Claude Agent SDK) are installed where it runs: in a checkout or on a
;; deployed host by `bun install`, for an :npm entry by `bun add`, and for a
;; bundled provider inside the npm package — whose directory is read-only in
;; a global install — into a copy under the cache, on the first start that
;; loads it.

(defn- read-package [dir]
  (try (js->clj (js/JSON.parse (fs/readFileSync (node-path/join dir "package.json") "utf8")))
       (catch :default _ nil)))

(defn- resolves?
  "Whether `dep` resolves from `dir` the way Node and Bun look it up: a
   node_modules/<dep> in `dir` or any directory above it."
  [dir dep]
  (loop [d (node-path/resolve dir)]
    (cond (fs/existsSync (node-path/join d "node_modules" dep "package.json")) true
          (= d (node-path/dirname d)) false
          :else (recur (node-path/dirname d)))))

(defn missing-deps
  "The npm dependencies of the provider in `dir` that don't resolve from it;
   the kit (@xi-agent/*) is the bundle's own and never counts."
  [dir]
  (->> (keys (get (read-package dir) "dependencies"))
       (remove #(str/starts-with? % "@xi-agent/"))
       (remove #(resolves? dir %))
       sort
       vec))

(defn- tree-hash
  "A hash of the files under `dirs` (paths and contents), node_modules left
   out: a changed provider gets a fresh copy."
  [dirs]
  (let [h (.createHash crypto "sha1")]
    (doseq [root dirs
            f (sort (.readdirSync fs root #js {:recursive true}))
            :let [p (node-path/join root f)]
            :when (and (not (str/includes? f "node_modules"))
                       (.isFile (fs/statSync p)))]
      (.update h f)
      (.update h (fs/readFileSync p)))
    (subs (.digest h "hex") 0 12)))

(defn- install-bundled!
  "A copy of the bundled provider in `dir` (and the kit next to it, which its
   imports name by relative path) under the cache, with its dependencies
   installed → the copy's provider directory, or {:error msg}. The install's
   output goes to stderr: stdout is `xi prompt`'s answer."
  [dir]
  (let [kit   (node-path/join (node-path/dirname dir) "provider-kit")
        name  (node-path/basename dir)
        root  (node-path/join (or (not-empty (aget js/process.env "XDG_CACHE_HOME"))
                                  (node-path/join (os/homedir) ".cache"))
                              "xi" "providers" "bundled"
                              (str name "-" (tree-hash (filter fs/existsSync [dir kit]))))
        copy  (node-path/join root "packages" name)
        done  (node-path/join copy ".xi-installed")]
    (if (fs/existsSync done)
      copy
      (do
        (js/console.error (str "[providers] installing the dependencies of " name
                               " (" (str/join ", " (missing-deps dir)) ") …"))
        (fs/rmSync root #js {:recursive true :force true})
        (fs/cpSync dir copy #js {:recursive true})
        (when (fs/existsSync kit)
          (fs/cpSync kit (node-path/join root "packages" "provider-kit") #js {:recursive true}))
        (let [^js r (child-process/spawnSync (.-execPath js/process) #js ["install" "--production"]
                                             #js {:cwd copy :stdio #js ["ignore" 2 "inherit"]})]
          (if (and (zero? (.-status r)) (empty? (missing-deps copy)))
            (do (fs/writeFileSync done (str (js/Date.) "\n")) copy)
            {:error (str "could not install the dependencies of " name
                         " (bun install exited " (.-status r) ")")}))))))

(defn- with-deps
  "`m` (a manifest) ready to run: its dependencies resolve, or a bundled
   one is installed into the cache (`install-bundled!`) → {:manifest m} or
   {:error msg}."
  [m bundled?]
  (let [missing (missing-deps (:dir m))
        workspace? (some #(str/starts-with? (str %) "workspace:")
                         (vals (get (read-package (:dir m)) "dependencies")))]
    (cond
      (empty? missing) {:manifest m}
      ;; a checkout of the workspace: its own install, not a copy
      (or workspace? (not bundled?))
      {:error (str "provider " (name (:id m)) " needs " (str/join ", " missing)
                   ": run `bun install` in " (if workspace? (bundled-root) (:dir m)))}
      :else
      (let [dir (install-bundled! (:dir m))]
        (if (string? dir)
          (let [{m' :manifest e :error} (manifest/read-dir dir)]
            (if m' {:manifest m'} {:error e}))
          dir)))))

;; ── Loading ──────────────────────────────────────────────────────────────────

(defn- options-of [entry]
  (if (map? entry) (dissoc entry :id :dir :npm) {}))

(defn- resolve-entry
  "One `:providers` entry → {:manifest m :options o} or {:error msg}."
  [entry bundled]
  (let [opts (options-of entry)]
    (cond
      (or (keyword? entry) (:id entry))
      (let [id (if (keyword? entry) entry (:id entry))]
        (if-let [m (some #(when (= id (:id %)) %) bundled)]
          {:manifest m :options opts}
          {:error (str "no bundled provider " id
                       (when-let [ids (seq (map :id bundled))]
                         (str " (bundled: " (str/join ", " (map name ids)) ")")))}))

      (:dir entry)
      ;; a relative :dir is relative to the config file
      (let [dir (node-path/resolve (node-path/dirname (user-config/config-file))
                                   (paths/expand-home (:dir entry)))
            {m :manifest e :error} (manifest/read-dir dir)]
        (if m {:manifest m :options opts} {:error e}))

      (:npm entry)
      (let [dir (npm-install! (:npm entry))]
        (if (string? dir)
          (let [{m :manifest e :error} (manifest/read-dir dir)]
            (if m {:manifest m :options opts} {:error e}))
          dir)))))

(defn load-providers
  "The providers to run, in picker order: {:providers [provider-map …]
   :errors [msg …]}. `entries` defaults to config.edn's `:providers`; a
   later provider with an id already taken is an error. Each one's npm
   dependencies must resolve; a bundled one's are installed (`with-deps`)."
  ([] (load-providers (user-config/providers)))
  ([entries]
   (let [{bundled :manifests bundle-errors :errors} (bundled-manifests)
         bundled? (fn [m] (boolean (some #(identical? m %) bundled)))
         resolved (if (some? entries)
                    (mapv #(resolve-entry % bundled) entries)
                    (mapv (fn [m] {:manifest m :options {}}) bundled))]
     (reduce (fn [acc {:keys [manifest options error]}]
               (cond
                 error
                 (update acc :errors conj error)

                 (some #(= (:id manifest) (:id %)) (:providers acc))
                 (update acc :errors conj (str "provider id " (name (:id manifest))
                                               " is taken; skipping " (:dir manifest)))

                 :else
                 (let [{m :manifest e :error} (with-deps manifest (bundled? manifest))]
                   (if m
                     (update acc :providers conj (process/create m options))
                     (update acc :errors conj e)))))
             {:providers [] :errors (if (some? entries) [] bundle-errors)}
             resolved))))
