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

(defn- room-new
  "Open a fresh *virtual* chat: switch to the chat view but create no server
   room yet. The room stays client-only (launch header, no spinner, nothing to
   clean up) until the first prompt, which fires :room/join + submits via
   submit-pending / pending-submit-tap."
  [st _]
  {:state   (-> st
                (assoc :web/route {:page :chat :session-id nil})
                (assoc :web/pending-room {:id (random-uuid) :cwd nil})
                (assoc :web/timeline-window nil))
   :effects [[:history/push {:route {:page :chat}}]]})

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
       :effects [[:cache/watch {:session-id sid :count cnt}]]}
      {:state (assoc st :web/response-counts counts)})))

(defn- mark-read
  "Mark a session read at its current response count (clears the unread dot)."
  [st {:keys [session-id]}]
  (let [cnt (get-in st [:web/response-counts session-id] 0)]
    {:state   (assoc-in st [:web/watched session-id] cnt)
     :effects [[:cache/watch {:session-id session-id :count cnt}]]}))

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
     navigation, so just stash and wait."
  [st {:keys [session-id text images]}]
  (let [pending  (:web/pending-room st)
        virtual? (and (nil? session-id) (some? pending))
        cwd      (:cwd pending)]
    (cond-> {:state (-> st
                        (assoc :web/pending-submit
                               (cond-> {:session-id session-id :text text}
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
      (assoc :effects [[:ws/send (cond-> {:type :room/join :target "new"}
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
          :sidebar/close         (fn [st _] {:state (assoc st :web/sidebar-open? false)})
          :overflow/toggle       (fn [st _] {:state (update st :web/overflow-menu? not)})
          :overflow/close        (fn [st _] {:state (dissoc st :web/overflow-menu?)})
          :queue/toggle-popover  (fn [st _] {:state (update st :web/queue-popover? not)})
          :queue/close-popover   (fn [st _] {:state (dissoc st :web/queue-popover?)})
          :models/web-list       forward
          :models/web-list-result (fn [st {:keys [models]}]
                                    {:state (assoc st :web/model-list models)})
          :models/select         (fn [st {:keys [model room-id]}]
                                    {:state (-> st
                                                (dissoc :web/model-list)
                                                (update :web/selector-search dissoc "model"))
                                     :effects [[:ws/send {:type :input/submit
                                                          :room-id room-id
                                                          :text (str "/model " model)}]]})
          :models/close          (fn [st _] {:state (-> st
                                                        (dissoc :web/model-list)
                                                        (update :web/selector-search dissoc "model"))})
          :selector/search       (fn [st {:keys [id query]}]
                                   {:state (assoc-in st [:web/selector-search id] query)})
          :skill/web-list        forward
          :skill/web-list-result (fn [st {:keys [skills]}]
                                    {:state (assoc st :web/skill-list skills)})
          :skill/select          (fn [st {:keys [name room-id]}]
                                    {:state (dissoc st :web/skill-list)
                                     :effects [[:ws/send {:type :input/submit
                                                          :room-id room-id
                                                          :text (str "/skill load " name)}]]})
          :skill/close           (fn [st _] {:state (dissoc st :web/skill-list)})
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
          ;; Project path picker (insert into compose)
          :projects/picker-open  (fn [st _]
                                    ;; Reuse already-loaded dirs, or fetch them
                                    (cond-> {:state (assoc st :web/project-picker? true)}
                                      (empty? (:web/project-dirs st))
                                      (assoc :effects [[:ws/send {:type :projects/web-list}]])))
          :projects/picker-close (fn [st _]
                                    {:state (dissoc st :web/project-picker?)})
          :projects/picker-insert (fn [st {:keys [path draft-key]}]
                                     (let [cur (get-in st [:web/drafts draft-key] "")
                                           sep (if (and (seq cur) (not (str/ends-with? cur " "))) " " "")
                                           new-text (str cur sep path)]
                                       {:state (-> st
                                                   (assoc-in [:web/drafts draft-key] new-text)
                                                   (dissoc :web/project-picker?))
                                        :effects [[:projects/sync-textarea {:text new-text}]]}))
          :projects/new-session   (fn [st {:keys [cwd]}]
                                    {:state (-> st
                                                (assoc :web/route {:page :chat :session-id nil})
                                                (assoc :web/pending-room {:id (random-uuid) :cwd cwd})
                                                (assoc :web/timeline-window nil)
                                                (assoc :web/sidebar-open? false))
                                     :effects [[:history/push {:route {:page :chat}}]]})}))


;; Auto-scroll gate (see the Auto-scroll section below). Declared here so the
;; prompt-nav scroll effect can suspend it — jumping to an earlier prompt must
;; not be yanked back to the bottom by the post-render scroll-to-bottom.
(defonce ^:private auto-scroll? (atom true))
(defonce ^:private tracked-timeline (atom nil))

(defn- web-effects [routes]
  {:history/push (router/history-effect routes)
   :nav/back     router/back-effect
   :projects/sync-textarea
   (fn [_ {:keys [text]}]
     (when-let [^js el (.querySelector js/document ".compose-input-wrapper textarea")]
       (set! (.-value el) text)
       (.focus el)))
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
  :cache/watch  (fn [_ {:keys [session-id count]}] (cache/watch! session-id count))
   :cache/recent-commands (fn [_ {:keys [commands]}] (cache/save-recent-commands! commands))
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

(defn- fill-url-tap
  "After joining a fresh room (URL has no session id yet), replace the URL
   with the real session id so reload resumes the same session."
  [dispatch!]
  (fn [event state]
    (when (= :room/joined (:type event))
      (let [sid (get-in event [:room :session :id])]
        (when (and sid
                   (= :chat (get-in state [:web/route :page]))
                   (nil? (get-in state [:web/route :session-id])))
          (dispatch! {:type :route/navigate :page :chat
                      :session-id sid :replace? true}))))))

(defn- pending-submit-tap
  "Fire a stashed message after the room it was aimed at finishes joining.
   Guards against session-id mismatch so a navigation race can't send a
   message into the wrong room."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-submit state))
      (let [{:keys [session-id text images]} (:web/pending-submit state)
            joined-sid (get-in event [:room :session :id])
            room-id    (:active-room state)]
        (when (or (nil? session-id) (= session-id joined-sid))
          (dispatch! {:type :submit/clear-pending})
          (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                       (seq images) (assoc :images (vec images)))))))))

(defn- record-command-tap
  "Record originating slash-command submissions into the recently-executed
   list that feeds the quick-command bar (skips mirrored/remote events and
   plain prompts)."
  [dispatch!]
  (fn [event _state]
    (when (and (= :input/submit (:type event))
               (not (:remote? event)))
      (let [parsed (commands/parse-input (:text event))]
        (when (and (= :command (:type parsed))
                   (views/known-command? (:name parsed)))
          (dispatch! {:type :web/record-command :name (:name parsed)}))))))

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
      (dispatch! {:type :web/optimistic-set
                  :room-id (:room-id event)
                  :session-id (get-in state [:web/route :session-id])
                  :text (:text event)
                  :images (:images event)})

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
                           (reset! auto-scroll? (at-bottom? timeline)))))))

(defn- scroll-to-bottom! []
  (when @auto-scroll?
    (when-let [timeline (.querySelector js/document ".timeline")]
      (set! (.-scrollTop timeline) (.-scrollHeight timeline)))))

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
(defonce ^:private dispatch-ref (atom nil))

(defn- ws-url
  "WS server URL. Served by the Bun server itself → same port as the page;
   shadow dev-http (8100) isn't the WS server → default 7474. ?host/?port
   query params override."
  []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        proto  (if (= "https:" js/location.protocol) "wss://" "ws://")
        host   (or (.get params "host") js/location.hostname)
        page-port (let [p js/location.port]
                    (when-not (or (= p "") (= p "8100")) p))
        port   (or (.get params "port") page-port "7474")]
    (str proto host ":" port)))

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
        (.addEventListener js/window "resize" (fn [_] (set-vh!)))))
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
