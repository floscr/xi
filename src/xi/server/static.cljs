(ns xi.server.static
  "The web client's files (resources/public) over the Bun server.

   Every response is `Cache-Control: no-cache` with an ETag and Last-Modified:
   the browser keeps the file but revalidates on each load, and an unchanged
   asset is answered with a 304 instead of the bundle. That keeps the PWA in
   sync with builds (the dev watch rewrites main.js on every save; iOS/WebKit
   caches heuristically without a Cache-Control) while a reopen on mobile,
   which reloads the evicted page, no longer re-downloads megabytes it has.
   Compressible assets go out gzipped, encoded once per file version and kept
   in memory."
  (:require [clojure.string :as str]))

(def ^:private node-path (js/require "node:path"))

(def compressible-exts
  #{"js" "css" "html" "json" "webmanifest" "svg" "wasm" "map" "txt" "edn"})

;; Below this gzip costs more than it saves.
(def ^:private min-gzip-bytes 1024)

;; Decoded bundles are large (the dev main.js is 11 MB); the cap bounds the
;; gzipped copies kept, least recently served out first.
(def ^:private max-gzip-entries 32)

(defn- ext-of [file]
  (some-> (re-find #"\.([a-zA-Z0-9]+)$" file) second str/lower-case))

(defn compressible? [file size]
  (and (contains? compressible-exts (ext-of file))
       (>= size min-gzip-bytes)))

(defn accepts-gzip?
  "Does an Accept-Encoding header value allow gzip (a `;q=0` refuses it)?"
  [accept-encoding]
  (boolean
   (some (fn [part]
           (let [[coding & params] (map str/trim (str/split part #";"))]
             (and (= "gzip" (str/lower-case coding))
                  (not (some #(re-matches #"(?i)q=0(\.0+)?" %) params)))))
         (str/split (or accept-encoding "") #","))))

(defn etag
  "Weak validator of one file version; the gzipped representation gets its
   own, as the spec asks."
  [size mtime-ms gzip?]
  (str "W/\"" (.toString size 16) "-" (.toString mtime-ms 16)
       (when gzip? "-gz") "\""))

(defn- opaque-tag [tag]
  (str/replace-first (str/trim tag) #"^W/" ""))

(defn not-modified?
  "Does the request still hold the file version `etag` / `mtime-ms` describe?
   If-None-Match wins when present (RFC 9110 §13.1.3); If-Modified-Since
   compares at the header's one-second resolution."
  [{:keys [if-none-match if-modified-since]} etag mtime-ms]
  (cond
    if-none-match
    (boolean (some #(or (= "*" (str/trim %))
                        (= (opaque-tag %) (opaque-tag etag)))
                   (str/split if-none-match #",")))

    if-modified-since
    (let [since (js/Date.parse if-modified-since)]
      (and (not (js/isNaN since))
           (<= (quot mtime-ms 1000) (quot since 1000))))

    :else false))

(defonce ^:private gzip-cache (js/Map.))

(defn- gzipped
  "Promise of `file`'s gzipped bytes; cached by path for as long as size and
   mtime match. The Map iterates in insertion order, so re-inserting a hit
   makes it the most recent and the first key is the eviction candidate."
  [full ^js file size mtime]
  (let [hit (.get gzip-cache full)]
    (if (and hit (= size (:size hit)) (= mtime (:mtime hit)))
      (do (.delete gzip-cache full)
          (.set gzip-cache full hit)
          (js/Promise.resolve (:body hit)))
      (-> (.arrayBuffer file)
          (.then (fn [buf]
                   (let [body (js/Bun.gzipSync (js/Uint8Array. buf))]
                     (.delete gzip-cache full)
                     (when (>= (.-size gzip-cache) max-gzip-entries)
                       (.delete gzip-cache (.-value (.next (.keys gzip-cache)))))
                     (.set gzip-cache full {:size size :mtime mtime :body body})
                     body)))))))

(defn- respond
  "200 with the file (gzipped when the client accepts it and it pays off), or
   304 when the request's validators match it."
  [full ^js req]
  (let [^js headers (.-headers req)
        ^js file  (js/Bun.file full)
        size      (.-size file)
        mtime     (js/Math.floor (.-lastModified file))
        gzip?     (and (compressible? full size)
                       (accepts-gzip? (.get headers "accept-encoding")))
        tag       (etag size mtime gzip?)
        base      #js {"Cache-Control" "no-cache"
                       "ETag"          tag
                       "Last-Modified" (.toUTCString (js/Date. mtime))
                       "Vary"          "Accept-Encoding"}]
    (cond
      (not-modified? {:if-none-match     (.get headers "if-none-match")
                      :if-modified-since (.get headers "if-modified-since")}
                     tag mtime)
      (js/Promise.resolve (js/Response. nil #js {:status 304 :headers base}))

      gzip?
      (.then (gzipped full file size mtime)
             (fn [body]
               (js/Response. body #js {:status  200
                                       :headers (doto base
                                                  (aset "Content-Encoding" "gzip")
                                                  (aset "Content-Type" (.-type file)))})))

      :else
      (js/Promise.resolve (js/Response. file #js {:status 200 :headers base})))))

(defn serve
  "Serve a file from public-dir, SPA-falling back to index.html for
   extensionless router paths. `icon-variant` (\"personal\", \"green\") picks
   the favicon served at /apple-touch-icon.png."
  [public-dir ^js req icon-variant]
  (let [url      (js/URL. (.-url req))
        pathname (js/decodeURIComponent (.-pathname url))
        rel      (if (= "/" pathname) "index.html" (.replace pathname #"^/+" ""))
        rel      (if (and (= rel "apple-touch-icon.png")
                          (contains? #{"personal" "green"} icon-variant))
                   (str "apple-touch-icon-" icon-variant ".png")
                   rel)
        ;; Normalize + contain to public-dir (no path traversal)
        full     (.normalize node-path (.join node-path public-dir rel))
        index    (.join node-path public-dir "index.html")
        has-ext? (re-find #"\.[a-zA-Z0-9]+$" rel)
        ;; SPA route prefixes — always fall back to index.html
        spa-route? (re-find #"^(chat)(/|$)" rel)]
    (if-not (.startsWith full public-dir)
      (js/Promise.resolve (js/Response. "Forbidden" #js {:status 403}))
      (-> (.exists (js/Bun.file full))
          (.then (fn [exists?]
                   (cond
                     exists?              (respond full req)
                     (or spa-route?
                         (not has-ext?))  (respond index req)
                     :else               (js/Response. "Not found" #js {:status 404}))))))))
