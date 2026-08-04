(ns xi.ext.canvas-review.web
  "Browser half of the canvas-review extension — the node-based review canvas.

   Registers the shared pure canvas handlers (so the mirrored
   :canvas-review/add-node|add-edge|set-plan|load broadcasts build the canvas
   live) plus client-local view handlers (:canvas-review/open navigation, the
   Next/Prev walkthrough stepper), and contributes the /canvas-review/:sid
   route + page and a chat overflow entry point.

   The canvas is a scrollable layer of absolutely-positioned node cards; edges
   are drawn imperatively into an SVG overlay from the nodes' laid-out
   positions after each render (Replicant lifecycle hook). The walkthrough
   plan drives a cursor that scrolls the focused node to center and highlights
   it."
  (:require [clojure.string :as str]
            [ui.button :as button]
            [ui.icon :as icon]
            [xi.core.state :as state]
            [xi.diff :as diff]
            [xi.ext.canvas-review.handlers :as h]
            [xi.markdown.hiccup :as md]
            [xi.web.views :as views]))

;; ── Client-local handlers ────────────────────────────────────────────────────

(defn- cr-state [st]
  (get-in (state/active-room st) [:ext :canvas-review]))

(defn- on-open
  "Originator-only :canvas-review/open from the server → navigate to the
   Canvas view for the session that ran /canvas-review."
  [st {:keys [session-id]}]
  {:state   (dissoc st :web/diff-sel)
   :effects [[:app/dispatch {:type :route/navigate :page :canvas-review
                             :session-id session-id}]]})

(defn- on-open-page
  "Chat overflow entry point — open the active room's canvas."
  [st {:keys [room-id]}]
  (let [sid (get-in st [:rooms room-id :session :id])]
    {:state   (dissoc st :web/diff-sel)
     :effects [[:app/dispatch {:type :route/navigate :page :canvas-review
                               :session-id sid}]]}))

(defn- on-navigate
  "Chained after the router: entering the canvas for a session we're not
   already in joins/resumes that room (so a deep link / reload works)."
  [st {:keys [page session-id]}]
  (when (and (= page :canvas-review) session-id
             (not= session-id (get-in (state/active-room st) [:session :id])))
    {:effects [[:cache/seed-room {:session-id session-id}]
               [:room/join-with-cache {:target {:session-id session-id}
                                       :session-id session-id}]]}))

(defn- plan-len [st] (count (:plan (cr-state st))))

(defn- cr-step
  "Advance/retreat the walkthrough cursor, clamped to the plan."
  [st {:keys [dir]}]
  (let [n (plan-len st)]
    (when (pos? n)
      (let [cur (or (get-in st [:web/cr :cursor]) 0)
            nc  (max 0 (min (dec n) (+ cur dir)))]
        {:state (assoc-in st [:web/cr :cursor] nc)}))))

(defn- cr-goto [st {:keys [idx]}]
  {:state (assoc-in st [:web/cr :cursor] idx)})

(defn- cr-select
  "Clicking a node jumps the walkthrough to its step, if it's in the plan."
  [st {:keys [id]}]
  (let [plan (:plan (cr-state st))
        idx  (first (keep-indexed (fn [i s] (when (= id (:node s)) i)) plan))]
    (when idx {:state (assoc-in st [:web/cr :cursor] idx)})))

;; ── Line selection → ask / collect (mirrors the diff view, shares its code) ──
;; Line clicks reuse the base :diff/select-line handler + :web/diff-sel, so the
;; selectable diff rows are literally the same component as the diff buffer.
;; From a selection you can send one question straight to the agent, or stash it
;; in a review "cart" ([:web/cr :items]) and send the whole batch at once.

(defn- cr-selection
  "The current selection's snippet + file + range, pulled from the canvas diff."
  [st]
  (let [range (diff/selection-range (:web/diff-sel st))
        text  (:diff (cr-state st))]
    (when (and range text)
      (let [rows (diff/diff-rows (diff/parse-diff-text text))
            file (some (fn [{:keys [row filename sel-idx]}]
                         (when (and (= :line row) sel-idx
                                    (<= (first range) sel-idx (second range)))
                           filename))
                       rows)]
        {:range range :file file :snippet (diff/selected-snippet rows range)}))))

