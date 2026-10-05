(ns xi.mcp.server
  "Minimal MCP server over stdio — the counterpart of xi.mcp.client, for
   writing MCP servers in ClojureScript that run under bun/node. No SDK,
   same as the client.

   Wire: newline-delimited JSON-RPC 2.0 on stdin/stdout. stdout carries
   protocol only; log to stderr.

   Methods:
     initialize                → {protocolVersion capabilities serverInfo}
     ping                      → {}
     tools/list                → {tools [...]}
     tools/call                → {content [...] isError bool}
     notifications/*           (no reply)
     anything else with an id  → JSON-RPC error -32601

   A tool that fails is a *result* with isError true (the model sees it and
   can react); a JSON-RPC error is reserved for protocol faults. That's the
   spec's split, and what modex / mcp-clj do too.

   A server is a map:
     {:name    \"chrome\"  :version \"0.1.0\"
      :tools   (fn [] → Promise<[{:name :description :inputSchema}]>) or a vector
      :call    (fn [tool-name args meta] → Promise<{:content [...] :is-error bool}>)}
   `args` and `meta` (the request's `_meta`) are Clojure maps with keyword keys
   for args and string keys for meta (`\"xi/clientPid\"` …)."
  (:require [clojure.string :as str]))

(def ^:private PROTOCOL_VERSION "2024-11-05")

(defn- ->promise [x]
  (if (instance? js/Promise x) x (js/Promise.resolve x)))

(defn- error-result [msg]
  {:content [{:type "text" :text msg}] :isError true})

(defn- tool-result
  "xi tool-result shape → MCP result shape."
  [{:keys [content is-error isError]}]
  {:content (or content [{:type "text" :text ""}])
   :isError (boolean (or is-error isError))})

(defn handle
  "Handle one decoded JSON-RPC message (a Clojure map, keyword keys) →
   Promise of the reply map, or of nil for a notification."
  [{:keys [name version tools call]} {:keys [id method params]}]
  (let [reply (fn [result] (when (some? id) {:jsonrpc "2.0" :id id :result result}))
        fail  (fn [code msg] (when (some? id)
                               {:jsonrpc "2.0" :id id :error {:code code :message msg}}))]
    (-> (case method
          "initialize"
          (js/Promise.resolve
           (reply {:protocolVersion (or (:protocolVersion params) PROTOCOL_VERSION)
                   :capabilities    {:tools {}}
                   :serverInfo      {:name (or name "xi-mcp") :version (or version "0.0.0")}}))

          "ping" (js/Promise.resolve (reply {}))

          "tools/list"
          (-> (->promise (if (fn? tools) (tools) tools))
              (.then (fn [ts] (reply {:tools (vec ts)}))))

          "tools/call"
          (let [tool (:name params)]
            (if (str/blank? (str tool))
              (js/Promise.resolve (fail -32602 "tools/call needs a tool name"))
              (-> (js/Promise.resolve nil)
                  (.then (fn [_] (call tool (or (:arguments params) {})
                                       (or (:_meta params) {}))))
                  (.then (fn [r] (reply (tool-result r))))
                  (.catch (fn [e] (reply (error-result (str tool " failed: "
                                                            (or (ex-message e) (str e))))))))))

          (js/Promise.resolve
           (if (str/starts-with? (str method) "notifications/")
             nil
             (fail -32601 (str "Method not found: " method)))))
        (.catch (fn [e] (fail -32603 (or (ex-message e) (str e))))))))

(defn- decode
  "One JSON line → a Clojure map: keyword keys, except inside `_meta` (its
   keys are namespaced strings like \"xi/clientPid\")."
  [line]
  (let [js-msg (js/JSON.parse line)
        meta   (some-> js-msg .-params (aget "_meta"))
        msg    (js->clj js-msg :keywordize-keys true)]
    (cond-> msg
      meta (assoc-in [:params :_meta] (js->clj meta)))))

(defn serve!
  "Run `server` on this process' stdin/stdout until stdin closes."
  [server]
  (let [buf      (atom "")
        pending  (atom 0)      ;; requests still being answered
        closing? (atom false)  ;; stdin ended: exit once pending drains
        done!    (fn [] (when (and @closing? (zero? @pending)) (js/process.exit 0)))
        write    (fn [m] (when m (.write js/process.stdout (str (js/JSON.stringify (clj->js m)) "\n"))))]
    (.setEncoding js/process.stdin "utf8")
    (.on js/process.stdin "data"
         (fn [chunk]
           (swap! buf str chunk)
           (loop []
             (let [b @buf idx (str/index-of b "\n")]
               (when idx
                 (reset! buf (subs b (inc idx)))
                 (let [line (str/trim (subs b 0 idx))]
                   (when (seq line)
                     (swap! pending inc)
                     (-> (js/Promise.resolve nil)
                         (.then (fn [_] (handle server (decode line))))
                         (.then write)
                         (.catch (fn [e]
                                   (write {:jsonrpc "2.0" :id nil
                                           :error {:code -32700 :message (str "Parse error: " (ex-message e))}})))
                         (.finally (fn [] (swap! pending dec) (done!))))))
                 (recur))))))
    ;; the client closed our stdin: finish what's in flight, then exit
    (.on js/process.stdin "end" (fn [] (reset! closing? true) (done!)))))
