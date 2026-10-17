(ns xi.ext.clj-bytes
  "JVM byte shims for the clj tool's sandbox (xi.ext.clj), so babashka-style
   `(String. (.decode (java.util.Base64/getDecoder) s))` works. A byte array
   is a JS array of signed bytes (-128…127), like a Java byte[]."
  (:require [clojure.string :as str]
            [xi.sandbox.sci :as sandbox]))

(defn ->buffer [x]
  (cond
    (string? x)     (js/Buffer.from x "utf8")
    (array? x)      (js/Buffer.from (js/Uint8Array.from x))
    (sequential? x) (js/Buffer.from (js/Uint8Array.from (into-array x)))
    :else (throw (ex-info (str "Not a byte array: " (pr-str x)) {}))))

(defn ->bytes [^js buf]
  (js/Array.from (js/Int8Array. (.-buffer buf) (.-byteOffset buf) (.-length buf))))

;; Node's charset names are case-insensitive: "UTF-8", "latin1", "ascii", …
(defn- string-ctor
  ([] "")
  ([x] (if (string? x) x (.toString (->buffer x) "utf8")))
  ([x charset] (.toString (->buffer x) (str/lower-case charset))))

(defn- get-bytes [s] (->bytes (js/Buffer.from s "utf8")))

;; Lenient where Java's decoder throws: Node skips whitespace and accepts the
;; URL-safe alphabet too.
(deftype Decoder []
  Object
  (decode [_ x] (->bytes (js/Buffer.from (if (string? x) x (.toString (->buffer x) "latin1")) "base64"))))

(deftype Encoder []
  Object
  (encodeToString [_ x] (.toString (->buffer x) "base64")))

(def ^:private base64-statics
  ;; SCI keys instance interop on the constructor's name; pin it (advanced
  ;; builds mangle it) to the :classes keys below.
  (let [_ (js/Object.defineProperty Decoder "name" #js {:value "Base64$Decoder"})
        _ (js/Object.defineProperty Encoder "name" #js {:value "Base64$Encoder"})
        decoder (Decoder.)
        encoder (Encoder.)]
    (sandbox/null-proto {:getDecoder (constantly decoder)
                         :getEncoder (constantly encoder)})))

(def classes
  "`:classes` entries for the sandbox ctx. Registering `String` also turns on
   instance interop for strings (`(.toUpperCase s)`)."
  (let [string {:class            (sandbox/null-proto {})
                ;; SCI calls a :constructor with `new`, which drops a primitive
                ;; result; a symbol carrying :sci.impl/constructor (its record
                ;; path) is called plainly.
                :constructor      (with-meta 'String {:sci.impl/constructor string-ctor})
                :instance-methods {'getBytes get-bytes}}]
    {'String           string
     'java.lang.String string
     'Base64           {:class base64-statics}
     'java.util.Base64 {:class base64-statics}
     'Base64$Decoder   {:class (sandbox/null-proto {})}
     'Base64$Encoder   {:class (sandbox/null-proto {})}}))
