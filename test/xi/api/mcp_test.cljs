(ns xi.api.mcp-test
  "xi.api.mcp — every call is a rules request first, and reaches the server
   with the caller's context as _meta."
  (:require [cljs.test :refer [deftest is async]]
            [xi.api.core :as core]
            [xi.api.mcp :as mcp]
            [xi.ext.mcp :as ext-mcp]))

(defn- ctx
  "The ctx the loader would hand extension `id` (see xi.ext.user.guard)."
  [id]
  {:extension id :xi.api/token (core/issue-token! id) :cwd "/proj"})

(defn- rejection [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

;; with-redefs restores as soon as its body returns, before a promise chain
;; runs, so the async stubs here are set! and restored when the chain settles.
(defn- with-call-tool [stub f]
  (let [orig ext-mcp/call-tool!]
    (set! ext-mcp/call-tool! stub)
    (-> (js/Promise.resolve nil) (.then f) (.finally #(set! ext-mcp/call-tool! orig)))))

(defn- with-call-extension-tool [stub f]
  (let [orig ext-mcp/call-extension-tool!]
    (set! ext-mcp/call-extension-tool! stub)
    (-> (js/Promise.resolve nil) (.then f) (.finally #(set! ext-mcp/call-extension-tool! orig)))))

(defn- unexpected [e] (is false (str "rejected: " (ex-message e))))

(deftest a-call-goes-through-the-rules
  (async done
    ;; no confirm! (no room/client) — the default :mcp ask is a refusal, and
    ;; the server is never reached
    (let [reached (atom false)]
      (-> (with-call-tool (fn [& _] (reset! reached true) (js/Promise.resolve {}))
            (fn [_] (rejection (mcp/call (assoc (ctx :design) :get-state (fn [] {}))
                                         :chrome "list_pages" {}))))
          (.then (fn [msg]
                   (is (re-find #"mcp chrome/list_pages needs approval" msg))
                   (is (not @reached))))
          (.finally done)))))

(deftest an-approved-call-reaches-the-server-with-meta
  (async done
    (let [seen (atom nil)
          asks (atom [])
          c    (assoc (ctx :design)
                      :get-state (fn [] {:connection {:clients {"c1" {:room-id "r1" :pid 4242
                                                                       :platform "tui"}}}})
                      :confirm! (fn [msg & _] (swap! asks conj msg) (js/Promise.resolve true)))]
      (-> (with-call-tool (fn [server tool args meta-ctx]
                            (reset! seen [server tool args meta-ctx])
                            (js/Promise.resolve {:content [] :is-error false}))
            (fn [_] (mcp/call c :chrome "evaluate_script" {:function "() => 1"} {:room-id "r1"})))
          (.then (fn [res]
                   (is (= {:content [] :is-error false} res))
                   (is (= ["chrome" "evaluate_script" {:function "() => 1"}
                           {:cwd "/proj" :room-id "r1" :client-pid 4242 :extension :design}]
                          @seen))
                   (is (re-find #"^\[extension design\] MCP tool call" (first @asks))
                       "the ask names the extension, not the agent")
                   (is (re-find #"Server: chrome" (first @asks)))))
          (.catch unexpected)
          (.finally done)))))

(deftest refuses-a-forged-ctx-and-bad-args
  (async done
    (-> (js/Promise.all
         #js [(rejection (mcp/call {:extension :design} :chrome "list_pages" {}))
              (rejection (mcp/call (ctx :design) :chrome "list_pages" "nope"))
              (rejection (mcp/call (ctx :design) "" "list_pages" {}))])
        (.then (fn [[forged bad-args no-server]]
                 (is (re-find #"not an extension ctx" forged))
                 (is (re-find #"args must be a map" bad-args))
                 (is (re-find #"needs a server and a tool" no-server))))
        (.finally done))))

(deftest unknown-servers-reject
  (async done
    (-> (rejection (ext-mcp/call-tool! :no-such-server "x" {} {}))
        (.then #(is (re-find #"not configured" %)))
        (.finally done))))

(deftest an-extension-calls-its-own-server-by-name
  (async done
    (ext-mcp/set-extension-servers! :shop {:browser {:command "npx" :args ["pkg@1"]}})
    (let [seen (atom nil)
          c    (assoc (ctx :shop) :get-state (fn [] {})
                      :confirm! (fn [& _] (js/Promise.resolve true)))]
      (-> (with-call-extension-tool (fn [caller id tool _args _meta]
                                      (reset! seen [caller id tool])
                                      (js/Promise.resolve {:content [] :is-error false}))
            (fn [_] (mcp/call c :browser "navigate_page" {:url "https://x"})))
          (.then (fn [_]
                   (is (= [:shop "shop/browser" "navigate_page"] @seen)
                       "routed to the extension's own server, as <ext>/<name>")))
          (.catch unexpected)
          (.finally (fn []
                      (ext-mcp/clear-extension-servers! :shop)
                      (done)))))))

(deftest another-extensions-server-is-off-limits
  (async done
    (ext-mcp/set-extension-servers! :shop {:browser {:command "npx"}})
    (-> (rejection (ext-mcp/call-extension-tool! :thief "shop/browser" "x" {} {}))
        (.then #(is (re-find #"belongs to another extension" %)))
        (.finally (fn []
                    (ext-mcp/clear-extension-servers! :shop)
                    (done))))))
