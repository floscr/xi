(ns xi.ext.snippets
  "Snippets extension — serves the web client a list of insertable prompt
   snippets. Two sources:

   - global   : ~/.config/xi/snippets.edn — a vector of {:label :text} maps
                (or a {label text} map), shown in every chat.
   - project  : the :snippets vector config.edn `:projects :settings` holds for
                the room's cwd (xi.projects/snippets). Shown only in that
                project's chat.

   The web client requests the list with :snippets/web-list (roomless, carries
   the active room's cwd); the server replies :snippets/web-list-result with
   {:global [...] :project [...]}. Selecting a snippet inserts its :text into
   the compose draft (see xi.web.core :snippets/picker-insert)."
  (:require [cljs.reader :as reader]
            [xi.projects :as projects]
            [xi.user-config :as user-config]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private GLOBAL_SNIPPETS_FILE
  (.join node-path (os/homedir) ".config" "xi" "snippets.edn"))

(defn- normalize-snippets
  "Coerce a parsed value into a vec of {:label :text} maps, dropping anything
   malformed. Accepts a vector of maps ({:label/:name/:trigger} +
   {:text/:expansion/:body}) or a plain {label text} map."
  [data]
  (->> (cond
         (map? data)        (map (fn [[k v]] {:label (name k) :text v}) data)
         (sequential? data) data
         :else              nil)
       (keep (fn [s]
               (when (map? s)
                 (let [label (or (:label s) (:name s) (:trigger s))
                       text  (or (:text s) (:expansion s) (:body s))]
                   (when (and label text)
                     {:label (str label) :text (str text)})))))
       vec))

(defn- load-global-snippets
  "Read + parse ~/.config/xi/snippets.edn. Returns a vec of snippets (possibly
   empty). Unknown tagged literals are passed through untouched."
  []
  (try
    (when (fs/existsSync GLOBAL_SNIPPETS_FILE)
      (let [raw    (str (fs/readFileSync GLOBAL_SNIPPETS_FILE "utf8"))
            parsed (reader/read-string {:default (fn [_tag v] v)} raw)]
        (normalize-snippets parsed)))
    (catch :default _ nil)))

(defn- load-project-snippets
  "The project's snippets from config.edn `:projects :settings`. Returns a vec
   (possibly empty), or nil when `cwd` is unusable or the lookup fails."
  [cwd]
  (when (and (string? cwd) (seq cwd))
    (try
      (normalize-snippets (projects/snippets! (user-config/projects-spec) cwd))
      (catch :default _ nil))))

;; ── Web snippets menu (roomless) ───────────────────────────────────────────

(defn- web-list
  "Roomless: defer to the effect that loads snippets for the client's cwd."
  [_st {:keys [client-id cwd]}]
  {:effects [[:snippets/web-list-reply {:client-id client-id :cwd cwd}]]})

(defn- server-fx
  "WS-server fx: load global + project snippets and reply to the requesting
   client."
  [{:keys [send!]}]
  {:snippets/web-list-reply
   (fn [_ {:keys [client-id cwd]}]
     (send! client-id {:type    :snippets/web-list-result
                       :global  (or (load-global-snippets) [])
                       :project (or (load-project-snippets cwd) [])}))})

(def extension
  {:id              :snippets
   :handlers        {:snippets/web-list web-list}
   :server-fx       server-fx
   :roomless-events #{:snippets/web-list}})
