(ns xi.api.chrome-test
  "xi.api.chrome refusals — each one is decided before a browser is launched."
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [xi.api.chrome :as chrome]
            [xi.api.core :as core]))

(use-fixtures :each
  {:before #(core/set-permissions! {:shop {:chrome-driver {:hosts ["amazon.de"]}}})
   :after  #(core/set-permissions! {})})

(defn- ctx
  "The ctx the loader would hand extension `id` (see xi.ext.user.guard)."
  [id]
  {:extension id :xi.api/token (core/issue-token! id)})

(defn- rejection [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

(deftest refuses-without-a-declaration
  (async done
    (-> (rejection (chrome/visit (ctx :other) "https://www.amazon.de/"))
        (.then #(is (re-find #"declare the hosts first" %)))
        (.finally done))))

(deftest refuses-a-ctx-naming-another-extension
  ;; :other relabels its own ctx as :shop to use :shop's declared hosts — the
  ;; token says who is really calling, so it is refused before any check
  (is (thrown-with-msg? js/Error #"issued to extension other, not shop"
                        (chrome/visit (assoc (ctx :other) :extension :shop)
                                      "https://www.amazon.de/")))
  (is (thrown-with-msg? js/Error #"not an extension ctx"
                        (chrome/visit {:extension :shop} "https://www.amazon.de/"))
      "a hand-built ctx has no token"))

(deftest refuses-hosts-outside-the-declaration
  (async done
    (-> (rejection (chrome/visit (ctx :shop) "https://evilamazon.de/"))
        (.then #(is (re-find #"evilamazon\.de is not in this extension's :chrome-driver :hosts" %)))
        (.finally done))))

(deftest refuses-non-https-urls
  (async done
    (let [shop (ctx :shop)]
      (-> (js/Promise.all
           #js [(rejection (chrome/visit shop "http://www.amazon.de/"))
                (rejection (chrome/visit shop "file:///etc/passwd"))
                (rejection (chrome/visit shop "not a url"))])
          (.then (fn [msgs] (is (every? #(re-find #"not an https URL" %) msgs))))
          (.finally done)))))

(deftest a-declared-host-still-goes-through-the-rules
  (async done
    ;; no confirm! (no room/client) — the default :browser ask is a refusal
    (-> (rejection (chrome/visit (assoc (ctx :shop) :get-state (fn [] {}))
                                 "https://www.amazon.de/s?k=kabel"))
        (.then #(is (re-find #"browser www\.amazon\.de needs approval" %)))
        (.finally done))))
