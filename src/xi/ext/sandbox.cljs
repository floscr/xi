(ns xi.ext.sandbox
  "Sandbox mode — OS-level confinement of agent tool execution, /sandbox.

   When enabled for a room, bash tool calls are intercepted by the
   tool-gate and executed under a pluggable sandbox backend
   (xi.sandbox.core — bubblewrap or firejail): filesystem read-only
   outside the room's cwd, credential paths hidden, private /tmp, env
   scrubbed to an allowlist, network dropped unless `/sandbox net`
   allows it.

   The deny-checks — writes outside the working tree, reads of credential
   paths, and backgrounded (`cmd &`) commands — live as default rules in
   xi.rules.defaults, gated on the same room flag (:when {:sandbox
   {:enabled? true}}). Because the rules extension is registered first, those
   denies run before this gate, so a blocked call never reaches the executor.
   write/edit/read run in-process (an OS sandbox can't cover them) and pass
   through this gate untouched.

   State is room-scoped ([:rooms rid :ext :sandbox]
   {:enabled? bool :backend kw :network :none|:all}) so joined clients
   see the badge the server enforces.

   Known gap (v1, by design): extension tools that spawn their own
   processes (commit, …) are curated code and not wrapped."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.sandbox.core :as sandbox]
            [xi.tools.bash :as bash]))

(def ^:private ext-id :sandbox)

(defn- ext-state [st room-id]
  (state/room-ext st room-id ext-id))

(defn- status-line [st room-id text]
  (update-in st [:rooms room-id :history] conj {:kind :status :text text}))

;; ── Tool gate ────────────────────────────────────────────────────────────────

(defn- run-bash!
  "Execute a bash tool call under the sandbox backend; resolves to an
   intercepted gate result."
  [arguments {:keys [backend network cwd]}]
  (let [policy (sandbox/make-policy {:cwd cwd :network network})]
    (-> (bash/execute arguments
                      {:cwd cwd
                       :wrap-argv #(sandbox/wrap-argv backend policy %)
                       :env (sandbox/scrub-env)})
        (.then (fn [result] {:intercepted true :result result})))))

(defn- tool-gate
  "While sandboxed, execute bash under the OS sandbox backend (bwrap/firejail).
   The deny-checks (writes outside cwd, credential reads, `cmd &`) are default
   rules gated on the same room flag and run first (rules ext is registered
   first), so a blocked call never reaches here. write/edit/read run in-process
   and pass through. Tool names may be PascalCase (from the SDK) or lowercase."
  [tool-call {:keys [get-state room-id cwd]}]
  (let [{:keys [enabled? backend network]} (ext-state (get-state) room-id)]
    (if (and enabled? (= "bash" (str/lower-case (or (:name tool-call) ""))))
      (run-bash! (:arguments tool-call)
                 {:backend backend :network network :cwd (or cwd (.cwd js/process))})
      tool-call)))

;; ── Command + state ──────────────────────────────────────────────────────────

(defn- set-sandbox
  "Handler for :ext.sandbox/set — enable with a backend, log a status line."
  [st {:keys [room-id backend]}]
  (when (state/get-room st room-id)
    (let [st' (update-in st [:rooms room-id :ext ext-id] merge
                         {:enabled? true :backend backend})
          network (:network (ext-state st' room-id))]
      {:state (status-line st' room-id
                           (str "Sandbox: ON (" (name backend)
                                ", network " (if (= network :all) "allowed" "blocked") ")"))})))

(defn- enable!
  "fx :ext.sandbox/enable — probe backend availability, then enable."
  [{:keys [dispatch!]} {:keys [room-id backend]}]
  (let [backend (or backend (sandbox/first-available))]
    (cond
      (nil? backend)
      (dispatch! {:type :ui/status :room-id room-id
                  :text "Sandbox: no backend installed (install bubblewrap or firejail)"})

      (not (sandbox/available? backend))
      (dispatch! {:type :ui/status :room-id room-id
                  :text (str "Sandbox: backend not installed: " (name backend))})

      :else
      (dispatch! {:type :ext.sandbox/set :room-id room-id :backend backend}))))

(defn- command
  "/sandbox [bwrap|firejail|off|net] — toggle sandboxed tool execution.
   No arg: toggle (picking the first installed backend). `net` toggles
   network access inside the sandbox."
  [st {:keys [room-id args]}]
  (when (state/get-room st room-id)
    (let [arg (some-> args str/trim str/lower-case not-empty)
          {:keys [enabled? network]} (ext-state st room-id)]
      (cond
        (or (= arg "off") (and (nil? arg) enabled?))
        {:state (-> st
                    (assoc-in [:rooms room-id :ext ext-id :enabled?] false)
                    (status-line room-id "Sandbox: OFF"))}

        (= arg "net")
        (let [net' (if (= network :all) :none :all)]
          {:state (-> st
                      (assoc-in [:rooms room-id :ext ext-id :network] net')
                      (status-line room-id (str "Sandbox network: "
                                                (if (= net' :all) "allowed" "blocked"))))})

        (nil? arg)
        {:effects [[:ext.sandbox/enable {:room-id room-id}]]}

        (contains? sandbox/backends (keyword arg))
        {:effects [[:ext.sandbox/enable {:room-id room-id :backend (keyword arg)}]]}

        :else
        {:state (status-line st room-id "Usage: /sandbox [bwrap|firejail|off|net]")}))))

(defn- prompt-badge [st]
  (when-let [room (state/active-room st)]
    (when (:enabled? (ext-state st (:id room)))
      " 🔒")))

(def extension
  {:id           ext-id
   :init         {:room {:enabled? false :backend nil :network :none}}
   :commands     [{:name "sandbox"
                   :description "Toggle sandboxed tool execution (bwrap|firejail|off|net)"
                   :handler command}]
   :handlers     {:ext.sandbox/set set-sandbox}
   :fx           {:ext.sandbox/enable enable!}
   :tool-gate    tool-gate
   :prompt-badge prompt-badge})
