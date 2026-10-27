(ns xi.web.image-edit
  "Crop and highlight rectangles on a composer image, in the lightbox
   (xi.web.views image-viewer, opened from the composer's attachment strip
   with `:edit {:draft-key :idxs}`).

   A staged attachment in :web/compose-images keeps its edit in metadata
   (`edit-of`): the original image, the crop and rectangles, and their
   undo/redo stacks. Its :data is the original with the edit drawn in, baked
   by the :image-edit/bake effect after every change. Transit drops
   metadata, so a sent prompt carries just the baked image."
  (:require [clojure.string :as str]
            [ui.button :as button]
            [ui.toolbar :as toolbar]))

(def colors ["#e5484d" "#f5d90a" "#30a46c" "#0090ff" "#ffffff"])

;; ── The edit (pure) ──────────────────────────────────────────────────────────
;; Rectangles {:x :y :w :h} are in the original image's pixels.

(defn edit-of [att] (::edit (meta att)))

(defn- snapshot-of [edit]
  {:crop (:crop edit) :shapes (vec (:shapes edit))})

(def ^:private unedited {:crop nil :shapes []})

(defn modified? [att]
  (not= unedited (snapshot-of (edit-of att))))

(defn can-undo? [att] (boolean (seq (:undo (edit-of att)))))
(defn can-redo? [att] (boolean (seq (:redo (edit-of att)))))

(defn- edit-or-new [att]
  (or (edit-of att)
      (assoc unedited :original (select-keys att [:data :media-type]) :undo [] :redo [])))

(defn- commit
  "`att` with its edit moved to `snap`, the current one pushed for undo; nil
   when `snap` changes nothing."
  [att snap]
  (let [edit (edit-or-new att)]
    (when (not= snap (snapshot-of edit))
      (vary-meta att assoc ::edit
                 (-> edit
                     (update :undo conj (snapshot-of edit))
                     (assoc :redo [])
                     (merge snap))))))

(defn crop [att rect]
  (commit att (assoc (snapshot-of (edit-or-new att)) :crop rect)))

(defn add-shape [att shape]
  (commit att (update (snapshot-of (edit-or-new att)) :shapes conj shape)))

(defn remove-edits [att]
  (when (modified? att) (commit att unedited)))

(defn- step
  "Move the edit back (`from` :undo, `to` :redo) or forward."
  [att from to]
  (let [edit (edit-of att)]
    (when-let [snap (peek (get edit from))]
      (vary-meta att assoc ::edit
                 (-> edit
                     (update from pop)
                     (update to conj (snapshot-of edit))
                     (merge snap))))))

(defn undo [att] (step att :undo :redo))
(defn redo [att] (step att :redo :undo))

;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- shown
  "[draft-key idx attachment] of the image the editing lightbox shows."
  [st]
  (let [{:keys [edit index]} (:web/lightbox st)
        {:keys [draft-key idxs]} edit]
    (when-let [idx (get idxs index)]
      (when-let [att (get-in st [:web/compose-images draft-key idx])]
        [draft-key idx att]))))

(defn- update-shown
  "Apply `f` to the shown attachment. An edited one gets baked; one back to
   unedited takes its original data again."
  [st f]
  (when-let [[draft-key idx att] (shown st)]
    (when-let [att (f att)]
      (let [{:keys [original] :as edit} (edit-of att)
            path [:web/compose-images draft-key idx]]
        (if (modified? att)
          {:state   (assoc-in st path att)
           :effects [[:image-edit/bake {:draft-key draft-key :idx idx :original original
                                        :snapshot (snapshot-of edit)}]]}
          {:state (assoc-in st path (merge att original))})))))

(defn- baked
  "Store a bake when the attachment still holds the edit it was made from."
  [st {:keys [draft-key idx original snapshot data media-type]}]
  (let [path [:web/compose-images draft-key idx]
        edit (edit-of (get-in st path))]
    (when (and edit (= original (:original edit)) (= snapshot (snapshot-of edit)))
      {:state (update-in st path assoc :data data :media-type media-type)})))

