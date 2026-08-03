(ns xi.ext.render
  "Render.com MCP server as a dedicated, disabled-by-default extension.

   Render hosts a Streamable-HTTP MCP server at https://mcp.render.com/mcp,
   authenticated with a personal API key via `Authorization: Bearer <key>`.
   Xi connects to it natively over HTTP (xi.mcp.client/connect-http) — no npm
   bridge to install, no OAuth flow.

   The API key is NEVER stored in mcp.edn. Put it in the gitignored
   per-extension config file (xi.ext.config):

     # ~/.config/xi/ext/render.env
     RENDER_API_KEY=rnd_your_key_here

   On startup this extension idempotently seeds a *disabled* :render entry into
   the MCP registry (~/.config/xi/mcp.edn) that references the key by name (the
   secret is resolved only at connect time). It defaults to disabled so
   Render's tools — which can trigger deploys and mutate service env vars —
   never load unless you opt in; once enabled, every call is still confirmed by
   the MCP tool gate.

   Turn it on with:
     /mcp enable render      then      /mcp refresh render   (caches its tools)
   or inspect status/setup with:
     /render"
  (:require [clojure.string :as str]
            [xi.ext.config :as ext-config]
            [xi.ext.manager :as manager]
            [xi.ext.mcp :as mcp]))

(def ^:private SERVER_ID :render)
(def ^:private MCP_URL "https://mcp.render.com/mcp")
(def ^:private API_KEY_NAME "RENDER_API_KEY")

(defn registry-entry
  "The disabled :http registry entry seeded into mcp.edn. Carries an :auth
   descriptor (not the key itself) so xi.ext.mcp resolves the secret from the
   gitignored config at connect time."
  []
  {:transport :http
   :url       MCP_URL
   :auth      {:ext-config (name SERVER_ID)
               :key        API_KEY_NAME
               :header     "Authorization"
               :scheme     "Bearer"}
   :enabled   false})

(defn- enabled? [manager]
  (boolean (some #(and (= (:id %) (mcp/ext-id SERVER_ID)) (:enabled? %))
                 (manager/ext-list manager))))

(defn- status-text [manager]
  (let [key?   (boolean (ext-config/get-value SERVER_ID API_KEY_NAME))
        on?    (enabled? manager)]
    (str "Render MCP\n"
         "  Server:  " MCP_URL " (Streamable HTTP)\n"
         "  API key: " (if key?
                         "found"
                         (str "MISSING — add it to " (ext-config/config-file SERVER_ID) "\n"
                              "             " API_KEY_NAME "=rnd_your_key_here"))
         "\n"
         "  Status:  " (if on? "enabled" "disabled (default)") "\n\n"
         (if on?
           "Enabled. If tools are missing, run /mcp refresh render to cache them."
           "To enable:  /mcp enable render   then   /mcp refresh render"))))

(defn- render-command
  "/render — pure: defer status rendering to an effect (needs the manager)."
  [_st {:keys [room-id]}]
  {:effects [[:render/status {:room-id room-id}]]})

(defn- status-fx [manager {:keys [dispatch!]} {:keys [room-id]}]
  (dispatch! {:type :history/append :room-id room-id
              :entry {:kind :status :text (status-text manager)}}))

(defn create
  "Factory — seeds the disabled Render MCP entry and returns the /render
   status extension. nil (dropped by compose) when no manager is in ctx.

   In a client mirror (`:mirror? true`) the extension is still returned so the
   /render command shows in the palette, but the mcp.edn seeding (real I/O) is
   skipped — the client only forwards /render to the server, never runs its fx."
  [{:keys [manager mirror?]}]
  (when manager
    (when-not mirror?
      (mcp/ensure-registry-entry! SERVER_ID (registry-entry)))
    {:id       :render
     :commands [{:name        "render"
                 :description "Render.com MCP status + setup help"
                 :handler     render-command}]
     :fx       {:render/status (partial status-fx manager)}}))
