(ns xi.web.core
  "Web client entry — the browser app shell.

   Same pure core as every other mode (xi.core.app), wired in :client mode
   through xi.client.ws-transport: local events forward to the server,
   :remote? broadcasts mirror into the local room cache with effects
   stripped. The router lives in the state atom (:web/route); xi.web.cache
   hydrates state before the WS connects and persists via an app tap."
  (:require [clojure.string :as str]
            [replicant.dom :as r]
            [xi.agent :as agent]
            [xi.buffers :as buffers]
            [xi.client.ws-transport :as ws-transport]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.dialog :as dlg]
            [xi.diff :as diff]
            [xi.ext.core :as ext]
            [xi.config :as config]
            [xi.naming :as naming]
            [xi.quick-replies :as quick-replies]
            [xi.util :as util]
            [xi.web.appearance :as appearance]
            [xi.web.theme :as theme]
            [xi.web.user-state :as user-state]
            [xi.web.cache :as cache]
            [xi.web.dashboard :as dashboard]
            [xi.web.demo :as demo]
            [xi.web.key-hints :as key-hints]
            [xi.web.flip :as flip]
            [xi.web.keymap :as keymap]
            [xi.web.models :as models]
            [xi.web.prefetch :as prefetch]
            [xi.web.resubmit :as resubmit]
            [xi.web.router :as router]
            [xi.web.title :as title]
            [xi.web.user-ext :as user-ext]
            [xi.session.sidebar :as sidebar]
            [xi.web.views :as views]))

;; ── Base handlers (browser-safe merge) ───────────────────────────────────────

(defn- base-handlers
  "The pure handler map shared with the server, sans node-coupled chains.
   The quick-reply gen-tracking chains are kept so mirrored suggestions
   pass the :gen check (see xi.quick-replies)."
  []
  (-> (merge events/core-handlers
             agent/handlers
             (commands/command-handlers)
             compaction/handlers
             naming/handlers
             quick-replies/handlers)
      (assoc :agent/turn-end (events/chain (:agent/turn-end agent/handlers)
                                           quick-replies/maybe-suggest)
             :prompt/submit  (events/chain (:prompt/submit agent/handlers)
                                           quick-replies/clear-on-submit))))

;; ── Web-local handlers (installed unwrapped; never mirrored) ──────────────────

(defn- forward
  [_st ev]
  {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]})

(defn- room-new-cwd
  "cwd for a fresh chat: the current chat's, else the viewed project's, else
   nil (server default)."
  [st]
  (let [sid (get-in st [:web/route :session-id])
        dir (get-in st [:web/route :dir])]
    (or (:cwd (state/active-room st))
        (some (fn [r] (when (= sid (:session-id r)) (:cwd r)))
              (get-in st [:lobby :rooms]))
        (some (fn [s] (when (= sid (:session-id s)) (:cwd s)))
              (get-in st [:lobby :sessions]))
        (when (string? dir) dir))))

(defn- new-chat-view?
  "True while the chat view shows a virtual new chat (:web/pending-room, no
   session id). The active room is then the previous room, so anything scoped
   to the viewed room must read the pending room instead."
  [st]
  (and (some? (:web/pending-room st))
       (nil? (get-in st [:web/route :session-id]))))

(defn- viewed-history
  "The history the chat view paints (xi.web.views/chat-history): the active
   room's once it is the routed session, else the cached one. Mid-switch the
   client is still attached to the previous room."
  [st]
  (let [sid  (get-in st [:web/route :session-id])
        room (state/active-room st)]
    (if (and (seq (:history room)) (= sid (get-in room [:session :id])))
      (:history room)
      (or (get-in st [:web/cache sid :history]) (:history room)))))

(defn- view-cwd
  [st]
  (if (new-chat-view? st)
    (get-in st [:web/pending-room :cwd])
    (:cwd (state/active-room st))))

(defn- open-pending-room
  "Show `pending` as the virtual new chat, parking a typed-in chat as a
   sidebar draft first."
  [st pending]
  (let [cwd (:cwd pending)]
    {:state   (-> (router/stash-draft-chat st)
                  (assoc :web/route {:page :chat :session-id nil})
                  (assoc :web/pending-room pending)
                  (assoc :web/timeline-window nil))
     :effects (cond-> [[:history/push {:route {:page :chat}}]
                       [:compose/focus]]
                cwd (conj [:ws/send {:type :cwd/agents-files :cwd cwd}]))}))

(defn- fresh-pending-room [st cwd]
  (cond-> {:id (random-uuid) :cwd cwd}
    (:web/preferred-model st) (assoc :model (:web/preferred-model st))))

(defn- room-new
  "Open a fresh virtual chat: no server room until the first prompt, which
   joins and submits via submit-pending / pending-submit-tap."
  [st _]
  (open-pending-room st (fresh-pending-room st (room-new-cwd st))))

