(ns xi.ext.browser-open
  "`/browser-open` — open the current session's chat in the web client.

   The web client serves each session at http://localhost:<port>/chat/<sid>
   (see xi.web.router). Opening a browser is I/O, so the command stays pure
   and defers to a :browser/open effect that shells out to `xdg-open`.

   Local-only: `xdg-open` runs on the machine hosting the server, so this is
   registered for the TUI/standalone/server surfaces (where that machine is
   the user's), not for a remote headless deployment."
  (:require [xi.core.state :as state]))

;; Fallback for states built without a port (tests); the CLI always sets one.
(def ^:private default-port 7474)

(defn- session-url [st sid]
  (str "http://localhost:" (or (state/port st) default-port) "/chat/" sid))

(defn- status-entry [text]
  {:kind :status :text text})

(defn- append-status [st room-id text]
  (update-in st [:rooms room-id :history] conj (status-entry text)))

(defn- browser-open-command
  "/browser-open — open the current session's chat URL in the browser."
  [st {:keys [room-id]}]
  (if-let [sid (get-in st [:rooms room-id :session :id])]
    (let [url (session-url st sid)]
      {:state   (append-status st room-id (str "Opening " url " in browser…"))
       :effects [[:browser/open {:url url}]]})
    {:state (append-status st room-id "No active session to open — send a prompt first.")}))

(defn- browser-open-fx
  "Fire-and-forget: hand the URL to xdg-open."
  [_ctx {:keys [url]}]
  (js/Bun.spawn (clj->js ["xdg-open" url])
                #js {:stdout "ignore" :stderr "ignore"}))

(def extension
  {:id       :browser-open
   :commands [{:name        "browser-open"
               :description "Open the current session's chat in the browser"
               :handler     browser-open-command}]
   :fx       {:browser/open browser-open-fx}})
