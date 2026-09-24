(ns xi.ext.plan-mode
  "Plan mode — a read-only exploration mode toggled with /plan.

   When enabled, the tool-gate blocks any mutation: write/edit are allowed
   only to the plan file (tasks/todo.md) and bash is restricted to commands
   that don't match a dangerous pattern. Everything else (read, grep, find,
   ls, read-only bash) passes through.

   State is room-scoped ([:rooms rid :ext :plan-mode] {:enabled? bool}) so a
   joined client sees the same plan-mode badge the server enforces."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def ^:private ext-id :plan-mode)
(def ^:private plan-file "tasks/todo.md")

(def ^:private dangerous-patterns
  #{"rm " "rm -" "mv " "cp " "chmod " "chown " "kill " "pkill "
    "sudo " "dd " "> " ">> " "tee " "truncate "})

(defn- read-only-bash?
  "A bash command is read-only when it matches no dangerous pattern."
  [command]
  (not (some #(str/includes? (or command "") %) dangerous-patterns)))

(defn- plan-file? [path]
  (= plan-file path))

(defn- enabled? [state room-id]
  (boolean (:enabled? (state/room-ext state room-id ext-id))))

(defn tool-gate
  "Block mutations while plan mode is on. Returns the tool-call to allow it,
   nil to block it (the provider surfaces a 'Blocked by Xi permission gate'
   tool result). Pure aside from reading live state via ctx :get-state."
  [tool-call {:keys [get-state room-id]}]
  (if-not (enabled? (get-state) room-id)
    tool-call
    (let [{:keys [name arguments]} tool-call]
      (case name
        "write" (when (plan-file? (:path arguments)) tool-call)
        "edit"  (when (plan-file? (:path arguments)) tool-call)
        "bash"  (when (read-only-bash? (:command arguments)) tool-call)
        ;; read / grep / find / ls / everything else: allowed
        tool-call))))

(defn- toggle
  "/plan — flip room-scoped :enabled? and report the new state."
  [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [st' (update-in st [:rooms room-id :ext ext-id :enabled?] not)
          on? (get-in st' [:rooms room-id :ext ext-id :enabled?])]
      {:state (update-in st' [:rooms room-id :history] conj
                         {:kind :status
                          :text (str "Plan mode: " (if on? "ON (read-only)" "OFF"))})})))

(defn- prompt-badge [state]
  (when-let [room (state/active-room state)]
    (when (enabled? state (:id room)) " 📋")))

(def extension
  {:id           ext-id
   :init         {:room {:enabled? false}}
   :commands     [{:name "plan"
                   :description "Toggle plan mode (read-only exploration)"
                   :handler toggle}]
   :tool-gate    tool-gate
   :prompt-badge prompt-badge})
