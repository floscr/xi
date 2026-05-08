(ns xi.ext.done-notify
  "Desktop notification on agent turn completion via dunstify.")

(defn- notify [_ctx]
  (try
    (js/Bun.spawn #js ["dunstify" "-a" "Xi" "Agent turn complete"]
                  #js {:stdout "ignore" :stderr "ignore"})
    (catch :default _e nil)))

(def extension
  {:name "done-notify"
   :hooks {:agent-end notify}})
