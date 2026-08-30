(ns xi.ext.image-graph.web
  "Browser half of the image-graph extension — a per-project gallery of Gemini
   art-iteration graphs and a node-canvas editor to iterate on one image.

   Three pages, all under /art (roomless):
     /art              → project picker (reuses :web/project-dirs)
     /art/:cwd         → gallery of that project's image graphs
     /art/:cwd/:gid    → node canvas for one graph (generate / fork / cover /
                         download / send-to-chat / delete / upload-sketch)

   The canvas is a pan/zoom layer of absolutely-positioned node cards with an
   SVG overlay for parent→child edges, laid out as a tidy left-to-right tree
   (pure `layout`), edges drawn imperatively after render (Replicant hook) —
   the same shape as the canvas-review extension.

   Route note: :route/navigate's whitelist has no :graph-id, so the graph id
   rides along as the route's :file (see open-graph / parse-route)."
  (:require [clojure.string :as str]
            [ui.button :as button]
            [ui.icon :as icon]
            [xi.web.views :as views]))

;; ── Tree layout (pure) ───────────────────────────────────────────────────────

(def ^:private COL-W 300)
(def ^:private ROW-H 260)
(def ^:private PAD 48)

(defn- layout
  "Tidy left-to-right tree: depth→column (x), leaf order→row (y). Nodes whose
   parent is nil or missing are roots. Returns {id {:left px :top px}}."
  [nodes]
  (let [ids      (into #{} (map :id) nodes)
        children (reduce (fn [m n]
                           (let [p (:parent n)
                                 k (when (ids p) p)]
                             (update m k (fnil conj []) (:id n))))
                         {} nodes)
        leaf     (volatile! 0)
        pos      (volatile! {})
        assign   (fn assign [id d]
                   (let [kids (get children id [])]
                     (if (empty? kids)
                       (let [y @leaf]
                         (vswap! leaf inc)
                         (vswap! pos assoc id {:col d :row y})
                         y)
                       (let [ys (mapv #(assign % (inc d)) kids)
                             y  (/ (+ (first ys) (last ys)) 2)]
                         (vswap! pos assoc id {:col d :row y})
                         y))))]
    (doseq [r (get children nil [])] (assign r 0))
    (into {} (map (fn [[id {:keys [col row]}]]
                    [id {:left (+ PAD (* col COL-W))
                         :top  (+ PAD (* row ROW-H))}]))
          @pos)))

(defn- canvas-size [positions]
  {:w (+ COL-W (reduce max 600 (map :left (vals positions))))
   :h (+ ROW-H (reduce max 400 (map :top (vals positions))))})

;; ── Pan / zoom (imperative, module-local) ────────────────────────────────────

(defonce ^:private view (atom {:x 0 :y 0 :z 1}))

(defn- apply-view! [^js canvas]
  (let [{:keys [x y z]} @view]
    (set! (.. canvas -style -transformOrigin) "0 0")
    (set! (.. canvas -style -transform)
          (str "translate(" x "px," y "px) scale(" z ")"))))

(def ^:private SVGNS "http://www.w3.org/2000/svg")

(defn- draw-edges!
  "Draw a bezier from each node's parent (right edge) to the node (left edge)."
  [^js canvas nodes]
  (when-let [svg (.querySelector canvas ".ig-edges")]
    (set! (.-innerHTML svg) "")
    (doseq [n nodes :when (:parent n)]
      (let [a (.querySelector canvas (str "[data-ig-node=\"" (:parent n) "\"]"))
            b (.querySelector canvas (str "[data-ig-node=\"" (:id n) "\"]"))]
        (when (and a b)
          (let [ax (+ (.-offsetLeft a) (.-offsetWidth a))
                ay (+ (.-offsetTop a) (/ (.-offsetHeight a) 2))
                bx (.-offsetLeft b)
                by (+ (.-offsetTop b) (/ (.-offsetHeight b) 2))
                mx (/ (+ ax bx) 2)
                p  (.createElementNS js/document SVGNS "path")]
            (.setAttribute p "d" (str "M " ax " " ay " C " mx " " ay " " mx " " by " " bx " " by))
            (.setAttribute p "class" "ig-edge-path")
            (.appendChild svg p)))))))

(defn- canvas-hook [nodes]
  (fn [{:replicant/keys [^js node]}]
    (draw-edges! node nodes)
    (apply-view! node)))

(defn- canvas-pan!
  "Grab-to-pan on empty background; wheel pans; ctrl/⌘+wheel zooms around the
   pointer. Listeners torn down on unmount."
  [{:replicant/keys [^js node life-cycle]}]
  (if (= life-cycle :replicant.life-cycle/unmount)
    (do (when-let [h (.-_igDown node)] (.removeEventListener node "mousedown" h))
        (when-let [w (.-_igWheel node)] (.removeEventListener node "wheel" w))
        (set! (.-_igDown node) nil)
        (set! (.-_igWheel node) nil))
    (when-not (.-_igDown node)
      (let [canvas  (fn [] (.querySelector node ".ig-canvas"))
            origin  (atom nil)
            on-move (fn [^js e]
                      (when-let [o @origin]
                        (swap! view assoc
                               :x (+ (:ox o) (- (.-clientX e) (:px o)))
                               :y (+ (:oy o) (- (.-clientY e) (:py o))))
                        (apply-view! (:c o))))
            on-up   (fn on-up [_]
                      (reset! origin nil)
                      (set! (.. node -style -cursor) "")
                      (.removeEventListener js/window "mousemove" on-move)
                      (.removeEventListener js/window "mouseup" on-up))
            on-down (fn [^js e]
                      (when (and (zero? (.-button e))
                                 (not (.closest (.-target e)
                                                ".ig-node, button, a, input, textarea, select")))
                        (when-let [c (canvas)]
                          (reset! origin {:px (.-clientX e) :py (.-clientY e)
                                          :ox (:x @view) :oy (:y @view) :c c})
                          (set! (.. node -style -cursor) "grabbing")
                          (.addEventListener js/window "mousemove" on-move)
                          (.addEventListener js/window "mouseup" on-up)
                          (.preventDefault e))))
            on-wheel (fn [^js e]
                       (when-let [c (canvas)]
                         (.preventDefault e)
                         (if (or (.-ctrlKey e) (.-metaKey e))
                           (let [rect (.getBoundingClientRect node)
                                 px   (- (.-clientX e) (.-left rect))
                                 py   (- (.-clientY e) (.-top rect))
                                 {:keys [x y z]} @view
                                 nz   (max 0.2 (min 3 (* z (if (pos? (.-deltaY e)) 0.9 1.1))))]
                             (reset! view {:z nz
                                           :x (- px (* (/ (- px x) z) nz))
                                           :y (- py (* (/ (- py y) z) nz))}))
                           (swap! view (fn [v] (-> v
                                                   (update :x - (.-deltaX e))
                                                   (update :y - (.-deltaY e))))))
                         (apply-view! c)))]
        (set! (.-_igDown node) on-down)
        (set! (.-_igWheel node) on-wheel)
        (.addEventListener node "mousedown" on-down)
        (.addEventListener node "wheel" on-wheel #js {:passive false})))))

(defn- zoom! [delta]
  (fn [_]
    (swap! view update :z (fn [z] (max 0.2 (min 3 (+ z delta)))))
    (when-let [c (.querySelector js/document ".ig-canvas")] (apply-view! c))))

(defn- reset-view! [_]
  (reset! view {:x 0 :y 0 :z 1})
  (when-let [c (.querySelector js/document ".ig-canvas")] (apply-view! c)))

;; ── File reading (sketch upload) ─────────────────────────────────────────────

(defn- read-image-file
  "Read a File as base64; promise of {:data base64 :mime mime} or nil."
  [^js file]
  (js/Promise.
   (fn [resolve _]
     (let [r (js/FileReader.)]
       (set! (.-onload r)
             (fn [_]
               (let [[_ mime b64] (re-matches #"^data:([^;,]*);base64,(.*)$" (.-result r))]
                 (resolve {:data b64
                           :mime (or (not-empty (.-type file)) (not-empty mime) "image/png")}))))
       (set! (.-onerror r) (fn [_] (resolve nil)))
       (.readAsDataURL r file)))))

;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- forward [st ev]
  {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]})

(defn- on-navigate
  "Chained after the base router: load data when entering an /art page."
  [st {:keys [page cwd file]}]
  (when (= page :art)
    (cond
      (nil? cwd)
      {:effects (when (empty? (:web/project-dirs st))
                  [[:app/dispatch {:type :projects/web-list}]])}
      file
      {:state   (assoc st :web/art-loading? true :web/art-selected nil :web/art-error nil)
       :effects [[:app/dispatch {:type :image-graph/read :cwd cwd :graph-id file}]]}
      :else
      {:state   (assoc st :web/art-graphs-loading? true)
       :effects [[:app/dispatch {:type :image-graph/list :cwd cwd}]]})))

(defn- send-to-chat
  "Open a fresh virtual chat in the project cwd with the node image staged as a
   compose attachment."
  [st {:keys [cwd image-data]}]
  (let [[_ mime b64] (re-matches #"data:([^;]+);base64,(.*)" (or image-data ""))]
    {:state   (-> st
                  (assoc :web/route {:page :chat :session-id nil})
                  (assoc :web/pending-room {:id (random-uuid) :cwd cwd})
                  (assoc :web/compose-images [{:data b64 :media-type (or mime "image/png")}])
                  (assoc :web/timeline-window nil))
     :effects [[:history/push {:route {:page :chat}}]
               [:compose/focus]]}))

(def ^:private handlers
  {:route/navigate on-navigate

   ;; Navigation helpers (centralize the :file=graph-id mapping)
   :image-graph/open
   (fn [_ _] {:effects [[:app/dispatch {:type :route/navigate :page :art}]]})
   :image-graph/open-project
   (fn [_ {:keys [cwd]}] {:effects [[:app/dispatch {:type :route/navigate :page :art :cwd cwd}]]})
   :image-graph/open-graph
   (fn [_ {:keys [cwd graph-id]}]
     {:effects [[:app/dispatch {:type :route/navigate :page :art :cwd cwd :file graph-id}]]})

   ;; Server round-trips (forward → server-fx replies)
   :image-graph/list
   (fn [st ev] {:state (assoc st :web/art-graphs-loading? true)
                :effects [[:ws/send (dissoc ev :event/id :event/ts)]]})
   :image-graph/list-result
   (fn [st {:keys [cwd graphs]}]
     {:state (assoc st :web/art-graphs graphs :web/art-graphs-cwd cwd
                    :web/art-graphs-loading? false)})
   :image-graph/read forward
   :image-graph/graph-result
   (fn [st {:keys [graph]}]
     {:state (assoc st :web/art-graph graph :web/art-loading? false
                    :web/art-generating? false :web/art-error nil)})
   :image-graph/error
   (fn [st {:keys [message]}]
     {:state (assoc st :web/art-error message :web/art-generating? false
                    :web/art-loading? false)})
   :image-graph/new forward
   :image-graph/created
   (fn [_ {:keys [cwd graph-id]}]
     {:effects [[:app/dispatch {:type :image-graph/open-graph :cwd cwd :graph-id graph-id}]]})
   :image-graph/delete-graph forward
   :image-graph/graph-deleted
   (fn [st {:keys [cwd]}]
     {:state   (dissoc st :web/art-graph)
      :effects [[:app/dispatch {:type :image-graph/open-project :cwd cwd}]]})
   :image-graph/generate
   (fn [st ev] {:state (assoc st :web/art-generating? true :web/art-error nil)
                :effects [[:ws/send (dissoc ev :event/id :event/ts)]]})
   :image-graph/set-cover forward
   :image-graph/delete-node forward
   :image-graph/upload-sketch
   (fn [st ev] {:state (assoc st :web/art-generating? true)
                :effects [[:ws/send (dissoc ev :event/id :event/ts)]]})

   ;; Local UI
   :image-graph/select-node
   (fn [st {:keys [id]}] {:state (update st :web/art-selected #(if (= % id) nil id))})
   :image-graph/set-model
   (fn [st {:keys [model]}] {:state (assoc st :web/art-model model)})
   :image-graph/send-to-chat send-to-chat})

;; ── Node card ────────────────────────────────────────────────────────────────

(defn- fmt-cost [c] (str "$" (.toFixed (js/Number (or c 0)) 3)))

(defn- read-prompt [] (some-> (.getElementById js/document "ig-prompt") .-value))

(defn- node-card
  [dispatch! {:keys [cwd graph-id selected]}
   {:keys [id prompt image-data cost sketch cover?] :as n}
   pos]
  [:div {:class (cond-> ["ig-node"]
                  (= id selected) (conj "ig-node--selected")
                  cover?          (conj "ig-node--cover")
                  sketch          (conj "ig-node--sketch"))
         :data-ig-node id
         :replicant/key id
         :style {:left (str (:left pos) "px") :top (str (:top pos) "px")}
         :on {:click (fn [^js e] (.stopPropagation e)
                       (dispatch! {:type :image-graph/select-node :id id}))}}
   (if image-data
     [:img {:class ["ig-node-img"] :src image-data :alt (or prompt "")}]
     [:div {:class ["ig-node-img" "ig-node-img--empty"]} "no image"])
   [:div {:class ["ig-node-cap"]}
    [:span {:class ["ig-node-prompt"]} (if sketch "sketch" (or (not-empty prompt) "—"))]
    (when cost [:span {:class ["ig-node-cost"]} (fmt-cost cost)])]
   (when (= id selected)
     [:div {:class ["ig-node-actions"] :on {:click (fn [^js e] (.stopPropagation e))}}
      (button/button {:variant :primary :size :sm :icon-left :star
                      :on-click (fn [_] (dispatch! {:type :image-graph/set-cover
                                                    :cwd cwd :graph-id graph-id :node-id id}))}
                     "Cover")
      (when image-data
        [:a {:class ["btn" "btn-sm"] :href image-data :download (str id ".png")}
         "Download"])
      (when image-data
        (button/button {:variant :ghost :size :sm :icon-left :message-circle
                        :on-click (fn [_] (dispatch! {:type :image-graph/send-to-chat
                                                      :cwd cwd :image-data image-data}))}
                       "Chat"))
      (button/button {:variant :ghost :size :sm :icon-left :trash
                      :on-click (fn [_] (dispatch! {:type :image-graph/delete-node
                                                    :cwd cwd :graph-id graph-id :node-id id}))}
                     "Delete")])])

;; ── Editor page ──────────────────────────────────────────────────────────────

(defn- model-toggle [dispatch! model]
  [:div {:class ["ig-models"]}
   (for [[id label] [["nano-banana" "Nano Banana"] ["nano-banana-pro" "Nano Banana Pro"]]]
     (button/button {:variant (if (= id model) :primary :ghost) :size :sm
                     :replicant/key id
                     :on-click (fn [_] (dispatch! {:type :image-graph/set-model :model id}))}
                    label))])

(defn- compose-bar [dispatch! cwd graph-id selected model generating?]
  [:div {:class ["ig-compose"]}
   [:div {:class ["ig-compose-target"]}
    (if selected "Fork from selected node" "New root image")]
   [:textarea {:id "ig-prompt" :class ["form-textarea" "ig-prompt"]
               :placeholder "Describe the image to generate…"
               :rows 2
               :disabled generating?
               :on {:keydown (fn [^js e]
                               (when (and (= "Enter" (.-key e))
                                          (or (.-metaKey e) (.-ctrlKey e)))
                                 (.preventDefault e)
                                 (let [t (read-prompt)]
                                   (when (and t (seq (str/trim t)))
                                     (dispatch! {:type :image-graph/generate
                                                 :cwd cwd :graph-id graph-id
                                                 :parent selected :prompt t :model model})))))}}]
   (button/button {:variant :primary :size :md :disabled generating?
                   :on-click (fn [_]
                               (let [t (read-prompt)]
                                 (when (and t (seq (str/trim t)))
                                   (dispatch! {:type :image-graph/generate
                                               :cwd cwd :graph-id graph-id
                                               :parent selected :prompt t :model model}))))}
                  (if generating? "Generating…" "Generate"))])

(defn- editor-page [state dispatch!]
  (let [graph      (:web/art-graph state)
        cwd        (:cwd graph)
        gid        (:id graph)
        cover      (:cover graph)
        nodes      (mapv #(assoc % :cover? (= (:id %) cover)) (:nodes graph))
        selected   (:web/art-selected state)
        model      (or (:web/art-model state) "nano-banana")
        generating? (:web/art-generating? state)
        error      (:web/art-error state)
        positions  (layout nodes)
        {cw :w ch :h} (canvas-size positions)]
    [:div {:class ["container" "ig-container"] :replicant/key "ig-editor"}
     [:div {:class ["topbar"]}
      [:button {:class ["icon-btn"]
                :on {:click (fn [_] (dispatch! {:type :nav/back
                                                :fallback {:page :art :cwd cwd}}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]}
       (or (:title graph) "Image graph")
       [:span {:class ["ig-cost-total"]} (str " · " (fmt-cost (:cost-total graph)))]]
      [:div {:class ["ig-topbar-tools"]}
       (model-toggle dispatch! model)
       [:label {:class ["btn" "btn-sm" "ig-upload"]}
        (icon/icon {:icon-name :image :size :sm}) " Sketch"
        [:input {:type "file" :accept "image/*" :style {:display "none"}
                 :on {:change (fn [^js e]
                                (when-let [f (aget (.. e -target -files) 0)]
                                  (-> (read-image-file f)
                                      (.then (fn [res]
                                               (when res
                                                 (dispatch! {:type :image-graph/upload-sketch
                                                             :cwd cwd :graph-id gid
                                                             :data (:data res) :mime (:mime res)})))))))}}]]
       [:button {:class ["icon-btn"] :title "Zoom out" :on {:click (zoom! -0.15)}} "−"]
       [:button {:class ["icon-btn"] :title "Reset view" :on {:click reset-view!}} "⟳"]
       [:button {:class ["icon-btn"] :title "Zoom in" :on {:click (zoom! 0.15)}} "+"]]
      (views/overflow-menu dispatch! state)]
     (when error [:div {:class ["ig-error"]} error])
     [:div {:class ["ig-viewport"]
            :replicant/on-mount canvas-pan!
            :replicant/on-unmount canvas-pan!
            :on {:click (fn [_] (dispatch! {:type :image-graph/select-node :id nil}))}}
      (if (empty? nodes)
        [:div {:class ["empty-state" "ig-empty"]}
         (if (:web/art-loading? state)
           (views/spinner)
           [:p "No images yet. Describe one below to generate the first, or upload a sketch."])]
        [:div {:class ["ig-canvas"]
               :style {:width (str cw "px") :height (str ch "px")}
               :replicant/on-mount (canvas-hook nodes)
               :replicant/on-update (canvas-hook nodes)}
         [:svg {:class ["ig-edges"]}]
         (for [n nodes]
           (node-card dispatch! {:cwd cwd :graph-id gid :selected selected}
                      n (get positions (:id n))))])]
     (compose-bar dispatch! cwd gid selected model generating?)]))

;; ── Gallery page ─────────────────────────────────────────────────────────────

(defn- gallery-card [dispatch! cwd {:keys [id title cover-data node-count cost-total]}]
  [:div {:class ["ig-gallery-card"] :replicant/key id
         :on {:click (fn [_] (dispatch! {:type :image-graph/open-graph :cwd cwd :graph-id id}))}}
   [:div {:class ["ig-gallery-thumb"]}
    (if cover-data
      [:img {:src cover-data :alt (or title "")}]
      [:div {:class ["ig-gallery-thumb--empty"]}
       (icon/icon {:icon-name :image :size :lg})])]
   [:div {:class ["ig-gallery-meta"]}
    [:span {:class ["ig-gallery-title"]} (or (not-empty title) "Untitled")]
    [:span {:class ["ig-gallery-sub"]}
     (str node-count " image" (when (not= 1 node-count) "s")
          " · " (fmt-cost cost-total))]]
   [:button {:class ["ig-gallery-del"] :title "Delete"
             :on {:click (fn [^js e]
                           (.stopPropagation e)
                           (when (js/confirm (str "Delete \"" (or title "Untitled") "\"? This cannot be undone."))
                             (dispatch! {:type :image-graph/delete-graph :cwd cwd :graph-id id})))}}
    (icon/icon {:icon-name :trash :size :sm})]])

(defn- gallery-page [state dispatch!]
  (let [cwd     (get-in state [:web/route :cwd])
        graphs  (:web/art-graphs state)
        loading? (:web/art-graphs-loading? state)]
    [:div {:class ["container"] :replicant/key "ig-gallery"}
     [:div {:class ["topbar"]}
      [:button {:class ["icon-btn"]
                :on {:click (fn [_] (dispatch! {:type :nav/back :fallback {:page :art}}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]} (views/shorten-path cwd)]
      [:button {:class ["icon-btn"] :title "New image"
                :on {:click (fn [_] (dispatch! {:type :image-graph/new :cwd cwd :title "Untitled"}))}}
       (icon/icon {:icon-name :plus :size :md})]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (cond
        (and loading? (empty? graphs))
        [:div {:class ["empty-state"]} (views/spinner)]
        (empty? graphs)
        [:div {:class ["empty-state"]}
         [:p "No image graphs yet."]
         (button/button {:variant :primary :size :md :icon-left :plus
                         :on-click (fn [_] (dispatch! {:type :image-graph/new :cwd cwd :title "Untitled"}))}
                        "New image")]
        :else
        [:div {:class ["ig-gallery"]}
         (for [g graphs] (gallery-card dispatch! cwd g))])]]))

;; ── Project picker page ──────────────────────────────────────────────────────

(defn- picker-page [state dispatch!]
  (let [dirs     (:web/project-dirs state)
        loading? (:web/projects-loading? state)]
    [:div {:class ["container"] :replicant/key "ig-picker"}
     [:div {:class ["topbar"]}
      (views/nav-group dispatch! (fn [_] (dispatch! {:type :nav/back :fallback {:page :home}})))
      [:div {:class ["topbar-title"]} "Image Graphs"]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (cond
        (and loading? (empty? dirs))
        [:div {:class ["empty-state"]} (views/spinner)]
        (empty? dirs)
        [:div {:class ["empty-state"]} [:p "No projects found."]]
        :else
        [:div {:class ["project-list"]}
         (for [d dirs]
           [:div {:class ["project-card"] :replicant/key d
                  :on {:click (fn [_] (dispatch! {:type :image-graph/open-project :cwd d}))}}
            [:div {:class ["project-card-icon"]} (icon/icon {:icon-name :image :size :sm})]
            [:div {:class ["project-card-info"]}
             [:span {:class ["project-card-name"]}
              (last (str/split (str/replace d #"/$" "") #"/"))]
             [:span {:class ["project-card-path"]} (views/shorten-path d)]]])])]]))

;; ── Page dispatch (one :art page, three sub-views by route shape) ────────────

(defn- art-page [state dispatch!]
  (let [{:keys [cwd file]} (:web/route state)]
    (cond
      (nil? cwd) (picker-page state dispatch!)
      file       (editor-page state dispatch!)
      :else      (gallery-page state dispatch!))))

;; ── Route ────────────────────────────────────────────────────────────────────

(defn- parse-route [segs]
  (case (count segs)
    0 {:page :art}
    1 {:page :art :cwd (js/decodeURIComponent (first segs))}
    {:page :art :cwd (js/decodeURIComponent (first segs)) :file (second segs)}))

(defn- route-path [{:keys [cwd file]}]
  (cond
    (and cwd file) (str "/art/" (js/encodeURIComponent cwd) "/" file)
    cwd            (str "/art/" (js/encodeURIComponent cwd))
    :else          "/art"))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id :image-graph
   :handlers handlers
   :routes {"art" {:parse parse-route
                   :path {:art route-path}
                   :roomless-pages #{:art}}}
   :pages {:art art-page}
   :nav-items [{:menu :home-topbar :label "Image Graphs" :icon :image
                :event {:type :route/navigate :page :art}}
               {:menu :sidebar :label "Image Graphs" :icon :image
                :event {:type :route/navigate :page :art}}
               {:menu :palette :label "Image Graphs" :icon :image
                :event {:type :route/navigate :page :art}}]})
