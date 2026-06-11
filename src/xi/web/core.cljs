(ns xi.web.core
  "Web client entry — the browser app shell.

   Same pure core as every other mode (xi.core.app), wired in :client mode
   through xi.client.ws-transport: local events forward to the server,
   :remote? broadcasts mirror into the local room cache with effects
   stripped. The browser only renders and collects input.

   Phase 7b: home/session list + deep-link routing + offline cache. The
   router lives in the single atom (:web/route); the cache hydrates state
   before the WS connects and persists via an app tap. Saved sessions, live
   rooms, unread dots and reconnect come from the lobby mirror + transport."
  (:require [replicant.dom :as r]
            [xi.agent :as agent]
            [xi.client.ws-transport :as ws-transport]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.web.cache :as cache]
            [xi.web.router :as router]
            [xi.web.views :as views]))

;; ── Base handlers (browser-safe merge) ───────────────────────────────────────

(defn- base-handlers
  "The pure handler map shared with the server, sans node-coupled chains.
   Effects are stripped on mirror, so the simple merge suffices for a
   read-and-forward client."
  []
  (merge events/core-handlers
         agent/handlers
         (commands/command-handlers)
         compaction/handlers))

;; ── Web-local handlers (installed unwrapped; never mirrored) ──────────────────

(defn- forward
  "Forward an originating client event to the server."
  [_st ev]
  {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]})

(defn- room-new
  "Start a fresh room and switch to the chat view; the real session id fills
   the URL once :room/joined arrives."
  [st _]
  {:state   (assoc st :web/route {:page :chat :session-id nil})
   :effects [[:history/push {:route {:page :chat}}]
             [:ws/send {:type :room/join :target "new"}]]})

(defn- counts-result
  "Store per-session response counts from a :session/counts reply."
  [st {:keys [counts]}]
  {:state (assoc st :web/response-counts (or counts {}))})

(defn- mark-read
  "Mark a session read at its current response count (clears the unread dot)."
  [st {:keys [session-id]}]
  (let [cnt (get-in st [:web/response-counts session-id] 0)]
    {:state   (assoc-in st [:web/watched session-id] cnt)
     :effects [[:cache/watch {:session-id session-id :count cnt}]]}))

(defn- connection-status [st {:keys [connected?]}]
  {:state (assoc st :web/connected? connected?)})

(defn- compose-add-images
  "Stage client-resized images ({:data b64 :media-type mime}) for the next
   prompt; they ride along on :input/submit and clear on send."
  [st {:keys [images]}]
  {:state (update st :web/compose-images (fnil into []) images)})

(defn- compose-remove-image [st {:keys [idx]}]
  {:state (update st :web/compose-images
                  (fn [imgs] (into (subvec imgs 0 idx) (subvec imgs (inc idx)))))})

(defn- compose-clear-images [st _]
  {:state (assoc st :web/compose-images [])})

(defn- web-handlers []
  (merge router/handlers
         {:room/new              room-new
          :room/join             forward
          :room/leave            forward
          :session/counts        forward
          :session/counts-result counts-result
          :session/mark-read     mark-read
          :connection/status     connection-status
          :compose/add-images    compose-add-images
          :compose/remove-image  compose-remove-image
          :compose/clear-images  compose-clear-images}))

(defn- web-effects []
  {:history/push router/history-effect
   :cache/watch  (fn [_ {:keys [session-id count]}] (cache/watch! session-id count))})

;; ── Taps (cache persistence + unread polling + post-join URL) ─────────────────

(defn- request-counts-tap
  "On a fresh lobby, ask the server for response counts of the saved
   sessions so the home view can flag unread ones."
  [dispatch!]
  (fn [event state]
    (when (= :lobby/state (:type event))
      (when-let [sids (seq (keep :session-id (get-in state [:lobby :sessions])))]
        (dispatch! {:type :session/counts :session-ids (vec sids)})))))

(defn- fill-url-tap
  "After joining a fresh room (URL has no session id yet), replace the URL
   with the real session id so reload resumes the same session."
  [dispatch!]
  (fn [event state]
    (when (= :room/joined (:type event))
      (let [sid (get-in event [:room :session :id])]
        (when (and sid
                   (= :chat (get-in state [:web/route :page]))
                   (nil? (get-in state [:web/route :session-id])))
          (dispatch! {:type :route/navigate :page :chat
                      :session-id sid :replace? true}))))))

;; ── Auto-scroll ──────────────────────────────────────────────────────────────

(defonce ^:private auto-scroll? (atom true))
(defonce ^:private tracked-timeline (atom nil))

(defn- at-bottom? [^js el]
  (<= (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 40))

(defn- attach-scroll-listener! []
  (when-let [timeline (.querySelector js/document ".timeline")]
    (when-not (identical? timeline @tracked-timeline)
      (reset! tracked-timeline timeline)
      (reset! auto-scroll? true)
      (.addEventListener timeline "scroll"
                         (fn [] (reset! auto-scroll? (at-bottom? timeline)))))))

(defn- scroll-to-bottom! []
  (when @auto-scroll?
    (when-let [timeline (.querySelector js/document ".timeline")]
      (set! (.-scrollTop timeline) (.-scrollHeight timeline)))))

;; ── Render ───────────────────────────────────────────────────────────────────

(defn- el [id] (.getElementById js/document id))

(defn- render! [app-state dispatch!]
  (r/render (el "app") (views/root-view app-state dispatch!))
  (attach-scroll-listener!)
  (js/requestAnimationFrame scroll-to-bottom!))

;; ── Init ─────────────────────────────────────────────────────────────────────

(defonce ^:private app-ref (atom nil))
(defonce ^:private dispatch-ref (atom nil))

(defn- ws-url []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        proto  (if (= "https:" js/location.protocol) "wss://" "ws://")
        host   (or (.get params "host") js/location.hostname)
        port   (or (.get params "port") "7474")]
    (str proto host ":" port)))

(defn ^:export init! []
  (js/console.log "[xi-web] starting")
  (r/set-dispatch! (fn [_ _]))
  (let [route     (router/parse-path (.-pathname js/window.location))
        initial   (cache/hydrate (state/initial-state {:mode :client}) route)
        transport (ws-transport/create!
                   {:url        (ws-url)
                    ;; nil → the router drives joins; reconnect replays them.
                    :target     nil
                    :reconnect? true
                    :on-status  (fn [connected?]
                                  (when-let [d @dispatch-ref]
                                    (d {:type :connection/status
                                        :connected? connected?})))})
        {:keys [dispatch! state add-tap!] :as app}
        (app/create-app {:initial-state initial
                         :handlers      (ws-transport/make-handlers
                                         (base-handlers)
                                         {:local-handlers (web-handlers)})
                         :effects       (merge (:effects transport) (web-effects))
                         :on-render     render!})]
    (reset! dispatch-ref dispatch!)
    (reset! app-ref app)
    ((:set-dispatch! transport) dispatch!)
    (add-tap! cache/persist-tap)
    (add-tap! (request-counts-tap dispatch!))
    (add-tap! (fill-url-tap dispatch!))
    (router/init! dispatch!)
    (.addEventListener js/document "visibilitychange"
                       (fn [_]
                         (dispatch! {:type :client/update
                                     :visible? (= "visible" (.-visibilityState js/document))})))
    (render! @state dispatch!)))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (when-let [{:keys [state dispatch!]} @app-ref]
    (render! @state dispatch!)))
