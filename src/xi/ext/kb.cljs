(ns xi.ext.kb
  "Knowledge base extension — search, get, store entries via kb CLI.")

(defn- run-kb
  "Run kb CLI command. Returns promise of {:content [...] :is-error bool}."
  [args]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 (clj->js (cons "kb" args))
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd (.cwd js/process)})]
       (-> (js/Promise.all #js [(.text (.-stdout proc))
                                 (.text (.-stderr proc))])
           (.then (fn [results]
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code (.-exitCode proc)]
                      (resolve
                       (if (= 0 code)
                         {:content [{:type "text" :text (if (seq stdout) stdout "(no output)")}]}
                         {:content [{:type "text" :text (str "kb error: " stderr)}]
                          :is-error true}))))))))))

(def extension
  {:name "kb"
   :tools [{:name "kb_search"
            :description "Search the knowledge base for entries matching a query. Returns matching headings with file and tags."
            :input_schema {:type "object"
                           :properties {:query {:type "string" :description "Search query (title substring match)"}
                                        :tags {:type "string" :description "Comma-separated tags to filter by"}}
                           :required ["query"]}
            :execute (fn [{:keys [query tags]}]
                       (run-kb (cond-> ["search" query]
                                 tags (conj "--tags" tags))))}

           {:name "kb_get"
            :description "Retrieve the full content of a knowledge base entry."
            :input_schema {:type "object"
                           :properties {:query {:type "string" :description "Entry title or substring"}}
                           :required ["query"]}
            :execute (fn [{:keys [query]}]
                       (run-kb ["get" query]))}

           {:name "kb_store"
            :description "Add a new knowledge entry to the knowledge base."
            :input_schema {:type "object"
                           :properties {:file {:type "string" :description "Target org file name"}
                                        :title {:type "string" :description "Entry title"}
                                        :tags {:type "string" :description "Comma-separated tags: PITFALL, EDGE_CASE, PATTERN, TECHNIQUE"}
                                        :body {:type "string" :description "Entry body in org-mode format"}}
                           :required ["file" "title" "tags" "body"]}
            :execute (fn [{:keys [file title tags body]}]
                       (run-kb ["store" "--file" file "--title" title "--tags" tags "--body" body]))}]})
