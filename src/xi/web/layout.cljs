(ns xi.web.layout
  "The chat page's windows in the web client: the state around xi.layout.

   `:web/layouts {session-id layout}` is this browser's (web-only); a
   session without an entry shows the single implicit pane of its
   `:active-buffer`, so only split sessions carry state. The focused pane's
   view *is* the room's `[:ui :active-buffer]`: the `:layout/*` handlers
   write both, and `tap` dispatches `:layout/sync-view` when another path
   moved the active buffer (a buffer switch, a sidebar row) and
   `:layout/prune` when a pane's view closed (a buffer close, a dismissed
   sub-agent). Per-user persistence rides on xi.web.user-state's `:layouts`:
   every change writes the trimmed store, and the server's copy only seeds
   sessions this browser has no layout for (`seed`), so two devices never
   fight over one chat's windows.

   Narrow screens (`:web/wide?` false): the main tree shows the focused main
   pane alone and a sidebar is a drawer, open while its pane has focus."
  (:require [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.layout :as layout]
            [xi.web.user-state :as user-state]))

(def resize-step
  "What one grow / shrink key does: a fraction of the parent split for a
   main pane, px for a sidebar."
  {:fraction 0.05 :px 40})

;; ── Reading ──────────────────────────────────────────────────────────────────

