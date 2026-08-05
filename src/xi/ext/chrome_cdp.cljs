(ns xi.ext.chrome-cdp
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
                  fail!   (fn [reason]
                            (doseq [k (js/Array.from (.keys pending))]
                              (when-let [p (.get pending k)]
                                (.delete pending k)
                                ((.-reject p) (js/Error. reason)))))]
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
                                 (js/Promise.
                                  (fn [res rej]
                                    (let [id (swap! next-id inc)]
                                      (.set pending id #js {:resolve res :reject rej})
                                      (.send ws (js/JSON.stringify
                                                 (clj->js (cond-> {:id id :method method}
                                                            (some? params) (assoc :params params)))))))))
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
   Resolves to the new target id."
  ([client] (create-window client "about:blank"))
  ([{:keys [call]} url]
   (-> (call "Target.createTarget" {:url url :newWindow true})
       (.then (fn [res] (aget res "targetId"))))))
