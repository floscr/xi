;; Demo extension bundled with xi — enable with config.edn
;; `:demo-extensions ["ping.cljs"]`. Ctrl+Shift+N (terminal client) toggles a
;; desktop notification (notify-send) for every finished turn in the chat; a
;; bell next to the prompt shows it is on. Running notify-send asks until a rule
;; allows it:
;;   {:match {:tool :sh :extension "ping" :cli "notify-send"} :action {:type :allow}}
;; Built step by step in docs/guide/extension-tutorial-command.md.
(ns ping
  (:require [xi.api.promise :as p]
            [xi.api.sh :as sh]
            [xi.core.state :as state]))

(defn- enabled? [st room-id]
  (boolean (:enabled? (state/room-ext st room-id :ping))))

(defn- toggle [st {:keys [room-id]}]
  (let [on? (not (enabled? st room-id))]
    {:state   (assoc-in st [:rooms room-id :ext :ping :enabled?] on?)
     :effects [[:app/dispatch {:type :ui/status :room-id room-id
                               :text (str "Notifications " (if on? "on" "off"))}]]}))

(defn- on-turn-end [st {:keys [room-id aborted?]}]
  (when (and (enabled? st room-id) (not aborted?))
    (let [title (or (get-in st [:rooms room-id :session :name]) "Xi")]
      {:effects [[:ext.ping/notify {:title title}]]})))

(defn- notify [ctx {:keys [title]}]
  (-> (sh/sh ctx "notify-send" "-a" "Xi" title "Turn finished")
      (p/catch (fn [_] nil))))

(defn- badge [st]
  (when-let [room (state/active-room st)]
    (when (enabled? st (:id room)) " 🔔")))

(def extension
  {:id           :ping
   :init         {:room {:enabled? false}}
   :handlers     {:ext.ping/toggle toggle
                  :agent/turn-end  on-turn-end}
   :fx           {:ext.ping/notify notify}
   :commands     [{:name        "ping"
                   :description "Toggle a desktop notification when a turn ends"
                   :handler     (fn [_ {:keys [room-id]}]
                                  {:effects [[:app/dispatch {:type :ext.ping/toggle :room-id room-id}]]})}]
   :keybindings  [{:key "ctrl+shift+n" :event {:type :ext.ping/toggle}}]
   :prompt-badge badge})
