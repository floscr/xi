(ns xi.providers.runner
  "Host side of the provider runner protocol, shared by every provider that
   runs its vendor SDK out of process (`providers/<id>/runner.mjs`, its own
   node_modules — see docs/mcp-tool-bridge.md).

   One runner process per turn, newline-delimited JSON over stdio:

     host → runner   {type \"start\", …}  {type \"tool-result\", id, result}
                     {type \"abort\"}
     runner → host   {type \"message\", message}  {type \"tool-call\", id, name,
                     arguments}  {type \"done\"}  {type \"error\", message}

   This namespace owns spawning, framing, tool-call proxying and the single
   terminal frame. What a `message` means is the provider's business."
  (:require ["node:child_process" :as child-process]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn script-path
  "Default location of a provider's runner script: `providers/<id>/runner.mjs`
   next to the directory holding the compiled main.js."
  [provider-id]
  (.resolve path js/__dirname ".." "providers" (name provider-id) "runner.mjs"))

(defn split-frames
  "Append `chunk` to the pending `buf` and split off the complete lines.
   Returns {:lines [non-blank complete lines] :rest unterminated tail}."
  [buf chunk]
  (let [parts (str/split (str buf chunk) #"\n" -1)]
    {:lines (vec (remove str/blank? (butlast parts)))
     :rest  (last parts)}))

(defn run-turn!
  "Spawn `script` and drive one turn. Returns {:promise :abort!}; the promise
   resolves (never rejects) once the runner sent its terminal frame or exited.

     :script        runner script path
     :start         the `start` frame's payload (a JSON-serializable map)
     :on-message    (fn [^js message]) — payload of each `message` frame
     :on-tool-call  (fn [tool-name args]) → Promise<#js {:content :isError}>
     :on-error      (fn [msg]) — an `error` frame or a failed spawn
     :log-tag       prefix for diagnostics (default \"runner\")"
  [{:keys [script start on-message on-tool-call on-error log-tag]
    :or {log-tag "runner"}}]
  (let [^js proc (child-process/spawn (.-execPath js/process)
                                      #js [script]
                                      #js {:stdio #js ["pipe" "pipe" "inherit"]})
        send-frame! (fn [m]
                      (try
                        (.write (.-stdin proc) (str (js/JSON.stringify (clj->js m)) "\n"))
                        (catch :default _e nil)))
        promise
        (js/Promise.
         (fn [resolve _reject]
           (let [buf (atom "")
                 done? (atom false)
                 finish! (fn []
                           (when-not @done?
                             (reset! done? true)
                             (try (.kill proc) (catch :default _e nil))
                             (resolve nil)))
                 handle-frame
                 (fn [^js frame]
                   ;; Frames after the terminal `done`/`error` (e.g. a late
                   ;; runner error while we're killing it) must not fire
                   ;; callbacks into an already-resolved turn.
                   (when-not @done?
                     (case (aget frame "type")
                       "message"
                       (on-message (aget frame "message"))

                       "tool-call"
                       (let [id (aget frame "id")
                             args (js->clj (aget frame "arguments") :keywordize-keys true)]
                         (-> (on-tool-call (aget frame "name") args)
                             (.then (fn [^js res]
                                      (send-frame! {:type "tool-result"
                                                    :id id
                                                    :result {:content (.-content res)
                                                             :isError (.-isError res)}})))
                             (.catch (fn [e]
                                       (send-frame!
                                        {:type "tool-result" :id id
                                         :result {:content #js [#js {:type "text" :text (str e)}]
                                                  :isError true}})))))

                       "done" (finish!)

                       "error"
                       (do (on-error (str (aget frame "message")))
                           (finish!))

                       nil)))]
             (.setEncoding (.-stdout proc) "utf8")
             (.on (.-stdout proc) "data"
                  (fn [chunk]
                    (let [{:keys [lines rest]} (split-frames @buf chunk)]
                      (reset! buf rest)
                      (doseq [line lines]
                        (try (handle-frame (js/JSON.parse line))
                             (catch :default e
                               (js/console.error (str "[" log-tag "] bad frame:") (str e))))))))
             (.on proc "error"
                  (fn [err]
                    (when-not @done?
                      (on-error (str err)))
                    (finish!)))
             (.on proc "close" (fn [_code] (finish!)))
             (send-frame! (assoc start :type "start")))))]
    {:promise promise
     :abort! (fn []
               (send-frame! {:type "abort"})
               (js/setTimeout (fn [] (try (.kill proc) (catch :default _e nil))) 500))}))
