(ns xi.ext.terminal.pty
  "The live terminals of the server process (impure edge of xi.ext.terminal).

   Each terminal is a program running under a Bun PTY (`Bun.spawn` with
   `:terminal`), keyed by its term id, with the clients watching it and the
   recent output (xi.ext.terminal.core/scrollback-append), which a client that
   attaches later gets first. Output reaches the watchers through `send!`
   straight from here, a few ms coalesced, so it never passes the app's
   event loop, state or event log.

   A terminal outlives its room (the room parks its buffers, see
   xi.server.room-manager) and ends when its buffer closes or the server
   stops."
  (:require [xi.ext.terminal.core :as core]
            [xi.paths :as paths]))

;; term-id → {:proc :decoder :scrollback :pending :timer :subs #{cid}
;;            :session-id :user :token :exit-code}
(defonce ^:private terms (atom {}))

(def ^:private flush-ms
  "How long output gathers before it goes out, so a burst is one message."
  6)

(defn live? [term-id] (contains? @terms term-id))

(defn- send-to-subs! [send! term-id event]
  (doseq [cid (get-in @terms [term-id :subs])]
    (send! cid (assoc event :term-id term-id))))

(defn- flush! [send! term-id]
  (let [text (get-in @terms [term-id :pending])]
    (swap! terms update term-id #(when % (assoc % :pending "" :timer nil)))
    (when (seq text)
      (send-to-subs! send! term-id {:type :terminal/output :text text}))))

(defn- output! [send! term-id text]
  (when (get @terms term-id)
    (swap! terms update term-id
           (fn [t]
             (-> t
                 (update :scrollback core/scrollback-append text)
                 (update :pending str text))))
    (when-not (get-in @terms [term-id :timer])
      (swap! terms assoc-in [term-id :timer]
             (js/setTimeout #(flush! send! term-id) flush-ms)))))

(defn- child-env
  "TERM_PROGRAM=xi tells programs they run in Xi's terminal, whose 16 ANSI
   colours follow the web theme (xi.web.terminal/theme): a colorscheme that
   sticks to those follows every theme switch. `extra` adds variables."
  [extra]
  (let [env (doto (paths/scrub-env)
              (unchecked-set "TERM" "xterm-256color")
              (unchecked-set "COLORTERM" "truecolor")
              (unchecked-set "TERM_PROGRAM" "xi"))]
    (doseq [[k v] extra] (unchecked-set env k v))
    env))

(defn by-token
  "The live terminal `token` belongs to (spawn!'s `:token`) → {:term-id
   :session-id :user}, nil for an unknown or empty token."
  [token]
  (when (seq token)
    (some (fn [[tid t]]
            (when (= token (:token t))
              (assoc (select-keys t [:session-id :user]) :term-id tid)))
          @terms)))

(defn spawn!
  "Start `argv` in `cwd` under a PTY as terminal `term-id` (replacing a
   finished one of that id, keeping its watchers). `env` adds variables,
   `token` and `user` are kept for by-token. `on-exit` gets the exit code.
   Throws when the program cannot start."
  [send! {:keys [term-id argv cwd cols rows session-id user token env]} on-exit]
  (when-let [{:keys [^js proc timer]} (get @terms term-id)]
    (when timer (js/clearTimeout timer))
    (try (.kill proc "SIGHUP") (catch :default _ nil))
    (send-to-subs! send! term-id {:type :terminal/output :reset? true :text ""}))
  (let [[cols rows] (or (get-in @terms [term-id :size]) [cols rows])
        decoder (js/TextDecoder.)
        proc    (js/Bun.spawn
                 (clj->js argv)
                 #js {:cwd cwd
                      :env (child-env env)
                      :terminal #js {:cols (or cols 80)
                                     :rows (or rows 24)
                                     :data (fn [_ data]
                                             (output! send! term-id
                                                      (.decode decoder data #js {:stream true})))}})]
    (swap! terms (fn [m]
                   (assoc m term-id {:proc       proc
                                     :scrollback core/empty-scrollback
                                     :pending    ""
                                     :subs       (get-in m [term-id :subs] #{})
                                     :size       (get-in m [term-id :size])
                                     :session-id session-id
                                     :user       user
                                     :token      token})))
    (.then (.-exited proc)
           (fn [code]
             ;; a restart may have replaced this process already
             (when (identical? proc (get-in @terms [term-id :proc]))
               ;; the PTY outlives its child and would hold its fd open
               (try (.close (.-terminal proc)) (catch :default _ nil))
               (flush! send! term-id)
               (swap! terms update term-id #(-> % (assoc :exit-code code) (dissoc :token)))
               (send-to-subs! send! term-id {:type :terminal/exit :code code})
               (on-exit code))))
    proc))

(defn attach!
  "Subscribe client `cid` to `term-id` and replay what it missed. A terminal
   this process doesn't run (the server restarted) is reported as gone."
  [send! cid term-id]
  (if-let [{:keys [scrollback exit-code]} (get @terms term-id)]
    (do (swap! terms update-in [term-id :subs] conj cid)
        (send! cid {:type :terminal/output :term-id term-id :reset? true
                    :text (core/scrollback-text scrollback)})
        (when exit-code
          (send! cid {:type :terminal/exit :term-id term-id :code exit-code})))
    (send! cid {:type :terminal/gone :term-id term-id})))

(defn detach! [cid term-id]
  (when (get @terms term-id)
    (swap! terms update-in [term-id :subs] disj cid)))

(defn detach-client!
  "Drop a disconnected client from every terminal."
  [cid]
  (swap! terms update-vals #(update % :subs disj cid)))

(defn write! [term-id text]
  (when-let [^js proc (get-in @terms [term-id :proc])]
    (when-not (get-in @terms [term-id :exit-code])
      (.write (.-terminal proc) text))))

(defn resize! [term-id cols rows]
  (when-let [^js proc (get-in @terms [term-id :proc])]
    (when (and (pos-int? cols) (pos-int? rows))
      (swap! terms assoc-in [term-id :size] [cols rows])
      (.resize (.-terminal proc) cols rows))))

(defn redraw!
  "Make a full-screen program repaint (a size change sends SIGWINCH): after
   a replay the scrollback may start mid-screen."
  [term-id cols rows]
  (when (and (pos-int? cols) (> rows 1))
    (resize! term-id cols (dec rows))
    (js/setTimeout #(resize! term-id cols rows) 30)))

(defn kill!
  "End terminal `term-id`: hang up its program and forget it."
  [term-id]
  (when-let [{:keys [^js proc timer]} (get @terms term-id)]
    (swap! terms dissoc term-id)
    (when timer (js/clearTimeout timer))
    (try (.kill proc "SIGHUP") (catch :default _ nil))
    (try (.close (.-terminal proc)) (catch :default _ nil))))

(defn session-terms
  "The ids of the terminals opened in session `session-id`."
  [session-id]
  (keep (fn [[id t]] (when (= session-id (:session-id t)) id)) @terms))

(defn kill-all! []
  (run! kill! (keys @terms)))
