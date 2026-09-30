;; Web half of the demo `notes` user extension (scripts/demo-extensions/notes.cljs).
;; Evaluated in the browser's sandbox (xi.web.user-ext); pages are sanitized
;; and may only dispatch :ext.notes/* (sent to the server) and navigation.
(ns notes.web
  (:require [ui.button :as button]
            [xi.core.state :as state]))

(defn- notes-page [st dispatch!]
  (let [room (state/active-room st)
        text (get-in room [:ext :notes :text])]
    [:div {:class "notes-page" :style {:padding "1.5rem"}}
     [:h2 "Notes"]
     (if room
       [:div
        (button/button {:variant :secondary :size :sm
                        :on-click (fn [_] (dispatch! {:type :ext.notes/refresh}))}
                       "Refresh")
        [:pre {:style {:white-space "pre-wrap"}}
         (if (seq text) text "(no notes yet — ask the agent to use notes_add)")]]
       [:p "Open a chat first; notes are read through the active room."])]))

(def web-extension
  {:id :notes
   :routes {"notes" {:parse (fn [_] {:page :notes/list})
                     :path  {:notes/list (fn [_] "/notes")}}}
   :pages {:notes/list notes-page}
   :nav-items [{:menu :sidebar :label "Notes" :icon :file-text
                :event {:type :route/navigate :page :notes/list}}]})
