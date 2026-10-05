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

(deftest a-call-goes-through-the-rules
  (async done
    ;; no confirm! (no room/client) — the default :mcp ask is a refusal, and
    ;; the server is never reached
    (let [reached (atom false)]
      (with-redefs [ext-mcp/call-tool! (fn [& _] (reset! reached true) (js/Promise.resolve {}))]
        (-> (rejection (mcp/call (assoc (ctx :design) :get-state (fn [] {}))
                                 :chrome "list_pages" {}))
            (.then (fn [msg]
                     (is (re-find #"mcp chrome/list_pages needs approval" msg))
                     (is (not @reached))))
            (.finally done))))))

(deftest an-approved-call-reaches-the-server-with-meta
  (async done
    (let [seen (atom nil)
          asks (atom [])
          c    (assoc (ctx :design)
                      :get-state (fn [] {:connection {:clients {"c1" {:room-id "r1" :pid 4242
                                                                       :platform "tui"}}}})
                      :confirm! (fn [msg & _] (swap! asks conj msg) (js/Promise.resolve true)))]
      (with-redefs [ext-mcp/call-tool! (fn [server tool args meta-ctx]
                                         (reset! seen [server tool args meta-ctx])
                                         (js/Promise.resolve {:content [] :is-error false}))]
        (-> (mcp/call c :chrome "evaluate_script" {:function "() => 1"} {:room-id "r1"})
            (.then (fn [res]
                     (is (= {:content [] :is-error false} res))
                     (is (= ["chrome" "evaluate_script" {:function "() => 1"}
                             {:cwd "/proj" :room-id "r1" :client-pid 4242 :extension :design}]
                            @seen))
                     (is (re-find #"^\[extension design\] MCP tool call" (first @asks))
                         "the ask names the extension, not the agent")
                     (is (re-find #"Server: chrome" (first @asks)))))
            (.finally done))))))

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
