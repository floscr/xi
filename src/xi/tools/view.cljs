(ns xi.tools.view
  "View-image tool — read an image file and return it as an image content
   block so the model can see its visual contents."
  (:require [xi.tools.fs :as tfs]
            [xi.image :as image]
            [clojure.string :as str]
            ["node:fs" :as fs]))

(defn image-mime
  "Map a file path's extension to a supported image MIME type, or nil."
  [path]
  (case (str/lower-case (or (last (str/split path #"\.")) ""))
    "png"          "image/png"
    ("jpg" "jpeg") "image/jpeg"
    "webp"         "image/webp"
    "gif"          "image/gif"
    nil))

(defn execute
  "Read an image file and return it as an image content block (resized to fit
   the API's pixel limits). Errors when the file is missing or the extension
   isn't a supported raster image type."
  [{:keys [path]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)]
      (cond
        (nil? (image-mime resolved))
        {:content [{:type "text"
                    :text (str "Unsupported image type: " path
                               ". Supported extensions: png, jpg, jpeg, webp, gif.")}]
         :is-error true}

        (not (tfs/file-exists? resolved))
        {:content [{:type "text" :text (str "File not found: " path)}]
         :is-error true}

        :else
        (let [mime (image-mime resolved)
              data (.toString (fs/readFileSync resolved) "base64")
              processed (image/ensure-within-limits {:data data :media-type mime})]
          {:content [{:type "text" :text (str "Viewed image: " path)}
                     {:type "image"
                      :data (:data processed)
                      :mimeType (:media-type processed)}]})))
    (catch :default e
      {:content [{:type "text" :text (str "Error viewing image: " (.-message e))}]
       :is-error true})))

(def definition
  {:name "view_image"
   :description "View an image file (screenshot, diagram, photo, etc.) so you can see its visual contents. Supports png, jpg/jpeg, webp, and gif; large images are automatically resized to fit."
   :input_schema {:type "object"
                  :properties {:path {:type "string" :description "Path to the image file"}}
                  :required ["path"]}})
