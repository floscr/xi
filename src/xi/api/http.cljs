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

(defn fetch
  "(fetch ctx url) / (fetch ctx url {:method :headers :body}) →
   Promise<{:status :ok? :headers {…} :body string}>. Only http(s) URLs."
  ([ctx url] (fetch ctx url nil))
  ([ctx url {:keys [method headers body]}]
   (let [u (parse-url url)]
     (if-not (and u (#{"http:" "https:"} (.-protocol u)))
       (js/Promise.reject (ex-info (str "xi.api.http: not an http(s) URL: " url) {}))
       (-> (core/gate! ctx {:tool :net :host (.-hostname u) :command (str url)})
           (.then (fn [_]
                    (js/fetch (str url)
                              (clj->js (cond-> {:method (or method "GET")}
                                         headers (assoc :headers headers)
                                         body    (assoc :body (str body)))))))
           (.then (fn [^js res]
                    (-> (.text res)
                        (.then (fn [text]
                                 {:status  (.-status res)
                                  :ok?     (.-ok res)
                                  :headers (into {} (map vec) (js/Array.from (.entries (.-headers res))))
                                  :body    text}))))))))))
