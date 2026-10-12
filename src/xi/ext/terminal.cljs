(ns xi.ext.terminal
  "Terminal buffers (server half). `:terminal/open {:room-id :argv :cwd
   :title :tag}` runs a program (the user's shell without `:argv`) under a
   PTY and shows it as a `:terminal` buffer of the room (xi.buffers,
   `term:<id>`), switched to on the client that asked. The web client renders
   it with xterm.js (xi.web.terminal).

   Opening one is a rules request `{:tool :terminal :cli :command :argv}`
   made for the event's user: no default rule matches it, so it runs unless
   a rules file denies or asks. User extensions may dispatch `:terminal/open`
   from user-initiated code only (xi.ext.user.guard).

   The I/O events (`:terminal/attach|detach|input|resize`) are roomless and
   never broadcast; output goes back to the watching clients from
   xi.ext.terminal.pty, outside the event loop. A terminal ends with its
   buffer: closing it hangs the program up; the program exiting 0 closes the
   buffer, any other exit keeps it with `:exit-code` (Restart / Close).

   A program reaches its chat back through xi.ext.terminal.socket: a prompt
   as the user who opened the terminal, or a sub-agent (core/send-event)."
  (:require [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.ext.rules :as rules-ext]
            [xi.ext.terminal.core :as core]
            [xi.ext.terminal.pty :as pty]
            [xi.ext.terminal.socket :as socket]
            [xi.rules.store :as store]
            [xi.tools.fs :as tfs]
            ["node:fs" :as fs]))

(defn- new-term-id []
  (subs (str (random-uuid)) 0 13))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn- terminal-open
  [st {:keys [room-id argv cwd title tag client-id user]}]
  (when-let [room (state/get-room st room-id)]
    (if (and (some? argv) (not (core/valid-argv? argv)))
      {:effects [[:app/dispatch {:type :ui/status :room-id room-id
                                 :text ":terminal/open needs :argv as a vector of strings"}]]}
      {:effects [[:terminal/spawn
                  (cond-> {:room-id    room-id
                           :term-id    (new-term-id)
                           :argv       argv
                           :cwd        (if cwd (tfs/resolve-path cwd (:cwd room)) (:cwd room))
                           :session-id (get-in room [:session :id])}
                    title     (assoc :title title)
                    tag       (assoc :tag tag)
                    client-id (assoc :client-id client-id)
                    user      (assoc :user user))]]})))

(defn- terminal-restart
  "Run a finished (or lost) terminal's program again in its buffer."
  [st {:keys [room-id buffer-id client-id user]}]
  (when-let [buf (get-in st [:rooms room-id :ui :buffers buffer-id])]
    (when (= :terminal (:kind buf))
      {:effects [[:terminal/spawn
                  (cond-> (-> (select-keys buf [:term-id :argv :cwd :title :tag])
                              (assoc :room-id room-id
                                     :session-id (get-in st [:rooms room-id :session :id])))
                    client-id (assoc :client-id client-id)
                    user      (assoc :user user))]]})))

(defn- room-with-buffer [st buffer-id]
  (some (fn [[rid room]] (when (get-in room [:ui :buffers buffer-id]) rid)) (:rooms st)))

(defn- session-with-buffer [st buffer-id]
  (some (fn [[sid bufs]] (when (get bufs buffer-id) sid)) (:parked-buffers st)))

(defn terminal-exited
  "The program of `term-id` ended: exit 0 closes its buffer (in its room, or
   parked), another code is kept on the buffer for the view to show."
  [st {:keys [term-id code]}]
  (let [bid (buffers/terminal-id term-id)
        rid (room-with-buffer st bid)
        sid (when-not rid (session-with-buffer st bid))]
    (cond
      (and rid (= 0 code))
      {:effects [[:app/dispatch {:type :ui/buffer-close :room-id rid :buffer-id bid}]]}

      rid
      {:effects [[:app/dispatch {:type :ui/buffer-set :room-id rid :buffer-id bid
                                 :buffer (assoc (get-in st [:rooms rid :ui :buffers bid])
                                                :exit-code code)}]]}

      (and sid (= 0 code))
      {:effects [[:app/dispatch {:type :session/buffer-close :session-id sid :buffer-id bid}]]}

      sid nil

      :else
      {:effects [[:terminal/kill {:term-ids [term-id]}]]})))

;; Closing a terminal's buffer ends it. These run after the core handlers, so
;; the buffer is already gone; the term id comes from the buffer id.

(defn- on-buffer-close [_st {:keys [buffer-id]}]
  (when-let [tid (buffers/terminal-of buffer-id)]
    {:effects [[:terminal/kill {:term-ids [tid]}]]}))

(defn- on-close-all [st {:keys [room-id]}]
  (when-let [sid (get-in st [:rooms room-id :session :id])]
    {:effects [[:terminal/kill {:session-id sid}]]}))

(defn- on-session-buffer-close [_st {:keys [session-id buffer-id]}]
  (if buffer-id
    (when-let [tid (buffers/terminal-of buffer-id)]
      {:effects [[:terminal/kill {:term-ids [tid]}]]})
    {:effects [[:terminal/kill {:session-id session-id}]]}))

(defn- on-session-delete [_st {:keys [session-id]}]
  (when session-id
    {:effects [[:terminal/kill {:session-id session-id}]]}))

(defn- sub-stream
  "A sub-agent event, for the program streaming it (socket/push!; a no-op
   for the sub-agents nobody streams)."
  [_st {:keys [sub-id] :as ev}]
  (when-let [item (core/stream-item ev)]
    {:effects [[:terminal/sub-stream {:sub-id sub-id :item item}]]}))

(defn- io [fx-type]
  (fn [_st ev]
    {:effects [[fx-type (select-keys ev [:client-id :term-id :text :cols :rows])]]}))

(def ^:private term-command
  {:name        "term"
   :description "Open a terminal in the chat's directory: /term [command …]"
   :handler     (fn [st {:keys [room-id args client-id user]}]
                  (terminal-open st {:room-id   room-id
                                     :argv      (core/parse-args args)
                                     :client-id client-id
                                     :user      user}))})

;; ── Effects ──────────────────────────────────────────────────────────────────

(defn- confirm-fn
  "A rules `confirm!` raising the ask in `room-id` (xi.agent builds the same)."
  [ask! dispatch! get-state room-id]
  (when ask!
    (fn confirm!
      ([message] (confirm! message nil))
      ([message opts]
       (ask! {:dispatch! dispatch! :state (get-state)}
             {:room-id room-id
              :dialog  (cond-> {:type :confirm :message message}
                         (:options opts) (assoc :options (:options opts)))})))))

(defn- gate!
  "Decide opening `argv` in `cwd` for `user` → Promise of nil when it may run,
   else of the refusal text."
  [ask! {:keys [dispatch! get-state]} {:keys [room-id user argv cwd]}]
  (let [ctx {:get-state get-state :dispatch! dispatch! :room-id room-id :user user
             :confirm! (confirm-fn ask! dispatch! get-state room-id)}]
    (-> (rules-ext/decide! (store/request (core/rules-request argv cwd) ctx) ctx)
        (.then (fn [{:keys [decision message]}]
                 (case decision
                   (:allow :approved :pass) nil
                   :unanswered "Opening this terminal needs approval, and nobody could answer."
                   (or message "Opening this terminal was denied by a rule.")))))))

(defn- on-send
  "A program's `POST /send` (xi.ext.terminal.socket): dispatch what it asks."
  [{:keys [dispatch! get-state]}]
  (fn [term body]
    (let [{:keys [event] :as res} (core/send-event (get-state) term body)]
      (when event (dispatch! event))
      res)))

(defn- spawn-fx [ask! send!]
  (fn [{:keys [dispatch!] :as ctx}
       {:keys [room-id term-id client-id session-id cwd user] :as p}]
    (let [argv  (or (:argv p) (core/shell-argv (aget js/process.env "SHELL")))
          p     (assoc p :argv argv)
          token (socket/new-token)
          bid   (buffers/terminal-id term-id)
          show! (fn [extra]
                  (dispatch! {:type :ui/buffer-set :room-id room-id :buffer-id bid
                              :buffer (merge (core/buffer p) extra)})
                  (dispatch! (cond-> {:type :ui/buffer-switch :room-id room-id :buffer-id bid}
                               client-id (assoc :client-id client-id))))]
      (-> (gate! ask! ctx p)
          (.then (fn [refusal]
                   (cond
                     refusal
                     (show! {:error refusal})

                     ;; posix_spawn blames the program for a missing cwd
                     (not (fs/existsSync cwd))
                     (show! {:error (str "The directory " cwd " does not exist.")})

                     :else
                     (try
                       (pty/spawn! send! {:term-id term-id :argv argv :cwd cwd
                                          :session-id session-id :user user
                                          :token token
                                          :env {"XI_TERM_SOCK"  (socket/ensure! (on-send ctx))
                                                "XI_TERM_TOKEN" token}}
                                   (fn [code]
                                     (dispatch! {:type :terminal/exited
                                                 :term-id term-id :code code})))
                       (show! nil)
                       (catch :default e
                         (show! {:error (str "Could not start " (first argv) ": "
                                             (.-message e))}))))))))))

(defn- server-fx [ask!]
  (fn [{:keys [send!]}]
    {:terminal/spawn  (spawn-fx ask! send!)
     :terminal/attach (fn [_ {:keys [client-id term-id cols rows]}]
                        (pty/attach! send! client-id term-id)
                        (pty/redraw! term-id cols rows))
     :terminal/detach (fn [_ {:keys [client-id term-id]}]
                        (pty/detach! client-id term-id))
     :terminal/input  (fn [_ {:keys [term-id text]}]
                        (pty/write! term-id text))
     :terminal/resize (fn [_ {:keys [term-id cols rows]}]
                        (pty/resize! term-id cols rows))
     :terminal/sub-stream (fn [_ {:keys [sub-id item]}]
                            (socket/push! sub-id item))
     :terminal/kill   (fn [_ {:keys [term-ids session-id]}]
                        (run! pty/kill! (concat term-ids
                                                (when session-id (pty/session-terms session-id)))))
     :terminal/detach-client (fn [_ {:keys [client-id]}]
                               (pty/detach-client! client-id))}))

(defn create
  "Factory (xi.config/server): closes over the dialog opener for rules asks."
  [{:keys [ask!]}]
  {:id              :terminal
   :handlers        {:terminal/open        terminal-open
                     :terminal/restart     terminal-restart
                     :terminal/exited      terminal-exited
                     :terminal/attach      (io :terminal/attach)
                     :terminal/detach      (io :terminal/detach)
                     :terminal/input       (io :terminal/input)
                     :terminal/resize      (io :terminal/resize)
                     :ui/buffer-close      on-buffer-close
                     :ui/buffers-close-all on-close-all
                     :session/buffer-close on-session-buffer-close
                     :session/delete       on-session-delete
                     :subagent/text-delta  sub-stream
                     :subagent/tool-start  sub-stream
                     :subagent/error       sub-stream
                     :subagent/turn-end    sub-stream
                     :client/disconnect    (fn [_ {:keys [client-id]}]
                                             {:effects [[:terminal/detach-client
                                                         {:client-id client-id}]]})}
   :commands        [term-command]
   :server-fx       (server-fx ask!)
   :roomless-events #{:terminal/attach :terminal/detach :terminal/input :terminal/resize}
   :no-broadcast    #{:terminal/open :terminal/restart :terminal/exited
                      :terminal/attach :terminal/detach :terminal/input :terminal/resize}
   :on-shutdown     (fn [& _]
                      (pty/kill-all!)
                      (socket/stop!))})
