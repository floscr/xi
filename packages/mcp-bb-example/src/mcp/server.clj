(ns mcp.server
  "A minimal MCP server over stdio for babashka, no dependencies. The same
   shape as xi.mcp.server (ClojureScript):

     newline-delimited JSON-RPC 2.0 on stdin/stdout, stdout carries protocol
     only (log to stderr)
     initialize · ping · tools/list · tools/call · notifications/* (no reply)
     a failing tool is a result with isError true; JSON-RPC errors are for
     protocol faults (unknown method -32601, bad params -32602)

   A tool is data: {:name :description :inputSchema :handler}, where the
   handler is (fn [args meta] → string | {:content [...] :isError bool}).
   `args` has keyword keys; `meta` is the request's `_meta` with string keys
   (\"xi/cwd\", \"xi/roomId\", \"xi/clientPid\", … when xi is the client)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private protocol-version "2024-11-05")

(defn- ->result [x]
  (if (string? x)
    {:content [{:type "text" :text x}] :isError false}
    (merge {:isError false} x)))

(defn handle
  "One decoded request → the reply map, or nil for a notification."
  [{:keys [name version tools]} {:keys [id method params]}]
  (let [reply (fn [result] (when (some? id) {:jsonrpc "2.0" :id id :result result}))
        fail  (fn [code msg] (when (some? id) {:jsonrpc "2.0" :id id :error {:code code :message msg}}))
        by-name (into {} (map (juxt :name identity)) tools)]
    (case method
      "initialize" (reply {:protocolVersion (or (:protocolVersion params) protocol-version)
                           :capabilities    {:tools {}}
                           :serverInfo      {:name name :version version}})
      "ping"       (reply {})
      "tools/list" (reply {:tools (mapv #(dissoc % :handler) tools)})
      "tools/call" (if-let [{:keys [handler]} (get by-name (:name params))]
                     (reply (try (->result (handler (or (:arguments params) {})
                                                    ;; keyword :xi/cwd → "xi/cwd", namespace kept
                                                    (update-keys (or (:_meta params) {}) #(subs (str %) 1))))
                                 (catch Exception e
                                   {:content [{:type "text" :text (str (:name params) " failed: " (ex-message e))}]
                                    :isError true})))
                     (fail -32602 (str "Unknown tool: " (:name params))))
      (when-not (str/starts-with? (str method) "notifications/")
        (fail -32601 (str "Method not found: " method))))))

(defn serve!
  "Serve `server` on stdin/stdout until stdin closes."
  [server]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))
          :when (not (str/blank? line))]
    (let [out (try (handle server (json/parse-string line true))
                   (catch Exception e
                     {:jsonrpc "2.0" :id nil :error {:code -32700 :message (str "Parse error: " (ex-message e))}}))]
      (when out
        (println (json/generate-string out))
        (flush)))))
