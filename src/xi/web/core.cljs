(ns xi.web.core
  "Web client entry — the browser app shell.

   Same pure core as every other mode (xi.core.app), wired in :client mode
   through xi.client.ws-transport: local events forward to the server,
   :remote? broadcasts mirror into the local room cache with effects
   stripped. The browser only renders and collects input.

   Phase 7b: home/session list + deep-link routing + offline cache. The
   router lives in the single atom (:web/route); the cache hydrates state
   before the WS connects and persists via an app tap. Saved sessions, live
   rooms, unread dots and reconnect come from the lobby mirror + transport."
  (:require ["@chenglou/pretext" :as pretext]
            [clojure.string :as str]
            [replicant.dom :as r]
            [xi.agent :as agent]
            [xi.client.ws-transport :as ws-transport]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.diff :as diff]
            [xi.ext.core :as ext]
            [xi.config :as config]
            [xi.naming :as naming]
            [xi.web.cache :as cache]
            [xi.web.demo :as demo]
            [xi.web.router :as router]
            [xi.web.views :as views]))

;; ── Base handlers (browser-safe merge) ───────────────────────────────────────

(defn- base-handlers
  "The pure handler map shared with the server, sans node-coupled chains.
   Effects are stripped on mirror, so the simple merge suffices for a
   read-and-forward client."
  []
  (merge events/core-handlers
         agent/handlers
         (commands/command-handlers)
         compaction/handlers
         naming/handlers))

;; ── Web-local handlers (installed unwrapped; never mirrored) ──────────────────

(defn- forward
  "Forward an originating client event to the server."
  [_st ev]
  {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]})

(defn- room-new-cwd
  "cwd for a fresh chat opened from the overflow menu: inherit the current
   chat's cwd, else the project dir currently being viewed, else nil (server
   default). Mirrors the user's expectation that a new chat opens in the same
   place they were already working."
  [st]
  (let [sid (get-in st [:web/route :session-id])
        dir (get-in st [:web/route :dir])]
    (or (:cwd (state/active-room st))
        (some (fn [r] (when (= sid (:session-id r)) (:cwd r)))
              (get-in st [:lobby :rooms]))
        (some (fn [s] (when (= sid (:session-id s)) (:cwd s)))
              (get-in st [:lobby :sessions]))
        (when (string? dir) dir))))

(defn- room-new
  "Open a fresh *virtual* chat: switch to the chat view but create no server
   room yet. The room stays client-only (launch header, no spinner, nothing to
   clean up) until the first prompt, which fires :room/join + submits via
   submit-pending / pending-submit-tap."
  [st _]
  {:state   (-> st
                (assoc :web/route {:page :chat :session-id nil})
                (assoc :web/pending-room {:id (random-uuid) :cwd (room-new-cwd st)})
                (assoc :web/timeline-window nil))
   :effects [[:history/push {:route {:page :chat}}]
             [:compose/focus]]})

(defn- counts-result
  "Store per-session response counts from a :session/counts reply.

   If a session was just left (`:web/pending-read`), mark it read at this
   fresh count: the user saw whatever landed while they were attached, but
   the count couldn't refresh until they returned to the lobby. Without this
   the dot reappears for a room the user already visited."
  [st {:keys [counts]}]
  (let [counts (or counts {})
        sid    (:web/pending-read st)
        cnt    (get counts sid)]
    (if (and sid cnt)
      {:state   (-> st
                    (assoc :web/response-counts counts)
                    (assoc-in [:web/watched sid] cnt)
                    (dissoc :web/pending-read))
       :effects [[:cache/watch {:session-id sid :count cnt}]
                 [:ws/send {:type :session/mark-read :session-id sid}]]}
      {:state (assoc st :web/response-counts counts)})))

(defn- mark-read
  "Mark a session read at its current response count (clears the unread dot).
   Updates the local overlay for an instant clear, caches it for offline
   paint, and forwards to the server so the marker persists and syncs to
   every other device (the server rebroadcasts an authoritative :read)."
  [st {:keys [session-id]}]
  (let [cnt (get-in st [:web/response-counts session-id] 0)]
    {:state   (assoc-in st [:web/watched session-id] cnt)
     :effects [[:cache/watch {:session-id session-id :count cnt}]
               [:ws/send {:type :session/mark-read :session-id session-id}]]}))

(defn- mark-all-read
  "Mark every unread session read at its current response count (clears all
   unread dots at once). Per session it mirrors `mark-read`: bumps the local
   overlay, caches it for offline paint, and forwards a marker to the server.
   A session is unread when its response count exceeds the seen-count (the
   later of the server-authoritative read state and the local overlay)."
  [st _]
  (let [reads  (get-in st [:lobby :read])
        unread (for [[sid cnt] (:web/response-counts st)
                     :let [seen (max (get reads sid 0)
                                     (get-in st [:web/watched sid] 0))]
                     :when (> cnt seen)]
                 [sid cnt])]
    {:state   (reduce (fn [s [sid cnt]] (assoc-in s [:web/watched sid] cnt))
                      st unread)
     :effects (into [] (mapcat (fn [[sid cnt]]
                                 [[:cache/watch {:session-id sid :count cnt}]
                                  [:ws/send {:type :session/mark-read :session-id sid}]]))
                    unread)}))

(defn- connection-status [st {:keys [connected?]}]
  {:state (assoc st :web/connected? connected?)})

(defn- compose-add-images
  "Stage client-resized images ({:data b64 :media-type mime}) for the next
   prompt; they ride along on :input/submit and clear on send."
  [st {:keys [images]}]
  {:state (update st :web/compose-images (fnil into []) images)})

(defn- compose-remove-image [st {:keys [idx]}]
  {:state (update st :web/compose-images
                  (fn [imgs] (into (subvec imgs 0 idx) (subvec imgs (inc idx)))))})

(defn- compose-clear-images [st _]
  {:state (assoc st :web/compose-images [])})

(defn- compose-set-draft
  "Track the compose text per session so drafts survive navigation."
  [st {:keys [draft-key text]}]
  {:state (-> (if (seq text)
                (assoc-in st [:web/drafts draft-key] text)
                (update st :web/drafts dissoc draft-key))
              ;; Reset command suggestion selection when the input changes
              (assoc :web/cmd-selected 0))})

(defn- compose-clear-draft [st {:keys [draft-key]}]
  {:state (update st :web/drafts dissoc draft-key)})

(defn- timeline-set-window
  "Widen the virtualized timeline window (\"Show earlier messages\")."
  [st {:keys [window]}]
  {:state (assoc st :web/timeline-window window)})

(defn- lightbox-open [st {:keys [src]}]
  {:state (assoc st :web/lightbox src)})

(defn- lightbox-close [st _]
  {:state (dissoc st :web/lightbox)})

(defn- submit-pending
  "Stash a message submitted before its room exists; pending-submit-tap fires
   it once :room/joined arrives. Two cases:
   - virtual new room (a :web/pending-room is set): create the server room now
     via :room/join \"new\" (carrying the stashed cwd). We detect this by the
     pending-room, not by a missing active-room — a new chat is opened while
     still attached to the previous room, so active-room is usually non-nil;
     keying off it would fire the prompt into that previous room.
   - cached session view (session-id set): the join is already in flight from
     navigation, so just stash and wait.

   The virtual join carries a :join-token (the pending-room id) which the
   server echoes back on :room/joined, so pending-submit-tap can fire the
   prompt into *this* new room only — never into some other room that happens
   to join first (a navigation back to a session, a reconnect). Both a fresh
   room and an existing session carry a non-nil session id, so the token is
   the only reliable way to correlate the reply with our own request."
  [st {:keys [session-id text images]}]
  (let [pending  (:web/pending-room st)
        virtual? (and (nil? session-id) (some? pending))
        cwd      (:cwd pending)
        token    (str (:id pending))]
    (cond-> {:state (-> st
                        (assoc :web/pending-submit
                               (cond-> {:session-id session-id :text text}
                                 virtual?     (assoc :join-token token)
                                 (seq images) (assoc :images images)))
                        ;; Show the user's bubble instantly, before the
                        ;; :room/join round-trips. room-id is nil (no room
                        ;; yet); optimistic-post matches the virtual window by
                        ;; the nil session/room, and optimistic-tap re-keys it
                        ;; to the real room once :input/submit fires post-join.
                        (assoc :web/optimistic
                               (cond-> {:room-id nil :session-id session-id :text text}
                                 (seq images) (assoc :images (vec images))))
                        (dissoc :web/pending-room))}
      virtual?
      (assoc :effects [[:ws/send (cond-> {:type :room/join :target "new" :join-token token}
                                   cwd (assoc :cwd cwd))]]))))

(defn- submit-clear-pending [st _]
  {:state (dissoc st :web/pending-submit)})

(defn- optimistic-set
  "Stash the just-submitted prompt so the timeline can show it instantly,
   before the server round-trips a :user history entry back."
  [st {:keys [room-id session-id text images]}]
  {:state (assoc st :web/optimistic
                 (cond-> {:room-id room-id :session-id session-id :text text}
                   (seq images) (assoc :images (vec images))))})

(defn- optimistic-clear [st _]
  {:state (dissoc st :web/optimistic)})

(defn- cmd-select [st {:keys [index]}]
  {:state (assoc st :web/cmd-selected (or index 0))})

(def ^:private max-recent-commands 6)

