(ns xi.tools.find
  "Find files tool — uses fd or find.")

(defn execute
  "Find files matching a pattern."
  [{:keys [pattern path]} {:keys [cwd]}]
  (let [dir (or path ".")
        args (cond-> ["fd" "--type" "f" "--color" "never" "--glob"]
               (seq pattern) (conj pattern)
               true (conj dir))]
    (js/Promise.
     (fn [resolve _reject]
       (let [proc (js/Bun.spawn
                   (clj->js args)
                   #js {:stdout "pipe"
                        :stderr "pipe"
                        :cwd (or cwd (.cwd js/process))})]
         (-> (js/Promise.all #js [(.text (.-stdout proc))
                                  (.text (.-stderr proc))
                                  (.-exited proc)])
             (.then (fn [results]
                      (let [stdout (aget results 0)
                            stderr (aget results 1)
                            code (aget results 2)]
                        (if (or (= 0 code) (= 1 code))
                          (resolve {:content [{:type "text"
                                               :text (if (seq stdout) stdout "No files found.")}]})
                          (resolve {:content [{:type "text"
                                              :text (str "fd error (exit " code "): " stderr)}]
                                    :is-error true})))))))))))

(def definition
  {:name "find"
   :description "Find files matching a pattern using fd. Returns file paths."
   :input_schema {:type "object"
                  :properties {:pattern {:type "string" :description "File name glob pattern, e.g. '*.cljs'"}
                               :path {:type "string" :description "Directory to search in (default: current dir)"}}
                  :required []}})