(defn- draft-chat-open
  "Resume a parked draft chat (see router/stash-draft-chat) from the sidebar."
  [st {:keys [id]}]
  (when-let [pending (some #(when (= id (:id %)) %) (:web/draft-chats st))]
    (-> (update st :web/draft-chats #(filterv (fn [r] (not= id (:id r))) %))
        (open-pending-room pending)
        (assoc-in [:state :web/sidebar-open?] false))))

(defn- draft-chat-discard
  [st {:keys [id]}]
  {:state (-> st
              (update :web/draft-chats #(filterv (fn [r] (not= id (:id r))) %))
              (update :web/drafts dissoc id)
              (update :web/compose-images dissoc id))})

(defn- counts-result
  "Store per-session response counts (ride along on :lobby/state). A session
   just left (:web/pending-read) is marked read at this fresh count."
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
  [st {:keys [session-id]}]
  (let [cnt (get-in st [:web/response-counts session-id] 0)]
    {:state   (assoc-in st [:web/watched session-id] cnt)
     :effects [[:cache/watch {:session-id session-id :count cnt}]
               [:ws/send {:type :session/mark-read :session-id session-id}]]}))

(defn- mark-all-read
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

(defn- dismiss-all
  "Hide every visible idle session from Recent: flip the local :dismissed?
   overlay, then forward a :dismissed/toggle per session. Pinned, dismissed
   and in-progress (busy or awaiting a dialog) sessions are left alone."
  [st _]
  (let [in-progress (->> (get-in st [:lobby :rooms])
                         (filter #(or (:busy? %) (:has-dialog? %)))
                         (map :session-id)
                         (filter some?)
                         set)
        target-ids  (->> (get-in st [:lobby :sessions])
                         (remove :dismissed?)
                         (remove :pinned?)
                         (map :session-id)
                         (filter some?)
                         (remove in-progress)
                         set)
        flip (fn [ss]
               (mapv #(if (target-ids (:session-id %))
                        (assoc % :dismissed? true)
                        %)
                     ss))]
    {:state   (-> st
                  (update-in [:lobby :sessions] flip)
                  (update :web/project-sessions flip))
     :effects (into [] (map (fn [sid]
                              [:ws/send {:type :dismissed/toggle :session-id sid}]))
                    target-ids)}))

(defn- connection-status [st {:keys [connected?]}]
  {:state (assoc st :web/connected? connected?)})

(defn room-joined-from-cache
  ":room/joined, splicing a cache-elided history back in. With a matching
   fingerprint the server sends :history-base {:hash :count} plus only the
   newer :history-tail (xi.server.room-manager/joined-payload); the base is
   the :web/cache snapshot. A stale cache paints what it has and re-joins
   without the fingerprint for a full snapshot."
  [st {:keys [room-id room history-base history-tail] :as ev}]
  (if-not history-base
    (ws-transport/room-joined st ev)
    (let [cached (get-in st [:web/cache (get-in room [:session :id])])
          base   (:history cached)]
      (if (and (vector? base)
               (= (:count history-base) (count base))
               (= (:hash history-base) (:history-hash cached)))
        (ws-transport/room-joined
         st (assoc ev :room (assoc room :history (if (seq history-tail)
                                                   (into base history-tail)
                                                   base))))
        (-> (ws-transport/room-joined st (assoc ev :room (assoc room :history (or base []))))
            (assoc :effects [[:ws/send {:type :room/join :target room-id}]]))))))

(defn- room-left-web
  "The transport's :room/left, plus navigating home when the viewed room was
   closed under us (deleted elsewhere, pruned)."
  [st {:keys [room-id] :as ev}]
  (let [sid      (get-in st [:rooms room-id :session :id])
        viewing? (and sid
                      (= :chat (get-in st [:web/route :page]))
                      (= sid (get-in st [:web/route :session-id])))]
    (cond-> (ws-transport/room-left st ev)
      viewing? (update :effects (fnil conj [])
                       [:app/dispatch {:type :route/navigate :page :home}]))))

(defn- compose-add-images
  [st {:keys [draft-key images]}]
  {:state (update-in st [:web/compose-images draft-key] (fnil into []) images)})

(defn- compose-remove-image [st {:keys [draft-key idx]}]
  {:state (update-in st [:web/compose-images draft-key]
                     (fn [imgs] (into (subvec imgs 0 idx) (subvec imgs (inc idx)))))})

(defn- compose-clear-images [st {:keys [draft-key]}]
  {:state (update st :web/compose-images dissoc draft-key)})

(defn- compose-set-draft
  [st {:keys [draft-key text]}]
  {:state (-> (if (seq text)
                (assoc-in st [:web/drafts draft-key] text)
                (update st :web/drafts dissoc draft-key))
              (assoc :web/cmd-selected 0))})

(defn- compose-clear-draft [st {:keys [draft-key]}]
  {:state (update st :web/drafts dissoc draft-key)})

(defn- timeline-set-window
  [st {:keys [window]}]
  {:state (assoc st :web/timeline-window window)})

(defn- lightbox-open [st {:keys [src]}]
  {:state (assoc st :web/lightbox src)})

(defn- lightbox-close [st _]
  {:state (dissoc st :web/lightbox)})

(defn- file-drag
  [st {:keys [on?]}]
  (when (not= (boolean on?) (boolean (:web/file-drag? st)))
    {:state (if on? (assoc st :web/file-drag? true) (dissoc st :web/file-drag?))}))

(defn- submit-pending
  "Stash a message submitted before its room exists; pending-submit-tap fires
   it once :room/joined arrives. A virtual new room (:web/pending-room) is
   created now via :room/join \"new\" with a :join-token the server echoes
   back, so the prompt lands in this room only. A cached session view just
   waits for the join already in flight, or sends the one a sidebar burst
   deferred (router/flush-pending-join). The optimistic bubble is keyed on
   the nil room and re-keyed by optimistic-tap after the join."
  [st {:keys [session-id text images model]}]
  (let [pending    (:web/pending-room st)
        virtual?   (and (nil? session-id) (some? pending))
        cwd        (:cwd pending)
        join-model (or model (:model pending))
        token      (str (:id pending))]
    (-> {:state (-> st
                    (assoc :web/pending-submit
                           (cond-> {:session-id session-id :text text}
                             virtual?     (assoc :join-token token)
                             model        (assoc :model model)
                             (seq images) (assoc :images images)))
                    (assoc :web/optimistic
                           (cond-> {:room-id nil :session-id session-id :text text}
                             (seq images) (assoc :images (vec images))))
                    (dissoc :web/pending-room))}
        (cond-> virtual?
          (assoc :effects [[:ws/send (cond-> {:type :room/join :target "new" :join-token token}
                                       cwd        (assoc :cwd cwd)
                                       join-model (assoc :model join-model))]]))
        router/flush-pending-join)))

(defn- dialog-response
  "Web :ui/dialog-response: the transport's forward/clear, plus logging the
   decision pill when the echo clears a dialog still live here (answered
   elsewhere; button answers already dropped it via :web/dialog-resolved)."
  [st {:keys [room-id dialog-id value reason remote?] :as ev}]
  (let [room   (get-in st [:rooms room-id])
        dialog (when remote? (some #(when (= dialog-id (:id %)) %) (get-in room [:ui :dialogs])))
        res    (ws-transport/dialog-response st ev)]
    (if-not dialog
      res
      (let [{:keys [type message text options]} dialog
            history  (vec (:history room))
            tool-idx (when (= :confirm type) (dlg/permission-tool-index dialog history 0))]
        (update res :state update-in [:web/resolved-dialogs room-id] (fnil conj [])
                (cond-> {:key     dialog-id
                         :anchor  (count history)
                         :message (or message text)
                         :type    type
                         :value   value
                         :label   (views/dialog-decision-label type options value)}
                  reason   (assoc :reason reason)
                  tool-idx (assoc :tool-id (:id (nth history tool-idx)))))))))

(defn- deny-reason-submit
  [st _]
  (when-let [{:keys [room-id dialog-id text]} (:web/deny-reason st)]
    {:state   (dissoc st :web/deny-reason)
     :effects [[:app/dispatch (cond-> {:type :ui/dialog-response :room-id room-id
                                       :dialog-id dialog-id :value false}
                                (not (str/blank? text)) (assoc :reason (str/trim text)))]]}))

(defn- submit-clear-pending [st _]
  {:state (dissoc st :web/pending-submit)})

(defn- optimistic-set
  [st {:keys [room-id session-id text images]}]
  {:state (assoc st :web/optimistic
                 (cond-> {:room-id room-id :session-id session-id :text text}
                   (seq images) (assoc :images (vec images))))})

(defn- optimistic-clear [st _]
  {:state (dissoc st :web/optimistic)})

(defn- web-command
  "Route a backend slash command: run it now when connected and in a room,
   else stash it as :web/pending-command for pending-command-tap (the command
   analogue of submit-pending, join-token included)."
  [st {:keys [room-id name args]}]
  (if (and (:web/connected? st) room-id)
    {:effects [[:app/dispatch (cond-> {:type :command/run :room-id room-id :name name}
                                args (assoc :args args))]]}
    (let [sid      (get-in st [:web/route :session-id])
          pending  (:web/pending-room st)
          virtual? (and (nil? sid) (some? pending))
          token    (when virtual? (str (:id pending)))
          cwd      (:cwd pending)
          model    (:model pending)]
      (-> {:state (-> st
                      (assoc :web/pending-command
                             (cond-> {:room-id room-id :session-id sid :name name}
                               args  (assoc :args args)
                               token (assoc :join-token token)))
                      (cond-> virtual? (dissoc :web/pending-room)))}
          (cond-> virtual?
            (assoc :effects [[:ws/send (cond-> {:type :room/join :target "new" :join-token token}
                                         cwd   (assoc :cwd cwd)
                                         model (assoc :model model))]]))
          router/flush-pending-join))))

(defn- command-clear-pending [st _]
  {:state (dissoc st :web/pending-command)})

(defn- cmd-select [st {:keys [index]}]
  {:state (assoc st :web/cmd-selected (or index 0))})

(def ^:private max-recent-commands 6)

(defn- record-command
  "Push a just-executed command name to the front of the usage list (deduped,
   capped) and persist it. Feeds the next reload's quick-command bar; the
   displayed order (:web/recent-commands) stays frozen for the session."
  [st {:keys [name]}]
  (let [usage (->> (cons name (remove #(= % name) (:web/command-usage st)))
                   (take max-recent-commands)
                   vec)]
    {:state   (assoc st :web/command-usage usage)
     :effects [[:cache/recent-commands {:commands usage}]
               (user-state/set-effect :recent-commands usage)]}))

;; ── Custom color themes (xi.web.theme) ───────────────────────────────────────
;; :web/themes is the user's value; :web/theme-draft the editor's copy while
;; the Appearance dialog edits one, previewed live and dropped on cancel.

(defn- themes-commit
  "Store `themes` as the user's value: cache, server, and the page."
  [st themes]
  {:state   (-> st (assoc :web/themes themes) (dissoc :web/theme-draft))
   :effects [[:cache/themes {:themes themes}]
             (user-state/set-effect :themes themes)
             [:theme/apply-vars {:vars (theme/active-vars themes) :persist? true}]]})

(defn- preview-draft
  [st draft]
  {:state   (assoc st :web/theme-draft draft)
   :effects [[:theme/apply-vars {:vars (theme/css-vars (:params draft))}]]})

(defn- themes-draft-cancel
  "Drop the draft and put the active theme back on the page."
  [st _]
  (when (:web/theme-draft st)
    {:state   (dissoc st :web/theme-draft)
     :effects [[:theme/apply-vars {:vars (theme/active-vars (:web/themes st))}]]}))

(defn- themes-save [st _]
  (let [draft (:web/theme-draft st)]
    (when (and draft (theme/draft-savable? (:web/themes st) draft))
      (themes-commit st (theme/save (:web/themes st) draft)))))

(def ^:private themes-handlers
  {:themes/select       (fn [st {:keys [name]}]
                          (themes-commit st (theme/select (:web/themes st) name)))
   :themes/edit         (fn [st {:keys [name]}]
                          (preview-draft st (theme/draft-for (:web/themes st) name)))
   :themes/draft-set    (fn [st {:keys [key value]}]
                          (when-let [draft (:web/theme-draft st)]
                            (preview-draft st (assoc-in draft [:params key] value))))
   :themes/draft-preset (fn [st {:keys [preset]}]
                          (when-let [draft (:web/theme-draft st)]
                            (preview-draft st (update draft :params merge (dissoc preset :name)))))
   :themes/draft-name   (fn [st {:keys [name]}]
                          (when (:web/theme-draft st)
                            {:state (assoc-in st [:web/theme-draft :name] name)}))
   :themes/draft-cancel themes-draft-cancel
   :themes/save         themes-save
   :themes/delete       (fn [st {:keys [name]}]
                          (themes-commit st (theme/remove-theme (:web/themes st) name)))
   ;; startup: the cached value onto the page (index.html's inline script
   ;; already did, from the var cache; this covers a browser without it)
   :themes/apply        (fn [st _]
                          {:effects [[:theme/apply-vars {:vars (theme/active-vars (:web/themes st))
                                                         :persist? true}]]})})

(defn- theme-set-mode
  "Switch the theme and persist it as per-user UI state (xi.user-state),
   except for the :init? startup dispatch that applies the cached theme."
  [st {:keys [mode init?]}]
  (let [m (if (#{"auto" "light" "dark"} mode) mode "auto")]
    {:state   (assoc st :web/theme-mode m)
     :effects (cond-> [[:theme/apply m]]
                (not init?) (conj (user-state/set-effect :theme m)))}))

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

(defn- diff-toggle-file
  [st {:keys [filename]}]
  {:state (update st :web/diff-collapsed
                  (fn [s] (let [s (or s #{})]
                            (if (contains? s filename)
                              (disj s filename)
                              (conj s filename)))))})

(defn- diff-show-all
  [st {:keys [filename]}]
  {:state (update st :web/diff-expanded (fnil conj #{}) filename)})

(defn- diff-toggle-all
  [st {:keys [filenames]}]
  {:state (update st :web/diff-collapsed
                  (fn [s]
                    (let [s (or s #{})]
                      (if (every? s filenames)
                        (apply disj s filenames)
                        (into s filenames)))))})

(defn- diff-modify-toggle [st _]
  {:state (update st :web/diff-modify? not)})

(defn- md-diff-toggle
  "Flip a markdown diff between its rendered and code view. `key` is a tool
   call id, or [:diff filename] in the diff viewer."
  [st {:keys [key]}]
  {:state (update st :web/md-diff-code
                  (fn [s] (let [s (or s #{})]
                            (if (contains? s key) (disj s key) (conj s key)))))})

(defn- prompt-part-toggle
  [st {:keys [idx]}]
  {:state (update st :web/prompt-expanded
                  (fn [s] (let [s (or s #{})]
                            (if (contains? s idx) (disj s idx) (conj s idx)))))})

(defn- prompt-toggle-all
  [st {:keys [n]}]
  {:state (assoc st :web/prompt-expanded
                 (if (= (:web/prompt-expanded st) (set (range n)))
                   #{}
                   (set (range n))))})

(defn- selected-diff-snippet
  [st room-id]
  (let [active (get-in st [:rooms room-id :ui :active-buffer])
        text   (get-in st [:rooms room-id :ui :buffers active :text])
        range  (diff/selection-range (:web/diff-sel st))]
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

(defonce ^{:private true
           :doc "Offscreen <pre.diff-difft> for reading the difft pane's resolved font
                 before any difft buffer has mounted."}
  mono-probe
  (atom nil))

(defn- mono-font-string
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
  16)

(defn- diff-content-px
  []
  (let [^js tab (.querySelector js/document ".diff-tab")
        ^js bar (.querySelector js/document ".diff-method-bar")]
    (if tab
      (let [pad (if bar (js/parseFloat (.-paddingLeft (js/getComputedStyle bar))) 0)]
        (max 0 (- (.-clientWidth tab) (* 2 pad) diff-scrollbar-px)))
      (.-innerWidth js/window))))

(defn- measure-diff-cols
  []
  (let [ctx      (.getContext (js/document.createElement "canvas") "2d")
        _        (set! (.-font ctx) (mono-font-string))
        char-px  (/ (.-width (.measureText ctx "0000000000")) 10)
        px       (diff-content-px)]
    (if (and (pos? char-px) (pos? px))
      (max 40 (js/Math.floor (/ px char-px)))
      120)))

(defn- diff-reopen
  "Re-run /diff for the chosen source + renderer. difftastic goes through
   :diff/measure-cols, which re-issues the command as `difft:<cols>` for the
   viewport width."
  [_st {:keys [room-id method engine]}]
  (if (= engine :difft)
    {:effects [[:diff/measure-cols {:room-id room-id :method method}]]}
    {:effects [[:ws/send {:type :input/submit :room-id room-id
                          :text (str "/diff " method)}]]}))


(defn- projects-web-list-result [st {:keys [dirs dirty]}]
  {:state (assoc st :web/project-dirs dirs
                    :web/project-dirty (set dirty)
                    :web/projects-loading? false)})

(defn- projects-web-sessions-result [st {:keys [cwd sessions]}]
  {:state (assoc st :web/project-sessions sessions
                    :web/project-sessions-cwd cwd
                    :web/project-sessions-loading? false)})

(defn- sessions-all-result
  "Full saved-session list for the all-sessions view, counts merged over the
   lobby's capped subset."
  [st {:keys [sessions counts]}]
  {:state (-> st
              (assoc :web/all-sessions sessions
                     :web/all-sessions-loading? false)
              (update :web/response-counts merge counts))})

;; ── Session content search ────────────────────────────────────────────────────
;; Names are searched client-side; message content lives on the server, so
;; content mode round-trips a query and caches the matching ids.

(defn- content-search-cwd
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
  [st {:keys [key query session-ids]}]
  (if (= (str/trim (or query ""))
         (str/trim (or (get-in st [:web/search key]) "")))
    {:state (assoc-in st [:web/content-matches key] (set session-ids))}
    {:state st}))

(defn- viewed-room-model
  "The model shown for the viewed chat (same resolution as chat-view), so a
   resubmit forks on the latest pick even before the /model round-trip lands."
  [st active sid]
  (or (get-in active [:agent :model])
      (get-in st [:web/cache sid :model])
      (get-in st [:web/pending-room :model])))

(defn- bubble-edit-save
  "Commit an inline bubble edit: fork at the edited message and resubmit the
   text with its original images (xi.web.resubmit/fork-effects). Empty text
   just closes the editor."
  [st _]
  (let [{:keys [index text images]} (:web/editing-bubble st)
        active  (state/active-room st)
        sid     (get-in st [:web/route :session-id])
        room-id (when (= (get-in active [:session :id]) sid) (:id active))
        model   (viewed-room-model st active sid)
        t       (str/trim (or text ""))]
    (cond-> {:state (dissoc st :web/editing-bubble)}
      (seq t)
      (assoc :effects (resubmit/fork-effects {:room-id room-id :sid sid :index index
                                              :text t :images images :model model})))))

(defn- bubble-retry
  "Resend a user message unchanged: fork at that message and resubmit its text
   and images. Like bubble-edit-save minus the editor."
  [st {:keys [index text images]}]
  (let [active  (state/active-room st)
        sid     (get-in st [:web/route :session-id])
        room-id (when (= (get-in active [:session :id]) sid) (:id active))
        model   (viewed-room-model st active sid)
        t       (str/trim (or text ""))]
    (cond-> {:state (dissoc st :web/bubble-menu)}
      (or (seq t) (seq images))
      (assoc :effects (resubmit/fork-effects {:room-id room-id :sid sid :index index
                                              :text t :images images :model model})))))

(defn- prompt-nav-step
  "Move the prompt-nav cursor one step (:prompt-nav/prev = older, :next = newer)
   over the full-history user-prompt indices; opening jumps to the newest.
   Grows :web/timeline-window so the target prompt's node exists to scroll to."
  [st {:keys [type user-indices total cur-window]}]
  (let [c (count user-indices)]
    (if (pos? c)
      (let [cur (:web/prompt-nav st)
            idx (if (= type :prompt-nav/next)
                  (min (dec c) (inc (or cur 0)))
                  (if (nil? cur) (dec c) (max 0 (dec cur))))
            hidx (nth user-indices idx)
            needed (+ (- total hidx) 4)
            win (max (or cur-window 0) needed)]
        {:state (-> st
                    (assoc :web/prompt-nav idx)
                    (assoc :web/timeline-window win))
         :effects [[:prompt-nav/scroll {:history-index hidx}]]})
      {:state st})))

(defn- lobby-state
  "Install the lobby mirror, then apply the counts riding along. Sessions
   deleted here (:web/deleted-session-ids) are filtered out until the server
   stops listing them, so a stale broadcast can't resurrect the card."
  [st ev]
  (let [gone   (:web/deleted-session-ids st)
        listed (into #{} (keep :session-id) (concat (:sessions ev) (:rooms ev)))
        strip  (fn [ev k]
                 (cond-> ev
                   (contains? ev k) (update k #(vec (remove (comp gone :session-id) %)))))
        ev'    (if (seq gone) (-> ev (strip :sessions) (strip :rooms)) ev)
        {st' :state} (ws-transport/lobby-state st ev')
        st'    (cond-> st'
                 (seq gone) (assoc :web/deleted-session-ids (set (filter listed gone))))]
    (if (contains? ev :counts)
      (counts-result st' ev)
      {:state st'})))

(defn- cached-room-state
  [st room-id cached history msg-hash msg-count]
  (cond-> (-> st
              (assoc-in [:rooms room-id :history] history)
              (assoc-in [:rooms room-id :msg-hash] msg-hash)
              (assoc-in [:rooms room-id :msg-count] msg-count))
    (:model cached)
    (assoc-in [:rooms room-id :agent :model] (:model cached))))

(defn- session-current
  "The server confirmed our cached snapshot matches the on-disk session and
   skipped the resume payload: promote the cache into the room mirror."
  [st {:keys [room-id session-id msg-hash msg-count]}]
  (let [cached (get-in st [:web/cache session-id])]
    (when (seq (:history cached))
      {:state (cached-room-state st room-id cached (:history cached) msg-hash msg-count)})))

(defn- session-resumed-tail
  "Incremental resume: our cache is a prefix of the on-disk session, so append
   only the new tail. Guarded on :base-hash; a non-matching client got a full
   :session/resumed instead."
  [st {:keys [room-id session-id base-hash messages msg-hash msg-count]}]
  (let [cached (get-in st [:web/cache session-id])]
    (when (and (seq (:history cached))
               (= base-hash (:msg-hash cached)))
      (let [history (into (vec (:history cached))
                          (commands/messages->history messages))]
        {:state (cached-room-state st room-id cached history msg-hash msg-count)}))))

(defn- viewed-room
  "The active room only when it is the one being viewed; nil for a new chat
   still attached to the previous room."
  [st]
  (let [sid    (get-in st [:web/route :session-id])
        active (state/active-room st)]
    (when (= (get-in active [:session :id]) sid) active)))

(defn- models-select
  "Pick a model from the palette: /model on the viewed room (reflected on the
   client room at once), or remembered on the pending room for a virtual new
   chat. The pick becomes the sticky default for future new chats."
  [st {:keys [model]}]
  (let [text    (str "/model " model)
        active  (state/active-room st)
        rid     (:id (viewed-room st))
        base    (-> st
                    (dissoc :web/palette-page :web/palette-open?)
                    (assoc :web/preferred-model model))
        persist [:cache/preferred-model {:model model}]
        sync    (user-state/set-effect :preferred-model model)]
    (models/with-check
      (cond
        rid
        {:state (assoc-in base [:rooms rid :agent :model] model)
         :effects [persist sync
                   [:palette/close nil]
                   [:ws/send {:type :input/submit :room-id rid :text text}]]}

        (:web/pending-room base)
        {:state (assoc-in base [:web/pending-room :model] model)
         :effects [persist sync [:palette/close nil]]}

        :else
        {:state base
         :effects (cond-> [persist sync [:palette/close nil]]
                    (:id active)
                    (conj [:ws/send {:type :input/submit
                                     :room-id (:id active) :text text}]))})
      model)))

(defn- submit-to-viewed-room
  "Effect submitting `text` (+ `images`) to the viewed room, or via
   :submit/pending when there is no server room for it yet."
  [st text images]
  (let [rid (:id (viewed-room st))
        sid (get-in st [:web/route :session-id])]
    (if rid
      [:ws/send (cond-> {:type :input/submit :room-id rid :text text}
                  (seq images) (assoc :images (vec images)))]
      [:app/dispatch (cond-> {:type :submit/pending :session-id sid :text text}
                       (seq images) (assoc :images (vec images)))])))

(defn- skill-select
  "Pick a skill from the palette. Skills with <input /> placeholders open the
   skill form in the compose dock (navigating to a chat page first); plain
   skills load directly. Recently used skills sort first next time."
  [st {:keys [name]}]
  (let [skill   (some #(when (= (:name %) name) %) (:web/skill-list st))
        recents (->> (cons name (remove #(= % name) (:web/recent-skills st)))
                     (take 8)
                     vec)
        st      (assoc st :web/recent-skills recents)
        record  [:cache/recent-skills {:skills recents}]
        sync    (user-state/set-effect :recent-skills recents)]
    (if (seq (:inputs skill))
      {:state (-> st
                  (dissoc :web/palette-page :web/palette-open?)
                  (assoc :web/skill-form
                         {:name name
                          :description (:description skill)
                          :inputs (vec (:inputs skill))
                          :values {}
                          :images []}))
       :effects (cond-> [[:palette/close nil]
                         [:ws/send {:type :skill/web-get :name name}]
                         record sync]
                  (not= :chat (get-in st [:web/route :page]))
                  (conj [:app/dispatch {:type :route/navigate
                                        :page :chat :session-id nil}]))}
      {:state (dissoc st :web/skill-list :web/palette-page :web/palette-open?)
       :effects [[:palette/close nil]
                 (submit-to-viewed-room st (str "/skill load " name) nil)
                 record sync]})))

(defn- skill-form-escape
  "Esc twice cancels the skill form: the first press arms, the second
   dismisses. Typing disarms (:skill-form/set-value)."
  [st _]
  (if (get-in st [:web/skill-form :armed?])
    {:state (dissoc st :web/skill-form)}
    {:state (assoc-in st [:web/skill-form :armed?] true)}))

(defn- skill-form-submit
  "Substitute each <name /> placeholder with its value (image placeholders
   are blanked; the images ride along as attachments) and submit."
  [st _]
  (let [{:keys [inputs values images body]} (:web/skill-form st)]
    (when body
      (let [text (reduce (fn [t {:keys [name type]}]
                           (let [re (js/RegExp. (str "<" name "\\s*/>") "g")
                                 v  (if (= type :image)
                                      ""
                                      (str/trim (or (get values name) "")))]
                             (.replace t re v)))
                         body inputs)]
        {:state   (dissoc st :web/skill-form)
         :effects [(submit-to-viewed-room st text images)]}))))

(defn- flip-session-lists
  [st session-id f]
  (let [flip (fn [ss] (mapv #(if (= (:session-id %) session-id) (f %) %) ss))]
    (-> st
        (update-in [:lobby :sessions] flip)
        (update :web/project-sessions flip)
        (update :web/all-sessions #(some-> % flip)))))

(defn- dismissed-toggle
  "Hide/show a session in Recent: flip locally, then forward so the server
   persists and rebroadcasts. Hiding unpins, as on the server."
  [st {:keys [session-id]}]
  {:state   (flip-session-lists st session-id
                                (fn [s]
                                  (let [hidden? (not (:dismissed? s))]
                                    (cond-> (assoc s :dismissed? hidden?)
                                      hidden? (assoc :pinned? false)))))
   :effects [[:ws/send {:type :dismissed/toggle :session-id session-id}]]})

(defn- pinned-toggle
  "Pin/unpin a session in Recent: flip locally, then forward. Pinning
   un-hides, as on the server."
  [st {:keys [session-id]}]
  {:state   (flip-session-lists st session-id
                                (fn [s]
                                  (let [pinned? (not (:pinned? s))]
                                    (cond-> (assoc s :pinned? pinned?)
                                      pinned? (assoc :dismissed? false)))))
   :effects [[:ws/send {:type :pinned/toggle :session-id session-id}]]})

(defn- session-delete-now
  "Delete a saved session: drop its cards locally, then forward. When the
   deleted session is the one being viewed, leave its room first (so the
   server closes it instead of swapping in a blank session) and open a fresh
   chat in the same cwd."
  [st {:keys [session-id]}]
  (let [drop     (fn [ss] (vec (remove #(= (:session-id %) session-id) ss)))
        viewing? (and (= :chat (get-in st [:web/route :page]))
                      (= session-id (get-in st [:web/route :session-id])))
        dropped  (-> st
                     (update-in [:lobby :sessions] drop)
                     (update-in [:lobby :rooms] drop)
                     (update :web/deleted-session-ids (fnil conj #{}) session-id)
                     (update :web/project-sessions drop)
                     (update :web/all-sessions #(some-> % drop)))
        {:keys [state effects]}
        (if viewing?
          (open-pending-room dropped (fresh-pending-room st (room-new-cwd st)))
          {:state dropped})]
    {:state state
     :effects (cond-> []
                viewing? (conj [:ws/send {:type :room/leave}])
                true     (conj [:ws/send {:type :session/delete :session-id session-id}])
                viewing? (into effects))}))

(defn- home-start
  "The dashboard composer (xi.web.views/dashboard-view): a fresh chat in `cwd`
   (nil: the server default) that submits `text` at once. A slash command
   opens the new chat with it as the draft instead, where the command menu
   completes it."
  [st {:keys [cwd text]}]
  (let [text    (str/trim (or text ""))
        pending (fresh-pending-room st cwd)
        opened  (open-pending-room (update st :web/drafts dissoc :home) pending)]
    (cond
      (str/blank? text) nil

      (str/starts-with? text "/")
      (assoc-in opened [:state :web/drafts (:id pending)] text)

      :else
      (let [sub (submit-pending (:state opened) {:session-id nil :text text})]
        {:state   (:state sub)
         :effects (into (vec (:effects opened)) (:effects sub))}))))

(defn- web-handlers [routes]
  (merge (router/handlers routes)
         user-state/handlers
         prefetch/handlers
         keymap/handlers
         themes-handlers
         dashboard/handlers
         {:room/new              room-new
          :home/start            home-start
          :home/set-cwd          (fn [st {:keys [cwd]}] {:state (assoc st :web/home-cwd cwd)})
          :compose/focus         (fn [_ _] {:effects [[:compose/focus]]})
          :compose/blur          (fn [_ _] {:effects [[:compose/blur]]})
          :room/join             forward
          :room/leave            forward
          :rooms/prune           forward
          :session/buffer-close  forward
          ;; Sub-agent panel toggles are client-local (xi.ext.subagent.web);
          ;; forwarding them would double-toggle via the broadcast echo.
          :lobby/state           lobby-state
          :session/mark-read     mark-read
          :session/mark-all-read mark-all-read
          :sessions/all          (fn [st _ev]
                                   {:state (assoc st :web/all-sessions-loading? true)
                                    :effects [[:ws/send {:type :sessions/all}]]})
          :sessions/all-result   sessions-all-result
          :session/dismiss-all   dismiss-all
          ;; Seed cached history so a chat paints while :room/joined is in flight.
          :web/cache-seed        (fn [st {:keys [session-id room]}]
                                   (when (and session-id room)
                                     {:state (assoc-in st [:web/cache session-id] room)}))
          :cache/prefetch        (fn [_ {:keys [session-id]}]
                                   (when session-id
                                     {:effects [[:cache/prefetch-room {:session-id session-id}]]}))
          :session/current       session-current
          :session/resumed-tail  session-resumed-tail
          :connection/status     connection-status
          :room/joined           room-joined-from-cache
          :room/left             room-left-web
          ;; ─ Client auth (transport-level handshake, xi.server.ws) ─
          :auth/pending          (fn [st {:keys [code]}]
                                   {:state (assoc st :web/auth {:status :pending :code code})})
          :auth/ok               (fn [st ev]
                                   (let [st (dissoc st :web/auth)]
                                     {:state (or (:state (ws-transport/auth-ok st ev)) st)}))
          :auth/denied           (fn [st _] {:state (assoc st :web/auth {:status :denied})})
          :auth/request          (fn [st {:keys [code client-name platform]}]
                                   {:state (assoc-in st [:web/auth-requests code]
                                                     {:code code
                                                      :client-name client-name
                                                      :platform platform})})
          :auth/resolved         (fn [st {:keys [code]}]
                                   {:state (update st :web/auth-requests dissoc code)})
          ;; Approve/deny clear the local banner optimistically; the server
          ;; answers everyone else with :auth/resolved.
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
          :web/file-drag         file-drag
          :copy/open             (fn [st {:keys [text]}] {:state (assoc st :web/copy-text text)})
          :copy/close            (fn [st _] {:state (dissoc st :web/copy-text)})
          :copy/flash            (fn [st _] {:state   (assoc st :web/copy-flash true)
                                            :effects [[:copy/flash-clear {}]]})
          :copy/flash-off        (fn [st _] {:state (dissoc st :web/copy-flash)})
          :bubble/menu-open      (fn [st {:keys [index text images x y]}]
                                   {:state (assoc st :web/bubble-menu {:index index :text text :images images
                                                                        :x x :y y})})
          :bubble/menu-close     (fn [st _] {:state (dissoc st :web/bubble-menu)})
          ;; Floating Copy button for a tapped code block (attach-code-copy-listener!).
          :code/menu-open        (fn [st {:keys [text path diff-path diff-text x y]}]
                                   {:state (assoc st :web/code-menu {:text text :path path
                                                                     :diff-path diff-path
                                                                     :diff-text diff-text
                                                                     :x x :y y})})
          :code/menu-close       (fn [st _] {:state (dissoc st :web/code-menu)})
          :bubble/edit-start     (fn [st {:keys [index text images]}]
                                   {:state (-> st
                                               (dissoc :web/bubble-menu)
                                               (assoc :web/editing-bubble {:index index :text text
                                                                           :images images}))})
          :bubble/edit-change    (fn [st {:keys [text]}]
                                   {:state (assoc-in st [:web/editing-bubble :text] text)})
          :bubble/edit-cancel    (fn [st _] {:state (dissoc st :web/editing-bubble)})
          ;; Prompt navigation: :web/prompt-nav is nil (collapsed) or a 0-based
          ;; index into :user-indices (0 = oldest). See prompt-nav-step.
          :prompt-nav/prev       prompt-nav-step
          :prompt-nav/next       prompt-nav-step
          :prompt-nav/close      (fn [st _] {:state (dissoc st :web/prompt-nav)
                                             :effects [[:prompt-nav/resume {}]]})
          ;; Timeline scroll-to-bottom (down-arrow beside the prompt-nav
          ;; up-arrow, and the next-arrow while sitting on the newest prompt).
          ;; Closes prompt-nav so the group collapses back to the plain arrow.
          :timeline/scroll-to-bottom
          (fn [st _] {:state (dissoc st :web/prompt-nav
                                     :web/scrolled-up? :web/frozen-window-start)
                      :effects [[:timeline/scroll-bottom {}]]})
          ;; The top of the chat is its first entry, so the render window grows
          ;; to the whole history before the snap.
          :timeline/scroll-to-top
          (fn [st _] {:state (-> st
                                 (dissoc :web/prompt-nav)
                                 (assoc :web/timeline-window
                                        (count (viewed-history st))))
                      :effects [[:timeline/scroll-top {}]]})
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
                  (let [total (count (viewed-history st))
                        win   (or (:web/timeline-window st) views/initial-window-size)]
                    {:state (assoc st :web/scrolled-up? true
                                      :web/frozen-window-start (max 0 (- total win)))})
                  {:state (-> st
                              (assoc :web/scrolled-up? false)
                              (dissoc :web/frozen-window-start))}))))
          :bubble/edit-save      bubble-edit-save
          :bubble/retry          bubble-retry
          :web/dialog-form-set   (fn [st {:keys [patch]}] {:state (update st :web/dialog-form merge patch)})
          :web/dialog-form-reset (fn [st _] {:state (dissoc st :web/dialog-form)})
          ;; Answered-dialog log: keep resolved dialog bubbles in the timeline and
          ;; drop the live dialog optimistically. The answering client also
          ;; restamps the gated call itself, since the echo finds no dialog here.
          :web/dialog-resolved   (fn [st {:keys [room-id dialog-id entry] :as ev}]
                                   (let [answered (some #(when (= dialog-id (:id %)) %)
                                                        (get-in st [:rooms room-id :ui :dialogs]))]
                                     {:state (-> st
                                                 (update-in [:web/resolved-dialogs room-id]
                                                            (fnil conj []) entry)
                                                 (update-in [:rooms room-id :ui :dialogs]
                                                            (fn [ds] (vec (remove #(= dialog-id (:id %)) ds))))
                                                 (update-in [:rooms room-id :history]
                                                            dlg/restamp-gated-call answered
                                                            (:value entry) (:event/ts ev)))}))
          :ui/dialog-response    dialog-response
          ;; Deny with reason: the composer becomes a reason field
          ;; (views/deny-reason-compose).
          :deny-reason/start     (fn [st {:keys [room-id dialog-id]}]
                                   {:state (assoc st :web/deny-reason
                                                  {:room-id room-id :dialog-id dialog-id :text ""})})
          :deny-reason/set-text  (fn [st {:keys [text]}]
                                   (when (:web/deny-reason st)
                                     {:state (assoc-in st [:web/deny-reason :text] text)}))
          :deny-reason/cancel    (fn [st _] {:state (dissoc st :web/deny-reason)})
          :deny-reason/submit    deny-reason-submit
          :submit/pending        submit-pending
          :submit/clear-pending  submit-clear-pending
          :web/optimistic-set    optimistic-set
          :web/optimistic-clear  optimistic-clear
          :web/command           web-command
          :command/clear-pending command-clear-pending
          :cmd/select            cmd-select
          :web/record-command    record-command
          :theme/set-mode        theme-set-mode
          :web/session-search    session-search
          :web/toggle-content-search toggle-content-search
          :session/content-search (fn [_st {:keys [key query cwd]}]
                                    {:effects [[:ws/send {:type :session/content-search
                                                          :key key :query query :cwd cwd}]]})
          :session/content-search-result content-search-result
          ;; Opening the drawer refetches Claude usage and the project list
          ;; (git-dirty dots); both ride back on the lobby broadcast.
          :sidebar/toggle        (fn [st _]
                                   (let [open? (not (:web/sidebar-open? st))]
                                     (cond-> {:state (assoc st :web/sidebar-open? open?)}
                                       open? (assoc :effects [[:ws/send {:type :usage/refresh}]
                                                              [:ws/send {:type :projects/web-list}]]))))
          :sidebar/open          (fn [st _] {:state (assoc st :web/sidebar-open? true)
                                             :effects [[:ws/send {:type :usage/refresh}]
                                                       [:ws/send {:type :projects/web-list}]]})
          :sidebar/close         (fn [st _] {:state (assoc st :web/sidebar-open? false)})
          ;; Drawer groups collapse in state (the drawer remounts on open) and
          ;; persist across reloads.
          :sidebar/toggle-group  (fn [st {:keys [group]}]
                                   (let [collapsed (or (:web/sidebar-collapsed st) #{})
                                         collapsed (if (contains? collapsed group)
                                                     (disj collapsed group)
                                                     (conj collapsed group))]
                                     {:state   (assoc st :web/sidebar-collapsed collapsed)
                                      :effects [[:cache/sidebar-collapsed {:groups collapsed}]
                                                (user-state/set-effect :sidebar-collapsed collapsed)]}))
          :web/set-wide          (fn [st {:keys [wide?]}] {:state (assoc st :web/wide? wide?)})
          :overflow/toggle       (fn [st _] {:state (update st :web/overflow-menu? not)})
          :overflow/close        (fn [st _] {:state (dissoc st :web/overflow-menu?)})
          ;; Appearance dialog (xi.web.appearance): :web/appearance holds only
          ;; this browser's overrides.
          :appearance/open       (fn [st _] {:state (assoc st :web/appearance-open? true)})
          ;; closing (×, Escape, outside click) abandons an unsaved theme draft
          :appearance/close      (fn [st ev]
                                   (-> (or (themes-draft-cancel st ev) {:state st})
                                       (update :state dissoc :web/appearance-open?)))
          ;; Done saves it; a draft Save refuses (name taken or invalid) keeps
          ;; the dialog open on the editor instead of losing the edits
          :appearance/done       (fn [st ev]
                                   (if (:web/theme-draft st)
                                     (some-> (themes-save st ev)
                                             (update :state dissoc :web/appearance-open?))
                                     {:state (dissoc st :web/appearance-open?)}))
          :keys/show             (fn [st _] {:state (assoc st :web/keys-open? true)})
          :keys/close            (fn [st _] {:state (dissoc st :web/keys-open?)})
          :appearance/set        (fn [st {:keys [key value]}]
                                   (let [settings (appearance/normalize
                                                   (assoc (:web/appearance st) key value))]
                                     {:state   (assoc st :web/appearance settings)
                                      :effects [[:cache/appearance {:settings settings}]
                                                (user-state/set-effect :appearance settings)]}))
          :appearance/reset      (fn [st _]
                                   {:state   (assoc st :web/appearance {})
                                    :effects [[:cache/appearance {:settings {}}]
                                              (user-state/set-effect :appearance {})]})
          :queue/toggle-popover  (fn [st _] {:state (update st :web/queue-popover? not)})
          :queue/close-popover   (fn [st _] {:state (dissoc st :web/queue-popover?)})
          :models/web-list-result (fn [st ev] (models/list-result st ev (:event/ts ev)))
          :models/select         models-select
          :skill/web-list-result (fn [st {:keys [skills]}]
                                    {:state (assoc st :web/skill-list skills)})
          :skill/select          skill-select
          :skill/web-get-result  (fn [st {:keys [name body]}]
                                    (when (= name (get-in st [:web/skill-form :name]))
                                      {:state (assoc-in st [:web/skill-form :body] body)}))
          :skill-form/set-value  (fn [st {:keys [name value]}]
                                    {:state (-> st
                                                (assoc-in [:web/skill-form :values name] value)
                                                (update :web/skill-form dissoc :armed?))})
          :skill-form/add-images (fn [st {:keys [images]}]
                                    {:state (update-in st [:web/skill-form :images]
                                                       (fnil into []) images)})
          :skill-form/remove-image (fn [st {:keys [idx]}]
                                     {:state (update-in st [:web/skill-form :images]
                                                        (fn [imgs]
                                                          (vec (keep-indexed
                                                                (fn [i img] (when (not= i idx) img))
                                                                imgs))))})
          :skill-form/cancel     (fn [st _]
                                    {:state (dissoc st :web/skill-form)})
          :skill-form/escape     skill-form-escape
          :skill-form/submit     skill-form-submit
          :diff/reopen           diff-reopen
          :diff/select-line      diff-select-line
          :diff/clear-selection  diff-clear-selection
          :diff/toggle-file      diff-toggle-file
          :diff/show-all         diff-show-all
          :diff/toggle-all       diff-toggle-all
          :diff/modify-toggle    diff-modify-toggle
          :diff/explain          diff-explain
          :diff/modify-submit    diff-modify-submit
          :md-diff/toggle        md-diff-toggle
          :prompt/part-toggle    prompt-part-toggle
          :prompt/toggle-all     prompt-toggle-all
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
          :dismissed/toggle      dismissed-toggle
          :pinned/toggle         pinned-toggle
          ;; The sidebar row animates out first, then :session/delete-now runs.
          :session/delete        (fn [_st {:keys [session-id]}]
                                   {:effects [[:sidebar/animate-leave
                                               {:session-id session-id
                                                :then {:type :session/delete-now
                                                       :session-id session-id}}]]})
          :session/delete-now    session-delete-now
          :projects/web-list     (fn [st _ev]
                                    {:state (assoc st :web/projects-loading? true)
                                     :effects [[:ws/send {:type :projects/web-list}]]})
          ;; Silent re-fetch (no loading flag).
          :projects/web-refresh  (fn [_ _] {:effects [[:ws/send {:type :projects/web-list}]]})
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
          :projects/picker-insert (fn [st {:keys [path draft-key]}]
                                     (let [cur (get-in st [:web/drafts draft-key] "")
                                           sep (if (and (seq cur) (not (str/ends-with? cur " "))) " " "")
                                           new-text (str cur sep path)]
                                       {:state (-> st
                                                   (assoc-in [:web/drafts draft-key] new-text)
                                                   (dissoc :web/palette-page :web/palette-open?))
                                        :effects [[:palette/close nil]
                                                  [:projects/sync-textarea {:text new-text}]]}))
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
                                    (-> (open-pending-room st (fresh-pending-room st cwd))
                                        (assoc-in [:state :web/sidebar-open?] false)))
          :draft-chat/open        draft-chat-open
          :draft-chat/discard     draft-chat-discard
          ;; AGENTS.md files for the virtual new chat's launch header.
          :cwd/agents-files-result (fn [st {:keys [cwd agents-files]}]
                                     (when (= cwd (get-in st [:web/pending-room :cwd]))
                                       {:state (assoc-in st [:web/pending-room :agents-files] agents-files)}))
          ;; Palette sub-pages (project actions, models, skills, commits, files)
          ;; share one drill pattern: the ui-runtime force-closes the <dialog> on
          ;; an item click, so the handler sets :web/palette-open? plus a one-shot
          ;; :web/palette-drilling? and re-opens it via :palette/reopen;
          ;; :palette/opened keeps the sub-page while the flag is set. Keyboard
          ;; Tab keeps the dialog open and needs no reopen. :palette/reset-filter
          ;; must run after the reopen so it finds the input.
          :palette/drill         (fn [st {:keys [cwd label reopen?]}]
                                   {:state (cond-> (assoc st :web/palette-page
                                                          {:kind :project :cwd cwd :label label})
                                             reopen? (assoc :web/palette-drilling? true))
                                    :effects (if reopen?
                                               [[:palette/reopen nil]
                                                [:palette/reset-filter nil]]
                                               [[:palette/reset-filter nil]])})
          :palette/open          (fn [st _]
                                   {:state (-> st
                                               (assoc :web/palette-open? true)
                                               (dissoc :web/palette-page))
                                    :effects [[:palette/reopen nil]
                                              [:palette/reset-filter nil]]})
          :palette/open-models   (fn [st ev] (models/open st (:event/ts ev)))
          ;; color themes (xi.web.theme): Default + the user's, picked like a model
          :palette/open-themes   (fn [st _]
                                   {:state   (assoc st :web/palette-page {:kind :theme}
                                                   :web/palette-open? true
                                                   :web/palette-drilling? true)
                                    :effects [[:palette/reopen nil]
                                              [:palette/reset-filter nil]]})
          :palette/open-skills   (fn [st _]
                                   {:state (-> st
                                               (assoc :web/palette-page {:kind :skill}
                                                      :web/palette-open? true
                                                      :web/palette-drilling? true)
                                               (dissoc :web/skill-list))
                                    :effects [[:ws/send {:type :skill/web-list}]
                                              [:palette/reopen nil]]})
          ;; Commits of the chat's repo. :scope :session (default) — the ones
          ;; made this session: the server computes base..HEAD from the room's
          ;; cwd and created timestamp; :log — the recent git log.
          :palette/open-commits  (fn [st {:keys [scope]}]
                                   (let [scope   (or scope :session)
                                         room    (state/active-room st)
                                         cwd     (:cwd room)
                                         created (or (get-in room [:session :created])
                                                     (when-let [ms (:created room)]
                                                       (.toISOString (js/Date. ms))))]
                                     {:state (-> st
                                                 (assoc :web/palette-page {:kind :commits :scope scope}
                                                        :web/palette-open? true
                                                        :web/palette-drilling? true)
                                                 (dissoc :web/commit-list))
                                      :effects [[:ws/send {:type :commits/web-load :scope scope
                                                           :cwd cwd :created created}]
                                                [:palette/reopen nil]]}))
          ;; A reply for the other scope (switched pages mid-load) is stale.
          :commits/web-load-result (fn [st {:keys [commits scope]}]
                                     (when (= (or scope :session)
                                              (get-in st [:web/palette-page :scope]))
                                       {:state (assoc st :web/commit-list commits)}))
          :commits/open-diff     (fn [st {:keys [sha room-id]}]
                                   {:state (dissoc st :web/palette-page :web/palette-open?)
                                    :effects [[:palette/close nil]
                                              [:ws/send {:type :input/submit
                                                         :room-id room-id
                                                         :text (str "/diff commit:" sha)}]]})
          :palette/open-files    (fn [st _]
                                   (let [cwd (view-cwd st)]
                                     {:state (-> st
                                                 (assoc :web/palette-page {:kind :files}
                                                        :web/palette-open? true
                                                        :web/palette-drilling? true)
                                                 (dissoc :web/file-list))
                                      :effects [[:ws/send {:type :files/web-list :cwd cwd :path cwd}]
                                                [:palette/reopen nil]]}))
          ;; Clearing :web/file-list shows a spinner while the listing loads.
          :files/cd              (fn [st {:keys [path]}]
                                   (let [cwd (view-cwd st)]
                                     {:state (-> st
                                                 (assoc :web/palette-drilling? true)
                                                 (dissoc :web/file-list))
                                      :effects [[:ws/send {:type :files/web-list :cwd cwd :path path}]
                                                [:palette/reopen nil]]}))
          :files/web-list-result (fn [st {:keys [path parent entries error]}]
                                   {:state (assoc st :web/file-list
                                                  {:path path :parent parent
                                                   :entries (or entries []) :error error})})
          :files/open            (fn [st {:keys [path cwd]}]
                                   (let [cwd (or cwd (view-cwd st))]
                                     {:state (dissoc st :web/palette-page :web/palette-open?)
                                      :effects [[:palette/close nil]
                                                [:ws/send {:type :file/web-read :cwd cwd :path path}]]}))
          ;; The file buffer is client-local, one per path (xi.buffers/file-id);
          ;; in a virtual new chat it lives on the :web/pending-room.
          :file/web-read-result  (fn [st {:keys [path text error] :as ev}]
                                   (let [id     (buffers/file-id path)
                                         buf    {:kind :file :title path :path path
                                                 :text (or text (str "Could not read file:\n" error))}
                                         open   (fn [room]
                                                  (-> room
                                                      (buffers/install id buf (:event/ts ev))
                                                      (assoc-in [:ui :active-buffer] id)))
                                         room-id (:id (state/active-room st))]
                                     (cond
                                       (new-chat-view? st)
                                       {:state (update st :web/pending-room open)}

                                       room-id
                                       {:state (update-in st [:rooms room-id] open)}

                                       :else {:state st})))
          ;; Buffer switch / close for a virtual new chat (no room id yet).
          :pending/buffer-switch (fn [st {:keys [buffer-id]}]
                                   (when (:web/pending-room st)
                                     {:state (assoc-in st [:web/pending-room :ui :active-buffer] buffer-id)}))
          :pending/buffer-close  (fn [st {:keys [buffer-id]}]
                                   (when (:web/pending-room st)
                                     {:state (update st :web/pending-room
                                                     #(if buffer-id (buffers/close % buffer-id) (buffers/close-all %)))}))
          :sidebar/buffers-toggle (fn [st {:keys [session-id]}]
                                    (let [open (let [s (or (:web/sidebar-buffers-open st) #{})]
                                                 (if (contains? s session-id)
                                                   (disj s session-id)
                                                   (conj s session-id)))]
                                      {:state   (assoc st :web/sidebar-buffers-open open)
                                       :effects [[:cache/sidebar-buffers-open {:session-ids open}]]}))
          :palette/open-buffers  (fn [st _]
                                   {:state (assoc st :web/palette-page {:kind :buffers}
                                                     :web/palette-open? true
                                                     :web/palette-drilling? true)
                                    :effects [[:palette/reset-filter nil]
                                              [:palette/reopen nil]]})
          :buffer/pending-clear  (fn [st _] {:state (dissoc st :web/pending-buffer)})
          ;; Fuzzy file finder: the page ranks the room's flat file list
          ;; client-side. :action is :open (view) or :insert (path into the
          ;; draft). :in-dialog? (Tab from the project picker) keeps the dialog
          ;; open, so the one-shot drilling flag must not be set.
          :palette/open-file-finder
          (fn [st {:keys [cwd action in-dialog?]}]
            (let [cwd (or cwd (view-cwd st))]
              {:state (-> st
                          (assoc :web/palette-page {:kind :file-finder :action (or action :open)}
                                 :web/palette-open? true
                                 :web/file-finder-query "")
                          (cond-> (not in-dialog?) (assoc :web/palette-drilling? true))
                          (dissoc :web/file-tree))
               :effects [[:ws/send {:type :files/web-tree :cwd cwd}]
                         [:palette/reset-filter nil]
                         [:palette/reopen nil]]}))
          :files/web-tree-result (fn [st {:keys [cwd files error]}]
                                   {:state (assoc st :web/file-tree
                                                  {:cwd cwd :files (or files []) :error error})})
          :file-finder/input     (fn [st {:keys [query]}]
                                   {:state (assoc st :web/file-finder-query query)})
          ;; :action is :drill (open the project's action page) or :insert
          ;; (path into the draft); Tab opens the file finder with the same action.
          :palette/open-projects (fn [st {:keys [action]}]
                                   {:state (assoc st :web/palette-page {:kind :projects
                                                                       :action (or action :drill)}
                                                     :web/palette-open? true
                                                     :web/palette-drilling? true)
                                    :effects (cond-> [[:palette/reset-filter nil]
                                                      [:palette/reopen nil]]
                                               (empty? (:web/project-dirs st))
                                               (conj [:ws/send {:type :projects/web-list}]))})
          ;; Always re-fetched: project snippets depend on the room's cwd.
          :palette/open-snippets (fn [st _]
                                   (let [cwd (or (:cwd (state/active-room st))
                                                 (get-in st [:web/pending-room :cwd]))]
                                     {:state (-> st
                                                 (assoc :web/palette-page {:kind :snippets}
                                                        :web/palette-drilling? true)
                                                 (dissoc :web/snippet-list))
                                      :effects [[:ws/send {:type :snippets/web-list :cwd cwd}]
                                                [:palette/reopen nil]]}))
          :palette/open-commands (fn [st _]
                                   {:state (assoc st :web/palette-page {:kind :commands}
                                                     :web/palette-drilling? true)
                                    :effects [[:palette/reopen nil]]})
          ;; Full-text session search: the palette input is the query, each
          ;; keystroke lands in :palette/search-input and debounces a server
          ;; search. An optional :query seeds the input via reset-filter.
          :palette/open-search   (fn [st {:keys [cwd query]}]
                                   {:state (-> st
                                               (assoc :web/palette-page
                                                      {:kind :search :cwd cwd
                                                       :label (get-in st [:web/palette-page :label])}
                                                      :web/palette-drilling? true)
                                               (dissoc :web/palette-search)
                                               (cond-> (not (str/blank? query))
                                                 (assoc-in [:web/palette-search :query] query)))
                                    :effects [[:palette/reopen nil]
                                              [:palette/reset-filter query]]})
          :palette/search-input  (fn [st {:keys [query]}]
                                   (when (= :search (get-in st [:web/palette-page :kind]))
                                     (let [st' (-> st
                                                   (assoc-in [:web/palette-search :query] query)
                                                   (update :web/palette-search dissoc :results))]
                                       {:state st'
                                        :effects [[:palette/search-debounce
                                                   {:query (or query "")
                                                    :cwd (get-in st [:web/palette-page :cwd])
                                                    :names-only? (get-in st' [:web/palette-search :names-only?])}]]})))
          ;; Full-text (default) vs. names-only; re-runs the current query.
          :palette/toggle-content-search
          (fn [st _]
            (when (= :search (get-in st [:web/palette-page :kind]))
              (let [names-only? (not (get-in st [:web/palette-search :names-only?]))]
                {:state (-> st
                            (assoc-in [:web/palette-search :names-only?] names-only?)
                            (update :web/palette-search dissoc :results))
                 :effects [[:palette/search-debounce
                            {:query (or (get-in st [:web/palette-search :query]) "")
                             :cwd (get-in st [:web/palette-page :cwd])
                             :names-only? names-only?}]]})))
          :session/web-search    (fn [_st {:keys [query cwd names-only?]}]
                                   {:effects [[:ws/send {:type :session/web-search
                                                         :query query :cwd cwd
                                                         :names-only? names-only?}]]})
          ;; Stale replies (query no longer matches the input) are dropped.
          :session/web-search-result
          (fn [st {:keys [query sessions]}]
            (when (= (str/trim (or query ""))
                     (str/trim (or (get-in st [:web/palette-search :query]) "")))
              {:state (assoc-in st [:web/palette-search :results] (vec sessions))}))
          :palette/back          (fn [st _]
                                   {:state (dissoc st :web/palette-page)
                                    :effects [[:palette/reset-filter nil]]})
          ;; The palette's contents are gated on :web/palette-open? so hidden
          ;; items aren't rebuilt every render; a MutationObserver on the <dialog>
          ;; flips the flag when it opens. A drill's reopen keeps the sub-page
          ;; (and a search page's seed query); a fresh open starts at the top.
          :palette/opened        (fn [st _]
                                   (if (:web/palette-drilling? st)
                                     {:state (-> st
                                                 (assoc :web/palette-open? true)
                                                 (dissoc :web/palette-drilling?))
                                      :effects [[:palette/reset-filter
                                                 (when (= :search (get-in st [:web/palette-page :kind]))
                                                   (get-in st [:web/palette-search :query]))]]}
                                     {:state (-> st
                                                 (assoc :web/palette-open? true)
                                                 (dissoc :web/palette-page))
                                      :effects [[:palette/reset-filter nil]]}))
          ;; :web/palette-page survives a drill's close+reopen.
          :palette/closed        (fn [st _]
                                   {:state (dissoc st :web/palette-open?)})}))


;; ── Timeline auto-scroll state ───────────────────────────────────────────────
;; Auto-scroll follows the bottom until the user scrolls up. Only a user
;; gesture may turn it off: layout alone lowers scrollTop (the render window
;; drops its top node, scroll anchoring compensates), so a scrollTop decrease
;; counts as "scrolled up" only while a gesture is in flight. See
;; attach-scroll-listener!.
(defonce ^:private auto-scroll? (atom true))
(defonce ^:private tracked-timeline (atom nil))
(defonce ^:private prev-scroll-top (atom 0))
;; Timestamp (ms) until which scrollTop changes count as a user gesture;
;; refreshed on touchmove, upward wheel, and scroll-up keys.
(defonce ^:private user-scroll-intent-until (atom 0))
(defn- mark-user-scroll-intent! []
  (reset! user-scroll-intent-until (+ (js/Date.now) 400)))
;; Pointer held on the timeline (scrollbar drag, drag-select) fires no wheel/touch.
(defonce ^:private timeline-pointer-down? (atom false))
(defn- user-scrolling? []
  (or @timeline-pointer-down?
      (<= (js/Date.now) @user-scroll-intent-until)))
;; Set at init; the scroll listener and scroll-to-bottom! need them early.
(defonce ^:private dispatch-ref (atom nil))
(defonce ^:private app-ref (atom nil))

;; Smooth-scroll follow: while parked at the bottom with content streaming in,
;; a rAF loop eases toward the growing bottom (native smooth scrollTo restarts
;; on every render and never moves). Jumps beyond smooth-scroll-max-vh screens
;; and everything within smooth-scroll-settle-ms of a chat switch stay instant.
(defonce ^:private smooth-scroll-armed? (atom false))
(defonce ^:private smooth-scroll-session (atom nil))
(defonce ^:private smooth-follow-raf (atom nil))
(def ^:private smooth-scroll-max-vh 1.25)
(defonce ^:private smooth-scroll-settle-until (atom 0))
(def ^:private smooth-scroll-settle-ms 1500)
(def ^:private smooth-follow-ease 0.28)

;; Debounces the offline badge so transient WS blips don't flicker it.
(defonce ^:private offline-timer (atom nil))

(defn- repaint-closed-drawer!
  "iOS WebKit can leave the closed drawer painted at its open position; drop
   its layer for one frame so it re-rasterizes. No-op when docked or open."
  [wide? sidebar-open?]
  (when (and (not wide?) (not sidebar-open?))
    (when-let [^js el (.querySelector js/document ".sidebar-layout--floating > .sidebar")]
      (let [st (.-style el)]
        (set! (.-display st) "none")
        (js/requestAnimationFrame (fn [] (set! (.-display st) "")))))))

(defn- web-effects [routes]
  {:history/push (router/history-effect routes)
   :sidebar/repaint
   (fn [{:keys [get-state]} _]
     (js/requestAnimationFrame
      (fn []
        (let [s (get-state)]
          (repaint-closed-drawer! (:web/wide? s) (:web/sidebar-open? s))))))
   :nav/back     router/back-effect
   :projects/sync-textarea
   (fn [_ {:keys [text]}]
     (when-let [^js el (.querySelector js/document ".compose-input-wrapper textarea")]
       (set! (.-value el) text)
       (.focus el)))
   ;; Deferred a frame so it runs after the re-render and after a closing
   ;; <dialog> restores focus. A stray open modal dialog would make the page
   ;; inert and the focus a no-op, so close those first.
   :compose/focus
   (fn [_ _]
     (js/requestAnimationFrame
      (fn []
        (when-let [^js el (.querySelector js/document ".compose-input-wrapper textarea")]
          (doseq [^js d (array-seq (.querySelectorAll js/document "dialog[open]"))]
            (when-not (.contains d el) (.close d)))
          (.focus el #js {:preventScroll true})))))
   :compose/blur
   (fn [_ _]
     (when-let [^js el (.querySelector js/document ".compose-input-wrapper textarea")]
       (.blur el)))
   ;; After a palette page switch: reset the search box, re-fire `input` so
   ;; ui-runtime.js re-filters and re-highlights, and re-focus it.
   :palette/reset-filter
   (fn [_ query]
     (js/requestAnimationFrame
      (fn []
        (when-let [^js input (.querySelector js/document ".command-dialog[open] .command-input")]
          (set! (.-value input) (or query ""))
          (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
          (.focus input #js {:preventScroll true})))))
   ;; Idempotent re-open after the ui-runtime force-closed the palette on an
   ;; item click.
   :palette/reopen
   (fn [_ _]
     (js/requestAnimationFrame
      (fn []
        (when-let [^js cmd (aget js/window "__uiCommand")]
          (.open cmd "cmdk")))))
   ;; Close by id: the ui-runtime's click handler misses a node the dispatch
   ;; already detached.
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
   :palette/search-debounce
  (let [timer (atom nil)]
    (fn [{:keys [dispatch!]} payload]
      (when-let [t @timer] (js/clearTimeout t))
      (reset! timer
              (js/setTimeout
               (fn [] (dispatch! (assoc payload :type :session/web-search)))
               180))))
   :copy/flash-clear
  (let [timer (atom nil)]
    (fn [{:keys [dispatch!]} _]
      (when-let [t @timer] (js/clearTimeout t))
      (reset! timer
              (js/setTimeout
               (fn [] (dispatch! {:type :copy/flash-off}))
               1500))))
  :prompt-nav/scroll
   (fn [_ {:keys [history-index]}]
     (reset! auto-scroll? false)
     ;; The node may only exist after :web/timeline-window grows: poll a few frames.
     (let [sel (str ".timeline .post--user[data-history-index=\"" history-index "\"]")]
       (letfn [(try-scroll [n]
                 (if-let [node (.querySelector js/document sel)]
                   (do
                     (.scrollIntoView node #js {:behavior "smooth" :block "start"})
                     (doseq [el (array-seq
                                 (.querySelectorAll js/document ".post--nav-target"))]
                       (.remove (.-classList el) "post--nav-target"))
                     (.add (.-classList node) "post--nav-target"))
                   (when (pos? n)
                     (js/requestAnimationFrame #(try-scroll (dec n))))))]
         (try-scroll 30))))
  :prompt-nav/resume
   (fn [_ _]
     (reset! auto-scroll? true)
     (doseq [el (array-seq (.querySelectorAll js/document ".post--nav-target"))]
       (.remove (.-classList el) "post--nav-target"))
     (when-let [timeline (.querySelector js/document ".timeline")]
       (set! (.-scrollTop timeline) (.-scrollHeight timeline))))
  ;; Unfreezing the render window re-tightens it on the next render, which
  ;; shifts scrollHeight after this fires, so the snap is re-asserted over a
  ;; few frames until it lands.
  :timeline/scroll-bottom
   (fn [_ _]
     (reset! auto-scroll? true)
     (doseq [el (array-seq (.querySelectorAll js/document ".post--nav-target"))]
       (.remove (.-classList el) "post--nav-target"))
     (letfn [(snap [n]
               (when-let [timeline (.querySelector js/document ".timeline")]
                 (set! (.-scrollTop timeline) (.-scrollHeight timeline))
                 (when (and (pos? n)
                            (> (- (.-scrollHeight timeline)
                                  (.-scrollTop timeline)
                                  (.-clientHeight timeline))
                               2))
                   (js/requestAnimationFrame #(snap (dec n))))))]
       (snap 30)))
  ;; The entries the grown window reveals lay out above the viewport over the
  ;; next frames (and scroll anchoring may hold the old spot), so the top is
  ;; re-asserted for a few frames.
  :timeline/scroll-top
   (fn [_ _]
     (reset! auto-scroll? false)
     (doseq [el (array-seq (.querySelectorAll js/document ".post--nav-target"))]
       (.remove (.-classList el) "post--nav-target"))
     (letfn [(snap [n]
               (when-let [timeline (.querySelector js/document ".timeline")]
                 (set! (.-scrollTop timeline) 0)
                 (when (pos? n)
                   (js/requestAnimationFrame #(snap (dec n))))))]
       (snap 10)))
  ;; Play the sidebar row's leave transition (style.css), then dispatch :then;
  ;; at once when no row is in the DOM or motion is reduced.
  :sidebar/animate-leave
  (fn [{:keys [dispatch!]} {:keys [session-id then]}]
    (let [els (when session-id
                (array-seq (.querySelectorAll js/document
                                              (str ".sidebar [data-session-id=\"" session-id "\"]"))))
          reduced? (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))]
      (if (and (seq els) (not reduced?))
        (do (doseq [^js el els]
              (set! (.. el -style -height) (str (.-offsetHeight el) "px"))
              (.-offsetHeight el) ; reflow: height can't transition from auto
              (.add (.-classList el) "project-card-trigger--leaving")
              (set! (.. el -style -height) "0px"))
            (js/setTimeout #(dispatch! then) 300))
        (dispatch! then))))
  :cache/watch  (fn [_ {:keys [session-id count]}] (cache/watch! session-id count))
   :cache/recent-commands (fn [_ {:keys [commands]}] (cache/save-recent-commands! commands))
   :cache/recent-skills   (fn [_ {:keys [skills]}] (cache/save-recent-skills! skills))
   :cache/preferred-model (fn [_ {:keys [model]}] (cache/save-preferred-model! model))
   :cache/model-list (fn [_ {:keys [models at]}] (cache/save-model-list! models at))
   :cache/sidebar-collapsed (fn [_ {:keys [groups]}] (cache/save-sidebar-collapsed! groups))
   :cache/sidebar-buffers-open (fn [_ {:keys [session-ids]}] (cache/save-sidebar-buffers-open! session-ids))
   :cache/appearance (fn [_ {:keys [settings]}] (cache/save-appearance! settings))
   :cache/themes (fn [_ {:keys [themes]}] (cache/save-themes! themes))
   ;; Set / remove every theme-managed property on <html>; a committed theme
   ;; (:persist?) also refreshes the early-paint cache, a draft preview does not.
   :theme/apply-vars (fn [_ {:keys [vars persist?]}]
                       (let [style (.-style js/document.documentElement)]
                         (doseq [n theme/var-names]
                           (if-let [v (get vars n)]
                             (.setProperty style n v)
                             (.removeProperty style n))))
                       (when persist? (cache/save-theme-vars! vars)))
   :cache/user (fn [_ {:keys [user]}] (cache/save-cached-user! user))
   :cache/clear-watched (fn [_ _] (cache/clear-watched!))
   :cache/seed-room
   (fn [{:keys [dispatch!]} {:keys [session-id]}]
     (when-let [room (cache/load-room session-id)]
       (dispatch! {:type :web/cache-seed :session-id session-id :room room})))
   :cache/prefetch-room
   (fn [_ {:keys [session-id]}]
     (cache/prefetch-room! session-id))
   ;; A live room being left (:room) or a peeked history (:slice,
   ;; xi.web.prefetch) into the cache's memory tier.
   :cache/remember-room
   (fn [_ {:keys [session-id room slice]}]
     (cache/remember-room! session-id (or slice (cache/live-slice room))))
   ;; :room/join carrying the cache fingerprints, so the server can answer
   ;; :session/current, :session/resumed-tail, or a history-base join
   ;; (room-joined-from-cache) instead of the full history.
   :room/join-with-cache
   (fn [{:keys [dispatch!]} {:keys [target session-id]}]
     (let [{:keys [msg-hash msg-count history history-hash]} (cache/load-room session-id)]
       (dispatch! (cond-> {:type :room/join :target target}
                    session-id   (assoc :session-id session-id)
                    msg-hash     (assoc :cached-msg-hash msg-hash)
                    msg-count    (assoc :cached-msg-count msg-count)
                    history-hash (assoc :cached-history-hash history-hash
                                        :cached-history-count (count history))))))
   :theme/apply  (fn [_ mode]
                   (let [el js/document.documentElement]
                     (.setAttribute el "data-no-transitions" "")
                     (.-offsetHeight el)
                     (js/requestAnimationFrame
                      (fn [] (js/requestAnimationFrame
                              (fn [] (.removeAttribute el "data-no-transitions")))))
                     (case mode
                       "light" (.setAttribute el "data-theme" "light")
                       "dark"  (.setAttribute el "data-theme" "dark")
                       (.removeAttribute el "data-theme"))
                     (try
                       (if (= mode "auto")
                         (.removeItem js/localStorage "ui-theme")
                         (.setItem js/localStorage "ui-theme" mode))
                       (catch :default _))))})

;; ── Taps (cache persistence + unread polling + post-join URL) ─────────────────

(defn- request-projects-tap
  [dispatch!]
  (fn [event state]
    (when (and (= :lobby/state (:type event))
               (nil? (get-in state [:lobby :agent-id]))
               (nil? (:web/project-dirs state)))
      (dispatch! {:type :projects/web-list}))))

(defn- mark-read-on-turn-tap
  "On turn-end: refresh the project dirty dots, and mark the session read when
   it is the one being viewed (the user watched it live)."
  [dispatch!]
  (fn [event state]
    (when (= :agent/turn-end (:type event))
      (dispatch! {:type :projects/web-refresh})
      (let [room-id (:room-id event)
            ended   (get-in state [:rooms room-id :session :id])
            viewed  (get-in state [:web/route :session-id])]
        (when (and ended (= ended viewed))
          (dispatch! {:type :session/mark-read :session-id ended}))))))

(defn- fill-url-tap
  "After the user's own new-chat join (the one carrying a :join-token),
   replace the session-less URL with the real session id so a reload resumes
   it. Token-less joins (reconnects, other rooms) must not touch the URL."
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

;; [room-id buffer-id] last told to the server (buffer-presence-tap).
(defonce ^:private buffer-presence-sent (atom nil))

(defn- buffer-presence-tap
  "Tell the server which buffer this client shows (`:client/update {:buffer
   id}`) whenever it changes; the room manager folds it into room presence
   (xi.buffers/viewers). The switch itself stays client-local."
  [dispatch!]
  (fn [_event state]
    (let [room (state/active-room state)
          cur  (when room [(:id room) (get-in room [:ui :active-buffer] :chat)])]
      (when (and cur (not= cur @buffer-presence-sent))
        (reset! buffer-presence-sent cur)
        (dispatch! {:type :client/update :buffer (second cur)})))))

(defn- pending-buffer-tap
  "Open the buffer a sidebar / palette row asked for (:web/pending-buffer,
   xi.web.router/with-buffer) once its chat has joined. A join of another
   session drops the request."
  [dispatch!]
  (fn [event state]
    (when (= :room/joined (:type event))
      (when-let [{:keys [session-id buffer-id]} (:web/pending-buffer state)]
        (dispatch! {:type :buffer/pending-clear})
        (when (= session-id (get-in event [:room :session :id]))
          (when-let [ev (router/buffer-open-event (:room-id event) (:room event) buffer-id)]
            (dispatch! ev)))))))

(defn- pending-submit-tap
  "Fire a stashed message once the room it was aimed at joins, matched by the
   echoed :join-token (virtual new chat) or the joined session id, so a
   navigation race or reconnect can't send it into the wrong room."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-submit state))
      (let [{:keys [session-id text images join-token model]} (:web/pending-submit state)
            joined-sid   (get-in event [:room :session :id])
            joined-token (:join-token event)
            room-id      (:room-id event)]
        (when (if join-token
                (= join-token joined-token)
                (or (nil? session-id) (= session-id joined-sid)))
          (dispatch! {:type :submit/clear-pending})
          (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                       model        (assoc :model model)
                       (seq images) (assoc :images (vec images)))))))))

(defn- pending-command-tap
  "The command analogue of pending-submit-tap."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-command state))
      (let [{:keys [session-id name args join-token]} (:web/pending-command state)
            joined-sid   (get-in event [:room :session :id])
            joined-token (:join-token event)
            room-id      (:room-id event)]
        (when (if join-token
                (= join-token joined-token)
                (or (nil? session-id) (= session-id joined-sid)))
          (dispatch! {:type :command/clear-pending})
          (dispatch! (cond-> {:type :command/run :room-id room-id :name name}
                       args (assoc :args args))))))))

(defn- record-command-tap
  [dispatch!]
  (fn [event _state]
    (when (and (= :web/command (:type event))
               (views/known-command? (:name event)))
      (dispatch! {:type :web/record-command :name (:name event)}))))

(defn- prompt-nav-close-tap
  [dispatch!]
  (fn [event state]
    (when (and (:web/prompt-nav state)
               (or (and (= :input/submit (:type event)) (not (:remote? event)))
                   (= :route/navigate (:type event))))
      (dispatch! {:type :prompt-nav/close}))))

(defn- optimistic-tap
  "Show the user's prompt the instant they submit it, before the :user history
   entry mirrors back (which clears it). Queued submissions (room busy) get no
   bubble; the queue count is the feedback. A send re-enables auto-scroll and
   arms smooth-follow so the bubble eases in like a streaming block."
  [dispatch!]
  (fn [event state]
    (cond
      (and (= :input/submit (:type event))
           (not (:remote? event))
           (not (get-in state [:rooms (:room-id event) :agent :busy?]))
           (let [parsed (commands/parse-input (:text event))]
             (or (= :prompt (:type parsed))
                 (seq (:images event)))))
      (do
        (reset! auto-scroll? true)
        (reset! smooth-scroll-armed? true)
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

(defn- cancel-smooth-follow! []
  (when-let [id @smooth-follow-raf]
    (js/cancelAnimationFrame id)
    (reset! smooth-follow-raf nil)))

(defn- snap-scroll-top!
  "Set scrollTop unless already within a pixel of `top`. scrollHeight and
   clientHeight are rounded while the real bottom can be fractional, so
   rewriting it on every render (each keystroke) jitters the view by 1px."
  [^js el top]
  (when (>= (js/Math.abs (- top (.-scrollTop el))) 1)
    (set! (.-scrollTop el) top)))

(defn- smooth-follow-step!
  "One frame of the follow loop: ease scrollTop toward the growing bottom and
   reschedule until within a pixel. Yields to an in-flight user gesture, which
   it would otherwise out-run."
  [^js timeline]
  (reset! smooth-follow-raf nil)
  (when (and @auto-scroll? (not (user-scrolling?)))
    (let [target (- (.-scrollHeight timeline) (.-clientHeight timeline))
          cur    (.-scrollTop timeline)
          delta  (- target cur)]
      (if (<= delta 1)
        (snap-scroll-top! timeline target)
        (do (set! (.-scrollTop timeline) (+ cur (max 1 (* delta smooth-follow-ease))))
            (reset! smooth-follow-raf
                    (js/requestAnimationFrame #(smooth-follow-step! timeline))))))))

(defn- scroll-to-bottom! []
  (when (and @auto-scroll? (not (user-scrolling?)))
    (when-let [timeline (.querySelector js/document ".timeline")]
      ;; A session switch lands instantly: disarm the follow and open a settle
      ;; window while the history is still arriving.
      (let [st  (some-> @app-ref :state deref)
            sid (get-in st [:web/route :session-id])]
        (when (not= sid @smooth-scroll-session)
          (reset! smooth-scroll-session sid)
          (reset! smooth-scroll-armed? false)
          (reset! smooth-scroll-settle-until (+ (js/Date.now) smooth-scroll-settle-ms))
          (cancel-smooth-follow!)))
      (let [target (- (.-scrollHeight timeline) (.-clientHeight timeline))
            delta  (- target (.-scrollTop timeline))]
        (if (and @smooth-scroll-armed?
                 (< delta (* smooth-scroll-max-vh (.-clientHeight timeline))))
          (when-not @smooth-follow-raf
            (reset! smooth-follow-raf
                    (js/requestAnimationFrame #(smooth-follow-step! timeline))))
          (do (cancel-smooth-follow!)
              (snap-scroll-top! timeline target)
              ;; Only live streaming earns the easing: arm once settled and busy.
              (when (and (> (js/Date.now) @smooth-scroll-settle-until)
                         (some-> @app-ref :state deref state/active-room
                                 :agent :busy?))
                (reset! smooth-scroll-armed? true))))))))

(defonce ^:private global-scroll-intent-attached? (atom false))

(defn- attach-global-scroll-intent!
  []
  (when-not @global-scroll-intent-attached?
    (reset! global-scroll-intent-attached? true)
    (doseq [t ["pointerup" "pointercancel" "blur"]]
      (.addEventListener js/window t (fn [] (reset! timeline-pointer-down? false))))
    (.addEventListener js/document "keydown"
                       (fn [^js e]
                         (when (and (#{"ArrowUp" "PageUp" "Home"} (.-key e))
                                    (not (.. e -target -isContentEditable))
                                    (not (#{"INPUT" "TEXTAREA" "SELECT"}
                                          (.. e -target -tagName))))
                           (mark-user-scroll-intent!))))))

(def ^:private scroll-step-px
  60)

(def ^:private scroller-selectors
  "The scrollable container of each view, most specific first: overlays, then
   the open buffer's pane, then the page."
  [".keys-body" ".diff-view" ".prompt-tab-body" ".file-tab-md" ".file-tab-code"
   ".timeline" ".home"])

(defn- active-scroller []
  (some (fn [sel]
          (when-let [el (.querySelector js/document sel)]
            (when (> (.-scrollHeight el) (+ (.-clientHeight el) 2)) el)))
        scroller-selectors))

;; Key scrolls (j / k, half pages, hunk / file jumps) ease toward their target
;; with a rAF loop; each press adds to the pending target so a held key keeps
;; a steady pace (native smooth scrollTo would restart on every repeat).
;; {:el :target :raf}, nil when idle.
(defonce ^:private key-scroll (atom nil))
(def ^:private key-scroll-ease 0.3)

(defn- cancel-key-scroll! []
  (when-let [{:keys [raf]} @key-scroll]
    (js/cancelAnimationFrame raf))
  (reset! key-scroll nil))

(defn- max-scroll-top [^js el]
  (max 0 (- (.-scrollHeight el) (.-clientHeight el))))

(defn- key-scroll-frame! []
  (when-let [{:keys [^js el target]} @key-scroll]
    (let [target (min target (max-scroll-top el))
          cur    (.-scrollTop el)
          delta  (- target cur)]
      (if (or (<= (js/Math.abs delta) 1) (not (.-isConnected el)))
        (do (set! (.-scrollTop el) target)
            (reset! key-scroll nil))
        (let [step (* delta key-scroll-ease)
              step (if (< (js/Math.abs step) 1) (js/Math.sign delta) step)]
          (when (neg? delta) (mark-user-scroll-intent!))
          (set! (.-scrollTop el) (+ cur step))
          (swap! key-scroll assoc :raf (js/requestAnimationFrame key-scroll-frame!)))))))

(defn- key-scroll-base
  [^js el]
  (let [{pending-el :el target :target} @key-scroll]
    (if (identical? pending-el el) target (.-scrollTop el))))

(defn- animate-scroll-to!
  "Ease `el`'s scrollTop to `top` (clamped); instant under
   prefers-reduced-motion. An upward move counts as a user gesture."
  [^js el top]
  (let [top (-> top (max 0) (min (max-scroll-top el)))]
    (when (< top (.-scrollTop el)) (mark-user-scroll-intent!))
    (if (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
      (do (cancel-key-scroll!)
          (set! (.-scrollTop el) top))
      (let [{:keys [raf] pending-el :el} @key-scroll]
        (when (and raf (not (identical? pending-el el)))
          (js/cancelAnimationFrame raf))
        (reset! key-scroll {:el el :target top
                            :raf (if (and raf (identical? pending-el el))
                                   raf
                                   (js/requestAnimationFrame key-scroll-frame!))})))))

;; The chat timeline goes through its event so hidden earlier entries are
;; revealed first; any other scroller (or a timeline too short to scroll)
;; just eases to its top.
(defn- scroll-to-top! [dispatch!]
  (let [el (active-scroller)]
    (if (or (nil? el) (.contains (.-classList el) "timeline"))
      (when (.querySelector js/document ".timeline")
        (dispatch! {:type :timeline/scroll-to-top}))
      (animate-scroll-to! el 0))))

(defn- scroll-step!
  ([dir] (scroll-step! dir false))
  ([dir half-page?]
   (when-let [el (active-scroller)]
     (let [px (if half-page? (/ (.-clientHeight el) 2) scroll-step-px)]
       (animate-scroll-to! el (+ (key-scroll-base el) (* dir px)))))))

(def ^:private load-earlier-threshold-px
  600)

(defonce ^:private load-earlier-pending? (atom false))

(defn- maybe-load-earlier!
  "Near the top of the timeline: press \"Show earlier messages\" for the user.
   Safari has no scroll anchoring, so the added height is compensated by hand
   when scrollTop did not move."
  [^js timeline]
  (when (and (not @load-earlier-pending?)
             (< (.-scrollTop timeline) load-earlier-threshold-px))
    (when-let [btn (.querySelector timeline ".load-earlier button")]
      (let [top-before    (.-scrollTop timeline)
            height-before (.-scrollHeight timeline)]
        (reset! load-earlier-pending? true)
        (.click btn)
        ;; Two frames: re-render, then scroll anchoring (if any) settles.
        (js/requestAnimationFrame
         (fn []
           (js/requestAnimationFrame
            (fn []
              (let [delta (- (.-scrollHeight timeline) height-before)]
                (when (and (pos? delta)
                           (<= (js/Math.abs (- (.-scrollTop timeline) top-before)) 2))
                  (set! (.-scrollTop timeline) (+ top-before delta))
                  (reset! prev-scroll-top (.-scrollTop timeline))))
              (reset! load-earlier-pending? false)
              (maybe-load-earlier! timeline)))))))))

(defn- attach-scroll-listener! []
  (when-let [timeline (.querySelector js/document ".timeline")]
    (when-not (identical? timeline @tracked-timeline)
      (reset! tracked-timeline timeline)
      (reset! auto-scroll? true)
      (reset! smooth-scroll-armed? false)
      (reset! prev-scroll-top (.-scrollTop timeline))
      ;; Only real input events mark user intent; wheel counts upward only.
      (.addEventListener timeline "touchmove"
                         (fn [] (mark-user-scroll-intent!)) #js {:passive true})
      (.addEventListener timeline "wheel"
                         (fn [^js e]
                           (when (neg? (.-deltaY e))
                             (mark-user-scroll-intent!)
                             ;; At scrollTop 0 no scroll event follows the wheel.
                             (maybe-load-earlier! timeline)))
                         #js {:passive true})
      ;; Primary button only: a context menu can swallow the pointerup.
      (.addEventListener timeline "pointerdown"
                         (fn [^js e]
                           (when (zero? (.-button e))
                             (reset! timeline-pointer-down? true))))
      (attach-global-scroll-intent!)
      (.addEventListener timeline "scroll"
                         (fn []
                           (let [top     (.-scrollTop timeline)
                                 prev    @prev-scroll-top
                                 bottom? (at-bottom? timeline)]
                             (reset! prev-scroll-top top)
                             ;; Layout lowers scrollTop too (a switch swaps in
                             ;; a short first paint, xi.web.router/
                             ;; first-paint-window): that must not load more.
                             (when (and (< top prev)
                                        (or (user-scrolling?) (not @auto-scroll?)))
                               (maybe-load-earlier! timeline))
                             (cond
                               bottom?
                               (reset! auto-scroll? true)
                               (and (< top (- prev 2)) (user-scrolling?))
                               (reset! auto-scroll? false))
                             (when-let [d @dispatch-ref]
                               (d {:type :web/set-scrolled-up
                                   :scrolled-up? (not @auto-scroll?)})))))
      ;; Content laid out after a render (images, highlighting, a growing
      ;; composer) fires no render or scroll event, so re-snap on size growth.
      (let [obs (js/ResizeObserver. (fn [] (scroll-to-bottom!)))]
        (when-let [content (.querySelector timeline ".timeline-content")]
          (.observe obs content))
        (when-let [dock (.querySelector js/document ".compose-dock")]
          (.observe obs dock)))
      (when-let [d @dispatch-ref]
        (d {:type :web/set-scrolled-up :scrolled-up? (not (at-bottom? timeline))})))))

(defonce ^:private code-copy-attached? (atom false))

(defn- attach-code-copy-listener!
  "Delegated document listeners: right-click or tap on a code block, inline
   code, or a rendered markdown diff opens the floating Copy button
   (:web/code-menu). Skipped inside user bubbles and inline editors."
  []
  (when-not @code-copy-attached?
    (reset! code-copy-attached? true)
    (let [code-node (fn [^js e]
                      (when-let [node (some-> (.-target e) (.closest "pre, code, .md-diff"))]
                        (when (and (not (.closest node ".post--user"))
                                   (not (.closest node ".bubble-edit-textarea")))
                          node)))
          open!     (fn [^js e ^js node]
                      (when-let [d @dispatch-ref]
                        (let [attr (fn [a] (some-> node
                                                   (.closest (str "[" a "]"))
                                                   (.getAttribute a)))
                              path (attr "data-file-path")
                              diff-path (attr "data-diff-path")
                              diff-text (attr "data-diff-text")
                              text (if (.matches node ".md-diff")
                                     diff-text
                                     (.-textContent node))]
                          (when (seq (str/trim (or text "")))
                            (d (cond-> {:type :code/menu-open
                                        :text text
                                        :x (.-clientX e)
                                        :y (.-clientY e)}
                                 path (assoc :path path)
                                 diff-text (assoc :diff-path diff-path
                                                  :diff-text diff-text)))
                            true))))]
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
           (when (open! e node)
             (.preventDefault e))))))))

;; ── Render ───────────────────────────────────────────────────────────────────

(defn- el [id] (.getElementById js/document id))

;; Composed extension page table and route table, set at init. Routes stay
;; an atom because user extensions' web halves add routes after startup.
(defonce ^:private pages-ref (atom nil))
(defonce ^:private routes-ref (atom nil))
(defonce ^:private sidebar-layout-sig (atom nil))

(defn- sidebar-layout-sig-of
  "The state slices that move sidebar rows. A render where they are all
   unchanged (streamed tokens, typing) skips the row measuring FLIP needs."
  [st]
  [(:lobby st) (:web/sidebar-collapsed st) (:web/draft-chats st)
   (:web/sidebar-buffers-open st) (:web/sidebar-open? st)
   (get-in st [:web/route :session-id]) (get-in st [:web/route :page])])

(defn- render! [app-state dispatch!]
  (let [root (el "app")
        hiccup (views/root-view app-state dispatch! @pages-ref)
        sig    (sidebar-layout-sig-of app-state)
        snap   (when (not= sig @sidebar-layout-sig) (flip/snapshot))]
    (reset! sidebar-layout-sig sig)
    (try
      (r/render root hiccup)
      (catch :default e
        ;; A throwing reconcile leaves Replicant's rendering? flag stuck and
        ;; every later render loops in rAF. Drop its cached vdom for the root
        ;; and rebuild from a clean baseline; the logged error is the real bug.
        (js/console.error "[xi-web] render failed — recovering:" e)
        (vswap! r/state dissoc root)
        (try
          (r/render root hiccup)
          (catch :default e2
            (js/console.error "[xi-web] recovery render also failed:" e2)
            (vswap! r/state dissoc root)))))
    (flip/play! snap))
  (let [t (title/page-title app-state)]
    (when (not= t (.-title js/document))
      (set! (.-title js/document) t)))
  (attach-scroll-listener!)
  (js/requestAnimationFrame scroll-to-bottom!))

;; ── Init ─────────────────────────────────────────────────────────────────────

(defn- ws-url
  "WS server URL: same origin as the page (port omitted behind a reverse
   proxy), except shadow dev-http (8100) maps to 7474. ?host/?port override."
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
  []
  (ext/instantiate config/web {}))

(defn- ensure-client-key!
  "Persistent random key identifying this browser to the server (the web
   analog of ~/.config/xi/client-key); unknown keys go through pairing."
  []
  (or (try (.getItem js/localStorage "xi-client-key") (catch :default _ nil))
      (let [arr (js/Uint8Array. 32)
            _   (.getRandomValues js/crypto arr)
            k   (.join (.from js/Array arr (fn [b] (.padStart (.toString b 16) 2 "0"))) "")]
        (try (.setItem js/localStorage "xi-client-key" k) (catch :default _ nil))
        k)))

(defn- claimed-user
  "The user id this browser claims in :auth/hello (`localStorage xi-user`),
   or nil for the server's device assignment. Unauthenticated by design."
  []
  (some-> (try (.getItem js/localStorage "xi-user") (catch :default _ nil))
          not-empty
          util/user-id))

(defn- device-name
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
  "Static one-shot render of fabricated data for README screenshots
   (?demo=<view>); no WS transport, no-op dispatch."
  [view]
  (js/console.log "[xi-web] demo mode:" view)
  (let [composed (ext/compose (web-extensions))]
    (reset! pages-ref (:pages composed))
    (dashboard/register! (:dashboard-cards composed))
    (.setProperty (.-style js/document.documentElement) "--app-height" "100dvh")
    (r/render (el "app")
              (views/root-view (assoc (demo/demo-state view)
                                      :web/nav-items (:nav-items composed)
                                      :web/sidebar-groups (:sidebar-groups composed)
                                      :web/session-menu-items (:session-menu-items composed)
                                      :web/appearance-config config/appearance)
                               (fn [& _])
                               (:pages composed)))))

(defn- reset-zoom!
  "Force iOS WebKit back to scale=1 after a standalone PWA resumes zoomed:
   momentarily tighten maximum-scale, then restore the viewport meta. No-op
   unless actually zoomed."
  []
  (let [vv    js/window.visualViewport
        scale (some-> vv .-scale)]
    (when (and scale (> scale 1.01))
      (when-let [meta (.querySelector js/document "meta[name=viewport]")]
        (let [content (.getAttribute meta "content")]
          (.setAttribute meta "content" (str content ", maximum-scale=0.99"))
          (js/requestAnimationFrame
           (fn [] (.setAttribute meta "content" content))))))))

(defn- session-step!
  [st dispatch! dir]
  (let [order (sidebar/sidebar-session-order st)
        n     (count order)]
    (when (pos? n)
      (let [route (:web/route st)
            cur   (when (= :chat (:page route)) (:session-id route))
            idx   (first (keep-indexed (fn [i sid] (when (= sid cur) i)) order))
            nxt   (cond
                    (nil? idx)    0
                    (= dir :next) (min (dec n) (inc idx))
                    :else         (max 0 (dec idx)))
            sid   (nth order nxt)]
        (when (not= sid cur)
          (dispatch! {:type :route/navigate :page :chat :session-id sid}))))))

;; {:last sid :visited #{sid}}: the sessions the current run of attention
;; jumps already landed on. Any other navigation breaks the chain.
(defonce ^:private attention-chain (atom nil))

(defn- jump-to-attention!
  "Jump to the session that most needs attention (sidebar/attention-order);
   repeated presses walk that order, skipping sessions already visited."
  [st dispatch!]
  (let [{:keys [recent hidden earlier]} (sidebar/sidebar-session-groups st)
        cur     (get-in st [:web/route :session-id])
        chain   @attention-chain
        visited (if (and cur (= cur (:last chain))) (:visited chain) #{})
        order   (sidebar/attention-order (concat recent hidden earlier))]
    (when-let [{:keys [sid visited]} (sidebar/next-attention-jump order cur visited)]
      (reset! attention-chain {:last sid :visited visited})
      (dispatch! {:type :route/navigate :page :chat :session-id sid}))))

(defn- prune-all!
  [st dispatch!]
  (let [pa?      (get-in st [:lobby :agent-id])
        cleanups (views/session-cleanups pa? (sidebar/sidebar-session-groups st))]
    (run! (comp dispatch! :event) cleanups)))

(defn- permission-answer
  [st option]
  (let [room (state/active-room st)
        {:keys [dialog-id value]} (dlg/answer room option)]
    (when dialog-id
      {:room-id (:id room) :dialog-id dialog-id :value value})))

(defn- jump-diff-file!
  [dir]
  (let [files    (vec (array-seq (.querySelectorAll js/document ".diff-tab .diff-file")))
        ^js view (some-> (first files) (.closest ".diff-view"))]
    (when view
      (let [view-top (.-top (.getBoundingClientRect view))
            tops     (mapv #(- (.-top (.getBoundingClientRect %)) view-top) files)
            cur      (or (last (keep-indexed (fn [i top] (when (<= top 4) i)) tops)) -1)
            target   (get tops (if (= dir :next) (inc cur) (dec cur)))]
        (when target
          (animate-scroll-to! view (+ (.-scrollTop view) target)))))))

(defn- jump-diff-hunk!
  [dir]
  (let [headers (vec (array-seq (.querySelectorAll js/document ".diff-view .diff-hunk-header")))
        ^js view (some-> (first headers) (.closest ".diff-view"))]
    (when view
      (let [view-top (.-top (.getBoundingClientRect view))
            pinned   (if-let [^js f (.querySelector view ".diff-file-header")]
                       (.-height (.getBoundingClientRect f))
                       0)
            offsets  (mapv #(- (.-top (.getBoundingClientRect %)) view-top pinned) headers)
            target   (if (= dir :next)
                       (first (filter #(> % 4) offsets))
                       (last (filter #(< % -4) offsets)))]
        (when target
          (animate-scroll-to! view (+ (.-scrollTop view) target)))))))

(defn- chat-room
  "The room of the chat on screen when its chat tab (not a diff / file
   buffer) is showing, else nil."
  [st]
  (let [room (state/active-room st)]
    (when (and (= :chat (get-in st [:web/route :page]))
               (:id room)
               (= :chat (get-in room [:ui :active-buffer] :chat)))
      room)))

(defn- chat-session-id
  [st]
  (when (= :chat (get-in st [:web/route :page]))
    (get-in st [:web/route :session-id])))

(defn- install-actions!
  "Register the web client's keyboard actions (xi.web.keymap) behind the
   action ids the keymap binds (xi.keys/defaults, config.edn :keys). `:when`
   guards let a key fall through when the action makes no sense; actions
   that just dispatch declare the :event so palette rows can show the key.
   Transient layers: :permission-pending and :agent-busy."
  []
  (keymap/register-layer! {:id :permission-pending
                           :when (fn [st] (some? (permission-answer st :yes)))})
  (keymap/register-layer! {:id :agent-busy
                           :when (fn [st] (boolean (get-in (state/active-room st) [:agent :busy?])))})
  (keymap/register-action! {:id :chat/new :event {:type :room/new}})
  (keymap/register-action! {:id :sidebar/toggle :event {:type :sidebar/toggle}})
  (keymap/register-action! {:id :session/jump-attention
                            :run (fn [st dispatch! _] (jump-to-attention! st dispatch!))})
  (keymap/register-action! {:id :sessions/prune
                            :run (fn [st dispatch! _] (prune-all! st dispatch!))})
  (keymap/register-action! {:id :files/find :event {:type :palette/open-file-finder}})
  (keymap/register-action! {:id :buffers/switch :event {:type :palette/open-buffers}})
  (keymap/register-action! {:id :palette/open :event {:type :palette/open}})
  (keymap/register-action! {:id :projects/pick :event {:type :palette/open-projects :action :drill}})
  ;; Web twin of the projects extension's TUI :project/open.
  (keymap/register-action! {:id :project/open :event {:type :palette/open-projects :action :insert}})
  (keymap/register-action! {:id :skills/search :event {:type :palette/open-skills}})
  (keymap/register-action! {:id :themes/pick :event {:type :palette/open-themes}})
  (keymap/register-action! {:id :projects/open
                            :event {:type :route/navigate :page :home}})
  (keymap/register-action! {:id :chat/hide
                            :when chat-session-id
                            :run (fn [st dispatch! _]
                                   (dispatch! {:type :dismissed/toggle
                                               :session-id (chat-session-id st)}))})
  (keymap/register-action! {:id :chat/delete
                            :when chat-session-id
                            :run (fn [st dispatch! _]
                                   (dispatch! {:type :session/delete
                                               :session-id (chat-session-id st)}))})
  (keymap/register-action! {:id :prompt/prev
                            :when chat-room
                            :run (fn [st dispatch! _]
                                   (dispatch! (assoc (views/prompt-nav-ctx st (:history (chat-room st)))
                                                     :type :prompt-nav/prev)))})
  (keymap/register-action! {:id :prompt/next
                            :when (fn [st] (and (chat-room st) (some? (:web/prompt-nav st))))
                            :run (fn [st dispatch! _]
                                   (let [ctx (views/prompt-nav-ctx st (:history (chat-room st)))]
                                     (dispatch! (if (>= (:web/prompt-nav st) (dec (:count ctx)))
                                                  {:type :timeline/scroll-to-bottom}
                                                  (assoc ctx :type :prompt-nav/next)))))})
  (keymap/register-action! {:id :git/log
                            :when (fn [st] (some? (:id (state/active-room st))))
                            :event {:type :palette/open-commits :scope :log}})
  (keymap/register-action! {:id :git/status
                            :when (fn [st] (some? (:id (state/active-room st))))
                            :run (fn [st dispatch! _]
                                   (dispatch! {:type :diff/reopen
                                               :room-id (:id (state/active-room st))
                                               :method "git" :engine :git}))})
  (keymap/register-action! {:id :session/next
                            :run (fn [st dispatch! _] (session-step! st dispatch! :next))})
  (keymap/register-action! {:id :session/prev
                            :run (fn [st dispatch! _] (session-step! st dispatch! :prev))})
  (doseq [[id option] [[:permission/allow      :yes]
                       [:permission/always     :always]
                       [:permission/allow-repo :allow-repo]
                       [:permission/allow-block :allow-block]
                       [:permission/deny       :no]]]
    (keymap/register-action! {:id id
                              :when (fn [st] (some? (permission-answer st option)))
                              :run (fn [st dispatch! _]
                                     (when-let [answer (permission-answer st option)]
                                       (dispatch! (assoc answer :type :ui/dialog-response))))}))
  (keymap/register-action! {:id :agent/abort
                            :when (fn [st] (boolean (get-in (state/active-room st) [:agent :busy?])))
                            :run (fn [st dispatch! _]
                                   (dispatch! {:type :agent/abort :room-id (:id (state/active-room st))}))})
  (keymap/register-action! {:id :compose/focus
                            :when (fn [st] (= :chat (get-in st [:web/route :page])))
                            :event {:type :compose/focus}})
  (keymap/register-action! {:id :timeline/bottom
                            :when (fn [st] (= :chat (get-in st [:web/route :page])))
                            :event {:type :timeline/scroll-to-bottom}})
  (keymap/register-action! {:id :scroll/top
                            :run (fn [_ dispatch! _] (scroll-to-top! dispatch!))})
  (keymap/register-action! {:id :scroll/down
                            :run (fn [_ _ _] (scroll-step! 1))})
  (keymap/register-action! {:id :scroll/up
                            :run (fn [_ _ _] (scroll-step! -1))})
  (keymap/register-action! {:id :scroll/half-down
                            :run (fn [_ _ _] (scroll-step! 1 true))})
  (keymap/register-action! {:id :scroll/half-up
                            :run (fn [_ _ _] (scroll-step! -1 true))})
  ;; Escape drops focus from the composer or any other text field, back to
  ;; navigate mode; fields inside an open <dialog> are left to the dialog.
  (keymap/register-action! {:id :compose/blur
                            :when (fn [_] (not (keymap/in-open-dialog?)))
                            :run (fn [_ dispatch! _]
                                   (if (keymap/compose-focused?)
                                     (dispatch! {:type :compose/blur})
                                     (keymap/blur-active!)))})
  (keymap/register-action! {:id :dialog/close
                            :when (fn [st] (boolean (or (:web/appearance-open? st)
                                                        (:web/keys-open? st))))
                            :run (fn [st dispatch! _]
                                   (dispatch! {:type (if (:web/keys-open? st)
                                                       :keys/close
                                                       :appearance/close)}))})
  (keymap/register-action! {:id :keys/show :event {:type :keys/show}})
  ;; Back to the chat; the buffer stays open. Not while an open <dialog> (the
  ;; Ctrl+K palette…) has focus: its Escape closes just the dialog.
  (keymap/register-action! {:id :buffer/close
                            :when (fn [_] (not (keymap/in-open-dialog?)))
                            :run (fn [st dispatch! _]
                                   (let [room-id (when-not (new-chat-view? st)
                                                   (:id (state/active-room st)))]
                                     (dispatch! {:type :diff/clear-selection})
                                     (dispatch! (if room-id
                                                  {:type :ui/buffer-switch :room-id room-id :buffer-id :chat}
                                                  {:type :pending/buffer-switch :buffer-id :chat}))))})
  (keymap/register-action! {:id :diff/next-file
                            :run (fn [_ _ _] (jump-diff-file! :next))})
  (keymap/register-action! {:id :diff/prev-file
                            :run (fn [_ _ _] (jump-diff-file! :prev))})
  (keymap/register-action! {:id :diff/next-hunk
                            :run (fn [_ _ _] (jump-diff-hunk! :next))})
  (keymap/register-action! {:id :diff/prev-hunk
                            :run (fn [_ _ _] (jump-diff-hunk! :prev))}))

(defn- on-connection-status
  "Transport status callback: a reconnect clears the offline badge at once; a
   drop only shows it after a debounce, since mobile WS drops are frequent."
  [connected?]
  (when-let [d @dispatch-ref]
    (if connected?
      (do (when-let [t @offline-timer]
            (js/clearTimeout t)
            (reset! offline-timer nil))
          (d {:type :connection/status :connected? true}))
      (when-not @offline-timer
        (reset! offline-timer
                (js/setTimeout
                 (fn []
                   (reset! offline-timer nil)
                   (d {:type :connection/status :connected? false}))
                 2500))))))

(defn- sync-viewport-height!
  "Keep --app-height in step with the visual viewport so the mobile keyboard
   doesn't push the compose box off-screen. iOS overlays the keyboard without
   shrinking the layout viewport, so the gap between the two tells whether it
   is open (exposed to CSS as .keyboard-open). The timeline's scrollTop is
   shifted by the height delta so the same line stays above the composer."
  []
  (let [vv       js/window.visualViewport
        layout-h (.-clientHeight js/document.documentElement)
        visual-h (if vv (.-height vv) js/window.innerHeight)
        root     js/document.documentElement
        style    (.-style root)
        kb-open? (> (- layout-h visual-h) 100)
        timeline (.querySelector js/document ".timeline")
        prev-h   (some-> timeline .-clientHeight)
        prev-top (some-> timeline .-scrollTop)]
    (.toggle (.-classList root) "keyboard-open" kb-open?)
    (if kb-open?
      (do (.setProperty style "--app-height" (str visual-h "px"))
          (.scrollTo js/window 0 0))
      ;; 100dvh is the real standalone window height; 100vh would include
      ;; the status bar iOS reserves.
      (.setProperty style "--app-height" "100dvh"))
    (when (and timeline prev-h)
      (let [delta (- prev-h (.-clientHeight timeline))]
        (when-not (zero? delta)
          (set! (.-scrollTop timeline) (+ prev-top delta)))))))

(defn- install-viewport-tracking!
  "Run sync-viewport-height! on viewport changes, and re-sync zoom, height
   and the drawer paint whenever a standalone PWA resumes from the
   background (iOS restores a stale scale and height until then)."
  [state]
  (sync-viewport-height!)
  (if js/window.visualViewport
    (do (.addEventListener js/window.visualViewport "resize" (fn [_] (sync-viewport-height!)))
        (.addEventListener js/window.visualViewport "scroll" (fn [_] (sync-viewport-height!))))
    (.addEventListener js/window "resize" (fn [_] (sync-viewport-height!))))
  (let [on-resume (fn []
                    (reset-zoom!)
                    (sync-viewport-height!)
                    (let [s @state]
                      (repaint-closed-drawer! (:web/wide? s) (:web/sidebar-open? s)))
                    (js/requestAnimationFrame
                     (fn [] (reset-zoom!) (sync-viewport-height!))))]
    (.addEventListener js/document "visibilitychange"
                       (fn [_] (when (= "visible" (.-visibilityState js/document))
                                 (on-resume))))
    (.addEventListener js/window "pageshow" (fn [_] (on-resume)))))

(defn- scrollable-x?
  [node]
  (loop [n node]
    (cond
      (or (nil? n) (not (instance? js/Element n))) false
      (and (> (.-scrollWidth n) (+ (.-clientWidth n) 1))
           (let [ox (.-overflowX (js/getComputedStyle n))]
             (or (= ox "auto") (= ox "scroll"))))
      true
      :else (recur (.-parentElement n)))))

(defn- selecting-text?
  []
  (when-let [sel (.getSelection js/window)]
    (and (not (.-isCollapsed sel))
         (pos? (.-length (.toString sel))))))

(defn- install-sidebar-swipe!
  "Left-edge swipe opens the sidebar, a swipe left closes it. The open zone
   is the left ~20% of the viewport, not the very edge iOS reserves for its
   back gesture; that edge's native swipe is suppressed best-effort with a
   non-passive touchstart. Gated on horizontal dominance and a distance
   threshold, ignored inside horizontally scrollable elements and while
   selecting text."
  [state dispatch!]
  (let [thresh-px 60
        start     (atom nil)]
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
                      (not (selecting-text?))
                      (> (js/Math.abs dx) (js/Math.abs dy)))
             (cond
               (and (not (:web/sidebar-open? @state))
                    (<= x open-zone) (>= dx thresh-px))
               (dispatch! {:type :sidebar/open})

               (and (:web/sidebar-open? @state) (<= dx (- thresh-px)))
               (dispatch! {:type :sidebar/close}))))))
     #js {:passive true})
    (.addEventListener
     js/document "touchstart"
     (fn [e]
       (when-let [t (aget (.-touches e) 0)]
         (when (and (not (:web/sidebar-open? @state))
                    (<= (.-pageX t) 20))
           (.preventDefault e))))
     #js {:passive false})))

(defn- real-init! []
  (let [composed  (ext/compose (web-extensions))
        _         (reset! routes-ref (:routes composed))
        routes    routes-ref
        stored-theme (or (try (.getItem js/localStorage "ui-theme") (catch :default _ nil))
                        "auto")
        ;; Legacy key from before the appearance settings.
        _         (try (.removeItem js/localStorage "xi-viewer-mode") (catch :default _ nil))
        route     (router/parse-path routes (.-pathname js/window.location))
        initial   (-> (state/initial-state {:mode :client})
                      (assoc :web/theme-mode stored-theme
                             :web/nav-items (:nav-items composed)
                             :web/sidebar-groups (:sidebar-groups composed)
                             :web/session-menu-items (:session-menu-items composed)
                             ;; Seeded here because views must not require xi.config.
                             :web/appearance-config config/appearance)
                      (cache/hydrate route))
        transport (ws-transport/create!
                   {:url        (ws-url)
                    :hello      (cond-> {:client-key  (ensure-client-key!)
                                         :client-name (device-name)
                                         :platform    "web"}
                                  (claimed-user) (assoc :user (claimed-user)))
                    ;; nil: the router drives joins; reconnect replays them.
                    :target     nil
                    :reconnect? true
                    :on-status  on-connection-status})
        {:keys [dispatch! state add-tap!] :as app}
        (app/create-app {:initial-state initial
                         :handlers      (ws-transport/make-handlers
                                         (base-handlers)
                                         {:local-handlers
                                          (ext/merge-handlers
                                           (merge (web-handlers routes) user-ext/handlers)
                                           composed)})
                         :effects       (merge (:effects transport)
                                               (web-effects routes)
                                               (user-ext/fx {:pages-ref   pages-ref
                                                             :routes-ref  routes-ref
                                                             :app-ref     app-ref
                                                             :builtin-ids (map :id (:extensions composed))})
                                               (:fx composed))
                         :on-render     render!})]
    (reset! pages-ref (:pages composed))
    (dashboard/register! (:dashboard-cards composed))
    (reset! dispatch-ref dispatch!)
    (reset! app-ref app)
    ((:set-dispatch! transport) dispatch!)
    (add-tap! cache/persist-tap)
    (add-tap! (mark-read-on-turn-tap dispatch!))
    (add-tap! (request-projects-tap dispatch!))
    (add-tap! (fill-url-tap dispatch!))
    (add-tap! (pending-submit-tap dispatch!))
    (add-tap! (pending-buffer-tap dispatch!))
    (add-tap! (prefetch/tap dispatch!))
    (add-tap! (buffer-presence-tap dispatch!))
    (add-tap! (pending-command-tap dispatch!))
    (add-tap! (optimistic-tap dispatch!))
    (add-tap! (record-command-tap dispatch!))
    (add-tap! (prompt-nav-close-tap dispatch!))
    (add-tap! (user-ext/request-tap dispatch!))
    (add-tap! (dashboard/load-tap dispatch!))
    (doseq [make-tap (:taps composed)]
      (add-tap! (make-tap dispatch!)))
    (router/init! routes dispatch!)
    (dispatch! {:type :theme/set-mode :mode stored-theme :init? true})
    (dispatch! {:type :themes/apply})
    (install-viewport-tracking! state)
    (views/install-pointer-type-tracker!)
    (attach-code-copy-listener!)
    ;; :web/wide? mirrors the >=1024px breakpoint at which style.css docks the sidebar.
    (let [mql (.matchMedia js/window "(min-width: 1024px)")]
      (dispatch! {:type :web/set-wide :wide? (.-matches mql)})
      (.addEventListener mql "change"
                         (fn [e] (dispatch! {:type :web/set-wide :wide? (.-matches e)}))))
    (install-sidebar-swipe! state dispatch!)
    ;; Prevent iOS Safari smart-zoom (double-tap & pinch).
    (.addEventListener js/document "gesturestart" (fn [e] (.preventDefault e)))
    (.addEventListener js/document "gesturechange" (fn [e] (.preventDefault e)))
    (.addEventListener js/document "gestureend" (fn [e] (.preventDefault e)))
    (.addEventListener js/document "visibilitychange"
                       (fn [_]
                         (dispatch! {:type :client/update
                                     :visible? (= "visible" (.-visibilityState js/document))})))
    (install-actions!)
    (.addEventListener js/document "keydown"
                       (fn [^js e] (keymap/handle-keydown @state dispatch! e)))
    (key-hints/install! #(deref state))
    (render! @state dispatch!)))

(defn- hide-shadow-hud-when-remote!
  "Hide the shadow-cljs devtools 'Reconnecting' HUD on non-localhost hosts,
   where its websocket (9630) is never reachable. DCE'd in release builds."
  []
  (when ^boolean js/goog.DEBUG
    (let [host (.-hostname js/window.location)]
      (when-not (contains? #{"localhost" "127.0.0.1" "[::1]"} host)
        (let [style (js/document.createElement "style")]
          (set! (.-textContent style) "#shadow-connection-error{display:none !important;}")
          (.append (.-head js/document) style))))))

(defn ^:export init! []
  (js/console.log "[xi-web] starting")
  (hide-shadow-hud-when-remote!)
  (r/set-dispatch! (fn [_ _]))
  (if-let [view (.get (js/URLSearchParams. (.-search js/window.location)) "demo")]
    (demo-init! view)
    (real-init!)))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (when-let [{:keys [state dispatch!]} @app-ref]
    (render! @state dispatch!)))
