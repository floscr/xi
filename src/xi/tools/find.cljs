(ns xi.tools.find
  "Find files tool — uses fd or find.")

(defn execute
  "Find files matching a pattern."
  [{:keys [pattern path]}]
  (let [dir (or path ".")
        args ["fd" "--type" "f" "--color" "never" (or pattern "")  dir]]
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
                        (if (or (= 0 code) (= 1 code))
                          (resolve {:content [{:type "text"
                                               :text (if (seq stdout) stdout "No files found.")}]})
                          (resolve {:content [{:type "text"
                                              :text (str "fd error: " stderr)}]
                                    :is-error true})))))))))))

(def definition
  {:name "find"
   :description "Find files matching a pattern using fd. Returns file paths."
   :input_schema {:type "object"
                  :properties {:pattern {:type "string" :description "File name pattern (regex)"}
                               :path {:type "string" :description "Directory to search in (default: current dir)"}}
                  :required []}})
