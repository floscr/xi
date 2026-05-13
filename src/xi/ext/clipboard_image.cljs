(ns xi.ext.clipboard-image
  "Clipboard Image Extension — intercepts pasted clipboard image paths
   and converts them to inline base64 images.

   Stock pi writes clipboard images to /tmp/pi-clipboard-<uuid>.png;
   this extension detects those paths, reads the file as base64,
   and sends the image inline so the model sees it directly."
  (:require [clojure.string :as str]))

(def ^:private clipboard-pattern
  #"/tmp/pi-clipboard-[0-9a-f-]+\.(png|jpg|jpeg|webp|gif)")

(defn- mime-type-for-ext [ext]
  (case (str/lower-case ext)
    "png"  "image/png"
    "jpg"  "image/jpeg"
    "jpeg" "image/jpeg"
    "webp" "image/webp"
    "gif"  "image/gif"
    "image/png"))

(defn transform-input
  "Transform hook for :input events.
   If text contains clipboard image paths, reads them as base64 and
   attaches them as inline images. Returns the (possibly modified) event."
  [event _state]
  (let [fs (js/require "node:fs")
        text (:text event)
        matches (re-seq clipboard-pattern text)]
    (if-not (seq matches)
      event
      (let [images (atom (vec (or (:images event) [])))
            new-text (atom text)]
        (doseq [[file-path ext] matches]
          (when (.existsSync fs file-path)
            (try
              (let [buffer (.readFileSync fs file-path)
                    base64 (.toString buffer "base64")]
                (swap! images conj {:data base64
                                    :media-type (mime-type-for-ext ext)})
                (swap! new-text str/replace file-path "[image]")
                (try (.unlinkSync fs file-path) (catch :default _)))
              (catch :default _ nil))))
        (if (seq @images)
          (let [t (str/trim @new-text)
                final-text (if (empty? t)
                             (let [n (count @images)]
                               (if (= 1 n) "[Pasted image]" (str "[Pasted " n " images]")))
                             t)]
            (assoc event :text final-text :images @images))
          event)))))

(def extension
  {:name "clipboard-image"
   :hooks {:input transform-input}})
