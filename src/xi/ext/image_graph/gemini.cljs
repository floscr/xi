(ns xi.ext.image-graph.gemini
  "Gemini (\"Nano Banana\") image generation + sketch description for the
   image-graph extension. ClojureScript port of deckbuilder's editor.gemini —
   uses js/fetch and pulls the API key from ~/.config/xi/ext/image-graph.env
   (or a GEMINI_API_KEY export) via xi.ext.config."
  (:require [clojure.string :as str]
            [xi.ext.config :as config]))

;; Model id (client-facing) -> Gemini API model name.
(def models
  {"nano-banana"     "gemini-2.5-flash-image"
   "nano-banana-pro" "gemini-3-pro-image-preview"})

;; USD pricing (Google AI dev rates, late 2025): flat price per output image
;; plus per-1M input tokens. Estimate only.
(def prices
  {"nano-banana"     {:image 0.039 :input-per-m 0.30}
   "nano-banana-pro" {:image 0.134 :input-per-m 2.0}})

;; Cheap vision->text model used to describe a sketch's composition.
(def describe-model "gemini-flash-lite-latest")

(def sketch-describe-prompt
  (str "This is a rough composition sketch (rough lines only). In 2–4 sentences, "
       "describe ONLY the composition, concretely enough that an artist could "
       "reproduce the same layout in a completely different style: the main subject "
       "and its pose/gesture, the camera framing and angle, the position and "
       "orientation of the key objects/props, and the direction of any motion or "
       "action lines. Do NOT mention that it is a sketch, and do NOT describe line "
       "quality, drawing style, shading, or colors — layout and pose only."))

(def sketch-composition-prefix
  (str "\n\nCOMPOSITION TO FOLLOW (from a rough layout sketch — match this arrangement, "
       "pose, and framing, but invent all the detail yourself as a fresh, fully "
       "realized illustration; there is NO sketch to copy): "))

(defn- api-key []
  (config/get-value :image-graph "GEMINI_API_KEY"))

(defn- post-generate
  "POST to Gemini generateContent. `parts` is a vector of clj maps
   ({:text ...} / {:inlineData {:mimeType ... :data <base64>}}). Returns a
   promise of the parsed response (clj map, keywordized), or rejects."
  [model-name parts]
  (let [k (api-key)]
    (if-not k
      (js/Promise.reject
       (js/Error. "GEMINI_API_KEY not set (put it in ~/.config/xi/ext/image-graph.env)"))
      (-> (js/fetch (str "https://generativelanguage.googleapis.com/v1beta/models/"
                         model-name ":generateContent")
                    #js {:method  "POST"
                         :headers #js {"x-goog-api-key" k
                                       "Content-Type"   "application/json"}
                         :body    (js/JSON.stringify
                                   (clj->js {:contents [{:parts parts}]}))})
          (.then (fn [^js res]
                   (-> (.text res)
                       (.then (fn [body]
                                (if (and (>= (.-status res) 200) (<= (.-status res) 299))
                                  (js->clj (js/JSON.parse body) :keywordize-keys true)
                                  (throw (js/Error. (str "Gemini " (.-status res) ": " body)))))))))))))

(defn generate
  "Generate one image with Gemini. `ref-bytes` (a Buffer of the parent image,
   optional) is sent inline before the instruction — Nano Banana's native edit
   mode (a fork). Returns a promise of
   {:png <Buffer> :mime <str> :usage {...} :cost <usd>}."
  [{:keys [prompt ref-bytes ref-mime model]}]
  (let [model-name (get models model (get models "nano-banana"))
        parts (cond-> []
                (and ref-bytes (pos? (.-length ref-bytes)))
                (conj {:inlineData {:mimeType (or ref-mime "image/png")
                                    :data     (.toString ref-bytes "base64")}})
                true (conj {:text prompt}))]
    (-> (post-generate model-name parts)
        (.then (fn [data]
                 (let [out-parts (get-in data [:candidates 0 :content :parts] [])
                       img       (some :inlineData out-parts)]
                   (if-not (:data img)
                     (let [t (->> out-parts (keep :text) (str/join " "))]
                       (throw (js/Error. (str "Gemini returned no image"
                                              (when (seq t) (str ": " t))))))
                     (let [um            (:usageMetadata data)
                           prompt-tokens (or (:promptTokenCount um) 0)
                           output-tokens (or (:candidatesTokenCount um) 0)
                           price         (get prices model (get prices "nano-banana"))
                           cost          (+ (:image price)
                                            (* (/ prompt-tokens 1e6) (:input-per-m price)))]
                       {:png   (js/Buffer.from (:data img) "base64")
                        :mime  (or (:mimeType img) "image/png")
                        :usage {:promptTokens prompt-tokens :outputTokens output-tokens}
                        :cost  (/ (js/Math.round (* cost 1e6)) 1e6)}))))))))

(defn describe
  "Vision->text: read a sketch and return a plain-language description of ONLY
   its composition (see sketch-describe-prompt). Returns a promise of a string."
  [image-bytes image-mime]
  (let [parts [{:inlineData {:mimeType (or image-mime "image/png")
                             :data     (.toString image-bytes "base64")}}
               {:text sketch-describe-prompt}]]
    (-> (post-generate describe-model parts)
        (.then (fn [data]
                 (let [out-parts (get-in data [:candidates 0 :content :parts] [])
                       text      (->> out-parts (keep :text) (str/join " ") str/trim)]
                   (if (str/blank? text)
                     (throw (js/Error. "Gemini returned no composition description"))
                     text)))))))