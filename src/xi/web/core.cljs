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

(defn- compose-set-draft
  "Track the compose text per session so drafts survive navigation."
  [st {:keys [draft-key text]}]
  {:state (-> (if (seq text)
                (assoc-in st [:web/drafts draft-key] text)
                (update st :web/drafts dissoc draft-key))
              ;; Reset command suggestion selection when the input changes
              (assoc :web/cmd-selected 0))})

(defn- compose-clear-draft [st {:keys [draft-key]}]
  {:state (update st :web/drafts dissoc draft-key)})

(defn- timeline-set-window
  "Widen the virtualized timeline window (\"Show earlier messages\")."
  [st {:keys [window]}]
  {:state (assoc st :web/timeline-window window)})

(defn- lightbox-open [st {:keys [src]}]
  {:state (assoc st :web/lightbox src)})

(defn- lightbox-close [st _]
  {:state (dissoc st :web/lightbox)})

(defn- submit-pending
  "Stash a message when the user submits before the room exists (cached
   session view). The pending-submit-tap fires it after :room/joined."
  [st {:keys [session-id text images]}]
  {:state (assoc st :web/pending-submit
                 (cond-> {:session-id session-id :text text}
                   (seq images) (assoc :images images)))})

(defn- submit-clear-pending [st _]
  {:state (dissoc st :web/pending-submit)})

(defn- cmd-select [st {:keys [index]}]
  {:state (assoc st :web/cmd-selected (or index 0))})

(defn- theme-set-mode [st {:keys [mode]}]
  (let [m (if (#{"auto" "light" "dark"} mode) mode "auto")]
    {:state   (assoc st :web/theme-mode m)
     :effects [[:theme/apply m]]}))

;; ── GTD ──────────────────────────────────────────────────────────────────────

(defn- gtd-web-list-result
  "Store the GTD task list returned by the server."
  [st {:keys [tasks]}]
  {:state (assoc st :web/gtd-tasks tasks :web/gtd-loading? false)})

(defn- gtd-web-start-task
  "Click a GTD task: create a new room with the task's cwd, stash the
   task as pending-gtd so it fires :gtd/start-task after :room/joined."
  [st {:keys [task-id title cwd]}]
  {:state   (-> st
                (assoc :web/route {:page :chat})
                (assoc :web/pending-gtd {:task-id task-id :title title :cwd cwd})
                (assoc :web/timeline-window nil))
   :effects [[:ws/send {:type :room/join :target "new" :cwd cwd}]]})

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
          :compose/clear-images  compose-clear-images
          :compose/set-draft     compose-set-draft
          :compose/clear-draft   compose-clear-draft
          :timeline/set-window   timeline-set-window
          :lightbox/open         lightbox-open
          :lightbox/close        lightbox-close
          :submit/pending        submit-pending
          :submit/clear-pending  submit-clear-pending
          :cmd/select            cmd-select
          :theme/set-mode        theme-set-mode
          :gtd/web-list          (fn [st ev]
                                    {:state (assoc st :web/gtd-loading? true)
                                     :effects [[:ws/send (dissoc ev :event/id :event/ts)]]})
          :gtd/web-list-result   gtd-web-list-result
          :gtd/web-start-task    gtd-web-start-task
          :gtd/start-task        forward
          :gtd/clear-pending     (fn [st _] {:state (dissoc st :web/pending-gtd)})}))

(defn- web-effects []
  {:history/push router/history-effect
   :cache/watch  (fn [_ {:keys [session-id count]}] (cache/watch! session-id count))
   :theme/apply  (fn [_ mode]
                   (let [el js/document.documentElement]
                     ;; Suppress transitions during switch
                     (.setAttribute el "data-no-transitions" "")
                     (.-offsetHeight el)
                     (js/requestAnimationFrame
                      (fn [] (js/requestAnimationFrame
                              (fn [] (.removeAttribute el "data-no-transitions")))))
                     ;; Apply data-theme
                     (case mode
                       "light" (.setAttribute el "data-theme" "light")
                       "dark"  (.setAttribute el "data-theme" "dark")
                       (.removeAttribute el "data-theme"))
                     ;; Persist
                     (try
                       (if (= mode "auto")
                         (.removeItem js/localStorage "ui-theme")
                         (.setItem js/localStorage "ui-theme" mode))
                       (catch :default _))))})

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