(defn view-open?
  "Can `room` show `view`: the chat, an empty pane, one of its buffers, or
   one of its sub-agents (xi.web.views/subagent-view)."
  [room view]
  (boolean
   (or (= buffers/chat view)
       (= :empty view)
       (contains? (get-in room [:ui :buffers]) view)
       (some #(= view (:id %)) (get-in room [:ext :subagents :agents])))))

(defn room-ctx
  "The joined room the chat page shows: {:key session-id :room :path}, nil
   while a switch is in flight or on a virtual new chat (which has no
   windows until it is a session)."
  [st]
  (let [sid  (get-in st [:web/route :session-id])
        room (state/active-room st)]
    (when (and sid (= :chat (get-in st [:web/route :page]))
               (= sid (get-in room [:session :id])))
      {:key sid :room room :path [:rooms (:id room)]})))

(defn stored
  "The layout kept for the chat on screen, nil for an unsplit session."
  [st]
  (when-let [{:keys [key]} (room-ctx st)]
    (get-in st [:web/layouts key])))

(defn current
  "The layout of the chat on screen: the stored one, else the implicit
   single pane of its active buffer."
  [st]
  (when-let [{:keys [key room]} (room-ctx st)]
    (or (get-in st [:web/layouts key])
        (layout/single (get-in room [:ui :active-buffer] buffers/chat)))))

(defn windows?
  "Does the chat on screen show more than the plain single chat pane?"
  [st]
  (some-> (stored st) layout/default? not))

(defn focused-side
  "The sidebar the focused pane sits in, nil for a main pane."
  [st]
  (when-let [l (current st)]
    (layout/side-of l (:id (layout/focused l)))))

(defn drawer-open?
  "Narrow screens: sidebar `side` is the open drawer while its pane has
   focus."
  [st side]
  (and (not (:web/wide? st)) (= side (focused-side st))))

;; ── Writing ──────────────────────────────────────────────────────────────────

(defn- persist-effects [st]
  (let [m (layout/trim-store (:web/layouts st))]
    [[:cache/layouts {:layouts m}]
     (user-state/set-effect :layouts m)]))

(defn- open-focused-side
  "A sidebar whose pane took focus is expanded (narrow: the drawer opens)."
  [l]
  (if-let [side (layout/side-of l (:id (layout/focused l)))]
    (layout/toggle-side l side true)
    l))

;; ── History (winner-mode) ────────────────────────────────────────────────────
;; `:web/layout-history {session-id {:undo [layout …] :redo [layout …]}}`,
;; this page's own: every change of the arrangement or the sizes pushes the
;; layout it replaced, a focus change does not; a new change drops the redo
;; stack. Undo / redo swap the whole layout back, focus included.

(def history-max 50)

(defn- shape
  "What history compares: the layout without its focus and timestamp."
  [l]
  (dissoc l :focus :at))

(defn- record
  "Push `before` for session `key` when `after` differs in shape."
  [st key before after]
  (if (= (shape before) (shape after))
    st
    (update-in st [:web/layout-history key]
               (fn [{:keys [undo]}]
                 {:undo (vec (take-last history-max (conj (vec undo) before)))
                  :redo []}))))

(defn history
  "[undo-count redo-count] of the chat on screen."
  [st]
  (let [{:keys [undo redo]} (get-in st [:web/layout-history (:key (room-ctx st))])]
    [(count undo) (count redo)]))

(defn- install
  "Put layout `l` in place for the chat on screen (`key`, `path`): stored,
   stamped, its focused view the room's active buffer."
  [st ev key path l]
  (let [l   (assoc l :at (or (:event/ts ev) (js/Date.now)))
        st' (-> st
                (assoc-in [:web/layouts key] l)
                (assoc-in (conj path :ui :active-buffer) (layout/focused-view l)))]
    {:state st' :effects (persist-effects st')}))

(defn- apply-layout
  "Store `(apply f layout args)` for the chat on screen and put its focused
   view in the room's active buffer, recording the previous layout for
   undo. nil when nothing changed."
  [st ev f & args]
  (when-let [{:keys [key room path]} (room-ctx st)]
    (let [l  (current st)
          l' (open-focused-side (apply f l args))]
      (when (not= l l')
        (install (record st key l l') ev key path l')))))

(defn- step-history
  "Undo (`from` :undo, `to` :redo) or redo the chat on screen's layout: the
   current one moves to the other stack, the popped one comes back pruned of
   views the room no longer has."
  [st ev from to]
  (when-let [{:keys [key room path]} (room-ctx st)]
    (let [h (get-in st [:web/layout-history key])]
      (when-let [popped (peek (vec (get h from)))]
        (let [cur (current st)
              l   (layout/prune popped #(view-open? room %))]
          (-> (assoc-in st [:web/layout-history key]
                        (-> h
                            (assoc from (pop (vec (get h from))))
                            (update to #(conj (vec %) cur))))
              (install ev key path l)))))))

(defn- undo [st ev] (step-history st ev :undo :redo))
(defn- redo [st ev] (step-history st ev :redo :undo))

(defn- focused-id [st] (:id (layout/focused (current st))))

(defn- split
  "Split the focused pane (or `:pane-id`); the new pane waits for a pick."
  [st {:keys [dir pane-id] :as ev}]
  (apply-layout st ev layout/split (or pane-id (focused-id st)) (or dir :row) :empty))

(defn- close-pane [st {:keys [pane-id] :as ev}]
  (apply-layout st ev layout/close-pane (or pane-id (focused-id st))))

(defn- only [st {:keys [pane-id] :as ev}]
  (apply-layout st ev layout/only (or pane-id (focused-id st))))

(defn- focus [st {:keys [pane-id] :as ev}]
  (apply-layout st ev layout/focus pane-id))

(defn- focus-dir [st {:keys [dir] :as ev}]
  (apply-layout st ev layout/focus-dir dir))

(defn- focus-next [st ev]
  (apply-layout st ev layout/focus-next))

(defn- swap [st ev]
  (apply-layout st ev layout/swap (focused-id st)))

(defn- dock [st {:keys [side pane-id] :as ev}]
  (apply-layout st ev layout/dock (or pane-id (focused-id st)) side))

(defn- undock [st {:keys [side] :as ev}]
  (when-let [side (or side (focused-side st))]
    (apply-layout st ev layout/undock side)))

(defn- toggle-side
  "Wide: collapse / expand the sidebar (collapsing the focused one hands
   focus to the main area). Narrow: open its drawer by focusing its pane,
   or close it by focusing the main area."
  [st {:keys [side open?] :as ev}]
  (when-let [l (current st)]
    (when (layout/side-pane l side)
      (let [main-id (:id (first (layout/main-panes l)))]
        (if (:web/wide? st)
          (apply-layout st ev (fn [l]
                                (let [l (if (some? open?)
                                          (layout/toggle-side l side open?)
                                          (layout/toggle-side l side))]
                                  (if (and (not (get-in l [side :open?]))
                                           (= side (focused-side st)))
                                    (layout/focus l main-id)
                                    l))))
          (apply-layout st ev layout/focus
                        (if (= side (focused-side st))
                          main-id
                          (:id (layout/side-pane l side)))))))))

(defn- resize [st {:keys [path sizes] :as ev}]
  (apply-layout st ev layout/resize (vec path) (vec sizes)))

(defn- resize-side [st {:keys [side px] :as ev}]
  (apply-layout st ev layout/set-side-size side px))

(defn- grow
  "`:axis` :width / :height, `:sign` 1 grows the focused pane, -1 shrinks."
  [st {:keys [axis sign] :as ev}]
  (if-let [side (focused-side st)]
    (when (= :width axis)
      (apply-layout st ev layout/resize-side side (* sign (:px resize-step))))
    (apply-layout st ev layout/resize-by (focused-id st) axis (* sign (:fraction resize-step)))))

(defn- balance [st ev]
  (apply-layout st ev layout/balance (focused-id st)))

(defn- set-view
  "A pane's own view switch (its header pill): show `view` there and focus
   it."
  [st {:keys [pane-id view] :as ev}]
  (apply-layout st ev (fn [l] (-> l (layout/set-view pane-id view) (layout/focus pane-id)))))

(defn- open-view
  "Show `view` somewhere (`:where`, xi.layout/open-view); `:focus?` moves
   focus to it, which a sidebar open otherwise leaves alone."
  [st {:keys [view where focus?] :as ev}]
  (apply-layout st ev (fn [l]
                        (let [l (layout/open-view l view (or where :focus))]
                          (if focus?
                            (layout/focus l (layout/pane-of-view l view))
                            l)))))

(defn- reset
  "Back to the single chat pane (undo brings the layout back)."
  [st ev]
  (when-let [{:keys [key path]} (room-ctx st)]
    (when-let [l (get-in st [:web/layouts key])]
      (let [single (layout/single buffers/chat)
            st'    (-> (record st key l single)
                       (update :web/layouts dissoc key)
                       (assoc-in (conj path :ui :active-buffer) buffers/chat))]
        {:state st' :effects (persist-effects st')}))))

(defn- sync-view
  "The active buffer moved behind the layout's back: focus the pane that
   shows it, else show it in the focused pane."
  [st ev]
  (when-let [{:keys [room]} (room-ctx st)]
    (let [view (get-in room [:ui :active-buffer] buffers/chat)]
      (apply-layout st ev (fn [l]
                            (if-let [id (layout/pane-of-view l view)]
                              (layout/focus l id)
                              (layout/set-view l (:id (layout/focused l)) view)))))))

(defn- prune [st ev]
  (when-let [{:keys [room]} (room-ctx st)]
    (apply-layout st ev layout/prune #(view-open? room %))))

(defn- seed
  "The server's copy of the user's layouts (`:user-state/state` on connect,
   `:user-state/changed` from another device): adopt the entries of
   sessions this browser has no layout for, never the chat on screen."
  [st {:keys [layouts]}]
  (let [viewing (:key (room-ctx st))
        mine    (or (:web/layouts st) {})
        adopted (reduce (fn [m [sid l]]
                          (if (and (string? sid) (layout/valid? l)
                                   (not= sid viewing)
                                   (not (contains? mine sid)))
                            (assoc m sid l)
                            m))
                        mine layouts)]
    (when (not= adopted mine)
      {:state   (assoc st :web/layouts adopted)
       :effects [[:cache/layouts {:layouts (layout/trim-store adopted)}]]})))

(def handlers
  {:layout/split       split
   :layout/close-pane  close-pane
   :layout/only        only
   :layout/focus       focus
   :layout/focus-dir   focus-dir
   :layout/focus-next  focus-next
   :layout/swap        swap
   :layout/dock        dock
   :layout/undock      undock
   :layout/toggle-side toggle-side
   :layout/resize      resize
   :layout/resize-side resize-side
   :layout/grow        grow
   :layout/balance     balance
   :layout/set-view    set-view
   :layout/open-view   open-view
   :layout/reset       reset
   :layout/undo        undo
   :layout/redo        redo
   :layout/sync-view   sync-view
   :layout/prune       prune
   :layout/seed        seed})

;; ── Join and tap ─────────────────────────────────────────────────────────────

(defn restore
  "On `:room/joined` (xi.web.core/room-joined-from-cache): a stored layout
   wins over the remembered single view — pruned of views the room no
   longer has, its focused view becomes the active buffer."
  [st room-id]
  (let [room (get-in st [:rooms room-id])
        sid  (get-in room [:session :id])]
    (if-let [l (and sid (get-in st [:web/layouts sid]))]
      (let [l' (layout/prune l #(view-open? room %))]
        (-> st
            (assoc-in [:web/layouts sid] l')
            (assoc-in [:rooms room-id :ui :active-buffer] (layout/focused-view l'))))
      st)))

(defn tap
  "Keep a stored layout and the room's active buffer in step after every
   other event (see the ns doc)."
  [dispatch!]
  (fn [event st]
    (when-not (and (keyword? (:type event)) (= "layout" (namespace (:type event))))
      (when-let [{:keys [key room]} (room-ctx st)]
        (when-let [l (get-in st [:web/layouts key])]
          (cond
            (some #(not (view-open? room (:view %))) (layout/panes l))
            (dispatch! {:type :layout/prune})

            (not= (get-in room [:ui :active-buffer] buffers/chat) (layout/focused-view l))
            (dispatch! {:type :layout/sync-view})))))))
