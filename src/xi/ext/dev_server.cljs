(ns xi.ext.dev-server
  "Dev-only: restart the server's tmux dev session from the web overflow menu.

   The web half (xi.ext.dev-server.web) adds a \"Restart server\" item to the
   three-dots menu (only in dev builds) that sends :dev/restart-server. Here we
   spawn a detached `bb serve:restart`.

   The restart MUST be detached from this process: `bb serve:restart` runs
   `tmux kill-session -t xi-serve`, which kills the pane this server lives in —
   so a child left in that pane would be killed mid-flight before it could
   start the new session. `setsid` reparents the restart to init (PID 1),
   outside the pane's process group, so it survives the kill and brings the
   server back. The env is inherited, so `bb`/`tmux` resolve on PATH.")

(defn- restart-handler
  "Roomless :dev/restart-server → defer to the server-fx that spawns the
   detached restart."
  [_st _ev]
  {:effects [[:dev/restart-server-run {}]]})

(defn- server-fx
  [_ctx]
  {:dev/restart-server-run
   (fn [_ _]
     (let [cwd (.cwd js/process)]
       (js/Bun.spawn
        #js ["setsid" "bash" "-c"
             (str "sleep 0.3; cd '" cwd "' && bb serve:restart")]
        #js {:stdin "ignore" :stdout "ignore" :stderr "ignore"})
       nil))})

(def extension
  {:id              :dev-server
   :handlers        {:dev/restart-server restart-handler}
   :server-fx       server-fx
   :roomless-events #{:dev/restart-server}})
