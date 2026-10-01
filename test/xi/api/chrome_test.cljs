(ns xi.api.chrome-test
  "xi.api.chrome refusals — each one is decided before a browser is launched."
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [xi.api.chrome :as chrome]
            [xi.api.core :as core]))

(use-fixtures :each
  {:before #(core/set-permissions! {:shop {:chrome-driver {:hosts ["amazon.de"]}}})
   :after  #(core/set-permissions! {})})

(defn- rejection [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

(deftest refuses-without-a-declaration
  (async done
    (-> (rejection (chrome/visit {:extension :other} "https://www.amazon.de/"))
        (.then #(is (re-find #"declare the hosts first" %)))
        (.finally done))))

(deftest refuses-hosts-outside-the-declaration
  (async done
    (-> (rejection (chrome/visit {:extension :shop} "https://evilamazon.de/"))
        (.then #(is (re-find #"evilamazon\.de is not in this extension's :chrome-driver :hosts" %)))
        (.finally done))))

(deftest refuses-non-https-urls
  (async done
    (-> (js/Promise.all
         #js [(rejection (chrome/visit {:extension :shop} "http://www.amazon.de/"))
              (rejection (chrome/visit {:extension :shop} "file:///etc/passwd"))
              (rejection (chrome/visit {:extension :shop} "not a url"))])
        (.then (fn [msgs] (is (every? #(re-find #"not an https URL" %) msgs))))
        (.finally done))))

(deftest a-declared-host-still-goes-through-the-rules
  (async done
    ;; no confirm! (no room/client) — the default :browser ask is a refusal
    (-> (rejection (chrome/visit {:extension :shop :get-state (fn [] {})}
                                 "https://www.amazon.de/s?k=kabel"))
        (.then #(is (re-find #"browser www\.amazon\.de needs approval" %)))
        (.finally done))))
