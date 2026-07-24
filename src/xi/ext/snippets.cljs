(ns xi.ext.snippets
  "Snippets extension — serves the web client a list of insertable prompt
   snippets. Two sources:

   - global   : ~/.config/xi/snippets.edn — a vector of {:label :text} maps
                (or a {label text} map), shown in every chat.
   - project  : a :snippets vector in the dotfiles profile whose :dir matches
                the room's cwd, fetched via `bb profile:snippets <cwd>`
                (mirrors profile:agents-prompt / profile:review-prompt). Shown
                only in that project's chat.

   The web client requests the list with :snippets/web-list (roomless, carries
   the active room's cwd); the server replies :snippets/web-list-result with
   {:global [...] :project [...]}. Selecting a snippet inserts its :text into
   the compose draft (see xi.web.core :snippets/picker-insert)."
  (:require [cljs.reader :as reader]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private GLOBAL_SNIPPETS_FILE
  (.join node-path (os/homedir) ".config" "xi" "snippets.edn"))

(def ^:private BB_EDN
  (str (aget js/process.env "HOME") "/.config/dotfiles/modules/scripts/bb.edn"))

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
  "Run `bb profile:snippets <cwd>` and parse its JSON output into snippets.
   Returns a vec (possibly empty) or nil when there is no matching profile."
  [cwd]
  (when (and (string? cwd) (seq cwd))
    (try
      (let [proc (js/Bun.spawnSync
                  #js ["bb" "--config" BB_EDN "profile:snippets" cwd]
                  #js {:stdout "pipe" :stderr "pipe" :timeout 10000})]
        (when (zero? (.-exitCode proc))
          (let [stdout (str (.toString (.-stdout proc)))
                parsed (js->clj (js/JSON.parse stdout) :keywordize-keys true)]
            (normalize-snippets parsed))))
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
