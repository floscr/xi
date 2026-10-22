(ns xi.providers.manifest
  "A provider's manifest (docs/provider-protocol.md): the `xi` key of the
   package.json in its directory, else a provider.edn there. Read once when
   the providers load (xi.providers.config); the program it names is spawned
   per request (xi.providers.process).

   Normalized shape:
     {:id kw :label str :dir abs-path :command [str …]
      :routes {:prefixes [str] :ids [str] :default? bool}
      :models [id …]|nil      ; static list: no `models` request
      :side-turns? bool :base-prompt? bool :transcripts :host|:self
      :capabilities #{:models :usage :login}
      :login {:name :label}|nil :prompt-note str|nil}"
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn- read-json [f]
  (js->clj (js/JSON.parse (fs/readFileSync f "utf8"))))

(defn- string-vec [v]
  (when (sequential? v) (vec (filter string? v))))

(defn- get-any
  "The first of `ks` present in `m` (JSON camelCase or EDN keyword)."
  [m & ks]
  (when-let [k (some #(when (contains? m %) %) ks)]
    (get m k)))

(defn normalize
  "`raw` (a manifest map with string or keyword keys) checked and normalized
   for the provider in `dir` → {:manifest m} or {:error msg}."
  [raw dir]
  (let [m       (into {} (map (fn [[k v]] [(keyword (name k)) v])) raw)
        id      (some-> (:id m) name not-empty keyword)
        command (string-vec (:command m))
        routes  (into {} (map (fn [[k v]] [(keyword (name k)) v])) (:routes m))
        login   (some->> (:login m) (into {} (map (fn [[k v]] [(keyword (name k)) v]))))]
    (cond
      (not (map? raw))         {:error "the manifest is not a map"}
      (nil? id)                {:error "the manifest has no :id"}
      (empty? command)         {:error (str "provider " (name id) " has no :command")}
      (and login (not (string? (:name login))))
      {:error (str "provider " (name id) ": :login needs a :name")}
      :else
      {:manifest
       (cond-> {:id           id
                :label        (or (:label m) (name id))
                :dir          dir
                :command      command
                :routes       {:prefixes (or (string-vec (:prefixes routes)) [])
                               :ids      (or (string-vec (:ids routes)) [])
                               :default? (boolean (get-any routes :default :default?))}
                :side-turns?  (boolean (get-any m :sideTurns :side-turns?))
                ;; Xi's base prompt rides along unless the provider brings its own
                :base-prompt? (not (false? (get-any m :basePrompt :base-prompt?)))
                :transcripts  (if (= "self" (some-> (:transcripts m) name)) :self :host)
                :capabilities (into #{} (map keyword) (:capabilities m))}
         (sequential? (:models m))                 (assoc :models (string-vec (:models m)))
         login                                     (assoc :login {:name  (:name login)
                                                                  :label (or (:label login) (:name login))})
         (string? (get-any m :promptNote :prompt-note)) (assoc :prompt-note (get-any m :promptNote :prompt-note)))})))

(defn read-dir
  "The manifest of the provider in `dir` → {:manifest m} or {:error msg}:
   package.json's `xi` key, else provider.edn."
  [dir]
  (let [dir  (node-path/resolve dir)
        pkg  (node-path/join dir "package.json")
        edn  (node-path/join dir "provider.edn")]
    (try
      (cond
        (and (fs/existsSync pkg) (contains? (read-json pkg) "xi"))
        (normalize (get (read-json pkg) "xi") dir)

        (fs/existsSync edn)
        (normalize (reader/read-string (fs/readFileSync edn "utf8")) dir)

        (not (fs/existsSync dir))
        {:error (str "no provider at " dir)}

        :else
        {:error (str "no provider manifest in " dir
                     " (package.json with an \"xi\" key, or provider.edn)")})
      (catch :default e
        {:error (str "unreadable provider manifest in " dir ": " (.-message e))}))))

(defn spec
  "The handler-free part clients route and render with (xi.providers.routing)."
  [{:keys [id label routes side-turns? prompt-note login models]}]
  (cond-> {:id id :label label :routes routes :side-turns? side-turns?}
    prompt-note (assoc :prompt-note prompt-note)
    login       (assoc :login-name (:name login))
    (seq models) (assoc :models models)))

(defn describe
  "One line naming a manifest's provider and where it comes from."
  [{:keys [id dir]}]
  (str (name id) " (" (str/replace dir (or (aget js/process.env "HOME") "\u0000") "~") ")"))
