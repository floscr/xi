(ns xi.web.views
  "Replicant view functions for the xi web client."
  (:require [clojure.string :as str]
            [xi.web.state :as state]
            [xi.web.ws :as ws]
            [ui.icon :as icon]
            [ui.button :as button]
            [ui.spinner :as spinner]
            [ui.badge :as badge]
            [ui.form :as form]))

;; ---------------------------------------------------------------------------
;; Chat actions
;; ---------------------------------------------------------------------------

(defn- send-message! []
  (let [text (str/trim (or (:compose-text @state/app-state) ""))]
    (when (seq text)
      (swap! state/app-state assoc :compose-text "")
      (when-let [el (.querySelector js/document ".compose-editable")]
        (set! (.-textContent el) ""))
      (ws/dispatch! text))))

;; ---------------------------------------------------------------------------
;; Topbar
;; ---------------------------------------------------------------------------

(defn- topbar [{:keys [title subtitle actions]}]
  [:div {:class ["topbar"]}
   [:div {:class ["topbar-title"]}
    title
    (when subtitle
      [:span {:class ["topbar-subtitle"]} (str " · " subtitle)])]
   (when (seq actions)
     (into [:div {:style {:display "flex" :gap "var(--size-1)" :margin-left "auto"}}]
           actions))])

;; ---------------------------------------------------------------------------
;; Message rendering
;; ---------------------------------------------------------------------------

(defn- truncate-lines
  "Keep at most n lines of text."
  [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) n)
      text
      (str (str/join "\n" (take n lines))
           "\n... (" (- (count lines) n) " more lines)"))))