(defn- record-command
  "Push a just-executed command name to the front of the usage list (deduped,
   capped) and persist it. This feeds the *next* reload's quick-command bar —
   the displayed order (`:web/recent-commands`) is deliberately frozen for the
   session so tapping a button never reorders the bar mid-tap (a moved DOM node
   cancels the pending click on touch devices)."
  [st {:keys [name]}]
  (let [usage (->> (cons name (remove #(= % name) (:web/command-usage st)))
                   (take max-recent-commands)
                   vec)]
    {:state   (assoc st :web/command-usage usage)
     :effects [[:cache/recent-commands {:commands usage}]]}))

(defn- theme-set-mode [st {:keys [mode]}]
  (let [m (if (#{"auto" "light" "dark"} mode) mode "auto")]
    {:state   (assoc st :web/theme-mode m)
     :effects [[:theme/apply m]]}))

;; ── Interactive diff selection / actions ──────────────────────────────────────

(defn- diff-select-line
  "Tap a diff line. First tap selects a single line; a second tap extends the
   range from the anchor; tapping again (range active) restarts at one line."
  [st {:keys [idx]}]
  (let [{:keys [anchor head] :as sel} (:web/diff-sel st)]
    {:state (cond
              (nil? sel)        (assoc st :web/diff-sel {:anchor idx :head idx})
              (= anchor head)   (assoc-in st [:web/diff-sel :head] idx)
              :else             (assoc st :web/diff-sel {:anchor idx :head idx}))}))

(defn- diff-clear-selection [st _]
  {:state (dissoc st :web/diff-sel :web/diff-modify?)})

(defn- diff-modify-toggle [st _]
  {:state (update st :web/diff-modify? not)})

(defn- selected-diff-snippet
  "Pull the diff buffer for room-id, flatten it, and return the snippet text
   for the current selection range (or nil)."
  [st room-id]
  (let [text  (get-in st [:rooms room-id :ui :buffers :diff :text])
        range (diff/selection-range (:web/diff-sel st))]
    (when (and text range)
      (diff/selected-snippet (diff/diff-rows (diff/parse-diff-text text)) range))))

(defn- diff-submit
  "Submit a prompt built from the selected diff region into the room, switch
   back to the chat view, and clear the selection."
  [st room-id prompt]
  {:state   (dissoc st :web/diff-sel :web/diff-modify?)
   :effects [[:ws/send {:type :input/submit :room-id room-id :text prompt}]
             [:app/dispatch {:type :ui/buffer-switch :room-id room-id
                             :buffer-id :chat}]]})

(defn- diff-explain [st {:keys [room-id]}]
  (when-let [snippet (selected-diff-snippet st room-id)]
    (diff-submit st room-id
                 (str "Explain the following changes from our session "
                      "in plain language — what they do and why:\n\n"
                      "```diff\n" snippet "\n```"))))

(defn- diff-modify-submit [st {:keys [room-id text]}]
  (let [instr (some-> text str/trim)]
    (when (and (seq instr))
      (when-let [snippet (selected-diff-snippet st room-id)]
        (diff-submit st room-id
                     (str instr "\n\nApply this to the following code from the diff:\n\n"
                          "```diff\n" snippet "\n```"))))))

;; ── difftastic column width (measured from the viewport) ─────────────────────

(defonce ^:private mono-probe
  ;; Offscreen <pre.diff-difft> kept around so we can read the difft pane's
  ;; resolved monospace font even before a difft buffer has ever mounted.
  (atom nil))

(defn- mono-font-string
  "Canvas-format font shorthand ('12px \"SF Mono\", monospace') for the difft
   <pre>, read from an offscreen probe carrying the same class."
  []
  (let [^js p (or @mono-probe
                  (let [el (.createElement js/document "pre")
                        st (.-style el)]
                    (set! (.-className el) "diff-difft")
                    (set! (.-position st) "absolute")
                    (set! (.-visibility st) "hidden")
                    (set! (.-left st) "-9999px")
                    (set! (.-top st) "0")
                    (.appendChild js/document.body el)
                    (reset! mono-probe el)
                    el))
        cs (js/getComputedStyle p)]
    (str (.-fontSize cs) " " (.-fontFamily cs))))

(def ^:private diff-scrollbar-px
  "Width reserved for the difft pane's vertical scrollbar, so a full-width
   diff doesn't also spill into a horizontal scrollbar."
  16)

(defn- diff-content-px
  "Pixel width available to the difft <pre>: the diff tab's inner width minus
   the pre's horizontal padding (which equals the method bar's, both var(--size-4))
   and the vertical scrollbar. Falls back to the window width before the diff
   view has mounted."
  []
  (let [^js tab (.querySelector js/document ".diff-tab")
        ^js bar (.querySelector js/document ".diff-method-bar")]
    (if tab
      (let [pad (if bar (js/parseFloat (.-paddingLeft (js/getComputedStyle bar))) 0)]
        (max 0 (- (.-clientWidth tab) (* 2 pad) diff-scrollbar-px)))
      (.-innerWidth js/window))))

(defn- measure-diff-cols
  "How many monospace columns fit the diff pane right now. Uses pretext to
   measure the character width (canvas, no DOM reflow) against the live
   content width. Clamped so a too-narrow or unmeasurable pane still works."
  []
  (let [font     (mono-font-string)
        prepared (pretext/prepareWithSegments "0000000000" font)
        char-px  (/ (pretext/measureNaturalWidth prepared) 10)
        px       (diff-content-px)]
    (if (and (pos? char-px) (pos? px))
      (max 40 (js/Math.floor (/ px char-px)))
      120)))

(defn- diff-reopen
  "Re-run /diff for the chosen source + renderer. difftastic is routed through
   the :diff/measure-cols effect, which reads the viewport width and re-issues
   the command as `difft:<cols>` so the output fills the browser width instead
   of difftastic's headless 80-col default."
  [_st {:keys [room-id method engine]}]
  (if (= engine :difft)
    {:effects [[:diff/measure-cols {:room-id room-id :method method}]]}
    {:effects [[:ws/send {:type :input/submit :room-id room-id
                          :text (str "/diff " method)}]]}))


(defn- projects-web-list-result [st {:keys [dirs]}]
  {:state (assoc st :web/project-dirs dirs :web/projects-loading? false)})

(defn- projects-web-sessions-result [st {:keys [cwd sessions]}]
  {:state (assoc st :web/project-sessions sessions
                    :web/project-sessions-cwd cwd
                    :web/project-sessions-loading? false)})

;; ── Session content search ────────────────────────────────────────────────────
;; Names are searchable client-side, but message *content* lives only on the
;; server, so content mode round-trips a query and caches the matching ids.

(defn- content-search-cwd
  "The project scope for a search key — only project-session lists are scoped."
  [st key]
  (when (= key :project-sessions) (:web/selected-project-dir st)))

(defn- session-search
  "Store the query; in content mode also (debounced) ask the server which
   sessions match on message text."
  [st {:keys [key query]}]
  (let [st' (assoc-in st [:web/search key] query)]
    (if (get-in st' [:web/content-search key])
      {:state st'
       :effects [[:session/content-search-debounce
                  {:key key :query query :cwd (content-search-cwd st' key)}]]}
      {:state st'})))

(defn- toggle-content-search
  "Flip a search box between name-only and message-content search. Turning it
   on with a live query kicks off a search immediately."
  [st {:keys [key]}]
  (let [on?   (not (get-in st [:web/content-search key]))
        query (get-in st [:web/search key])
        st'   (-> st
                  (assoc-in [:web/content-search key] on?)
                  (update :web/content-matches dissoc key))]
    (if (and on? (not (str/blank? (str/trim (or query "")))))
      {:state st'
       :effects [[:session/content-search-debounce
                  {:key key :query query :cwd (content-search-cwd st' key)}]]}
      {:state st'})))

(defn- content-search-result
  "Apply a content-search reply, ignoring stale ones whose query no longer
   matches the live input."
  [st {:keys [key query session-ids]}]
  (if (= (str/trim (or query ""))
         (str/trim (or (get-in st [:web/search key]) "")))
    {:state (assoc-in st [:web/content-matches key] (set session-ids))}
    {:state st}))

(defn- bubble-edit-save
  "Commit an inline bubble edit: fork the conversation at the edited message
   (truncate history to before it, like /tree edit) and resubmit the edited
   text as a fresh prompt. Only invoked on Save, so tapping Edit + Cancel is a
   no-op. Empty text just closes the editor without forking."
  [st _]
  (let [{:keys [index text]} (:web/editing-bubble st)
        active  (state/active-room st)
        sid     (get-in st [:web/route :session-id])
        room-id (when (= (get-in active [:session :id]) sid) (:id active))
        t       (str/trim (or text ""))]
    (cond-> {:state (dissoc st :web/editing-bubble)}
      (seq t)
      (assoc :effects
             [[:app/dispatch {:type :tree/navigate :room-id room-id :index index}]
              (if room-id
                [:app/dispatch {:type :input/submit :room-id room-id :text t}]
                [:app/dispatch {:type :submit/pending :session-id sid :text t}])]))))

(defn- prompt-nav-step
  "Move the prompt-nav cursor one step (:prompt-nav/prev = older, :next = newer)
   over the full-history user-prompt indices. Opening jumps to the newest
   prompt. Grows :web/timeline-window when the target prompt sits above the top
   of the rendered window so its node exists for the scroll effect to reach."
  [st {:keys [type user-indices total cur-window]}]
  (let [c (count user-indices)]
    (if (pos? c)
      (let [cur (:web/prompt-nav st)
            idx (if (= type :prompt-nav/next)
                  (min (dec c) (inc (or cur 0)))
                  (if (nil? cur) (dec c) (max 0 (dec cur))))
            hidx (nth user-indices idx)
            ;; +4 entries of context above the target prompt.
            needed (+ (- total hidx) 4)
            win (max (or cur-window 0) needed)]
        {:state (-> st
                    (assoc :web/prompt-nav idx)
                    (assoc :web/timeline-window win))
         :effects [[:prompt-nav/scroll {:history-index hidx}]]})
      {:state st})))

(defn- web-handlers [routes]
  (merge (router/handlers routes)
         {:room/new              room-new
          :room/join             forward
          :room/leave            forward
          :rooms/prune           forward
          :session/counts        forward
          :session/counts-result counts-result
          :session/mark-read     mark-read
          :session/mark-all-read mark-all-read
          ;; Seed a chat's cached history into :web/cache so it paints
          ;; instantly on SPA navigation while the WS :room/joined is in
          ;; flight (esp. on slow mobile links). :room/joined overwrites it.
          :web/cache-seed        (fn [st {:keys [session-id room]}]
                                   (when (and session-id room)
                                     {:state (assoc-in st [:web/cache session-id] room)}))
          ;; The server compared our :cached-msg-hash against the on-disk
          ;; session and confirmed we're current, so it SKIPPED re-sending the
          ;; (potentially large) resume payload. Promote our cached snapshot
          ;; into the room mirror so the chat flips from the "Updating…" hint
          ;; to authoritative with no transfer.
          :session/current
          (fn [st {:keys [room-id session-id msg-hash msg-count]}]
            (let [cached (get-in st [:web/cache session-id])]
              (when (seq (:history cached))
                {:state (cond-> (-> st
                                    (assoc-in [:rooms room-id :history] (:history cached))
                                    (assoc-in [:rooms room-id :msg-hash] msg-hash)
                                    (assoc-in [:rooms room-id :msg-count] msg-count))
                          (:model cached)
                          (assoc-in [:rooms room-id :agent :model] (:model cached)))})))
          ;; Incremental resume: the server confirmed our cache is a clean
          ;; PREFIX of the on-disk session and sent only the new tail messages.
          ;; Append their rendered history onto our cached base instead of
          ;; re-downloading the whole transcript. Guarded on :base-hash so a
          ;; client whose cache doesn't match the prefix ignores the tail (it
          ;; will have gotten a full :session/resumed instead).
          :session/resumed-tail
          (fn [st {:keys [room-id session-id base-hash messages msg-hash msg-count]}]
            (let [cached (get-in st [:web/cache session-id])]
              (when (and (seq (:history cached))
                         (= base-hash (:msg-hash cached)))
                (let [history (into (vec (:history cached))
                                    (commands/messages->history messages))]
                  {:state (cond-> (-> st
                                      (assoc-in [:rooms room-id :history] history)
                                      (assoc-in [:rooms room-id :msg-hash] msg-hash)
                                      (assoc-in [:rooms room-id :msg-count] msg-count))
                            (:model cached)
                            (assoc-in [:rooms room-id :agent :model] (:model cached)))}))))
          :connection/status     connection-status
          ;; ─ Client auth (transport-level handshake, xi.server.ws) ─
          :auth/pending          (fn [st {:keys [code]}]
                                   {:state (assoc st :web/auth {:status :pending :code code})})
          :auth/ok               (fn [st _] {:state (dissoc st :web/auth)})
          :auth/denied           (fn [st _] {:state (assoc st :web/auth {:status :denied})})
          :auth/request          (fn [st {:keys [code client-name platform]}]
                                   {:state (assoc-in st [:web/auth-requests code]
                                                     {:code code
                                                      :client-name client-name
                                                      :platform platform})})
          :auth/resolved         (fn [st {:keys [code]}]
                                   {:state (update st :web/auth-requests dissoc code)})
          ;; Approve/deny a pairing request from this (already-authed) client;
          ;; the server answers with :auth/resolved for everyone else, so
          ;; clear the local banner optimistically.
          :auth/approve          (fn [st {:keys [code] :as ev}]
                                   (when-not (:remote? ev)
                                     {:state (update st :web/auth-requests dissoc code)
                                      :effects [[:ws/send {:type :auth/approve :code code}]]}))
          :auth/deny             (fn [st {:keys [code] :as ev}]
                                   (when-not (:remote? ev)
                                     {:state (update st :web/auth-requests dissoc code)
                                      :effects [[:ws/send {:type :auth/deny :code code}]]}))
          :compose/add-images    compose-add-images
          :compose/remove-image  compose-remove-image
          :compose/clear-images  compose-clear-images
          :compose/set-draft     compose-set-draft
          :compose/clear-draft   compose-clear-draft
          :timeline/set-window   timeline-set-window
          :lightbox/open         lightbox-open
          :lightbox/close        lightbox-close
          :copy/open             (fn [st {:keys [text]}] {:state (assoc st :web/copy-text text)})
          :copy/close            (fn [st _] {:state (dissoc st :web/copy-text)})
          :bubble/menu-open      (fn [st {:keys [index text x y]}]
                                   {:state (assoc st :web/bubble-menu {:index index :text text :x x :y y})})
          :bubble/menu-close     (fn [st _] {:state (dissoc st :web/bubble-menu)})
          ;; Floating Copy button surfaced when a rendered code block (`pre`)
          ;; or inline `code` is tapped (see attach-code-copy-listener!).
          :code/menu-open        (fn [st {:keys [text x y]}]
                                   {:state (assoc st :web/code-menu {:text text :x x :y y})})
          :code/menu-close       (fn [st _] {:state (dissoc st :web/code-menu)})
          :bubble/edit-start     (fn [st {:keys [index text]}]
                                   {:state (-> st
                                               (dissoc :web/bubble-menu)
                                               (assoc :web/editing-bubble {:index index :text text}))})
          :bubble/edit-change    (fn [st {:keys [text]}]
                                   {:state (assoc-in st [:web/editing-bubble :text] text)})
          :bubble/edit-cancel    (fn [st _] {:state (dissoc st :web/editing-bubble)})
          ;; Prompt navigation: jump between the user's own prompts across the
          ;; FULL history. :web/prompt-nav is nil (collapsed) or a 0-based index
          ;; into :user-indices (0 = oldest). Prompts scrolled off the top of the
          ;; render window are reached by growing :web/timeline-window on demand;
          ;; the scroll effect then targets the node by its history index.
          :prompt-nav/prev       prompt-nav-step
          :prompt-nav/next       prompt-nav-step
          :prompt-nav/close      (fn [st _] {:state (dissoc st :web/prompt-nav)
                                             :effects [[:prompt-nav/resume {}]]})
          ;; Timeline scroll-to-bottom (down-arrow beside the prompt-nav
          ;; up-arrow, and the next-arrow while sitting on the newest prompt).
          ;; Closes prompt-nav so the group collapses back to the plain arrow.
          :timeline/scroll-to-bottom
          (fn [st _] {:state (dissoc st :web/prompt-nav)
                      :effects [[:timeline/scroll-bottom {}]]})
          ;; Reflect the timeline's scroll position into state so the
          ;; scroll-to-bottom down-arrow can appear only while scrolled up.
          ;; On the transition INTO scrolled-up, freeze the render window's top
          ;; edge (:web/frozen-window-start) at its current value. While the
          ;; user reads scrolled up, new blocks append BELOW the viewport, so
          ;; freezing the top stops the window from sliding — which would
          ;; otherwise drop DOM nodes above the viewport and (on Safari/iOS,
          ;; which has no scroll anchoring) yank the scroll position. Cleared
          ;; on the way back to the bottom so the window trims normally again.
          :web/set-scrolled-up
          (fn [st {:keys [scrolled-up?]}]
            (let [su (boolean scrolled-up?)]
              (when (not= su (boolean (:web/scrolled-up? st)))
                (if su
                  (let [room  (state/active-room st)
                        total (count (:history room))
                        win   (or (:web/timeline-window st) views/initial-window-size)]
                    {:state (assoc st :web/scrolled-up? true
                                      :web/frozen-window-start (max 0 (- total win)))})
                  {:state (-> st
                              (assoc :web/scrolled-up? false)
                              (dissoc :web/frozen-window-start))}))))
          :bubble/edit-save      bubble-edit-save
          :web/dialog-form-set   (fn [st {:keys [patch]}] {:state (update st :web/dialog-form merge patch)})
          :web/dialog-form-reset (fn [st _] {:state (dissoc st :web/dialog-form)})
          ;; Answered-dialog log (web-only): keep resolved confirm/select
          ;; bubbles in the timeline, anchored to their history position.
          ;; Also drop the live dialog optimistically so the interactive
          ;; bubble swaps to its static record in a single render, instead of
          ;; lingering until the server echoes the removal back.
          :web/dialog-resolved   (fn [st {:keys [room-id dialog-id entry]}]
                                   {:state (-> st
                                               (update-in [:web/resolved-dialogs room-id]
                                                          (fnil conj []) entry)
                                               (update-in [:rooms room-id :ui :dialogs]
                                                          (fn [ds] (vec (remove #(= dialog-id (:id %)) ds)))))})
          :submit/pending        submit-pending
          :submit/clear-pending  submit-clear-pending
          :web/optimistic-set    optimistic-set
          :web/optimistic-clear  optimistic-clear
          :cmd/select            cmd-select
          :web/record-command    record-command
          :theme/set-mode        theme-set-mode
          :web/session-search    session-search
          :web/toggle-content-search toggle-content-search
          :session/content-search (fn [_st {:keys [key query cwd]}]
                                    {:effects [[:ws/send {:type :session/content-search
                                                          :key key :query query :cwd cwd}]]})
          :session/content-search-result content-search-result
          :sidebar/toggle        (fn [st _] {:state (update st :web/sidebar-open? not)})
          :sidebar/open          (fn [st _] {:state (assoc st :web/sidebar-open? true)})
          :sidebar/close         (fn [st _] {:state (assoc st :web/sidebar-open? false)})
          :web/set-wide          (fn [st {:keys [wide?]}] {:state (assoc st :web/wide? wide?)})
          :overflow/toggle       (fn [st _] {:state (update st :web/overflow-menu? not)})
          :overflow/close        (fn [st _] {:state (dissoc st :web/overflow-menu?)})
          :queue/toggle-popover  (fn [st _] {:state (update st :web/queue-popover? not)})
          :queue/close-popover   (fn [st _] {:state (dissoc st :web/queue-popover?)})
          :models/web-list-result (fn [st {:keys [models]}]
                                    {:state (assoc st :web/model-list models)})
          :models/select         (fn [st {:keys [model room-id]}]
                                    {:state (dissoc st :web/model-list :web/palette-page :web/palette-open?)
                                     :effects [[:palette/close nil]
                                               [:ws/send {:type :input/submit
                                                          :room-id room-id
                                                          :text (str "/model " model)}]]})
          :skill/web-list-result (fn [st {:keys [skills]}]
                                    {:state (assoc st :web/skill-list skills)})
          :skill/select          (fn [st {:keys [name room-id]}]
                                    {:state (dissoc st :web/skill-list :web/palette-page :web/palette-open?)
                                     :effects [[:palette/close nil]
                                               [:ws/send {:type :input/submit
                                                          :room-id room-id
                                                          :text (str "/skill load " name)}]]})
          :diff/reopen           diff-reopen
          :diff/select-line      diff-select-line
          :diff/clear-selection  diff-clear-selection
          :diff/modify-toggle    diff-modify-toggle
          :diff/explain          diff-explain
          :diff/modify-submit    diff-modify-submit
          ;; Git status (roomless working-tree diff page)
          :git-status/open       (fn [_st {:keys [cwd]}]
                                   {:effects [[:app/dispatch {:type :route/navigate
                                                              :page :git-status :cwd cwd}]]})
          :git-status/load       (fn [st {:keys [cwd]}]
                                   {:state (assoc st :web/git-status-loading? true
                                                     :web/git-status-cwd cwd
                                                     :web/git-status-text nil)
                                    :effects [[:ws/send {:type :diff/web-load :cwd cwd}]]})
          :git-status/refresh    (fn [st _]
                                   (let [cwd (:web/git-status-cwd st)]
                                     {:state (assoc st :web/git-status-loading? true)
                                      :effects [[:ws/send {:type :diff/web-load :cwd cwd}]]}))
          :diff/web-load-result  (fn [st {:keys [cwd text]}]
                                   {:state (assoc st :web/git-status-text text
                                                     :web/git-status-cwd cwd
                                                     :web/git-status-loading? false)})
          ;; Session bookmarks. Flip locally for a snappy star, then forward:
          ;; the server persists and rebroadcasts an authoritative :lobby/state.
          ;; Project-session listings aren't rebroadcast, so the local flip is
          ;; what keeps that view in sync until it's re-fetched.
          :favorites/toggle      (fn [st {:keys [session-id]}]
                                   (let [flip (fn [ss]
                                                (mapv #(if (= (:session-id %) session-id)
                                                         (update % :favorite? not)
                                                         %)
                                                      ss))]
                                     {:state (-> st
                                                 (update-in [:lobby :sessions] flip)
                                                 (update :web/project-sessions flip))
                                      :effects [[:ws/send {:type :favorites/toggle
                                                           :session-id session-id}]]}))
          ;; Projects
          :projects/web-list     (fn [st _ev]
                                    {:state (assoc st :web/projects-loading? true)
                                     :effects [[:ws/send {:type :projects/web-list}]]})
          :projects/web-list-result projects-web-list-result
          :projects/web-sessions (fn [st {:keys [cwd]}]
                                    {:state (assoc st :web/project-sessions-loading? true)
                                     :effects [[:ws/send {:type :projects/web-sessions :cwd cwd}]]})
          :projects/web-sessions-result projects-web-sessions-result
          :projects/select-dir   (fn [_st {:keys [cwd]}]
                                    {:effects [[:app/dispatch {:type :route/navigate :page :home :dir cwd}]]})
          :projects/back          (fn [st _]
                                    {:state (dissoc st :web/selected-project-dir
                                                      :web/project-sessions
                                                      :web/project-sessions-cwd)})
          ;; Project path picker (insert into compose) — an in-palette sub-page.
          :projects/picker-insert (fn [st {:keys [path draft-key]}]
                                     (let [cur (get-in st [:web/drafts draft-key] "")
                                           sep (if (and (seq cur) (not (str/ends-with? cur " "))) " " "")
                                           new-text (str cur sep path)]
                                       {:state (-> st
                                                   (assoc-in [:web/drafts draft-key] new-text)
                                                   (dissoc :web/palette-page :web/palette-open?))
                                        :effects [[:palette/close nil]
                                                  [:projects/sync-textarea {:text new-text}]]}))
          ;; Snippets picker (insert into compose) — an in-palette sub-page.
          :snippets/web-list-result (fn [st {:keys [global project]}]
                                      {:state (assoc st :web/snippet-list
                                                     {:global (vec global) :project (vec project)})})
          :snippets/picker-insert (fn [st {:keys [text draft-key]}]
                                    (let [cur (get-in st [:web/drafts draft-key] "")
                                          sep (if (and (seq cur) (not (str/ends-with? cur " "))) " " "")
                                          new-text (str cur sep text)]
                                      {:state (-> st
                                                  (assoc-in [:web/drafts draft-key] new-text)
                                                  (dissoc :web/palette-page :web/palette-open?))
                                       :effects [[:palette/close nil]
                                                 [:projects/sync-textarea {:text new-text}]]}))
          :projects/new-session   (fn [st {:keys [cwd]}]
                                    {:state (-> st
                                                (assoc :web/route {:page :chat :session-id nil})
                                                (assoc :web/pending-room {:id (random-uuid) :cwd cwd})
                                                (assoc :web/timeline-window nil)
                                                (assoc :web/sidebar-open? false))
                                     :effects [[:history/push {:route {:page :chat}}]
                                               [:compose/focus]]})
          ;; Command palette second level: Tab on a project row opens its
          ;; action page; back/close return to the top level. The reset-filter
          ;; effect re-syncs ui-runtime.js (clears the query, re-highlights).
          ;; Drill a project row into its action sub-page. Keyboard Tab keeps
          ;; the <dialog> open (preventDefault), so it needs no reopen. A mouse
          ;; click is a `.command-item` click, which the runtime force-closes —
          ;; :reopen? re-opens it (same one-shot :web/palette-drilling? cycle as
          ;; :palette/open-models) so the panel stays open on the sub-page.
          :palette/drill         (fn [st {:keys [cwd label reopen?]}]
                                   {:state (cond-> (assoc st :web/palette-page
                                                          {:kind :project :cwd cwd :label label})
                                             reopen? (assoc :web/palette-drilling? true))
                                    :effects (cond-> [[:palette/reset-filter nil]]
                                               reopen? (conj [:palette/reopen nil]))})
          ;; Change model / /model: drill into an in-palette model picker. The
          ;; runtime force-closes the <dialog> on the item click, so we set a
          ;; one-shot :web/palette-drilling? flag and re-open the dialog (see the
          ;; :palette/reopen effect); :palette/opened keeps the sub-page when the
          ;; flag is set. Clearing :web/model-list makes the page show a spinner
          ;; until the fresh model list arrives.
          ;; The three open-* handlers below open the palette from a compose
          ;; button (not mod+k), so they set :web/palette-open? true directly
          ;; instead of waiting on the async MutationObserver → :palette/opened
          ;; round-trip that the :palette/reopen showModal triggers. On iOS that
          ;; round-trip can race/fail, leaving the dialog natively open but
          ;; rendering the empty shell — a collapsed 65px palette. Flipping the
          ;; flag here guarantees the items render before the dialog is shown.
          :palette/open-models   (fn [st _]
                                   {:state (-> st
                                               (assoc :web/palette-page {:kind :model}
                                                      :web/palette-open? true
                                                      :web/palette-drilling? true)
                                               (dissoc :web/model-list))
                                    :effects [[:ws/send {:type :models/web-list}]
                                              [:palette/reopen nil]]})
          ;; Skills / project path picker: same drill pattern as models.
          :palette/open-skills   (fn [st _]
                                   {:state (-> st
                                               (assoc :web/palette-page {:kind :skill}
                                                      :web/palette-open? true
                                                      :web/palette-drilling? true)
                                               (dissoc :web/skill-list))
                                    :effects [[:ws/send {:type :skill/web-list}]
                                              [:palette/reopen nil]]})
          ;; Session commits: list the commits made this session in a palette
          ;; sub-page. cwd + the session's created timestamp come from the
          ;; mirrored room; the server computes base..HEAD and replies.
          :palette/open-commits  (fn [st _]
                                   (let [room    (state/active-room st)
                                         cwd     (:cwd room)
                                         created (or (get-in room [:session :created])
                                                     (when-let [ms (:created room)]
                                                       (.toISOString (js/Date. ms))))]
                                     {:state (-> st
                                                 (assoc :web/palette-page {:kind :commits}
                                                        :web/palette-open? true
                                                        :web/palette-drilling? true)
                                                 (dissoc :web/commit-list))
                                      :effects [[:ws/send {:type :commits/web-load
                                                           :cwd cwd :created created}]
                                                [:palette/reopen nil]]}))
          :commits/web-load-result (fn [st {:keys [commits]}]
                                     {:state (assoc st :web/commit-list commits)})
          ;; Selecting a commit opens its diff in the room's diff viewer via
          ;; /diff commit:<sha> (originator-only, so only this client flips).
          :commits/open-diff     (fn [st {:keys [sha room-id]}]
                                   {:state (dissoc st :web/palette-page :web/palette-open?)
                                    :effects [[:palette/close nil]
                                              [:ws/send {:type :input/submit
                                                         :room-id room-id
                                                         :text (str "/diff commit:" sha)}]]})
          ;; File browser: open the drill-down browser palette page seeded at
          ;; the active room's cwd. Same drill pattern as commits/models — the
          ;; server lists the directory and replies with :files/web-list-result.
          :palette/open-files    (fn [st _]
                                   (let [cwd (:cwd (state/active-room st))]
                                     {:state (-> st
                                                 (assoc :web/palette-page {:kind :files}
                                                        :web/palette-open? true
                                                        :web/palette-drilling? true)
                                                 (dissoc :web/file-list))
                                      :effects [[:ws/send {:type :files/web-list :cwd cwd :path cwd}]
                                                [:palette/reopen nil]]}))
          ;; Drill into a directory (or step up via ".."): clearing
          ;; :web/file-list shows a spinner while the new listing loads. The
          ;; ui-runtime force-closes the dialog on the item click, so re-open it
          ;; (same one-shot :web/palette-drilling? cycle as the model/commits
          ;; pages). Paths are absolute, so cwd is only a fallback.
          :files/cd              (fn [st {:keys [path]}]
                                   (let [cwd (:cwd (state/active-room st))]
                                     {:state (-> st
                                                 (assoc :web/palette-drilling? true)
                                                 (dissoc :web/file-list))
                                      :effects [[:ws/send {:type :files/web-list :cwd cwd :path path}]
                                                [:palette/reopen nil]]}))
          :files/web-list-result (fn [st {:keys [path parent entries error]}]
                                   {:state (assoc st :web/file-list
                                                  {:path path :parent parent
                                                   :entries (or entries []) :error error})})
          ;; Selecting a file closes the browser and asks the server to read it;
          ;; the reply installs the :file tab (client-local, like the diff tab).
          :files/open            (fn [st {:keys [path]}]
                                   (let [cwd (:cwd (state/active-room st))]
                                     {:state (dissoc st :web/palette-page :web/palette-open?)
                                      :effects [[:palette/close nil]
                                                [:ws/send {:type :file/web-read :cwd cwd :path path}]]}))
          :file/web-read-result  (fn [st {:keys [path text error]}]
                                   (if-let [room-id (:id (state/active-room st))]
                                     {:state (-> st
                                                 (assoc-in [:rooms room-id :ui :buffers :file]
                                                           {:title path :path path
                                                            :text (or text (str "Could not read file:\n" error))})
                                                 (assoc-in [:rooms room-id :ui :active-buffer] :file))}
                                     {:state st}))
          :palette/open-projects (fn [st _]
                                   {:state (assoc st :web/palette-page {:kind :project-insert}
                                                     :web/palette-open? true
                                                     :web/palette-drilling? true)
                                    :effects (cond-> [[:palette/reopen nil]]
                                               (empty? (:web/project-dirs st))
                                               (conj [:ws/send {:type :projects/web-list}]))})
          ;; Snippets: same drill pattern as projects. Always re-fetch (the
          ;; project snippets depend on the active room's cwd, which differs
          ;; per chat); clearing :web/snippet-list shows a spinner meanwhile.
          :palette/open-snippets (fn [st _]
                                   (let [cwd (or (:cwd (state/active-room st))
                                                 (get-in st [:web/pending-room :cwd]))]
                                     {:state (-> st
                                                 (assoc :web/palette-page {:kind :snippets}
                                                        :web/palette-drilling? true)
                                                 (dissoc :web/snippet-list))
                                      :effects [[:ws/send {:type :snippets/web-list :cwd cwd}]
                                                [:palette/reopen nil]]}))
          :palette/back          (fn [st _]
                                   {:state (dissoc st :web/palette-page)
                                    :effects [[:palette/reset-filter nil]]})
          ;; The palette dialog is always in the DOM but its (heavy) contents
          ;; are gated on :web/palette-open? so keystrokes elsewhere don't
          ;; rebuild ~120 hidden command items every render. An on-mount
          ;; MutationObserver on the <dialog> flips the flag when the `open`
          ;; attribute appears; reset-filter re-runs ui-runtime's highlight now
          ;; that the items exist. Microtasks run before paint, so the content
          ;; fills before the dialog is visibly shown.
          :palette/opened        (fn [st _]
                                   (if (:web/palette-drilling? st)
                                     ;; Re-open triggered by a drill (e.g. Change
                                     ;; model): keep the sub-page, consume flag.
                                     {:state (-> st
                                                 (assoc :web/palette-open? true)
                                                 (dissoc :web/palette-drilling?))
                                      :effects [[:palette/reset-filter nil]]}
                                     ;; Fresh mod+k open: always start at the top.
                                     {:state (-> st
                                                 (assoc :web/palette-open? true)
                                                 (dissoc :web/palette-page))
                                      :effects [[:palette/reset-filter nil]]}))
          ;; Keep :web/palette-page here so a drill's close+reopen doesn't lose
          ;; the sub-page; a fresh mod+k open (:palette/opened) resets it.
          :palette/closed        (fn [st _]
                                   {:state (dissoc st :web/palette-open?)})}))


;; Auto-scroll gate (see the Auto-scroll section below). Declared here so the
;; prompt-nav scroll effect can suspend it — jumping to an earlier prompt must
;; not be yanked back to the bottom by the post-render scroll-to-bottom.
(defonce ^:private auto-scroll? (atom true))
(defonce ^:private tracked-timeline (atom nil))
;; Set at init (see below); referenced by the scroll listener to push the
;; scrolled-up flag into state. Declared here so attach-scroll-listener! can
;; reach it without a forward reference.
(defonce ^:private dispatch-ref (atom nil))

(defn- web-effects [routes]
  {:history/push (router/history-effect routes)
   :nav/back     router/back-effect
   :projects/sync-textarea
   (fn [_ {:keys [text]}]
     (when-let [^js el (.querySelector js/document ".compose-input-wrapper textarea")]
       (set! (.-value el) text)
       (.focus el)))
   ;; Focus the compose input when entering a fresh chat. Deferred a frame so
   ;; it runs after the Replicant re-render (the textarea may be freshly
   ;; mounted) and after the command palette's <dialog>.close() restores focus
   ;; to the pre-dialog element — otherwise that restoration clobbers the
   ;; focus. preventScroll avoids a layout jump (matches the on-mount focus).
   :compose/focus
   (fn [_ _]
     (js/requestAnimationFrame
      (fn []
        (when-let [^js el (.querySelector js/document ".compose-input-wrapper textarea")]
          (.focus el #js {:preventScroll true})))))
   ;; After a palette page switch, clear the search box and re-fire `input` so
   ;; ui-runtime.js re-filters the freshly-rendered items and re-highlights the
   ;; first one. Deferred a frame so the Replicant re-render lands first.
   :palette/reset-filter
   (fn [_ _]
     (js/requestAnimationFrame
      (fn []
        (when-let [^js input (.querySelector js/document ".command-dialog[open] .command-input")]
          (set! (.-value input) "")
          (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))))))
   ;; Re-open the command palette after the ui-runtime force-closed it on a
   ;; command-item click (used when drilling into a sub-page). Idempotent:
   ;; __uiCommand.open only calls showModal when the dialog isn't already open.
   :palette/reopen
   (fn [_ _]
     (js/requestAnimationFrame
      (fn []
        (when-let [^js cmd (aget js/window "__uiCommand")]
          (.open cmd "cmdk")))))
   ;; Explicitly close the palette after a leaf action (insert path, select
   ;; model/skill). We can't rely on ui-runtime's document click handler here:
   ;; the on-click dispatch re-renders and detaches the clicked node before the
   ;; runtime runs, so its `target.closest('.command-dialog')` is null and it
   ;; never closes. Closing by id is robust to the detached node.
   :palette/close
   (fn [_ _]
     (when-let [^js cmd (aget js/window "__uiCommand")]
       (.close cmd "cmdk")))
   :diff/measure-cols
   (fn [{:keys [dispatch!]} {:keys [room-id method]}]
     (dispatch! {:type :input/submit :room-id room-id
                 :text (str "/diff difft:" (measure-diff-cols) " " method)}))
   :session/content-search-debounce
  (let [timer (atom nil)]
    (fn [{:keys [dispatch!]} payload]
      (when-let [t @timer] (js/clearTimeout t))
      (reset! timer
              (js/setTimeout
               (fn [] (dispatch! (assoc payload :type :session/content-search)))
               180))))
  :prompt-nav/scroll
   (fn [_ {:keys [history-index]}]
     ;; Suspend auto-scroll so the post-render scroll-to-bottom doesn't fight us.
     (reset! auto-scroll? false)
     ;; The target prompt may live outside the current render window; growing
     ;; :web/timeline-window re-renders it, so poll a few frames for the node.
     (let [sel (str ".timeline .post--user[data-history-index=\"" history-index "\"]")]
       (letfn [(try-scroll [n]
                 (if-let [node (.querySelector js/document sel)]
                   (do
                     (.scrollIntoView node #js {:behavior "smooth" :block "start"})
                     ;; Outline only the current target: clear the mark from any
                     ;; previously-navigated prompt, then flag this one.
                     (doseq [el (array-seq
                                 (.querySelectorAll js/document ".post--nav-target"))]
                       (.remove (.-classList el) "post--nav-target"))
                     (.add (.-classList node) "post--nav-target"))
                   (when (pos? n)
                     (js/requestAnimationFrame #(try-scroll (dec n))))))]
         (try-scroll 30))))
  :prompt-nav/resume
   (fn [_ _]
     ;; Re-enable auto-scroll and snap to the newest content.
     (reset! auto-scroll? true)
     (doseq [el (array-seq (.querySelectorAll js/document ".post--nav-target"))]
       (.remove (.-classList el) "post--nav-target"))
     (when-let [timeline (.querySelector js/document ".timeline")]
       (set! (.-scrollTop timeline) (.-scrollHeight timeline))))
  :timeline/scroll-bottom
   (fn [_ _]
     ;; Re-enable auto-scroll and snap to the newest content (mirrors
     ;; :prompt-nav/resume, but for the standalone scroll-to-bottom arrow).
     (reset! auto-scroll? true)
     (doseq [el (array-seq (.querySelectorAll js/document ".post--nav-target"))]
       (.remove (.-classList el) "post--nav-target"))
     (when-let [timeline (.querySelector js/document ".timeline")]
       (set! (.-scrollTop timeline) (.-scrollHeight timeline))))
  :cache/watch  (fn [_ {:keys [session-id count]}] (cache/watch! session-id count))
   :cache/recent-commands (fn [_ {:keys [commands]}] (cache/save-recent-commands! commands))
   ;; Read a session's cached snapshot and feed it into :web/cache so the chat
   ;; view paints from it while the WS join lands.
   :cache/seed-room
   (fn [{:keys [dispatch!]} {:keys [session-id]}]
     (when-let [room (cache/load-room session-id)]
       (dispatch! {:type :web/cache-seed :session-id session-id :room room})))
   ;; Dispatch a :room/join carrying our cached message-hash + count (if any)
   ;; so the server can skip re-sending an unchanged session's history over the
   ;; wire (answers :session/current), or ship only the new tail when our cache
   ;; is a clean prefix (answers :session/resumed-tail). Reads localStorage,
   ;; hence an effect.
   :room/join-with-cache
   (fn [{:keys [dispatch!]} {:keys [target session-id]}]
     (let [{:keys [msg-hash msg-count]} (cache/load-room session-id)]
       (dispatch! (cond-> {:type :room/join :target target}
                    session-id (assoc :session-id session-id)
                    msg-hash   (assoc :cached-msg-hash msg-hash)
                    msg-count  (assoc :cached-msg-count msg-count)))))
   :theme/apply  (fn [_ mode]
                   (let [el js/document.documentElement]
                     ;; Suppress transitions during switch
                     (.setAttribute el "data-no-transitions" "")
                     (.-offsetHeight el)
                     (js/requestAnimationFrame
                      (fn [] (js/requestAnimationFrame
                              (fn [] (.removeAttribute el "data-no-transitions")))))
                     ;; Apply data-theme
                     (case mode
                       "light" (.setAttribute el "data-theme" "light")
                       "dark"  (.setAttribute el "data-theme" "dark")
                       (.removeAttribute el "data-theme"))
                     ;; Persist
                     (try
                       (if (= mode "auto")
                         (.removeItem js/localStorage "ui-theme")
                         (.setItem js/localStorage "ui-theme" mode))
                       (catch :default _))))})

;; ── Taps (cache persistence + unread polling + post-join URL) ─────────────────

(defn- request-projects-tap
  "On a fresh lobby, fetch the project directory list if not already loaded."
  [dispatch!]
  (fn [event state]
    (when (and (= :lobby/state (:type event))
               (not (get-in state [:lobby :personal-agent?]))
               (nil? (:web/project-dirs state)))
      (dispatch! {:type :projects/web-list}))))

(defn- request-counts-tap
  "On a fresh lobby, ask the server for response counts of the saved
   sessions so the home view can flag unread ones."
  [dispatch!]
  (fn [event state]
    (when (= :lobby/state (:type event))
      (when-let [sids (seq (keep :session-id (get-in state [:lobby :sessions])))]
        (dispatch! {:type :session/counts :session-ids (vec sids)})))))

(defn- mark-read-on-turn-tap
  "When a turn ends in the room the user is currently viewing, mark that
   session read. Closes the auto-exit gap: previously a room could finish a
   turn and then auto-exit (tab closed, no navigation) before the seen-count
   was ever bumped, so the dot wrongly reappeared. Marking read on every
   turn-end while attached records \"I watched it live\" and — since mark-read
   forwards to the server — syncs that across devices."
  [dispatch!]
  (fn [event state]
    (when (= :agent/turn-end (:type event))
      (let [room-id (:room-id event)
            ended   (get-in state [:rooms room-id :session :id])
            viewed  (get-in state [:web/route :session-id])]
        (when (and ended (= ended viewed))
          (dispatch! {:type :session/mark-read :session-id ended}))))))

(defn- fill-url-tap
  "After joining a fresh room (URL has no session id yet), replace the URL
   with the real session id so reload resumes the same session.

   Gated on the :join-token: only the user's OWN new-chat join carries one
   (submit-pending sets it, the server echoes it back). A background reattach
   — a reconnect re-joining the room we were still attached to (the virtual
   new-chat view never leaves the previous room), or any other room joining —
   arrives WITHOUT a token, so it can no longer hijack the URL and yank the
   user off the new-chat view onto that session."
  [dispatch!]
  (fn [event state]
    (when (= :room/joined (:type event))
      (let [sid (get-in event [:room :session :id])]
        (when (and sid
                   (:join-token event)
                   (= :chat (get-in state [:web/route :page]))
                   (nil? (get-in state [:web/route :session-id])))
          (dispatch! {:type :route/navigate :page :chat
                      :session-id sid :replace? true}))))))

(defn- pending-submit-tap
  "Fire a stashed message after the room it was aimed at finishes joining.
   Correlate the reply with our own request so a navigation race (or a mobile
   reconnect) can't send the message into the wrong room:
   - virtual new chat: match the :join-token the server echoes back — a
     bare session-id check would match ANY room, since a fresh room and an
     existing session both carry a non-nil session id.
   - cached session view: match the joined session id.
   Target the room that actually joined (:room-id event), not :active-room."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-submit state))
      (let [{:keys [session-id text images join-token]} (:web/pending-submit state)
            joined-sid   (get-in event [:room :session :id])
            joined-token (:join-token event)
            room-id      (:room-id event)]
        (when (if join-token
                (= join-token joined-token)
                (or (nil? session-id) (= session-id joined-sid)))
          (dispatch! {:type :submit/clear-pending})
          (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                       (seq images) (assoc :images (vec images)))))))))

(defn- record-command-tap
  "Record originating slash-command invocations (:command/run keys) into the
   recently-executed list that feeds the quick-command bar (skips
   mirrored/remote events)."
  [dispatch!]
  (fn [event _state]
    (when (and (= :command/run (:type event))
               (not (:remote? event))
               (views/known-command? (:name event)))
      (dispatch! {:type :web/record-command :name (:name event)}))))

(defn- prompt-nav-close-tap
  "Collapse the prompt-navigation group when the user sends a message or
   switches sessions, so a stale index/count never lingers."
  [dispatch!]
  (fn [event state]
    (when (and (:web/prompt-nav state)
               (or (and (= :input/submit (:type event)) (not (:remote? event)))
                   (= :route/navigate (:type event))))
      (dispatch! {:type :prompt-nav/close}))))

(defn- optimistic-tap
  "Show the user's prompt in the timeline the instant they submit it,
   before the server round-trips a :user history entry back. Cleared when
   the real entry mirrors back (remote :prompt/submit)."
  [dispatch!]
  (fn [event state]
    (cond
      (and (= :input/submit (:type event))
           (not (:remote? event))
           ;; While busy the submission is queued, not sent — the queue count
           ;; is the feedback, so skip the optimistic timeline bubble.
           (not (get-in state [:rooms (:room-id event) :agent :busy?]))
           (let [parsed (commands/parse-input (:text event))]
             (or (= :prompt (:type parsed))
                 (seq (:images event)))))
      (do
        ;; A newly-sent (non-queued) message always jumps to the bottom, even
        ;; if the user had scrolled up into history — re-enable the auto-scroll
        ;; gate so the post-render scroll-to-bottom snaps to the new bubble.
        (reset! auto-scroll? true)
        (dispatch! {:type :web/optimistic-set
                  :room-id (:room-id event)
                  :session-id (get-in state [:web/route :session-id])
                  :text (:text event)
                  :images (:images event)}))

      (and (:remote? event)
           (= :prompt/submit (:type event)))
      (dispatch! {:type :web/optimistic-clear}))))

;; ── Auto-scroll ──────────────────────────────────────────────────────────────

(defn- at-bottom? [^js el]
  (<= (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 40))

(defn- attach-scroll-listener! []
  (when-let [timeline (.querySelector js/document ".timeline")]
    (when-not (identical? timeline @tracked-timeline)
      (reset! tracked-timeline timeline)
      (reset! auto-scroll? true)
      (.addEventListener timeline "scroll"
                         (fn []
                           (let [bottom? (at-bottom? timeline)]
                             (reset! auto-scroll? bottom?)
                             ;; Surface "scrolled up" into state so the
                             ;; scroll-to-bottom down-arrow can toggle. The
                             ;; handler no-ops when the flag is unchanged, so
                             ;; this only re-renders on the two transitions.
                             (when-let [d @dispatch-ref]
                               (d {:type :web/set-scrolled-up
                                   :scrolled-up? (not bottom?)})))))
      ;; Sync the flag once on (re)attach so a fresh timeline starts consistent.
      (when-let [d @dispatch-ref]
        (d {:type :web/set-scrolled-up :scrolled-up? (not (at-bottom? timeline))})))))

(defn- scroll-to-bottom! []
  (when @auto-scroll?
    (when-let [timeline (.querySelector js/document ".timeline")]
      (set! (.-scrollTop timeline) (.-scrollHeight timeline)))))

(defonce ^:private code-copy-attached? (atom false))

(defn- attach-code-copy-listener!
  "Delegated document listeners: right-clicking (mouse) or tapping (touch) a
   rendered code block (`pre`) or inline `code` surfaces a floating Copy button
   near the pointer (:web/code-menu, rendered by chat-view); a plain mouse
   left-click does nothing. Skipped inside user bubbles (which already have
   their own tap menu with Copy) and inline editors. Mirrors the
   right-click/tap contract of user bubbles via views/tap-opens-context-menu?."
  []
  (when-not @code-copy-attached?
    (reset! code-copy-attached? true)
    (let [code-node (fn [^js e]
                      (when-let [node (some-> (.-target e) (.closest "pre, code"))]
                        (when (and (not (.closest node ".post--user"))
                                   (not (.closest node ".bubble-edit-textarea")))
                          node)))
          open!     (fn [^js e ^js node]
                      (when-let [d @dispatch-ref]
                        (let [text (.-textContent node)]
                          (when (seq (str/trim (or text "")))
                            (d {:type :code/menu-open
                                :text text
                                :x (.-clientX e)
                                :y (.-clientY e)})))))]
      (.addEventListener
       js/document "click"
       (fn [^js e]
         (when (views/tap-opens-context-menu?)
           (when-let [node (code-node e)]
             (open! e node)))))
      (.addEventListener
       js/document "contextmenu"
       (fn [^js e]
         (when-let [node (code-node e)]
           (.preventDefault e)
           (open! e node)))))))

;; ── Render ───────────────────────────────────────────────────────────────────

(defn- el [id] (.getElementById js/document id))

;; The composed extension page table (set at init, read by render! so
;; reload! keeps working across hot reloads).
(defonce ^:private pages-ref (atom nil))

(defn- render! [app-state dispatch!]
  (r/render (el "app") (views/root-view app-state dispatch! @pages-ref))
  (attach-scroll-listener!)
  (js/requestAnimationFrame scroll-to-bottom!))

;; ── Init ─────────────────────────────────────────────────────────────────────

(defonce ^:private app-ref (atom nil))

(defn- ws-url
  "WS server URL. Served by the Bun server itself → same origin as the page,
   including behind a reverse proxy on 80/443 (empty location.port → omit the
   port so the WS goes through the proxy, e.g. wss://xi.home). Exception:
   shadow dev-http (8100) isn't the WS server → force 7474. ?host/?port query
   params override."
  []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        proto  (if (= "https:" js/location.protocol) "wss://" "ws://")
        host   (or (.get params "host") js/location.hostname)
        page-port (let [p js/location.port]
                    (cond
                      (= p "8100") "7474"  ; shadow dev-http → real WS port
                      (= p "")     nil     ; standard 80/443 → same-origin
                      :else        p))
        port   (or (.get params "port") page-port)]
    (if port
      (str proto host ":" port)
      (str proto host))))

(defn- web-extensions
  "Browser-safe extension web halves (xi.config/web), composed at init —
   symmetric to server-extensions/client-extensions in xi.cli."
  []
  (ext/instantiate config/web {}))

(defn- ensure-client-key!
  "Persistent random key identifying this browser to the server (the web
   analog of ~/.config/xi/client-key). Unknown keys must be approved once
   via the pairing flow (see xi.server.ws handshake)."
  []
  (or (try (.getItem js/localStorage "xi-client-key") (catch :default _ nil))
      (let [arr (js/Uint8Array. 32)
            _   (.getRandomValues js/crypto arr)
            k   (.join (.from js/Array arr (fn [b] (.padStart (.toString b 16) 2 "0"))) "")]
        (try (.setItem js/localStorage "xi-client-key" k) (catch :default _ nil))
        k)))

(defn- device-name
  "Human label shown in pairing approvals. Client-claimed — the pairing code
   comparison is the actual security, not this label."
  []
  (let [ua (.-userAgent js/navigator)]
    (cond
      (re-find #"iPhone" ua)  "iPhone (web)"
      (re-find #"iPad" ua)    "iPad (web)"
      (re-find #"Android" ua) "Android (web)"
      (re-find #"Mac" ua)     "Mac (web)"
      (re-find #"Linux" ua)   "Linux (web)"
      :else                   "Browser")))

(defn- demo-init!
  "Static one-shot render for README screenshots (?demo=<view>). Seeds
   fabricated data, skips the WS transport entirely, and renders once with a
   no-op dispatch so the page shows a fully-populated view backed by no real
   data. Color scheme is left on `auto` so a devtools emulation can drive
   light/dark."
  [view]
  (js/console.log "[xi-web] demo mode:" view)
  (let [composed (ext/compose (web-extensions))]
    (reset! pages-ref (:pages composed))
    (.setProperty (.-style js/document.documentElement) "--app-height" "100dvh")
    (r/render (el "app")
              (views/root-view (assoc (demo/demo-state view)
                                      :web/nav-items (:nav-items composed))
                               (fn [& _])
                               (:pages composed)))))

(defn- reset-zoom!
  "Force iOS WebKit back to scale=1. In a standalone PWA, resuming from the
   background can restore the page at a stuck visual-viewport scale > 1 — the
   whole app looks zoomed until the keyboard is first opened, which focuses an
   input and snaps the scale back. Momentarily tightening maximum-scale below
   the current scale makes WebKit clamp the zoom down; restoring the original
   viewport meta on the next frame settles it at 1. No-op unless the viewport
   is actually zoomed, so it stays inert on desktop."
  []
  (let [vv    js/window.visualViewport
        scale (some-> vv .-scale)]
    (when (and scale (> scale 1.01))
      (when-let [meta (.querySelector js/document "meta[name=viewport]")]
        (let [content (.getAttribute meta "content")]
          (.setAttribute meta "content" (str content ", maximum-scale=0.99"))
          (js/requestAnimationFrame
           (fn [] (.setAttribute meta "content" content))))))))

(defn- real-init! []
  (let [composed  (ext/compose (web-extensions))
        routes    (:routes composed)
        stored-theme (or (try (.getItem js/localStorage "ui-theme") (catch :default _ nil))
                        "auto")
        route     (router/parse-path routes (.-pathname js/window.location))
        initial   (-> (state/initial-state {:mode :client})
                      (assoc :web/theme-mode stored-theme
                             :web/nav-items (:nav-items composed))
                      (cache/hydrate route))
        transport (ws-transport/create!
                   {:url        (ws-url)
                    :hello      {:client-key  (ensure-client-key!)
                                 :client-name (device-name)
                                 :platform    "web"}
                    ;; nil → the router drives joins; reconnect replays them.
                    :target     nil
                    :reconnect? true
                    :on-status  (fn [connected?]
                                  (when-let [d @dispatch-ref]
                                    (d {:type :connection/status
                                        :connected? connected?})))})
        {:keys [dispatch! state add-tap!] :as app}
        (app/create-app {:initial-state initial
                         :handlers      (ws-transport/make-handlers
                                         (base-handlers)
                                         {:local-handlers
                                          (ext/merge-handlers (web-handlers routes) composed)})
                         :effects       (merge (:effects transport)
                                               (web-effects routes)
                                               (:fx composed))
                         :on-render     render!})]
    (reset! pages-ref (:pages composed))
    (reset! dispatch-ref dispatch!)
    (reset! app-ref app)
    ((:set-dispatch! transport) dispatch!)
    (add-tap! cache/persist-tap)
    (add-tap! (request-counts-tap dispatch!))
    (add-tap! (mark-read-on-turn-tap dispatch!))
    (add-tap! (request-projects-tap dispatch!))
    (add-tap! (fill-url-tap dispatch!))
    (add-tap! (pending-submit-tap dispatch!))
    (add-tap! (optimistic-tap dispatch!))
    (add-tap! (record-command-tap dispatch!))
    (add-tap! (prompt-nav-close-tap dispatch!))
    (doseq [make-tap (:taps composed)]
      (add-tap! (make-tap dispatch!)))
    (router/init! routes dispatch!)
    ;; Apply stored theme immediately (before first render)
    (dispatch! {:type :theme/set-mode :mode stored-theme})
    ;; Track visual viewport height so the mobile keyboard doesn't push
    ;; the compose box off-screen.  Falls back to window.innerHeight.
    (let [set-vh! (fn []
                    (let [vv       js/window.visualViewport
                          ;; Layout viewport — on iOS this does NOT shrink
                          ;; when the soft keyboard overlays the page, so the
                          ;; gap between it and the visual viewport tells us
                          ;; whether the keyboard is open.
                          layout-h (.-clientHeight js/document.documentElement)
                          visual-h (if vv (.-height vv) js/window.innerHeight)
                          root     js/document.documentElement
                          style    (.-style root)
                          kb-open? (> (- layout-h visual-h) 100)
                          ;; Capture the timeline's scroll geometry before the
                          ;; --app-height change resizes it, so we can keep the
                          ;; same line pinned just above the compose box.
                          timeline (.querySelector js/document ".timeline")
                          prev-h   (some-> timeline .-clientHeight)
                          prev-top (some-> timeline .-scrollTop)]
                      ;; Expose keyboard state to CSS so layout that assumes the
                      ;; home-indicator safe area (e.g. the compose row's bottom
                      ;; inset) can drop it while the keyboard covers that area.
                      (.toggle (.-classList root) "keyboard-open" kb-open?)
                      (if kb-open?
                        ;; Keyboard is open: iOS overlays it without resizing
                        ;; the layout viewport, so pin the layout to the
                        ;; (smaller) visual viewport and scroll back to origin
                        ;; to keep the compose box pinned above the keyboard.
                        (do (.setProperty style "--app-height" (str visual-h "px"))
                            (.scrollTo js/window 0 0))
                        ;; At rest: use 100dvh, which reports the real
                        ;; standalone window height (screen minus the status
                        ;; bar iOS reserves at the top). 100vh reports the full
                        ;; physical screen, making the app taller than the
                        ;; window and pushing the footer/compose box off the
                        ;; bottom.
                        (.setProperty style "--app-height" "100dvh"))
                      ;; Reading clientHeight forces the reflow the height
                      ;; change queued; the timeline shrank (keyboard opening)
                      ;; or grew (closing) from its bottom edge, so shift
                      ;; scrollTop by the delta to keep the content that was
                      ;; just above the prompt bar in view.
                      (when (and timeline prev-h)
                        (let [delta (- prev-h (.-clientHeight timeline))]
                          (when-not (zero? delta)
                            (set! (.-scrollTop timeline) (+ prev-top delta)))))))]
      (set-vh!)
      (if js/window.visualViewport
        (do (.addEventListener js/window.visualViewport "resize" (fn [_] (set-vh!)))
            (.addEventListener js/window.visualViewport "scroll" (fn [_] (set-vh!))))
        (.addEventListener js/window "resize" (fn [_] (set-vh!))))
      ;; Resuming a standalone PWA from the background can restore the page
      ;; zoomed (visual-viewport scale stuck > 1) and with a stale height,
      ;; until the keyboard is first opened. Re-sync the viewport height and
      ;; force the scale back to 1 whenever the app becomes visible again. The
      ;; rAF pass re-runs it after WebKit has settled the restored scale.
      (let [repaint-drawer!
            (fn []
              ;; iOS WebKit can restore a standalone PWA showing a STALE
              ;; composited frame of the recent-sessions drawer: it looks open
              ;; (stuck at translateX(0)) even though its state is closed, until
              ;; a later interaction — focusing the compose box, which opens the
              ;; keyboard and resizes the viewport — forces a repaint. Drop the
              ;; drawer's layer for a frame so it re-rasterizes at its true
              ;; (closed, off-screen) position. Skipped when the drawer is docked
              ;; (wide screens) or genuinely open, so we never flash a visible
              ;; drawer.
              (let [s @state]
                (when (and (not (:web/wide? s)) (not (:web/sidebar-open? s)))
                  (when-let [^js el (.querySelector js/document ".sidebar-layout--floating > .sidebar")]
                    (let [st (.-style el)]
                      (set! (.-display st) "none")
                      (js/requestAnimationFrame (fn [] (set! (.-display st) ""))))))))
            on-resume (fn []
                        (reset-zoom!)
                        (set-vh!)
                        (repaint-drawer!)
                        (js/requestAnimationFrame
                         (fn [] (reset-zoom!) (set-vh!))))]
        (.addEventListener js/document "visibilitychange"
                           (fn [_] (when (= "visible" (.-visibilityState js/document))
                                     (on-resume))))
        (.addEventListener js/window "pageshow" (fn [_] (on-resume)))))
    (views/install-pointer-type-tracker!)
    (attach-code-copy-listener!)
    ;; Track wide viewports so the sidebar can dock (always-visible, no overlay)
    ;; at >=1024px. The matching CSS lives in style.css; this only keeps the
    ;; :web/wide? flag in sync so the docked drawer actually renders content.
    (let [mql (.matchMedia js/window "(min-width: 1024px)")]
      (dispatch! {:type :web/set-wide :wide? (.-matches mql)})
      (.addEventListener mql "change"
                         (fn [e] (dispatch! {:type :web/set-wide :wide? (.-matches e)}))))
    ;; Left-edge swipe to open the sidebar; swipe left again to close it.
    ;;
    ;; iOS/WebKit reserves the extreme left edge (~first 20px) for its own
    ;; interactive back gesture and web content can't intercept it, so we do
    ;; NOT anchor on the very edge — the open zone is the left ~20% of the
    ;; viewport (capped), which catches the natural swipe most people start a
    ;; little inward. Gated on horizontal dominance and a distance threshold
    ;; (tracked across touchmove, since touchend alone can miss the peak), and
    ;; ignored when the drag begins inside a horizontally-scrollable element
    ;; (code blocks, wide tables) so it doesn't hijack their scroll.
    (let [thresh-px 60
          scrollable-x?
          (fn [node]
            (loop [n node]
              (cond
                (or (nil? n) (not (instance? js/Element n))) false
                (and (> (.-scrollWidth n) (+ (.-clientWidth n) 1))
                     (let [ox (.-overflowX (js/getComputedStyle n))]
                       (or (= ox "auto") (= ox "scroll"))))
                true
                :else (recur (.-parentElement n)))))
          start (atom nil)]
      (.addEventListener
       js/document "touchstart"
       (fn [e]
         (let [t (aget (.-touches e) 0)]
           (reset! start (when (and t (= 1 (.-length (.-touches e))))
                           {:x       (.-clientX t)
                            :y       (.-clientY t)
                            :dx      0
                            :dy      0
                            :scroll? (scrollable-x? (.-target e))}))))
       #js {:passive true})
      (.addEventListener
       js/document "touchmove"
       (fn [e]
         (when-let [{:keys [x y] :as s} @start]
           (when-let [t (aget (.-touches e) 0)]
             (reset! start (assoc s
                                  :dx (- (.-clientX t) x)
                                  :dy (- (.-clientY t) y))))))
       #js {:passive true})
      (.addEventListener
       js/document "touchcancel"
       (fn [_] (reset! start nil))
       #js {:passive true})
      (.addEventListener
       js/document "touchend"
       (fn [_]
         (when-let [{:keys [x dx dy scroll?]} @start]
           (reset! start nil)
           (let [open-zone (min 100 (* 0.2 (or (.-innerWidth js/window) 0)))]
             (when (and (not scroll?)
                        (> (js/Math.abs dx) (js/Math.abs dy)))
               (cond
                 (and (not (:web/sidebar-open? @state))
                      (<= x open-zone) (>= dx thresh-px))
                 (dispatch! {:type :sidebar/open})

                 (and (:web/sidebar-open? @state) (<= dx (- thresh-px)))
                 (dispatch! {:type :sidebar/close}))))))
       #js {:passive true})
      ;; Suppress iOS' native left-edge back-swipe (iOS 13.4+) so a swipe that
      ;; starts right at the edge falls through to the open gesture above
      ;; instead of navigating history. A non-passive touchstart that
      ;; preventDefaults only within ~20px of the LEFT edge is the documented
      ;; way to do this; scoped to the left edge (back-nav) so the right-edge
      ;; forward gesture is untouched, and the strip is thin enough that normal
      ;; taps/scrolls are effectively unaffected. (Best-effort: some standalone
      ;; PWA builds still ignore it — the inward open-zone above is the
      ;; fallback for those.)
      (.addEventListener
       js/document "touchstart"
       (fn [e]
         (when-let [t (aget (.-touches e) 0)]
           (when (and (not (:web/sidebar-open? @state))
                      (<= (.-pageX t) 20))
             (.preventDefault e))))
       #js {:passive false}))
    ;; Prevent iOS Safari smart-zoom (double-tap & pinch)
    (.addEventListener js/document "gesturestart" (fn [e] (.preventDefault e)))
    (.addEventListener js/document "gesturechange" (fn [e] (.preventDefault e)))
    (.addEventListener js/document "gestureend" (fn [e] (.preventDefault e)))
    (.addEventListener js/document "visibilitychange"
                       (fn [_]
                         (dispatch! {:type :client/update
                                     :visible? (= "visible" (.-visibilityState js/document))})))
    (render! @state dispatch!)))

(defn ^:export init! []
  (js/console.log "[xi-web] starting")
  (r/set-dispatch! (fn [_ _]))
  (if-let [view (.get (js/URLSearchParams. (.-search js/window.location)) "demo")]
    (demo-init! view)
    (real-init!)))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (when-let [{:keys [state dispatch!]} @app-ref]
    (render! @state dispatch!)))
