(ns xi.tools.grep
  "Grep tool — search file contents using ripgrep.")

(defn execute
  "Search for a pattern in files using ripgrep."
  [{:keys [pattern path glob]}]
  (let [args (cond-> ["rg" "--line-number" "--no-heading" "--color" "never" "-m" "100"]
               glob (into ["--glob" glob])
               true (conj pattern)
               path (conj path))]
    (js/Promise.
     (fn [resolve _reject]
       (let [proc (js/Bun.spawn
                   (clj->js args)
                   #js {:stdout "pipe"
                        :stderr "pipe"
                        :cwd (.cwd js/process)})]
         (-> (js/Promise.all #js [(.text (.-stdout proc))
                                  (.text (.-stderr proc))])
             (.then (fn [results]
                      (let [stdout (aget results 0)
                            stderr (aget results 1)
                            code (.-exitCode proc)]
                        (cond
                          (= 0 code)
                          (resolve {:content [{:type "text" :text stdout}]})

                          (= 1 code)
                          (resolve {:content [{:type "text" :text "No matches found."}]})

                          :else
                          (resolve {:content [{:type "text"
                                              :text (str "ripgrep error: " stderr)}]
                                    :is-error true})))))))))))

(def definition
  {:name "grep"
   :description "Search file contents using ripgrep (rg). Returns matching lines with file paths and line numbers."
   :input_schema {:type "object"
                  :properties {:pattern {:type "string" :description "Search pattern (regex)"}
                               :path {:type "string" :description "Directory or file to search in"}
                               :glob {:type "string" :description "File glob pattern, e.g. '*.cljs'"}}
                  :required ["pattern"]}})
