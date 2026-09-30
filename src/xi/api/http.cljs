(ns xi.api.http
  "Rules-gated network access for user extensions: every request is a
   `{:tool :net :host … :command <url>}` decision — by default it asks, and
   `[a]lways` grants that extension the host for the session. Allow a host
   permanently with a rule, e.g.
     {:match {:tool :net :extension \"pushover\" :host \"api.pushover.net\"}
      :action {:type :allow}}"
  (:require [xi.api.core :as core]))

(defn- parse-url [url]
  (try (js/URL. (str url)) (catch :default _ nil)))

(defn url-encode
  "Percent-encode `s` for use as one URL component (query value, path segment)."
  [s]
  (js/encodeURIComponent (str s)))

(defn url-decode
  "Inverse of url-encode; `+` is read as a space, as in query strings. A
   malformed escape returns `s` unchanged."
  [s]
  (try (js/decodeURIComponent (.replaceAll (str s) "+" " "))
       (catch :default _ (str s))))

(defn fetch
  "(fetch ctx url) / (fetch ctx url {:method :headers :body :timeout-ms}) →
   Promise<{:status :ok? :url :headers {…} :body string}>. Only http(s) URLs.
   `:url` is the final URL after redirects; header names are lower-case.
   With `:timeout-ms` the request is aborted (the Promise rejects) once that
   long has passed without a complete response."
  ([ctx url] (fetch ctx url nil))
  ([ctx url {:keys [method headers body timeout-ms]}]
   (let [u (parse-url url)]
     (if-not (and u (#{"http:" "https:"} (.-protocol u)))
       (js/Promise.reject (ex-info (str "xi.api.http: not an http(s) URL: " url) {}))
       (-> (core/gate! ctx {:tool :net :host (.-hostname u) :command (str url)})
           (.then (fn [_]
                    (js/fetch (str url)
                              (clj->js (cond-> {:method (or method "GET")}
                                         headers    (assoc :headers headers)
                                         body       (assoc :body (str body))
                                         timeout-ms (assoc :signal (js/AbortSignal.timeout timeout-ms)))))))
           (.then (fn [^js res]
                    (-> (.text res)
                        (.then (fn [text]
                                 {:status  (.-status res)
                                  :ok?     (.-ok res)
                                  :url     (.-url res)
                                  :headers (into {} (map vec) (js/Array.from (.entries (.-headers res))))
                                  :body    text}))))))))))
