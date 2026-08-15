(ns xi.ext.chrome-mcp.cdp
  "Minimal Chrome DevTools Protocol client over the browser-level WebSocket.

   chrome-devtools-mcp addresses pages by a flat index and never exposes which
   *window* a page belongs to. CDP does: `Target.getTargets` +
   `Browser.getWindowForTarget` group tabs by window. This client talks the
   browser endpoint (`<browserUrl>/json/version` → webSocketDebuggerUrl)
   directly, alongside the MCP proxy, purely to read that window grouping and
   to open new windows.

   Bun/Node ≥ 22 provide global `fetch` and `WebSocket`."
  (:require [clojure.string :as str]))

(defn- ws-endpoint
  "Resolve the browser-level WS debugger URL from `<browser-url>/json/version`."
  [browser-url]
  (-> (js/fetch (str (str/replace browser-url #"/+$" "") "/json/version"))
      (.then (fn [r] (.json r)))
      (.then (fn [j] (aget j "webSocketDebuggerUrl")))))

(defn connect
  "Connect a CDP client to `browser-url`. Resolves to
   {:call (fn [method params] → Promise<result>) :close (fn [])}."
  [browser-url]
  (-> (ws-endpoint browser-url)
      (.then
       (fn [url]
         (js/Promise.
          (fn [resolve reject]
            (let [ws      (js/WebSocket. url)
                  pending (js/Map.)
                  next-id (atom 0)
                  dead    (atom false)
                  fail!   (fn [reason]
                            (reset! dead true)
                            (doseq [k (js/Array.from (.keys pending))]
                              (when-let [p (.get pending k)]
                                (.delete pending k)
                                ((.-reject p) (js/Error. reason)))))
                  open?   (fn [] (and (not @dead)
                                      (= (.-readyState ws) (.-OPEN js/WebSocket))))]
              (set! (.-onmessage ws)
                    (fn [ev]
                      (when-let [msg (try (js/JSON.parse (.-data ev)) (catch :default _ nil))]
                        (let [id (.-id msg)]
                          (when (and (some? id) (.has pending id))
                            (let [p (.get pending id)]
                              (.delete pending id)
                              (if-let [err (.-error msg)]
                                ((.-reject p) (js/Error. (or (.-message err) "CDP error")))
                                ((.-resolve p) (.-result msg)))))))))
              (set! (.-onerror ws) (fn [_] (fail! "CDP socket error")))
              (set! (.-onclose ws) (fn [_] (fail! "CDP socket closed")))
              (set! (.-onopen ws)
                    (fn [_]
                      (resolve
                       {:call  (fn [method params]
                                 ;; Bun's WebSocket.send() on a CLOSED socket
                                 ;; silently no-ops (no throw), so a call on a
                                 ;; dead client would register a pending entry
                                 ;; that never settles — hanging forever. Guard
                                 ;; the readyState and reject instead, so callers
                                 ;; (and the guard's reconnect) can recover.
                                 (js/Promise.
                                  (fn [res rej]
                                    (if-not (open?)
                                      (rej (js/Error. "CDP socket closed"))
                                      (let [id (swap! next-id inc)]
                                        (.set pending id #js {:resolve res :reject rej})
                                        (try
                                          (.send ws (js/JSON.stringify
                                                     (clj->js (cond-> {:id id :method method}
                                                                (some? params) (assoc :params params)))))
                                          (catch :default e
                                            (.delete pending id)
                                            (rej e))))))))
                        :closed? (fn [] (not (open?)))
                        :close (fn [] (try (.close ws) (catch :default _ nil)))})))))))))) 

(defn page-windows
  "→ Promise<[{:target-id :url :title :window-id}]> for every page target,
   annotated with the CDP window it lives in (windowId, or nil if unknown)."
  [{:keys [call]}]
  (-> (call "Target.getTargets" nil)
      (.then (fn [res]
               (let [infos (js->clj (aget res "targetInfos") :keywordize-keys true)
                     pages (filter #(= "page" (:type %)) infos)]
                 (js/Promise.all
                  (clj->js
                   (map (fn [t]
                          (-> (call "Browser.getWindowForTarget" {:targetId (:targetId t)})
                              (.then (fn [w] {:target-id (:targetId t)
                                              :url (:url t) :title (:title t)
                                              :window-id (aget w "windowId")}))
                              (.catch (fn [_] {:target-id (:targetId t)
                                               :url (:url t) :title (:title t)
                                               :window-id nil}))))
                        pages))))))
      (.then (fn [arr] (vec arr)))))

(defn window-for-target
  "→ Promise<windowId|nil> — the CDP window a target id belongs to."
  [{:keys [call]} target-id]
  (-> (call "Browser.getWindowForTarget" {:targetId target-id})
      (.then (fn [w] (aget w "windowId")))
      (.catch (fn [_] nil))))

(defn create-window
  "Open `url` (default about:blank) in a brand-new browser window.
   Resolves to the new target id.

   `:background true` creates the window *without activating/focusing it*, so a
   window opened on the currently-viewed xmonad workspace (before the guard
   moves it to the agent's workspace) doesn't steal focus. It still gets mapped
   and moved as usual."
  ([client] (create-window client "about:blank"))
  ([{:keys [call]} url]
   (-> (call "Target.createTarget" {:url url :newWindow true :background true})
       (.then (fn [res] (aget res "targetId"))))))

(defn create-tab
  "Open `url` as a new *tab* in the existing window `window-id` (no new OS
   window). Resolves to the new target id.

   Reusing a window on the agent's workspace avoids the new-window creation +
   `wm move` dance entirely — and with it the transient flash of a window
   mapping on the viewed workspace before it's relocated. `:background true`
   keeps the tab from stealing focus on create; the guard brings it to front
   afterwards with `select_page`."
  [{:keys [call]} url window-id]
  (-> (call "Target.createTarget"
            {:url url :newWindow false :windowId window-id :background true})
      (.then (fn [res] (aget res "targetId")))))