(def handlers
  {:lightbox/tool       (fn [st {:keys [tool]}]
                          (when (get-in st [:web/lightbox :edit])
                            {:state (update st :web/lightbox
                                            #(assoc % :tool (when (not= tool (:tool %)) tool)))}))
   :lightbox/color      (fn [st {:keys [color]}]
                          (when (get-in st [:web/lightbox :edit])
                            {:state (-> (assoc st :web/image-edit-color color)
                                        (assoc-in [:web/lightbox :tool] :rect))}))
   :lightbox/edit       (fn [st ev]
                          (update-shown st (fn [att]
                                             (if-let [rect (:crop ev)]
                                               (crop att rect)
                                               (add-shape att (:shape ev))))))
   :lightbox/undo       (fn [st _] (update-shown st undo))
   :lightbox/redo       (fn [st _] (update-shown st redo))
   :lightbox/reset      (fn [st _] (update-shown st remove-edits))
   :compose/image-baked baked})

(defn editable?
  "True while the lightbox shows a composer image it can edit."
  [st]
  (some? (shown st)))

;; ── Drawing ──────────────────────────────────────────────────────────────────

(defonce ^:private ^{:doc "data: URL → promise of its decoded image."}
  image-cache (js/Map.))

(defn- data-url [{:keys [data media-type]}]
  (str "data:" media-type ";base64," data))

(defn- load-image [src]
  (or (.get image-cache src)
      (let [p (js/Promise.
               (fn [resolve reject]
                 (let [img (js/Image.)]
                   (set! (.-onload img) #(resolve img))
                   (set! (.-onerror img) reject)
                   (set! (.-src img) src))))]
        (when (> (.-size image-cache) 8) (.clear image-cache))
        (.set image-cache src p)
        p)))

(defn- region [^js img crop]
  (or crop {:x 0 :y 0 :w (.-naturalWidth img) :h (.-naturalHeight img)}))

(defn- stroke-width
  "A highlight's line width in image pixels: thicker on bigger images."
  [^js img]
  (max 3 (js/Math.round (/ (max (.-naturalWidth img) (.-naturalHeight img)) 400))))

(defn- draw!
  "Paint the crop of `img` with the rectangles onto `canvas`, at the image's
   own resolution. Returns the region shown."
  [^js canvas img {:keys [crop shapes]}]
  (let [{:keys [x y w h] :as reg} (region img crop)
        ctx (.getContext canvas "2d")]
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    (.drawImage ctx img x y w h 0 0 w h)
    (doseq [{sx :x sy :y sw :w sh :h :keys [color width]} shapes]
      (set! (.-strokeStyle ctx) color)
      (set! (.-lineWidth ctx) width)
      (.strokeRect ctx (- sx x) (- sy y) sw sh))
    reg))

(defn- bake
  "{:data :media-type} of `img` with `snapshot` drawn in: JPEG stays JPEG."
  [img snapshot media-type]
  (let [canvas (js/document.createElement "canvas")
        _      (draw! canvas img snapshot)
        type   (if (= media-type "image/jpeg") "image/jpeg" "image/png")
        url    (.toDataURL canvas type 0.92)]
    {:data (subs url (inc (str/index-of url ","))) :media-type type}))

(def effects
  {:image-edit/bake
   (fn [{:keys [dispatch!]} {:keys [original snapshot] :as req}]
     (-> (load-image (data-url original))
         (.then (fn [img]
                  (dispatch! (merge (assoc (select-keys req [:draft-key :idx :original :snapshot])
                                           :type :compose/image-baked)
                                    (bake img snapshot (:media-type original))))))
         (.catch (fn [e] (js/console.error "[xi-web] image edit failed:" e)))))})

;; ── Canvas ───────────────────────────────────────────────────────────────────
;; The view's props and an ongoing drag live on the canvas node, so a drag
;; repaints without going through the app state; only its result is
;; dispatched (:lightbox/edit).

(defn- props [^js node] (aget node "__xiImageEdit"))

(defn- rect-of [{:keys [x0 y0 x1 y1]}]
  (let [x (js/Math.round (min x0 x1))
        y (js/Math.round (min y0 y1))]
    {:x x :y y
     :w (- (js/Math.round (max x0 x1)) x)
     :h (- (js/Math.round (max y0 y1)) y)}))

(defn- css-scale
  "Canvas pixels per CSS pixel."
  [^js node]
  (/ (.-width node) (max 1 (.-width (.getBoundingClientRect node)))))

(defn- draw-drag! [^js node img {:keys [x y w h]} {:keys [tool color]} drag]
  (let [ctx  (.getContext node "2d")
        r    (rect-of drag)
        rx   (- (:x r) x)
        ry   (- (:y r) y)
        {rw :w rh :h} r]
    (if (= tool :crop)
      (do (set! (.-fillStyle ctx) "rgba(0,0,0,0.5)")
          (.fillRect ctx 0 0 w ry)
          (.fillRect ctx 0 (+ ry rh) w (- h ry rh))
          (.fillRect ctx 0 ry rx rh)
          (.fillRect ctx (+ rx rw) ry (- w rx rw) rh)
          (set! (.-strokeStyle ctx) "#fff")
          (set! (.-lineWidth ctx) (* 1.5 (css-scale node)))
          (.setLineDash ctx #js [(* 6 (css-scale node)) (* 4 (css-scale node))])
          (.strokeRect ctx rx ry rw rh))
      (do (set! (.-strokeStyle ctx) color)
          (set! (.-lineWidth ctx) (stroke-width img))
          (.strokeRect ctx rx ry rw rh)))))

(defn- repaint!
  "Paint the node's current props once its image is decoded. Skips a paint
   that would draw what is already there (an app render that changed nothing
   here)."
  [^js node]
  (let [{:keys [src snapshot]} (props node)]
    (.then (load-image src)
           (fn [img]
             (let [{cur-src :src cur-snap :snapshot :as p} (props node)
                   drag    (aget node "__xiDrag")
                   painted [cur-src cur-snap]]
               (when (and (= src cur-src) (= snapshot cur-snap)
                          (or drag (not= painted (aget node "__xiPainted"))))
                 (let [reg (draw! node img cur-snap)]
                   (aset node "__xiRegion" reg)
                   (aset node "__xiImage" img)
                   (aset node "__xiPainted" (when-not drag painted))
                   (when drag (draw-drag! node img reg p drag)))))))))

(defn- point
  "The pointer's position in image pixels, kept inside the shown region."
  [^js node ^js e]
  (let [r (.getBoundingClientRect node)
        {:keys [x y w h]} (aget node "__xiRegion")
        clamp (fn [v lo hi] (max lo (min hi v)))]
    {:x (clamp (+ x (* w (/ (- (.-clientX e) (.-left r)) (max 1 (.-width r))))) x (+ x w))
     :y (clamp (+ y (* h (/ (- (.-clientY e) (.-top r)) (max 1 (.-height r))))) y (+ y h))}))

(defn- pointer-down [^js e]
  (let [node (.-currentTarget e)]
    (when (and (:tool (props node)) (aget node "__xiRegion")
               (.-isPrimary e) (or (not= "mouse" (.-pointerType e)) (zero? (.-button e))))
      ;; Not a swipe to the next image.
      (.stopPropagation e)
      (.preventDefault e)
      (.setPointerCapture node (.-pointerId e))
      (let [{:keys [x y]} (point node e)]
        (aset node "__xiDrag" {:id (.-pointerId e) :x0 x :y0 y :x1 x :y1 y})))))

(defn- pointer-move [^js e]
  (let [node (.-currentTarget e)
        drag (aget node "__xiDrag")]
    (when (and drag (= (:id drag) (.-pointerId e)))
      (let [{:keys [x y]} (point node e)]
        (aset node "__xiDrag" (assoc drag :x1 x :y1 y))
        (repaint! node)))))

(defn- pointer-up [^js e]
  (let [node (.-currentTarget e)
        drag (aget node "__xiDrag")]
    (when (and drag (= (:id drag) (.-pointerId e)))
      (aset node "__xiDrag" nil)
      (aset node "__xiPainted" nil)
      (let [{:keys [tool color dispatch!]} (props node)
            {:keys [w h] :as rect} (rect-of drag)
            min-px (* 4 (css-scale node))]
        (when (and (= "pointerup" (.-type e)) (>= w min-px) (>= h min-px))
          (dispatch! (if (= tool :crop)
                       {:type :lightbox/edit :crop rect}
                       {:type :lightbox/edit
                        :shape (assoc rect :color color
                                      :width (stroke-width (aget node "__xiImage")))})))
        ;; After the render the edit causes, which repaints first: this one
        ;; only clears the drag when nothing changed.
        (js/requestAnimationFrame #(repaint! node))))))

(defn canvas
  "The shown attachment `att` as an editable canvas. `tool` is :crop, :rect
   or nil (then a drag swipes, as on an image)."
  [dispatch! att {:keys [tool color index n dir]}]
  (let [edit (edit-of att)
        p    {:src       (data-url (or (:original edit) att))
              :snapshot  (snapshot-of edit)
              :tool      tool
              :color     color
              :dispatch! dispatch!}]
    [:canvas {:replicant/key index
              :class ["lightbox-canvas"
                      (case dir
                        :next "lightbox-image--from-next"
                        :prev "lightbox-image--from-prev"
                        nil)]
              :data-tool (some-> tool name)
              :role "img"
              :aria-label (str "Image " (inc index) " of " n)
              :replicant/on-render (fn [{:replicant/keys [node]}]
                                     (aset node "__xiImageEdit" p)
                                     (repaint! node))
              :on {:pointerdown   pointer-down
                   :pointermove   pointer-move
                   :pointerup     pointer-up
                   :pointercancel pointer-up
                   :click         (fn [^js e] (.stopPropagation e))}}]))

;; ── Toolbar ──────────────────────────────────────────────────────────────────

(defn- lucide
  "A Lucide icon (ISC) inline: the framework's icon set has none of these."
  [& children]
  (into [:svg {:class ["icon"] :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
               :fill "none" :stroke "currentColor" :stroke-width "2"
               :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}]
        children))

(def ^:private icons
  {:crop  (lucide [:path {:d "M6 2v14a2 2 0 0 0 2 2h14"}]
                  [:path {:d "M18 22V8a2 2 0 0 0-2-2H2"}])
   :rect  (lucide [:rect {:width "18" :height "18" :x "3" :y "3" :rx "2"}])
   :undo  (lucide [:path {:d "M9 14 4 9l5-5"}]
                  [:path {:d "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"}])
   :redo  (lucide [:path {:d "m15 14 5-5-5-5"}]
                  [:path {:d "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13"}])
   :reset (lucide [:path {:d "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"}]
                  [:path {:d "M3 3v5h5"}])})

(defn- bar-button [label pressed? disabled? on-click & children]
  (apply button/button
         {:variant  :ghost
          :class    "image-edit-btn"
          :disabled disabled?
          :on-click on-click
          :attrs    (cond-> {:replicant/key label :type "button" :title label :aria-label label}
                      (some? pressed?) (assoc :aria-pressed (str pressed?)))}
         children))

(defn bar
  "The editor's toolbar over the lightbox: crop and highlight tools, the
   highlight colors, undo / redo / remove the edits."
  [dispatch! att {:keys [tool color]}]
  (let [tool! (fn [t] #(dispatch! {:type :lightbox/tool :tool t}))
        stop  (fn [^js e] (.stopPropagation e))]
    [:div {:class ["image-edit-bar"]
           :on {:click stop :pointerdown stop}}
     (toolbar/toolbar
      {}
      (bar-button "Crop (C)" (= tool :crop) false (tool! :crop) (:crop icons))
      (bar-button "Highlight (R)" (= tool :rect) false (tool! :rect) (:rect icons))
      (when (= tool :rect) (toolbar/toolbar-separator {}))
      (when (= tool :rect)
        (for [c colors]
          (bar-button (str "Color " c) (= c color) false
                      #(dispatch! {:type :lightbox/color :color c})
                      [:span {:class ["image-edit-swatch"] :style {:background c}}])))
      (toolbar/toolbar-separator {})
      (bar-button "Undo (Ctrl+Z)" nil (not (can-undo? att))
                  #(dispatch! {:type :lightbox/undo}) (:undo icons))
      (bar-button "Redo (Ctrl+Shift+Z)" nil (not (can-redo? att))
                  #(dispatch! {:type :lightbox/redo}) (:redo icons))
      (bar-button "Remove all edits" nil (not (modified? att))
                  #(dispatch! {:type :lightbox/reset}) (:reset icons)))]))

(defn view-props
  "What image-viewer needs to show the editor, or nil when the lightbox
   shows no composer image."
  [st]
  (when-let [[_ _ att] (shown st)]
    {:att   att
     :tool  (get-in st [:web/lightbox :tool])
     :color (or (:web/image-edit-color st) (first colors))}))
