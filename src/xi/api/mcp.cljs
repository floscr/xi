(ns xi.api.mcp
  "Rules-gated MCP tool calls for user extensions.

     (call ctx :browser \"navigate_page\" {:type \"url\" :url \"https://…\"})
     → Promise<{:content [...] :is-error bool}>

   `server` names, in this order:
     - one of the extension's own servers (its `:mcp-servers`), private to it
       and started on first use (xi.ext.mcp)
     - a server configured in ~/.config/xi/mcp.edn, over the same connection
       the agent's `mcp__<server>__<tool>` tools use, so a stateful server (a
       browser) is shared, not started twice

   Each call is the request `{:tool :mcp :mcp-server :mcp-tool :arguments}`
   tagged with the extension and decided like the agent's MCP calls: a
   trusted server runs, an untrusted one asks (and `[a]lways` trusts it until
   its code changes, xi.mcp.trust). An extension's own server is
   `\"<extension>/<name>\"` to the rules engine. Unknown, disabled or another
   extension's servers reject.

   The server gets the room's cwd / id / driving client pid and the
   extension's id as `_meta` (xi.ext.mcp/call-meta). An optional trailing
   opts map takes :room-id, for an :fx that has its room only in its payload."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.api.core :as core]
            [xi.ext.mcp :as mcp]))

(defn- server-name [server]
  (if (keyword? server) (name server) (str server)))

(defn- resolve-server
  "{:id :own?} for `srv` called by extension `ext`: its own declared server
   of that name, else the mcp.edn server."
  [ext srv]
  (let [own (mcp/extension-server-id ext srv)]
    (if (contains? (mcp/extension-servers) own)
      {:id own :own? true}
      {:id srv :own? false})))

(defn call
  "Call `tool` on MCP `server` (keyword or string) with `args` (a map)."
  ([ctx server tool] (call ctx server tool {} nil))
  ([ctx server tool args] (call ctx server tool args nil))
  ([ctx server tool args opts]
   (let [srv (server-name server)
         ctx (cond-> ctx (:room-id opts) (assoc :room-id (:room-id opts)))
         target (atom nil)]
     (-> (js/Promise.resolve nil)
         (.then
          (fn [_]
            (when (or (str/blank? srv) (str/blank? (str tool)))
              (throw (ex-info "xi.api.mcp: (call ctx server tool args) needs a server and a tool" {})))
            (when-not (or (nil? args) (map? args))
              (throw (ex-info "xi.api.mcp: tool args must be a map" {})))
            (let [{:keys [id] :as t} (resolve-server (core/caller ctx) srv)]
              (reset! target t)
              (core/gate! ctx {:tool       :mcp
                               :tool-name  (mcp/qualify-name id tool)
                               :mcp-server id
                               :mcp-tool   (str tool)
                               :arguments  (or args {})}))))
         (.then
          (fn [_]
            (let [{:keys [get-state room-id]} ctx
                  {:keys [id own?]} @target
                  ext      (core/caller ctx)
                  meta-ctx {:cwd        (core/base-cwd ctx)
                            :room-id    room-id
                            :client-pid (when (and get-state room-id)
                                          (agent/room-client-pid (get-state) room-id))
                            :extension  ext}]
              (if own?
                (mcp/call-extension-tool! ext id (str tool) (or args {}) meta-ctx)
                (mcp/call-tool! id (str tool) (or args {}) meta-ctx)))))))))
