;; Web half of the demo `deploys` extension (resources/extensions/deploys.cljs):
;; draws the `deploys/board` agent block kind. The tree goes through the agent
;; blocks' allowlist (xi.blocks.hiccup), so it uses its tags, ui/* components
;; and semantic attributes; a control's :action records a block action.
(ns deploys.web)

(def ^:private status-variant
  {"healthy" "success" "degraded" "warning" "down" "danger"})

(defn- board [{:keys [envs]} state]
  (let [last-action (:last state)]
    [:div {:layout "stack"}
     [:table
      [:thead [:tr [:th "Environment"] [:th "Version"] [:th "Health"] [:th ""]]]
      [:tbody
       (for [{:keys [name version status]} envs]
         [:tr
          [:td [:strong name]]
          [:td [:code version]]
          [:td [:ui/badge {:variant (status-variant status "secondary")} status]]
          [:td [:ui/button {:action "promote" :value name :size "sm"} "Promote"]]])]]
     [:div {:layout "row"}
      [:ui/button {:action "rollback" :variant "danger"} "Roll back prod"]
      (when last-action
        [:span {:emphasis "muted"}
         (str "Last: " (:action last-action) " " (or (:value last-action) ""))])]]))

(def web-extension
  {:id          :deploys
   :block-kinds {"board" board}})
