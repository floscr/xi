(ns xi.api.chrome
  "A headless Chrome for user extensions — for sites that block scripted HTTP
   or render client-side, where xi.api.http gets nothing useful.

   Opt-in twice over:
     - the extension declares the hosts it drives, in its extension map:
         :permissions {:chrome-driver {:hosts [\"amazon.de\" \"willhaben.at\"]}}
       A host also covers its subdomains. Without the declaration, or for a
       URL outside it, `visit` is refused before anything is asked.
     - each visit is a `{:tool :browser :host … :command <url>}` request to the
       rules engine. By default it asks, and `[a]lways` grants that extension
       the host for the session; allow it permanently with a rule, e.g.
         {:match {:tool :browser :extension \"shop\"} :action {:type :allow}}

   The browser itself can only reach the declared hosts (see xi.browser.chrome
   and xi.browser.proxy), runs on a throwaway profile, and is shared by that
   extension's visits only."
  (:require [xi.api.core :as core]
            [xi.browser.chrome :as chrome]
            [xi.browser.proxy :as proxy]))

(def ^:private DEFAULT_TIMEOUT_MS 15000)
(def ^:private MAX_TIMEOUT_MS 60000)

(defn- parse-url [url]
  (try (js/URL. (str url)) (catch :default _ nil)))

(defn visit
  "(visit ctx url {:wait \"js\" :eval \"js\" :timeout-ms n}) →
   Promise<{:url :ready? :value}>. Loads the https `url` in a fresh tab, polls
   the `:wait` JS expression until it is truthy (a positive number, non-empty
   string or true; default: the page finished loading) for up to `:timeout-ms`
   (default 15s, max 60s), then evaluates the `:eval` JS expression in the page.
   `:value` is its result as Clojure data (keyword keys; a returned Promise is
   awaited), `:url` the page's final URL, `:ready?` false when `:wait` timed
   out. Both expressions run in the page, not in xi."
  ([ctx url] (visit ctx url nil))
  ([ctx url {:keys [wait timeout-ms] :as opts}]
   (let [u     (parse-url url)
         hosts (:hosts (core/permission ctx :chrome-driver))]
     (cond
       (not (and u (= "https:" (.-protocol u))))
       (js/Promise.reject (ex-info (str "xi.api.chrome: not an https URL: " url) {}))

       (empty? hosts)
       (js/Promise.reject (ex-info (str "xi.api.chrome: declare the hosts first — "
                                        ":permissions {:chrome-driver {:hosts [...]}}")
                                   {}))

       (not (proxy/host-allowed? hosts (.-hostname u)))
       (js/Promise.reject (ex-info (str "xi.api.chrome: " (.-hostname u)
                                        " is not in this extension's :chrome-driver :hosts")
                                   {}))

       :else
       (-> (core/gate! ctx {:tool :browser :host (.-hostname u) :command (str url)})
           (.then (fn [_]
                    ;; the browser is the extension's: closed when it unmounts
                    (core/own! ctx ::browser #(chrome/close-extension! (:extension ctx)))
                    (chrome/visit! (:extension ctx) (set hosts) (str url)
                                   {:wait       wait
                                    :eval       (:eval opts)
                                    :timeout-ms (min MAX_TIMEOUT_MS
                                                     (or timeout-ms DEFAULT_TIMEOUT_MS))}))))))))
