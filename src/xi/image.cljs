(ns xi.image
  "Image utilities — resize images to fit within API limits.
   Uses ImageMagick (magick/convert) which is available on NixOS.
   Falls back gracefully if ImageMagick is not installed."
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [clojure.string :as str]))

(def ^:private MAX_DIMENSION
  "Maximum width or height in pixels before resizing."
  1568)

(defn- tmp-path [ext]
  (let [id (.toString (.randomBytes crypto 8) "hex")]
    (.join path (.tmpdir os) (str "xi-img-" id "." ext))))

(defn- ext-for-mime [media-type]
  (case media-type
    "image/png"  "png"
    "image/jpeg" "jpg"
    "image/webp" "webp"
    "image/gif"  "gif"
    "png"))

(defn- run-sync
  "Run a command synchronously, return {:ok bool :stdout Buffer}."
  [cmd args]
  (try
    (let [result (child-process/spawnSync cmd (clj->js args)
                   #js {:timeout 10000
                        :maxBuffer (* 50 1024 1024)})]
      (if (and (some? (.-status result)) (zero? (.-status result)))
        {:ok true :stdout (.-stdout result)}
        {:ok false}))
    (catch :default _e {:ok false})))

(defn- identify-dimensions
  "Get [width height] of an image file using ImageMagick."
  [file-path]
  (let [result (run-sync "magick" ["identify" "-format" "%w %h" (str file-path "[0]")])]
    (when (:ok result)
      (let [parts (str/split (str/trim (.toString (:stdout result) "utf-8")) #"\s+")]
        (when (= 2 (count parts))
          [(js/parseInt (first parts) 10)
           (js/parseInt (second parts) 10)])))))

(defn- needs-resize?
  "Check if dimensions exceed the max."
  [[w h]]
  (or (> w MAX_DIMENSION) (> h MAX_DIMENSION)))

(defn- resize-file!
  "Resize an image file in-place to fit within MAX_DIMENSION, preserving aspect ratio."
  [input-path output-path]
  (let [geometry (str MAX_DIMENSION "x" MAX_DIMENSION ">")
        result (run-sync "magick"
                 [input-path "-resize" geometry "-quality" "85" output-path])]
    (:ok result)))

(defn ensure-within-limits
  "Ensure an image map {:data base64 :media-type mime} fits within API pixel limits.
   Returns the (possibly resized) image map. If resize fails, returns original."
  [{:keys [data media-type] :as image}]
  (let [ext (ext-for-mime media-type)
        in-path (tmp-path ext)]
    (try
      ;; Write base64 to temp file
      (fs/writeFileSync in-path (js/Buffer.from data "base64"))
      ;; Check dimensions
      (let [dims (identify-dimensions in-path)]
        (if (and dims (needs-resize? dims))
          ;; Resize needed
          (let [out-path (tmp-path ext)
                ;; Always output as PNG for lossless quality (SDK accepts it)
                out-png (tmp-path "png")]
            (if (resize-file! in-path out-png)
              (let [buffer (fs/readFileSync out-png)
                    resized {:data (.toString buffer "base64")
                             :media-type "image/png"}]
                ;; Cleanup
                (try (fs/unlinkSync out-png) (catch :default _))
                (try (fs/unlinkSync in-path) (catch :default _))
                resized)
              ;; Resize failed — return original
              (do (try (fs/unlinkSync in-path) (catch :default _))
                  image)))
          ;; No resize needed
          (do (try (fs/unlinkSync in-path) (catch :default _))
              image)))
      (catch :default _e
        (try (fs/unlinkSync in-path) (catch :default _))
        image))))

(defn ensure-content-images-within-limits
  "Resize any oversized image blocks inside a tool-result `content` vector so
   a tool can never poison the session with an image the API rejects (max
   8000px per dimension — e.g. a full-page chrome take_screenshot). Handles
   both the MCP block shape {:type \"image\" :data .. :mimeType ..} and the
   API shape {:type \"image\" :source {:media_type .. :data ..}}. Non-image
   blocks and non-sequential content pass through untouched."
  [content]
  (if (sequential? content)
    (mapv (fn [block]
            (if-not (and (map? block) (= "image" (:type block)))
              block
              (cond
                (:data block)
                (let [{:keys [data media-type]}
                      (ensure-within-limits {:data (:data block)
                                             :media-type (or (:mimeType block) "image/png")})]
                  (assoc block :data data :mimeType media-type))

                (some-> block :source :data)
                (let [src (:source block)
                      {:keys [data media-type]}
                      (ensure-within-limits {:data (:data src)
                                             :media-type (or (:media_type src) "image/png")})]
                  (assoc block :source (assoc src :data data :media_type media-type)))

                :else block)))
          content)
    content))

(defn process-images
  "Process a seq of image maps, resizing any that exceed limits.
   Returns a vec of processed image maps."
  [images]
  (when (seq images)
    (mapv ensure-within-limits images)))

(defn- uploads-dir []
  (.join path (.homedir os) ".config" "xi" "uploads"))

(defn persist-image!
  "Write an image map {:data base64 :media-type mime} to
   ~/.config/xi/uploads/<sha256>.<ext>, deduped by content hash. Returns the
   absolute file path so the agent's file tools and subagents can reach the
   image on disk, or nil on failure."
  [{:keys [data media-type]}]
  (try
    (let [buf  (js/Buffer.from data "base64")
          sha  (-> (.createHash crypto "sha256")
                   (.update buf)
                   (.digest "hex"))
          ext  (ext-for-mime media-type)
          dir  (uploads-dir)
          file (.join path dir (str sha "." ext))]
      (.mkdirSync fs dir #js {:recursive true})
      (when-not (.existsSync fs file)
        (fs/writeFileSync file buf))
      file)
    (catch :default _e nil)))

(defn- sanitize-name
  "Make an uploaded filename safe to use as a leaf path component: strip any
   directory separators / NULs and leading dots."
  [name]
  (-> (or (not-empty name) "file")
      (str/replace #"[\\/\x00]" "_")
      (str/replace #"^\.+" "_")))

(defn persist-file!
  "Write an arbitrary attachment {:data base64 :name filename} to
   ~/.config/xi/uploads/<sha16>-<name>, deduped by content hash. Preserves the
   original filename (and extension) so the agent's file tools and subagents can
   recognise and read it by path. Returns the absolute file path, or nil."
  [{:keys [data name]}]
  (try
    (let [buf  (js/Buffer.from data "base64")
          sha  (-> (.createHash crypto "sha256")
                   (.update buf)
                   (.digest "hex"))
          base (sanitize-name name)
          dir  (uploads-dir)
          file (.join path dir (str (subs sha 0 16) "-" base))]
      (.mkdirSync fs dir #js {:recursive true})
      (when-not (.existsSync fs file)
        (fs/writeFileSync file buf))
      file)
    (catch :default _e nil)))
