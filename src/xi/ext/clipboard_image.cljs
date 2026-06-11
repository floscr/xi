(ns xi.ext.clipboard-image
  "Clipboard image extension — intercepts pasted clipboard image paths in
   input text and converts them to inline base64 images.

   Implemented as an event-hook on :input/submit: the hook rewrites the
   event text (removing file paths, adding [image] placeholders) and
   attaches the decoded images on the event's :images key. The input-submit
   handler merges these with any already-pending images."
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

(defn- transform-input
  "Event-hook: detect clipboard image paths, read as base64, attach to event."
  [event _state]
  (let [fs  (js/require "node:fs")
        text (:text event)
        matches (re-seq clipboard-pattern (or text ""))]
    (if-not (seq matches)
      event
      (let [images (atom [])
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
  {:id          :clipboard-image
   :event-hooks {:input/submit transform-input}})
