(ns xi.ext.browser-open
  "`/browser-open` — open the current session's chat in the web client.

   The web client serves each session at http://localhost:<port>/chat/<sid>
   (see xi.web.router). Opening a browser is I/O, so the command stays pure
   and defers to a :browser/open effect that shells out to `xdg-open`.

   Local-only: `xdg-open` runs on the machine hosting the server, so this is
   registered for the TUI/standalone/server surfaces (where that machine is
   the user's), not for a remote headless deployment.")

;; Default web-client port. The real port only lives in CLI opts, not state,
;; so we open on the default; an XI_PORT env override is honored if present.
(defn- web-port []
  (or (some-> (aget js/process.env "XI_PORT") js/parseInt)
      7474))

(defn- session-url [sid]
  (str "http://localhost:" (web-port) "/chat/" sid))

(defn- status-entry [text]
  {:kind :status :text text})

(defn- append-status [st room-id text]
  (update-in st [:rooms room-id :history] conj (status-entry text)))

(defn- browser-open-command
  "/browser-open — open the current session's chat URL in the browser."
  [st {:keys [room-id]}]
  (if-let [sid (get-in st [:rooms room-id :session :id])]
    (let [url (session-url sid)]
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
