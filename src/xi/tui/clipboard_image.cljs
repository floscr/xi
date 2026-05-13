(ns xi.tui.clipboard-image
  "Read images from the system clipboard in the TUI.
   Uses wl-paste (Wayland) or xclip (X11) to read image data
   and returns base64-encoded images suitable for the API."
  (:require ["node:child_process" :as child-process]
            [clojure.string :as str]))

(def ^:private SUPPORTED_MIME_TYPES
  #{"image/png" "image/jpeg" "image/webp" "image/gif"})

(defn- wayland-session?
  "Check if running under Wayland."
  []
  (or (some? (aget js/process.env "WAYLAND_DISPLAY"))
      (= "wayland" (aget js/process.env "XDG_SESSION_TYPE"))))

(defn- run-command
  "Run a command synchronously. Returns {:ok true :stdout Buffer} or {:ok false}."
  [cmd args & [{:keys [timeout-ms]}]]
  (try
    (let [result (child-process/spawnSync
                  cmd (clj->js args)
                  #js {:timeout (or timeout-ms 3000)
                       :maxBuffer (* 50 1024 1024)})]
      (if (and (zero? (.-status result)) (.-stdout result))
        {:ok true :stdout (.-stdout result)}
        {:ok false}))
    (catch :default _e
      {:ok false})))

(defn- select-preferred-mime
  "From a list of available MIME types, pick the best image type."
  [types]
  (let [preferred ["image/png" "image/jpeg" "image/webp" "image/gif"]]
    (or (some (fn [p] (when (some #(str/starts-with? % p) types) p))
              preferred)
        ;; Fallback: any image type
        (some (fn [t] (when (str/starts-with? t "image/") t)) types))))

(defn- read-via-wl-paste
  "Read clipboard image via wl-paste (Wayland)."
  []
  (let [list-result (run-command "wl-paste" ["--list-types"] {:timeout-ms 1000})]
    (when (:ok list-result)
      (let [types (-> (.toString (:stdout list-result) "utf-8")
                      (str/split #"\r?\n")
                      (->> (map str/trim)
                           (filter seq)))
            mime (select-preferred-mime types)]
        (when mime
          (let [data-result (run-command "wl-paste" ["--type" mime "--no-newline"])]
            (when (and (:ok data-result) (pos? (.-length (:stdout data-result))))
              {:bytes (:stdout data-result)
               :media-type (first (str/split mime #";"))})))))))

(defn- read-via-xclip
  "Read clipboard image via xclip (X11)."
  []
  (let [targets-result (run-command "xclip" ["-selection" "clipboard" "-t" "TARGETS" "-o"]
                                    {:timeout-ms 1000})
        candidate-types (when (:ok targets-result)
                          (-> (.toString (:stdout targets-result) "utf-8")
                              (str/split #"\r?\n")
                              (->> (map str/trim) (filter seq))))
        preferred (when (seq candidate-types) (select-preferred-mime candidate-types))
        try-types (if preferred
                    (cons preferred ["image/png" "image/jpeg" "image/webp" "image/gif"])
                    ["image/png" "image/jpeg" "image/webp" "image/gif"])]
    (some (fn [mime]
            (let [result (run-command "xclip" ["-selection" "clipboard" "-t" mime "-o"])]
              (when (and (:ok result) (pos? (.-length (:stdout result))))
                {:bytes (:stdout result)
                 :media-type (first (str/split mime #";"))})))
          try-types)))

(defn read-clipboard-image
  "Read an image from the system clipboard.
   Returns {:data base64-string :media-type mime-type} or nil."
  []
  (let [image (if (wayland-session?)
                (or (read-via-wl-paste) (read-via-xclip))
                (read-via-xclip))]
    (when image
      {:data (.toString (:bytes image) "base64")
       :media-type (:media-type image)})))
