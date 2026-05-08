(ns xi.ext.web
  "Web fetch extension — retrieve and clean web content."
  (:require [clojure.string :as str]))

(def ^:private MAX_LENGTH 50000)

(defn- strip-html-tags
  "Basic HTML tag stripping. Not perfect but good enough for readability."
  [html]
  (-> html
      (str/replace #"<script[^>]*>[\s\S]*?</script>" "")
      (str/replace #"<style[^>]*>[\s\S]*?</style>" "")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"\s{3,}" "\n\n")
      str/trim))

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "\n\n[truncated at " max-len " chars]")
    s))

(def extension
  {:name "web"
   :tools [{:name "fetch"
            :description "Fetch a URL and return its content. HTML is cleaned to readable text. JSON is returned formatted."
            :input_schema {:type "object"
                           :properties {:url {:type "string" :description "URL to fetch"}
                                        :raw {:type "boolean" :description "Return raw HTML without cleaning"}}
                           :required ["url"]}
            :execute (fn [{:keys [url raw]}]
                       (-> (js/fetch url
                                     #js {:headers #js {"User-Agent" "Xi/1.0"
                                                        "Accept" "text/html,application/json,*/*"}
                                          :redirect "follow"})
                           (.then (fn [resp]
                                    (if-not (.-ok resp)
                                      {:content [{:type "text" :text (str "HTTP " (.-status resp))}]
                                       :is-error true}
                                      (let [ct (or (.get (.-headers resp) "content-type") "")]
                                        (if (str/includes? ct "json")
                                          (-> (.json resp)
                                              (.then (fn [data]
                                                       {:content [{:type "text"
                                                                    :text (truncate (js/JSON.stringify data nil 2) MAX_LENGTH)}]})))
                                          (-> (.text resp)
                                              (.then (fn [body]
                                                       (let [text (if raw body (strip-html-tags body))]
                                                         {:content [{:type "text"
                                                                      :text (truncate text MAX_LENGTH)}]})))))))))
                           (.catch (fn [e]
                                     {:content [{:type "text" :text (str "Fetch error: " (.-message e))}]
                                      :is-error true}))))}]})
