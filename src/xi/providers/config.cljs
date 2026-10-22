(ns xi.providers.config
  "Which providers load, from config.edn's `:providers`
   (xi.user-config/providers) — each entry a bundled provider's id, or a map
   naming one (:id), a directory (:dir) or an npm package (:npm); the entry's
   other keys are the provider's options. Without the key every bundled
   provider loads, in resources/providers.edn's order.

   Bundled providers live in the providers/ directory next to the bundle
   (an xi-providers checkout: packages/<name>), or in $XI_PROVIDERS_DIR. An
   :npm package is installed once with `bun add` into
   ~/.cache/xi/providers/<name>@<version>/.

   Read once at startup (xi.cli); adding or removing a provider takes a
   restart, an edit to a provider's program applies on its next request."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["node:child_process" :as child-process]
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
                                             #js {:cwd dir :stdio #js ["ignore" "inherit" "inherit"]})]
          (if (and (zero? (.-status r)) (fs/existsSync (node-path/join pkg "package.json")))
            pkg
            {:error (str "could not install " spec " (bun add exited " (.-status r) ")")}))))))

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
   later provider with an id already taken is an error."
  ([] (load-providers (user-config/providers)))
  ([entries]
   (let [{bundled :manifests bundle-errors :errors} (bundled-manifests)
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
                 (update acc :providers conj (process/create manifest options))))
             {:providers [] :errors (if (some? entries) [] bundle-errors)}
             resolved))))
