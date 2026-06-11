(ns xi.web.core
  "Web client entry — the browser app shell.

   Same pure core as every other mode (xi.core.app), wired in :client mode
   through xi.client.ws-transport: local events forward to the server,
   :remote? broadcasts mirror into the local room cache with effects
   stripped. The browser only renders and collects input.

   Phase 7a: online-only chat. Connects to the hosting server over WS,
   joins the latest room, renders the chat view with Replicant. Home view,
   router and offline cache arrive in 7b."
  (:require [replicant.dom :as r]
            [xi.agent :as agent]
            [xi.client.ws-transport :as ws-transport]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
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

(defn ^:export init! []
  (js/console.log "[xi-web] starting")
  (r/set-dispatch! (fn [_ _]))
  (let [transport (ws-transport/create!
                   {:url    (str (if (= "https:" js/location.protocol) "wss://" "ws://")
                                 js/location.host)
                    :target "latest"
                    :on-close (fn [] (js/console.error "[xi-web] disconnected"))})
        {:keys [dispatch!] :as app}
        (app/create-app {:initial-state (state/initial-state {:mode :client})
                         :handlers      (ws-transport/make-handlers (base-handlers))
                         :effects       (:effects transport)
                         :on-render     render!})]
    ((:set-dispatch! transport) dispatch!)
    (reset! app-ref app)
    ;; First paint (connecting placeholder until :room/joined arrives).
    (render! @(:state app) dispatch!)))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (when-let [{:keys [state dispatch!]} @app-ref]
    (render! @state dispatch!)))