(defn- cr-clear-sel [st _]
  {:state (dissoc st :web/diff-sel)})

(defn- send-fx
  "Submit a prompt into the room and jump to the chat view (mirrors
   diff-submit): the agent's answer lands in the conversation."
  [st prompt]
  (let [rid (:active-room st)
        sid (get-in (state/active-room st) [:session :id])]
    [[:ws/send {:type :input/submit :room-id rid :text prompt}]
     [:app/dispatch {:type :route/navigate :page :chat :session-id sid}]]))

(defn- one-prompt [{:keys [file snippet]} comment]
  (str (or (not-empty comment)
           "About this code from the review canvas:")
       "\n\n" (when file (str "`" file "`\n\n"))
       "```diff\n" snippet "\n```"))

(defn- mark-reviewed
  "Accumulate every sel-idx in `[lo hi]` into the persistent reviewed set so the
   canvas keeps those lines flagged as already-looked-at."
  [st [lo hi]]
  (if (and lo hi)
    (update-in st [:web/cr :reviewed] (fnil into #{})
               (clojure.core/range lo (inc hi)))
    st))

(defn- cr-send-selection
  "Send the current selection (+ optional comment) to the agent right away."
  [st {:keys [text]}]
  (when-let [{:keys [range] :as sel} (cr-selection st)]
    {:state   (-> st (mark-reviewed range) (dissoc :web/diff-sel))
     :effects (send-fx st (one-prompt sel (some-> text str/trim)))}))

(defn- cr-add-item
  "Stash the current selection (+ optional comment) in the review cart."
  [st {:keys [text]}]
  (when-let [{:keys [file range snippet]} (cr-selection st)]
    {:state (-> st
                (update-in [:web/cr :items] (fnil conj [])
                           {:file file :range range :snippet snippet
                            :comment (some-> text str/trim not-empty)})
                (mark-reviewed range)
                (dissoc :web/diff-sel))}))

(defn- cr-remove-item [st {:keys [idx]}]
  {:state (update-in st [:web/cr :items]
                     (fn [items] (into (subvec items 0 idx) (subvec items (inc idx)))))})

(defn- cr-send-all
  "Send every stashed review item as one combined prompt."
  [st _]
  (let [items (get-in st [:web/cr :items])]
    (when (seq items)
      (let [body (str/join "\n\n---\n\n"
                           (map-indexed
                            (fn [i {:keys [file comment snippet]}]
                              (str "### " (inc i) ". " (or file "")
                                   (when (seq comment) (str "\n\n" comment))
                                   "\n\n```diff\n" snippet "\n```"))
                            items))
            prompt (str "Here are " (count items)
                        " points from the review canvas to address:\n\n" body)]
        {:state   (update st :web/cr dissoc :items)
         :effects (send-fx st prompt)}))))

(defn- cr-toggle-comments
  "Expand/collapse the inline comment thread anchored at `anchor` (a sel-idx)."
  [st {:keys [anchor]}]
  {:state (update-in st [:web/cr :open-comments]
                     (fn [s] (let [s (or s #{})]
                               (if (s anchor) (disj s anchor) (conj s anchor)))))})

(defn- cr-save-comment
  "Update a review item's comment in place (from the inline editor), then
   collapse the thread back to its pill."
  [st {:keys [idx anchor text]}]
  {:state (-> st
              (assoc-in [:web/cr :items idx :comment] (some-> text str/trim not-empty))
              (update-in [:web/cr :open-comments] (fnil disj #{}) anchor))})

(def ^:private handlers
  (merge h/handlers
         {:route/navigate              on-navigate
          :canvas-review/open          on-open
          :canvas-review/open-page     on-open-page
          :canvas-review/step          cr-step
          :canvas-review/goto          cr-goto
          :canvas-review/select        cr-select
          :canvas-review/clear-sel     cr-clear-sel
          :canvas-review/send-selection cr-send-selection
          :canvas-review/add-item      cr-add-item
          :canvas-review/remove-item   cr-remove-item
          :canvas-review/toggle-comments cr-toggle-comments
          :canvas-review/save-comment  cr-save-comment
          :canvas-review/send-all      cr-send-all}))

;; ── Edge drawing + walkthrough focus (imperative, post-render) ───────────────

(defonce ^:private last-focus (atom nil))

(def ^:private SVGNS "http://www.w3.org/2000/svg")

(defn- edge-geometry
  "Compute a readable connector between cards `a` and `b` from their live
   positions. Two cases keep lines out of the cards:

   • cross-lane (different x) — connect the *facing* sides: the right edge of
     the left-hand card to the left edge of the right-hand card, so the line is
     a short horizontal hop across the lane gap regardless of edge direction.
   • same-lane (vertically stacked, e.g. code→code relations) — bow the line
     out into the left margin instead of cutting straight down through the
     cards between them.

   Anchors sit near each card's top so lane-aligned cards yield a near-flat
   line. Returns {:path d :lx :ly} (label anchor)."
  [^js a ^js b]
  (let [ax (.-offsetLeft a) aw (.-offsetWidth a)
        bx (.-offsetLeft b) bw (.-offsetWidth b)
        ay (+ (.-offsetTop a) (min 34 (/ (.-offsetHeight a) 2)))
        by (+ (.-offsetTop b) (min 34 (/ (.-offsetHeight b) 2)))]
    (if (< (js/Math.abs (- ax bx)) 8)
      ;; same lane → bow into the left margin
      (let [x   ax
            bow (+ 40 (* 0.15 (js/Math.abs (- ay by))))]
        {:path (str "M " x " " ay
                    " C " (- x bow) " " ay " " (- x bow) " " by " " x " " by)
         :lx (- x (* bow 0.55)) :ly (/ (+ ay by) 2)})
      ;; cross-lane → connect the facing sides, left card → right card
      (let [[sx sy ex ey] (if (< ax bx)
                            [(+ ax aw) ay bx by]
                            [(+ bx bw) by ax ay])
            mx (/ (+ sx ex) 2)]
        {:path (str "M " sx " " sy " C " mx " " sy " " mx " " ey " " ex " " ey)
         :lx mx :ly (- (/ (+ sy ey) 2) 5)}))))

(defn- draw-edges!
  "Redraw the SVG edge overlay from the nodes' current laid-out positions.
   Runs inside the transform-free canvas coordinate space (offsetLeft/Top), so
   endpoints track the node cards. `:explains` edges (comment→block) are drawn
   as quiet local connectors with no label (the comment card is the label);
   structural code↔code relations get a dashed accent line and keep their
   label."
  [^js canvas edges]
  (when-let [svg (.querySelector canvas ".cr-edges")]
    (set! (.-innerHTML svg) "")
    (doseq [{:keys [from to label kind]} edges]
      (let [a (.querySelector canvas (str "[data-cr-node=\"" from "\"]"))
            b (.querySelector canvas (str "[data-cr-node=\"" to "\"]"))]
        (when (and a b)
          (let [explains? (= :explains kind)
                {:keys [path lx ly]} (edge-geometry a b)
                p (.createElementNS js/document SVGNS "path")]
            (.setAttribute p "d" path)
            (.setAttribute p "class" (str "cr-edge-path"
                                          (when-not explains? " cr-edge-path--structural")))
            (.appendChild svg p)
            (when (and (not explains?) (not-empty label))
              (let [t (.createElementNS js/document SVGNS "text")]
                (.setAttribute t "x" (str lx))
                (.setAttribute t "y" (str ly))
                (.setAttribute t "class" "cr-edge-label")
                (set! (.-textContent t) label)
                (.appendChild svg t)))))))))

(defn- focus-current!
  "When the walkthrough cursor changes, scroll the focused node to center."
  [^js canvas]
  (let [cur (.getAttribute canvas "data-cr-cursor")]
    (when (and cur (seq cur) (not= cur @last-focus))
      (reset! last-focus cur)
      (when-let [el (.querySelector canvas (str "[data-cr-node=\"" cur "\"]"))]
        (.scrollIntoView el #js {:behavior "smooth" :block "center" :inline "center"})))))

(def ^:private LANE-GAP
  "Vertical gap between stacked cards in a lane. The server seeds a rough
   position per node, but cards have wildly different heights (a long diff vs a
   one-line note), so a fixed seed step can't avoid overlap — we re-pack here
   from measured heights instead."
  48)

(defn- reflow!
  "Re-pack the canvas after each render so tall cards never overlap and the
   connectors read cleanly. Each kind keeps its horizontal lane (the seeded x),
   but y is recomputed from measured heights.

   The code lane packs top-to-bottom. Each comment is then placed at the
   vertical position of the block it explains (its `:explains` edge target),
   stacking multiple comments for the same block and never rising above the
   previous comment — so a comment sits beside its block and the connector is a
   short near-horizontal hop instead of a diagonal across the canvas. Prose
   packs in its own lane. Finally the scroll area grows to fit."
  [^js canvas nodes edges]
  (let [target   (into {} (keep (fn [e] (when (= :explains (:kind e))
                                          [(:from e) (:to e)])) edges))
        el       (fn [id] (.querySelector canvas (str "[data-cr-node=\"" id "\"]")))
        oh       (fn [^js e] (.-offsetHeight e))
        right-of (fn [^js e] (+ (.-offsetLeft e) (.-offsetWidth e)))
        by-kind  (fn [k] (->> (vals nodes)
                              (filter #(= k (:kind %)))
                              (sort-by (juxt :y :id))
                              (mapv :id)))
        code     (by-kind :code)
        prose    (by-kind :prose)
        comments-for (fn [bid]
                       (->> (vals nodes)
                            (filter #(and (= :comment (:kind %)) (= bid (target (:id %)))))
                            (sort-by (juxt :y :id))
                            (mapv :id)))
        orphans  (->> (vals nodes)
                      (filter #(and (= :comment (:kind %)) (nil? (target (:id %)))))
                      (sort-by (juxt :y :id))
                      (mapv :id))
        place!   (fn [^js e y right]
                   (set! (.. e -style -top) (str y "px"))
                   (max right (right-of e)))
        ;; pack the code lane, recording each block's top
        tops     (volatile! {})
        [cy cr]  (loop [ids code y 40 right 0]
                   (if-let [id (first ids)]
                     (if-let [^js e (el id)]
                       (do (vswap! tops assoc id y)
                           (recur (next ids) (+ y (oh e) LANE-GAP) (place! e y right)))
                       (recur (next ids) y right))
                     [y right]))
        ;; comments follow their target block's top (then orphans), never
        ;; overlapping the previous comment
        comment-order (into (vec (mapcat comments-for code)) orphans)
        min-y    (fn [id] (get @tops (target id) 40))
        [my mr]  (loop [ids comment-order y 40 right 0]
                   (if-let [id (first ids)]
                     (if-let [^js e (el id)]
                       (let [top (max y (min-y id))]
                         (recur (next ids) (+ top (oh e) LANE-GAP) (place! e top right)))
                       (recur (next ids) y right))
                     [y right]))
        [py pr]  (loop [ids prose y 40 right 0]
                   (if-let [id (first ids)]
                     (if-let [^js e (el id)]
                       (recur (next ids) (+ y (oh e) LANE-GAP) (place! e y right))
                       (recur (next ids) y right))
                     [y right]))]
    (set! (.. canvas -style -height) (str (+ 40 (max cy my py)) "px"))
    (set! (.. canvas -style -width)  (str (+ 80 (max cr mr pr)) "px"))))

(defn- canvas-hook [nodes edges]
  (fn [{:replicant/keys [^js node]}]
    (reflow! node nodes edges)
    (draw-edges! node edges)
    (focus-current! node)))

;; ── Node rendering ───────────────────────────────────────────────────────────

(defn- node-diff-rows
  "The diff rows for a code node — the changed/context lines of `file` whose
   new-side (or old-side, for deletions) number falls in [start end]."
  [diff-text file start end]
  (let [rows  (diff/diff-rows (diff/parse-diff-text diff-text))
        in?   (fn [{:keys [new-line old-line]}]
                (let [n (or new-line old-line)]
                  (and n (<= start n end))))
        lines (filter (fn [r] (and (= :line (:row r))
                                   (= file (:filename r))
                                   (in? (:line r))))
                      rows)]
    (when (seq lines)
      (into [{:row :file :filename file :status :modified}] lines))))

(defn- highlight-idx
  "Convert a code node's highlighted new-side line numbers into the sel-idx
   set diff-rows-view spotlights."
  [rows lines]
  (let [hl (set lines)]
    (when (seq hl)
      (into #{} (keep (fn [{:keys [row line sel-idx]}]
                        (when (and (= :line row) sel-idx
                                   (or (hl (:new-line line)) (hl (:old-line line))))
                          sel-idx)))
            rows))))

(defn- comments-index
  "Map each commented review item's anchor sel-idx (its range end) to a vector of
   {:idx :comment}, so a code node can render an inline thread under that line."
  [items]
  (reduce (fn [m [i {:keys [range comment]}]]
            (if (not-empty comment)
              (update m (second range) (fnil conj []) {:idx i :comment comment})
              m))
          {}
          (map-indexed vector items)))

(defn- read-edit [idx]
  (some-> (.getElementById js/document (str "cr-edit-" idx)) .-value))

(defn- comment-row
  "Inline comment thread rendered under a commented line. Collapsed it shows a
   `(N comment)` pill; clicking it expands per-comment editors with save/delete."
  [dispatch! open? anchor comments]
  (let [n (count comments)]
    [:div {:class ["diff-comment-block"] :replicant/key (str "cmt-" anchor)
           :on {:click (fn [^js e] (.stopPropagation e))}}
     (if open?
       (list
        (for [{:keys [idx comment]} comments]
          [:div {:class ["diff-comment-edit"] :replicant/key idx}
           [:textarea {:id (str "cr-edit-" idx)
                       :class ["form-textarea" "cr-comment-input"]
                       :rows 2}
            comment]
           [:div {:class ["diff-comment-actions"]}
            (button/button {:variant :ghost :size :sm :icon-left :trash
                            :on-click (fn [_] (dispatch! {:type :canvas-review/remove-item
                                                         :idx idx}))}
                           "Delete")
            (button/button {:variant :primary :size :sm
                            :on-click (fn [_] (dispatch! {:type :canvas-review/save-comment
                                                         :idx idx :anchor anchor
                                                         :text (read-edit idx)}))}
                           "Save")]])
        [:button {:class ["diff-comment-collapse"]
                  :on {:click (fn [_] (dispatch! {:type :canvas-review/toggle-comments
                                                 :anchor anchor}))}}
         "Collapse"])
       [:button {:class ["diff-comment-row"]
                 :on {:click (fn [_] (dispatch! {:type :canvas-review/toggle-comments
                                                :anchor anchor}))}}
        [:span {:class ["diff-comment-count"]}
         (str "(" n " comment" (when (not= 1 n) "s") ")")]
        (when-let [preview (:comment (first comments))]
          [:span {:class ["diff-comment-preview"]} preview])])]))

(defn- read-new []
  (some-> (.getElementById js/document "cr-new-comment") .-value))

(defn- new-comment-editor
  [dispatch! range]
  (let [n (inc (- (second range) (first range)))]
    [:div {:class ["diff-comment-block"] :replicant/key "cr-new"
           :on {:click (fn [^js e] (.stopPropagation e))}}
     [:div {:class ["diff-comment-edit"]}
      [:span {:class ["diff-comment-count"]}
       (str n " line" (when (not= 1 n) "s") " selected")]
      [:textarea {:id "cr-new-comment"
                  :class ["form-textarea" "cr-comment-input"]
                  :placeholder "Add a comment or question (optional)…"
                  :rows 2
                  :replicant/on-mount (fn [{:replicant/keys [^js node]}] (.focus node))
                  :on {:keydown (fn [^js e]
                                  (when (and (= "Enter" (.-key e)) (.-metaKey e))
                                    (.preventDefault e)
                                    (dispatch! {:type :canvas-review/send-selection
                                                :text (read-new)})))}}]
      [:div {:class ["diff-comment-actions"]}
       (button/button {:variant :ghost :size :sm
                       :on-click (fn [_] (dispatch! {:type :canvas-review/clear-sel}))}
                      "Cancel")
       (button/button {:variant :outline :size :sm
                       :on-click (fn [_] (dispatch! {:type :canvas-review/add-item
                                                     :text (read-new)}))}
                      "Add to review")
       (button/button {:variant :primary :size :sm
                       :on-click (fn [_] (dispatch! {:type :canvas-review/send-selection
                                                     :text (read-new)}))}
                      "Send now")]]]))

(defn- node-card [dispatch! diff-text range {:keys [reviewed comments open]} current? [id {:keys [kind x y w] :as n}]]
  [:div {:class (cond-> ["cr-node" (str "cr-node--" (name (or kind :comment)))]
                  current? (conj "cr-node--current"))
         :data-cr-node id
         :replicant/key id
         :style {:left (str x "px") :top (str y "px") :width (str w "px")}
         :on {:click (fn [_] (dispatch! {:type :canvas-review/select :id id}))}}
   (case kind
     :code
     (list
      [:div {:class ["cr-node-head"]}
       [:span {:class ["cr-node-title"]} (or (:label n) (:file n))]
       [:span {:class ["cr-node-loc"]} (:file n) ":" (:start n) "-" (:end n)]]
      [:div {:class ["cr-node-body" "cr-node-code"]}
       (if-let [rows (node-diff-rows diff-text (:file n) (:start n) (:end n))]
         (views/diff-rows-view dispatch! rows range nil
                               {:highlight (highlight-idx rows (:highlight n))
                                :reviewed  reviewed
                                :line-suffix (fn [idx]
                                               (let [saved  (when-let [cs (get comments idx)]
                                                              (comment-row dispatch! (contains? (or open #{}) idx)
                                                                           idx cs))
                                                     editor (when (and range (= idx (second range)))
                                                              (new-comment-editor dispatch! range))]
                                                 (if (and saved editor)
                                                   (list saved editor)
                                                   (or editor saved))))})
         [:div {:class ["cr-node-missing"]} "No matching diff lines."])])

     :prose
     (list
      (when (:title n) [:div {:class ["cr-node-head"]}
                        [:span {:class ["cr-node-title"]} (:title n)]])
      [:div {:class ["cr-node-body" "cr-node-prose" "post-content"]}
       (md/render (:markdown n))])

     ;; :comment (default)
     [:div {:class ["cr-node-body" "cr-node-note" "post-content"]}
      (md/render (:text n))])])

;; ── Plan panel (walkthrough) ─────────────────────────────────────────────────

(defn- plan-panel [dispatch! plan cursor]
  [:div {:class ["cr-plan"]}
   [:div {:class ["cr-plan-head"]} "Review plan"]
   (if (empty? plan)
     [:div {:class ["cr-plan-empty"]} "The agent is preparing a walkthrough…"]
     (let [n (count plan)
           cur (max 0 (min (dec n) (or cursor 0)))]
       [:div {:class ["cr-plan-inner"]}
        [:div {:class ["cr-plan-steps"]}
         (map-indexed
          (fn [i step]
            [:button {:class (cond-> ["cr-step"] (= i cur) (conj "cr-step--current"))
                      :replicant/key i
                      :on {:click (fn [_] (dispatch! {:type :canvas-review/goto :idx i}))}}
             [:span {:class ["cr-step-n"]} (inc i)]
             [:span {:class ["cr-step-title"]} (or (:title step) (:node step))]])
          plan)]
        [:div {:class ["cr-plan-nav"]}
         (button/button {:variant :outline :size :sm :icon-left :arrow-left
                         :disabled (zero? cur)
                         :on-click (fn [_] (dispatch! {:type :canvas-review/step :dir -1}))}
                        "Prev")
         [:span {:class ["cr-plan-count"]} (str (inc cur) " / " n)]
         (button/button {:variant :primary :size :sm :icon-right :arrow-right
                         :disabled (= cur (dec n))
                         :on-click (fn [_] (dispatch! {:type :canvas-review/step :dir 1}))}
                        "Next")]
        (when-let [note (:note (get plan cur))]
          [:div {:class ["cr-step-note" "post-content"]} (md/render note)])]))])

;; ── Review cart (stashed items; comments are entered inline in the diff) ──

(defn- review-tray [dispatch! items]
  (when (seq items)
    (let [n (count items)]
      [:div {:class ["cr-tray"] :replicant/key "cr-tray"}
       [:div {:class ["cr-tray-head"]}
        [:span {:class ["cr-tray-title"]}
         (str "Review · " n " item" (when (not= 1 n) "s"))]
        (button/button {:variant :primary :size :sm
                        :on-click (fn [_] (dispatch! {:type :canvas-review/send-all}))}
                       "Send all")]
       [:div {:class ["cr-tray-items"]}
        (map-indexed
         (fn [i {:keys [file range comment]}]
           [:div {:class ["cr-tray-item"] :replicant/key i}
            [:div {:class ["cr-tray-item-main"]}
             [:span {:class ["cr-tray-loc"]}
              (str (or file "") " · " (inc (- (second range) (first range))) " ln")]
             (when (seq comment)
               [:span {:class ["cr-tray-comment"]} comment])]
            [:button {:class ["cr-tray-remove"]
                      :title "Remove"
                      :on {:click (fn [_] (dispatch! {:type :canvas-review/remove-item
                                                      :idx i}))}}
             (icon/icon {:icon-name :x :size :sm})]])
         items)]])))

;; ── Page ─────────────────────────────────────────────────────────────────────

(defn- canvas-size
  "Explicit canvas dimensions so the absolutely-positioned nodes have a
   scrollable area and the SVG overlay can cover them."
  [nodes]
  (let [xs (map (fn [n] (+ (:x n) (:w n))) (vals nodes))
        ys (map (fn [n] (+ (:y n) 360)) (vals nodes))]
    {:w (+ 80 (apply max 900 xs))
     :h (apply max 640 ys)}))

(defn- canvas-page [state dispatch!]
  (let [room     (state/active-room state)
        cr       (get-in room [:ext :canvas-review])
        sid      (get-in room [:session :id])
        nodes    (:nodes cr)
        edges    (vals (:edges cr))
        plan     (:plan cr)
        cursor   (get-in state [:web/cr :cursor])
        cur-node (:node (get plan (max 0 (min (dec (count plan)) (or cursor 0)))))
        range    (diff/selection-range (:web/diff-sel state))
        items    (get-in state [:web/cr :items])
        reviewed (get-in state [:web/cr :reviewed])
        comments (comments-index items)
        open     (get-in state [:web/cr :open-comments])
        {cw :w ch :h} (canvas-size nodes)]
    [:div {:class ["container" "cr-container"] :replicant/key "canvas-review"}
     [:div {:class ["topbar"]}
      (views/nav-group dispatch! (fn [_]
                                   (dispatch! {:type :nav/back
                                               :fallback {:page :chat :session-id sid}})))
      [:div {:class ["topbar-title"]} "Canvas review"
       (when (:title cr) [:span {:class ["cr-source"]} " · " (:title cr)])]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["cr-layout"]}
      [:div {:class ["cr-viewport"]}
       (if (empty? nodes)
         [:div {:class ["empty-state"]}
          (views/spinner)
          [:p "The agent is laying out the review…"]]
         [:div {:class ["cr-canvas"]
                :data-cr-cursor (or cur-node "")
                :style {:width (str cw "px") :height (str ch "px")}
                :replicant/on-mount (canvas-hook nodes edges)
                :replicant/on-update (canvas-hook nodes edges)}
          [:svg {:class ["cr-edges"]}]
          (for [entry (sort-by (fn [[_ n]] [(:y n) (:x n)]) nodes)]
            (node-card dispatch! (:diff cr) range
                       {:reviewed reviewed :comments comments :open open}
                       (= (first entry) cur-node) entry))])
       (when (seq items)
         [:div {:class ["cr-dock"]}
          (review-tray dispatch! items)])]
      (plan-panel dispatch! plan cursor)]]))

;; ── Route ────────────────────────────────────────────────────────────────────

(defn- parse-route [segs]
  {:page :canvas-review :session-id (first segs)})

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id       :canvas-review
   :handlers handlers
   :routes   {"canvas-review"
              {:parse parse-route
               :path  {:canvas-review (fn [{:keys [session-id]}]
                                        (str "/canvas-review/" session-id))}}}
   :pages    {:canvas-review canvas-page}
   :nav-items [{:menu :overflow :mode :room :label "Canvas review"
                :icon :git-branch :event {:type :canvas-review/open-page}}]})
