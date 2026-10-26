(ns xi.sandbox.url
  "java.net.URLEncoder / URLDecoder for every SCI sandbox (xi.sandbox.sci
   registers `encode` / `decode`), so babashka-style form encoding works in
   the clj tool and in node and web extensions alike. Pure JS: no Node API."
  (:require [clojure.string :as str]))

(defn form-encode
  "URLEncoder's form encoding, which bb uses: a space is `+`, and only
   `A-Za-z0-9.-*_` stay unescaped."
  [x]
  (-> (js/encodeURIComponent (if (keyword? x) (name x) (str x)))
      (str/replace "%20" "+")
      (str/replace #"[!'()~]" #(str "%" (.toUpperCase (.toString (.charCodeAt % 0) 16))))))

(defn form-decode
  "URLDecoder's inverse: `+` is a space; a malformed escape throws."
  [s]
  (js/decodeURIComponent (str/replace (str s) "+" "%20")))

(defn- utf-8-only
  "The statics take an optional charset; only UTF-8 exists."
  [f]
  (fn
    ([s] (f s))
    ([s charset]
     (when-not (re-matches #"(?i)utf-?8" (str charset))
       (throw (ex-info (str "Unsupported charset: " charset ", only UTF-8") {})))
     (f s))))

(def encode "URLEncoder/encode" (utf-8-only form-encode))
(def decode "URLDecoder/decode" (utf-8-only form-decode))
