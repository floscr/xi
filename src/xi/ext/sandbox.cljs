(ns xi.ext.sandbox
  "Sandbox mode — OS-level confinement of agent tool execution, /sandbox.

   When enabled for a room, bash tool calls are intercepted by the
   tool-gate and executed under a pluggable sandbox backend
   (xi.sandbox.core — bubblewrap or firejail): filesystem read-only
   outside the room's cwd, credential paths hidden, private /tmp, env
   scrubbed to an allowlist, network dropped unless `/sandbox net`
   allows it. write/edit run in-process (an OS sandbox can't cover
   them), so they are confined to the cwd by path checks; read-type
   tools (read/grep/find/ls) are blocked from hidden credential paths
   the same way.

   State is room-scoped ([:rooms rid :ext :sandbox]
   {:enabled? bool :backend kw :network :none|:all}) so joined clients
   see the badge the server enforces.

   Background commands (`cmd &`) are refused while sandboxed: the
   sandbox gate must run before process-manager's (see xi.config), and
   a backgrounded child would outlive its bwrap wrapper anyway.
   In-process path gates canonicalize symlinks (sandbox/real-resolve)
   so a link created inside cwd can't launder reads/writes outside it.

   Known gap (v1, by design): extension tools that spawn their own
   processes (commit, gtd, …) are curated code and not wrapped."
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

(defn- blocked [text]
  {:intercepted true
   :result {:content [{:type "text" :text text}]
            :is-error true}})

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

(defn- target-path [arguments]
  (or (:path arguments) (:file_path arguments)))

(defn- background-command?
  "True for `cmd &` — backgrounded children would escape (or be killed
   with) the sandbox wrapper, so they are refused while sandboxed."
  [command]
  (str/ends-with? (str/trimr (or command "")) "&"))

(defn- gate-write
  "Allow write/edit only inside the room's cwd (symlink-canonicalized)."
  [tool-call cwd]
  (let [path (target-path (:arguments tool-call))
        resolved (sandbox/real-resolve cwd (str path))
        real-cwd (sandbox/real-resolve cwd ".")]
    (if (sandbox/path-within? resolved real-cwd)
      tool-call
      (blocked (str "Sandbox: writes outside the working directory are blocked: " path)))))

(defn- gate-read
  "Block read-type tools from hidden credential paths (symlink-canonicalized)."
  [tool-call cwd]
  (let [path (or (target-path (:arguments tool-call)) cwd)
        resolved (sandbox/real-resolve cwd (str path))]
    (if (some #(sandbox/path-within? resolved %) (sandbox/hidden-paths))
      (blocked (str "Sandbox: reading credential paths is blocked: " path))
      tool-call)))

(defn- tool-gate
  "Enforce the sandbox while enabled. Tool names may be PascalCase (from
   the SDK) or lowercase."
  [tool-call {:keys [get-state room-id cwd]}]
  (let [{:keys [enabled? backend network]} (ext-state (get-state) room-id)]
    (if-not enabled?
      tool-call
      (let [lname (str/lower-case (or (:name tool-call) ""))
            cwd (or cwd (.cwd js/process))]
        (case lname
          "bash"
          (if (background-command? (:command (:arguments tool-call)))
            (blocked "Sandbox: background processes (`cmd &`) are disabled in sandbox mode.")
            (run-bash! (:arguments tool-call)
                       {:backend backend :network network :cwd cwd}))

          ("write" "edit")
          (gate-write tool-call cwd)

          ("read" "grep" "find" "ls")
          (gate-read tool-call cwd)

          tool-call)))))

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
