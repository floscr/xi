(ns xi.ext.plan-mode
  "Plan mode — a read-only exploration mode toggled with /plan.

   This extension only owns the *toggle*, the room-scoped flag, and the prompt
   badge. The read-only *policy* (allow the plan file, deny other writes/edits
   and any mutating bash) lives as `:when {:plan-mode {:enabled? true}}` data
   rules in `xi.rules.defaults`, enforced by the rules engine.

   State is room-scoped ([:rooms rid :ext :plan-mode] {:enabled? bool}) so a
   joined client sees the same plan-mode badge the server enforces."
  (:require [xi.core.state :as state]))

(def ^:private ext-id :plan-mode)

(defn- enabled? [state room-id]
  (boolean (:enabled? (state/room-ext state room-id ext-id))))

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
   :prompt-badge prompt-badge})