(defn- pending-submit-tap
  "Fire a stashed message after the room it was aimed at finishes joining.
   Guards against session-id mismatch so a navigation race can't send a
   message into the wrong room."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-submit state))
      (let [{:keys [session-id text images]} (:web/pending-submit state)
            joined-sid (get-in event [:room :session :id])
            room-id    (:active-room state)]
        (when (or (nil? session-id) (= session-id joined-sid))
          (dispatch! {:type :submit/clear-pending})
          (dispatch! (cond-> {:type :input/submit :room-id room-id :text text}
                       (seq images) (assoc :images (vec images)))))))))

(defn- pending-gtd-tap
  "After room join, if there's a pending GTD task, dispatch :gtd/start-task
   to the server which handles activation + prompt submission."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-gtd state))
      (let [{:keys [task-id title]} (:web/pending-gtd state)
            room-id (:active-room state)]
        (dispatch! {:type :gtd/start-task :room-id room-id
                    :task-id task-id :title title})
        (dispatch! {:type :gtd/clear-pending})))))

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

(defn- ws-url
  "WS server URL. Served by the Bun server itself → same port as the page;
   shadow dev-http (8100) isn't the WS server → default 7474. ?host/?port
   query params override."
  []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        proto  (if (= "https:" js/location.protocol) "wss://" "ws://")
        host   (or (.get params "host") js/location.hostname)
        page-port (let [p js/location.port]
                    (when-not (or (= p "") (= p "8100")) p))
        port   (or (.get params "port") page-port "7474")]
    (str proto host ":" port)))

(defn ^:export init! []
  (js/console.log "[xi-web] starting")
  (r/set-dispatch! (fn [_ _]))
  (let [stored-theme (or (try (.getItem js/localStorage "ui-theme") (catch :default _ nil))
                        "auto")
        route     (router/parse-path (.-pathname js/window.location))
        initial   (-> (state/initial-state {:mode :client})
                      (assoc :web/theme-mode stored-theme)
                      (cache/hydrate route))
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
    (add-tap! (pending-submit-tap dispatch!))
    (add-tap! (pending-gtd-tap dispatch!))
    (router/init! dispatch!)
    ;; Apply stored theme immediately (before first render)
    (dispatch! {:type :theme/set-mode :mode stored-theme})
    ;; Track visual viewport height so the mobile keyboard doesn't push
    ;; the compose box off-screen.  Falls back to window.innerHeight.
    (let [set-vh! (fn []
                    (let [h (if js/window.visualViewport
                              (.-height js/window.visualViewport)
                              js/window.innerHeight)]
                      (.setProperty (.-style js/document.documentElement)
                                    "--app-height" (str h "px"))
                      ;; iOS Safari scrolls the page when the keyboard opens,
                      ;; creating a gap between compose box and keyboard.
                      ;; Force scroll back to origin so the fixed layout stays
                      ;; pinned to the top of the visual viewport.
                      (.scrollTo js/window 0 0)))]
      (set-vh!)
      (if js/window.visualViewport
        (do (.addEventListener js/window.visualViewport "resize" (fn [_] (set-vh!)))
            (.addEventListener js/window.visualViewport "scroll" (fn [_] (set-vh!))))
        (.addEventListener js/window "resize" (fn [_] (set-vh!)))))
    (.addEventListener js/document "visibilitychange"
                       (fn [_]
                         (dispatch! {:type :client/update
                                     :visible? (= "visible" (.-visibilityState js/document))})))
    (render! @state dispatch!)))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (when-let [{:keys [state dispatch!]} @app-ref]
    (render! @state dispatch!)))
