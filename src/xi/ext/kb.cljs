(ns xi.ext.kb
  "Knowledge base extension — search, get and store entries via the `kb`
   CLI. Pure data + tool exec fns; the CLI call is the contained edge.")

(defn- run-kb
  "Run the kb CLI in cwd. Returns a promise of {:content … :is-error?}."
  [args cwd]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 (clj->js (cons "kb" args))
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd (or cwd (.cwd js/process))})]
       (-> (js/Promise.all #js [(.text (.-stdout proc)) (.text (.-stderr proc)) (.-exited proc)])
           (.then (fn [results]
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code   (aget results 2)]
                      (resolve
                       (if (= 0 code)
                         {:content [{:type "text" :text (if (seq stdout) stdout "(no output)")}]}
                         {:content [{:type "text" :text (str "kb error: " stderr)}]
                          :is-error true}))))))))))

(def ^:private tool-defs
  [{:name "kb_search"
    :description "Search the knowledge base for entries matching a query. Returns matching headings with file and tags."
    :input_schema {:type "object"
                   :properties {:query {:type "string" :description "Search query (title substring match)"}
                                :tags  {:type "string" :description "Comma-separated tags to filter by"}}
                   :required ["query"]}}
   {:name "kb_get"
    :description "Retrieve the full content of a knowledge base entry."
    :input_schema {:type "object"
                   :properties {:query {:type "string" :description "Entry title or substring"}}
                   :required ["query"]}}
   {:name "kb_store"
    :description "Add a new knowledge entry to the knowledge base."
    :input_schema {:type "object"
                   :properties {:file  {:type "string" :description "Target org file name"}
                                :title {:type "string" :description "Entry title"}
                                :tags  {:type "string" :description "Comma-separated tags: PITFALL, EDGE_CASE, PATTERN, TECHNIQUE"}
                                :body  {:type "string" :description "Entry body in org-mode format"}}
                   :required ["file" "title" "tags" "body"]}}])

(defn- kb-search [{:keys [query tags]} {:keys [cwd]}]
  (run-kb (cond-> ["search" query] tags (conj "--tags" tags)) cwd))

(defn- kb-get [{:keys [query]} {:keys [cwd]}]
  (run-kb ["get" query] cwd))

(defn- kb-store [{:keys [file title tags body]} {:keys [cwd]}]
  (run-kb ["add" "--file" file "--title" title "--tags" tags "--body" body] cwd))

(def extension
  {:id               :kb
   :tool-definitions tool-defs
   :tool-registry    {"kb_search" kb-search
                      "kb_get"    kb-get
                      "kb_store"  kb-store}})
