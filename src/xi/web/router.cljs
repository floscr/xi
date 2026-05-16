(ns xi.web.router
  "History-based routing for the web client.
   Routes: / → home, /chat/:session-id → chat."
  (:require [clojure.string :as str]
            [xi.web.state :as state]))

(defn- parse-path
  "Parse a URL path into a route map."
  [path]
  (let [segments (filterv seq (str/split path #"/"))]
    (case (first segments)
      "chat" {:page :chat
              :session-id (second segments)}
      {:page :home})))

(defn- route->path [{:keys [page session-id]}]
  (case page
    :chat (if session-id
             (str "/chat/" session-id)
             "/chat")
    "/"))

(defn current-route []
  (parse-path (.-pathname js/window.location)))

(defn navigate!
  "Push a new route onto the history stack and update app-state :route."
  [route]
  (let [path (route->path route)]
    (when (not= path (.-pathname js/window.location))
      (.pushState js/window.history nil "" path))
    (swap! state/app-state assoc :route route)))

(defn replace!
  "Replace the current history entry (no new back entry)."
  [route]
  (let [path (route->path route)]
    (.replaceState js/window.history nil "" path)
    (swap! state/app-state assoc :route route)))

(defonce ^:private on-navigate-home (atom nil))
(defonce ^:private on-navigate-chat (atom nil))

(defn set-on-navigate-home!
  "Register a callback for when browser back navigates to home."
  [f]
  (reset! on-navigate-home f))

(defn set-on-navigate-chat!
  "Register a callback for when browser navigation enters a chat route."
  [f]
  (reset! on-navigate-chat f))

(defn- on-popstate [_event]
  (let [route (current-route)]
    (swap! state/app-state assoc :route route)
    (case (:page route)
      :home (do
              (swap! state/app-state assoc
                     :room-id nil
                     :session-id nil
                     :messages []
                     :busy? false)
              (when-let [f @on-navigate-home] (f)))
      :chat (when-let [f @on-navigate-chat] (f route))
      nil)))

(defn init!
  "Initialize the router — set initial route from URL and listen for popstate."
  []
  (let [route (current-route)]
    (swap! state/app-state assoc :route route)
    (.addEventListener js/window "popstate" on-popstate)))
