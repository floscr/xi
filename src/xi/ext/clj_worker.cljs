(ns xi.ext.clj-worker
  "Worker-thread entry for the clj/bb tools.

   The clj sandbox runs shell commands via synchronous spawnSync; running that
   on the server's single event loop freezes every room and WS client for the
   command's whole duration (up to SH_TIMEOUT). Loading target/main.js as a
   node:worker_threads Worker (dispatched by xi.cli/main's isMainThread guard)
   runs the eval on a separate OS thread — one worker per room, so a blocking
   eval only stalls its own room and the server stays responsive.

   Protocol (structured-clone plain JS objects):
     main → worker  #js {:id n :kind \"clj\"|\"bb\" :code s :cwd s
                         :allowed #js[...] :roomId s :task s :args #js[...]
                         :abortSab SharedArrayBuffer}
     worker → main  #js {:id n :text s :isError bool}
                    | #js {:processEvent \"register\"|\"deregister\" …}
                    | #js {:gateRequest \"read\"|\"write\" :path s :roomKey s
                           :sab SharedArrayBuffer}
                      (runtime path gate — the worker blocks in Atomics.wait
                      on :sab until the main thread writes the verdict)

   The actual dispatch lives in xi.ext.clj/eval-message; this namespace only
   owns the parentPort message pump."
  (:require ["node:worker_threads" :as wt]
            [xi.ext.clj :as clj]))

(defn install! []
  (when-let [port wt/parentPort]
    (.on port "message"
         (fn [^js m]
           (let [reply (try
                         (clj/eval-message m)
                         (catch :default e
                           #js {:id      (.-id m)
                                :text    (str "clj worker error: " (.-message e))
                                :isError true}))]
             (.postMessage port reply))))))