(defn- tool-message [{:keys [title result is-error finished]} idx]
  (let [block-id (str "tool-" idx)
        expanded? (not (contains? (:collapsed-blocks @state/app-state) block-id))]
    [:div {:class ["tool-call-block"]}
     [:button {:class ["tool-call-toggle"]
               :on {:click (fn [_]
                             (swap! state/app-state update :collapsed-blocks
                                    (fn [s] (if (contains? s block-id)
                                              (disj s block-id)
                                              (conj (or s #{}) block-id)))))}}
      [:span {:class ["tool-call-toggle-icon"]}
       (icon/icon {:icon-name (if expanded? :chevron-down :chevron-right) :size :sm})]
      [:span {:class ["tool-call-toggle-label"]} (or title "tool")]
      (when finished
        (if is-error
          (badge/badge {:variant :danger} "error")
          (badge/badge {:variant :success} "done")))]
     (when (and expanded? result)
       [:div {:class ["tool-call-content"]}
        [:pre {:class ["tool-call-code"]}
         (truncate-lines result 30)]])]))

(defn- thinking-message [text idx]
  (let [block-id (str "thinking-" idx)
        expanded? (not (contains? (:collapsed-blocks @state/app-state) block-id))]
    [:div {:class ["thinking-block"]}
     [:button {:class ["thinking-toggle"]
               :on {:click (fn [_]
                             (swap! state/app-state update :collapsed-blocks
                                    (fn [s] (if (contains? s block-id)
                                              (disj s block-id)
                                              (conj (or s #{}) block-id)))))}}
      (icon/icon {:icon-name (if expanded? :chevron-down :chevron-right) :size :sm})
      [:span {:style {:font-weight "500" :margin-left "var(--size-1)"}} "Thinking"]]
     (when expanded?
       [:div {:style {:padding "0 0 var(--size-2)"}}
        [:pre {:class ["thinking-text"]} text]])]))

(defn- message-view [msg idx]
  (case (:type msg)
    :user
    [:div {:class ["post"]}
     [:div {:class ["post-avatar" "user-avatar"]} "U"]
     [:div {:class ["post-body"]}
      [:div {:class ["post-meta"]}
       [:span {:class ["post-author"]} "You"]]
      [:div {:class ["post-content"]}
       [:p (:text msg)]]]]

    :assistant
    [:div {:class ["post"]}
     [:div {:class ["post-avatar"]} "Xi"]
     [:div {:class ["post-body"]}
      [:div {:class ["post-meta"]}
       [:span {:class ["post-author"]} "Xi"]]
      [:div {:class ["post-content"]}
       ;; Simple paragraph splitting
       (for [para (str/split (:text msg) #"\n\n+")]
         [:p para])]]]

    :thinking
    [:div {:class ["post"]}
     [:div {:class ["post-avatar" "thinking-avatar"]} "·"]
     [:div {:class ["post-body"]}
      (thinking-message (:text msg) idx)]]

    :tool
    [:div {:class ["post"]}
     [:div {:class ["post-avatar" "tool-avatar"]} "$"]
     [:div {:class ["post-body"]}
      (tool-message msg idx)]]

    :error
    [:div {:class ["post"]}
     [:div {:class ["post-avatar" "error-avatar"]} "!"]
     [:div {:class ["post-body"]}
      [:div {:class ["post-content" "error-text"]}
       [:p (:text msg)]]]]

    :status
    [:div {:class ["post"]}
     [:div {:class ["post-avatar" "status-avatar"]} "·"]
     [:div {:class ["post-body"]}
      [:div {:class ["post-content" "status-text"]}
       [:p (:text msg)]]]]

    ;; fallback
    nil))

;; ---------------------------------------------------------------------------
;; Working indicator
;; ---------------------------------------------------------------------------

(defn- working-indicator []
  (when (:busy? @state/app-state)
    [:div {:class ["agent-status"]}
     [:div {:class ["agent-status-spinner"]}]
     [:span {:class ["agent-status-text"]} "Working..."]
     [:button {:class ["agent-status-cancel"]
               :on {:click (fn [_] (ws/dispatch! {:type :abort}))}}
      (icon/icon {:icon-name :x :size :sm})]]))

;; ---------------------------------------------------------------------------
;; Compose box
;; ---------------------------------------------------------------------------

(defn- compose-box []
  (let [{:keys [compose-text busy?]} @state/app-state
        can-send? (and (not busy?) (seq (str/trim (or compose-text ""))))]
    [:div {:class ["compose-box"]}
     [:div {:class ["compose-input-wrapper"]}
      (form/form-input {:type :text
                         :placeholder "Message..."
                         :value (or compose-text "")
                         :attrs {:on {:input (fn [e]
                                              (swap! state/app-state assoc :compose-text (.. e -target -value)))
                                      :keydown (fn [e]
                                                 (when (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                                                   (.preventDefault e)
                                                   (send-message!)))}}})]
     [:button {:class ["icon-btn"]
               :disabled (not can-send?)
               :on {:click (fn [_] (send-message!))}}
      (icon/icon {:icon-name :arrow-up :size :sm})]]))

;; ---------------------------------------------------------------------------
;; Chat view
;; ---------------------------------------------------------------------------

(defn- chat-view [{:keys [messages model]}]
  [:div {:class ["container"]}
   (topbar {:title "Xi"
            :subtitle model
            :actions [[:button {:class ["icon-btn"]
                                :on {:click (fn [_] (ws/leave-room!))}}
                       (icon/icon {:icon-name :terminal :size :sm})]
                      [:button {:class ["icon-btn"]
                                :on {:click (fn [_] (ws/dispatch! "/new"))}}
                       (icon/icon {:icon-name :plus :size :sm})]]})
   (working-indicator)
   [:div {:class ["timeline"]}
    [:div {:class ["timeline-content"]}
     (map-indexed
      (fn [idx msg] (message-view msg idx))
      messages)]]
   (compose-box)])

;; ---------------------------------------------------------------------------
;; Home view (disconnected / room selection)
;; ---------------------------------------------------------------------------

(defn- home-view [{:keys [connected? rooms]}]
  [:div {:class ["container"]}
   (topbar {:title "Xi"})
   [:div {:class ["home"]}
    (if connected?
      ;; Connected but not yet in a room
      [:div {:class ["section"]}
       [:div {:class ["section-title"]} "Rooms"]
       [:div {:class ["project-list"]}
        [:div {:class ["project-card"]
               :on {:click (fn [_] (ws/join-room! "new"))}}
         [:div {:class ["project-card-icon"]}
          (icon/icon {:icon-name :plus})]
         [:div {:class ["project-card-info"]}
          [:span {:class ["project-card-name"]} "New Room"]]]
        (for [r rooms]
          [:div {:class ["project-card"]
                 :on {:click (fn [_] (ws/join-room! (:id r)))}}
           [:div {:class ["project-card-icon"]}
            (icon/icon {:icon-name :terminal})]
           [:div {:class ["project-card-info"]}
            [:span {:class ["project-card-name"]} (:id r)]
            [:span {:class ["project-card-path"]}
             (str (:clients r) " client(s)")]]])]]
      ;; Not connected
      [:div {:class ["empty-state"]}
       [:div
        [:p "Connecting to xi server..."]
        [:p {:class ["status-text"]}
         (str "ws://localhost:7474")]]])]])

;; ---------------------------------------------------------------------------
;; Root
;; ---------------------------------------------------------------------------

(defn root-view [app-state]
  (if (and (:connected? app-state) (:room-id app-state))
    (chat-view app-state)
    (home-view app-state)))
