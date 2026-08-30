(ns xi.ext.image-graph
  "Server half of the image-graph extension — a per-project gallery of Gemini
   (\"Nano Banana\") art-iteration graphs. Each *graph* is one image lineage: a
   tree of generation steps (nodes), each with its output PNG. A project (cwd)
   holds many graphs; the web client shows the gallery, opens one into a node
   canvas, and iterates (generate / fork / set-cover / delete / upload sketch).

   Persistence (all gitignored, outside the repo):
     ~/.config/xi/art/graphs/<project-key>/<graph-id>.json   — one graph
     ~/.config/xi/art/images/<hash>                          — image bytes (shared)

   `project-key` is a sha256 of the cwd; the cwd is also stored in each graph so
   it can be displayed. Images are content-addressed by a salted sha256 so every
   generation is unique. Generated PNGs live on disk for persistence but are
   inlined as base64 data URLs when a graph is sent to the browser — Xi renders
   all images as data URLs, and this dodges the client-key auth that guards the
   HTTP /api routes (which an <img src> can't send).

   Everything I/O runs in server-fx replying to the requesting client; the
   roomless handlers just forward (mirrors the gtd/projects web pattern)."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [xi.ext.image-graph.gemini :as gemini]))

;; ── Paths ────────────────────────────────────────────────────────────────────

(defn- art-root [] (path/join (os/homedir) ".config" "xi" "art"))
(defn- images-dir [] (path/join (art-root) "images"))
(defn- graphs-root [] (path/join (art-root) "graphs"))

(defn- sha256 [s]
  (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))

(defn- project-key [cwd] (subs (sha256 (str cwd)) 0 16))
(defn- project-dir [cwd] (path/join (graphs-root) (project-key cwd)))
(defn- graph-path [cwd id] (path/join (project-dir cwd) (str id ".json")))

(defn- ensure-dir! [d] (.mkdirSync fs d #js {:recursive true}))

;; ── Ids ──────────────────────────────────────────────────────────────────────

(defn- rand36 [] (.toString (js/Math.floor (* (js/Math.random) 1e9)) 36))
(defn- gen-id [prefix] (str prefix (.toString (js/Date.now) 36) (subs (rand36) 0 4)))

;; ── Image store ──────────────────────────────────────────────────────────────

(defn- image-path [hash] (path/join (images-dir) hash))

(defn- write-image!
  "Write image bytes to the content-addressed store, returning the salted hash."
  [^js buf]
  (ensure-dir! (images-dir))
  (let [hash (subs (sha256 (str (.toString buf "base64") (js/Date.now) (rand36))) 0 32)
        f    (image-path hash)]
    (when-not (fs/existsSync f)
      (fs/writeFileSync f buf))
    hash))

(defn- read-image-bytes [hash]
  (let [f (image-path hash)]
    (when (and hash (fs/existsSync f))
      (fs/readFileSync f))))

(defn- image-data-url
  "Inline an image hash as a data URL, or nil when missing."
  [hash mime]
  (when-let [^js buf (read-image-bytes hash)]
    (str "data:" (or mime "image/png") ";base64," (.toString buf "base64"))))

;; ── Graph store ──────────────────────────────────────────────────────────────

(defn- read-graph [cwd id]
  (let [p (graph-path cwd id)]
    (when (fs/existsSync p)
      (js->clj (js/JSON.parse (fs/readFileSync p "utf8")) :keywordize-keys true))))

(defn- write-graph! [cwd id g]
  (ensure-dir! (project-dir cwd))
  (fs/writeFileSync (graph-path cwd id)
                    (str (js/JSON.stringify (clj->js g) nil 2) "\n")))

(defn- list-graph-ids [cwd]
  (let [d (project-dir cwd)]
    (if (fs/existsSync d)
      (->> (js->clj (.readdirSync fs d))
           (filter #(str/ends-with? % ".json"))
           (mapv #(subs % 0 (- (count %) 5))))
      [])))

(defn- find-node [g nid] (some #(when (= (:id %) nid) %) (:nodes g)))

(defn- graph-cost [g] (reduce + 0 (keep :cost (:nodes g))))

;; ── Client shapes ────────────────────────────────────────────────────────────

(defn- node->client
  "Inline a node's image as a data URL for the browser."
  [n]
  (assoc n :image-data (image-data-url (:image n) (:mime n))))

(defn- graph->client
  "Full graph with every node's image inlined (for the canvas editor)."
  [g]
  (-> g
      (assoc :nodes (mapv node->client (:nodes g)))
      (assoc :cost-total (graph-cost g))))

(defn- graph->summary
  "Lightweight gallery entry: title, node count, total cost, cover thumbnail."
  [g]
  (let [cover (or (find-node g (:cover g))
                  (last (:nodes g)))]
    {:id         (:id g)
     :title      (:title g)
     :created    (:created g)
     :node-count (count (:nodes g))
     :cost-total (graph-cost g)
     :cover-data (when cover (image-data-url (:image cover) (:mime cover)))}))

(defn- list-summaries [cwd]
  (->> (list-graph-ids cwd)
       (keep #(read-graph cwd %))
       (sort-by #(or (:created %) 0) >)
       (mapv graph->summary)))

;; ── Image pruning ────────────────────────────────────────────────────────────

(defn- project-image-hashes
  "Every image hash referenced by any graph in the project (optionally ignoring
   one graph id, e.g. one being deleted)."
  [cwd & [ignore-id]]
  (into #{}
        (comp (remove #(= % ignore-id))
              (keep #(read-graph cwd %))
              (mapcat :nodes)
              (keep :image))
        (list-graph-ids cwd)))

(defn- prune-images!
  "Delete image files in `hashes` that no graph in the project still references."
  [cwd hashes ignore-id]
  (let [keep (project-image-hashes cwd ignore-id)]
    (doseq [h hashes
            :when (and h (not (contains? keep h)))]
      (let [f (image-path h)]
        (when (fs/existsSync f) (fs/unlinkSync f))))))

;; ── Roomless handlers (forward to reply effects) ─────────────────────────────

(defn- fwd [reply-kw]
  (fn [_st ev]
    {:effects [[reply-kw (dissoc ev :type :event/id :event/ts)]]}))

(def ^:private handlers
  {:image-graph/list         (fwd :image-graph/list-reply)
   :image-graph/read         (fwd :image-graph/read-reply)
   :image-graph/new          (fwd :image-graph/new-reply)
   :image-graph/delete-graph (fwd :image-graph/delete-graph-reply)
   :image-graph/generate     (fwd :image-graph/generate-reply)
   :image-graph/set-cover    (fwd :image-graph/set-cover-reply)
   :image-graph/delete-node  (fwd :image-graph/delete-node-reply)
   :image-graph/upload-sketch (fwd :image-graph/upload-sketch-reply)})

;; ── Server fx ────────────────────────────────────────────────────────────────

(defn- send-graph! [send cwd g]
  (send {:type :image-graph/graph-result :cwd cwd :graph (graph->client g)}))

(defn- send-list! [send cwd]
  (send {:type :image-graph/list-result :cwd cwd :graphs (list-summaries cwd)}))

(defn- send-error! [send cwd message]
  (send {:type :image-graph/error :cwd cwd :message message}))

(defn- do-generate!
  "Add a generated node (or a fork off `parent`) to the graph, persist it, and
   send the fresh graph. Sketch parents run the two-stage describe→generate
   pipeline (deckbuilder's from-sketch path)."
  [send cwd graph-id {:keys [parent prompt model]}]
  (let [g (read-graph cwd graph-id)]
    (cond
      (nil? g)                    (send-error! send cwd "graph not found")
      (str/blank? (str prompt))   (send-error! send cwd "empty prompt")
      :else
      (let [pn          (when parent (find-node g parent))
            from-sketch (boolean (:sketch pn))
            ref-bytes   (when (:image pn) (read-image-bytes (:image pn)))
            ref-mime    (:mime pn)
            user-prompt (str prompt)
            prep        (if (and from-sketch ref-bytes)
                          (-> (gemini/describe ref-bytes ref-mime)
                              (.then (fn [c]
                                       {:composition c
                                        :gen-prompt  (str user-prompt
                                                          gemini/sketch-composition-prefix c)
                                        :ref-bytes   nil :ref-mime nil})))
                          (js/Promise.resolve {:composition nil
                                               :gen-prompt  user-prompt
                                               :ref-bytes   ref-bytes :ref-mime ref-mime}))]
        (-> prep
            (.then (fn [{:keys [composition gen-prompt ref-bytes ref-mime]}]
                     (-> (gemini/generate {:prompt    gen-prompt
                                           :ref-bytes ref-bytes
                                           :ref-mime  ref-mime
                                           :model     model})
                         (.then (fn [{:keys [png mime usage cost]}]
                                  (let [hash (write-image! png)
                                        node (cond-> {:id     (gen-id "n")
                                                      :parent (or parent nil)
                                                      :prompt user-prompt
                                                      :model  (or model "nano-banana")
                                                      :image  hash
                                                      :mime   mime
                                                      :ts     (js/Date.now)
                                                      :cost   cost
                                                      :usage  usage}
                                               composition (assoc :composition composition))
                                        g'   (-> (update g :nodes (fnil conj []) node)
                                                 (update :cover #(or % (:id node))))]
                                    (write-graph! cwd graph-id g')
                                    (send-graph! send cwd g'))))))))
            (.catch (fn [err]
                      (send-error! send cwd (str "Generation failed: " (.-message err)))))))))

(defn- do-upload-sketch!
  "Store an uploaded sketch image as a parentless :sketch node."
  [send cwd graph-id {:keys [data mime]}]
  (let [g (read-graph cwd graph-id)]
    (if (or (nil? g) (str/blank? (str data)))
      (send-error! send cwd "no graph or empty upload")
      (let [buf  (js/Buffer.from data "base64")
            hash (write-image! buf)
            node {:id     (gen-id "sk")
                  :parent nil
                  :prompt "sketch (uploaded)"
                  :model  nil
                  :image  hash
                  :mime   (or mime "image/png")
                  :ts     (js/Date.now)
                  :sketch true}
            g'   (update g :nodes (fnil conj []) node)]
        (write-graph! cwd graph-id g')
        (send-graph! send cwd g')))))

(defn- do-set-cover! [send cwd graph-id node-id]
  (when-let [g (read-graph cwd graph-id)]
    (let [g' (assoc g :cover node-id)]
      (write-graph! cwd graph-id g')
      (send-graph! send cwd g'))))

(defn- do-delete-node!
  "Remove a node and its whole subtree (BFS), prune now-orphaned images, and
   send the fresh graph."
  [send cwd graph-id node-id]
  (when-let [g (read-graph cwd graph-id)]
    (if-not (find-node g node-id)
      (send-graph! send cwd g)
      (let [kids    (reduce (fn [m n] (update m (:parent n) (fnil conj []) (:id n)))
                            {} (:nodes g))
            removed (loop [stack [node-id] acc #{}]
                      (if-let [cur (peek stack)]
                        (let [stack (pop stack)]
                          (if (acc cur)
                            (recur stack acc)
                            (recur (into stack (get kids cur [])) (conj acc cur))))
                        acc))
            removed-imgs (->> (:nodes g) (filter #(removed (:id %))) (keep :image))
            nodes'  (vec (remove #(removed (:id %)) (:nodes g)))
            cover'  (if (removed (:cover g)) nil (:cover g))
            g'      (assoc g :nodes nodes' :cover cover')]
        (write-graph! cwd graph-id g')
        ;; write first so project-image-hashes sees the updated graph
        (prune-images! cwd removed-imgs nil)
        (send-graph! send cwd g')))))

(defn- do-new-graph! [send cwd {:keys [title]}]
  (let [id (gen-id "g")
        g  {:id id :title (or (not-empty (str/trim (str title))) "Untitled")
            :cwd cwd :cover nil :created (js/Date.now) :nodes []}]
    (write-graph! cwd id g)
    (send {:type :image-graph/created :cwd cwd :graph-id id})
    (send-list! send cwd)))

(defn- do-delete-graph! [send cwd graph-id]
  (let [g    (read-graph cwd graph-id)
        imgs (keep :image (:nodes g))
        p    (graph-path cwd graph-id)]
    (when (fs/existsSync p) (fs/unlinkSync p))
    (prune-images! cwd imgs graph-id)
    (send {:type :image-graph/graph-deleted :cwd cwd :graph-id graph-id})
    (send-list! send cwd)))

(defn- server-fx
  [{:keys [send!]}]
  (letfn [(reply [f]
            (fn [_ {:keys [client-id cwd] :as ev}]
              (f (fn [event] (send! client-id event)) cwd ev)))]
    {:image-graph/list-reply
     (reply (fn [send cwd _] (send-list! send cwd)))

     :image-graph/read-reply
     (reply (fn [send cwd {:keys [graph-id]}]
              (if-let [g (read-graph cwd graph-id)]
                (send-graph! send cwd g)
                (send-error! send cwd "graph not found"))))

     :image-graph/new-reply
     (reply (fn [send cwd ev] (do-new-graph! send cwd ev)))

     :image-graph/delete-graph-reply
     (reply (fn [send cwd {:keys [graph-id]}] (do-delete-graph! send cwd graph-id)))

     :image-graph/generate-reply
     (reply (fn [send cwd {:keys [graph-id] :as ev}] (do-generate! send cwd graph-id ev)))

     :image-graph/set-cover-reply
     (reply (fn [send cwd {:keys [graph-id node-id]}] (do-set-cover! send cwd graph-id node-id)))

     :image-graph/delete-node-reply
     (reply (fn [send cwd {:keys [graph-id node-id]}] (do-delete-node! send cwd graph-id node-id)))

     :image-graph/upload-sketch-reply
     (reply (fn [send cwd {:keys [graph-id] :as ev}] (do-upload-sketch! send cwd graph-id ev)))}))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id              :image-graph
   :handlers        handlers
   :server-fx       server-fx
   :roomless-events #{:image-graph/list :image-graph/read :image-graph/new
                      :image-graph/delete-graph :image-graph/generate
                      :image-graph/set-cover :image-graph/delete-node
                      :image-graph/upload-sketch}})