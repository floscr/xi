(ns xi.web.user-ext
  "Browser side of user extensions' web halves (main bundle — kept small).

   Once connected, the client asks the server for the web halves
   (:user-ext/web-sources, roomless). When some exist, the `:user-ext` shadow
   module (SCI + xi.web.user-ext.sci) is loaded lazily, the halves are
   evaluated in the sandbox, and their pages / routes / nav items / sidebar
   groups / session menu items / taps are added to the running web client. Their own events reach the server via
   :user-ext/forward (see xi.web.user-ext.guard).

   Only the surfaces the web client can extend after startup are supported:
   :pages (pages-ref), :routes (routes-ref), :nav-items, :sidebar-groups and
   :session-menu-items (state), :taps and :tool-views (xi.web.tool-views), :palette-items (xi.web.palette-items),
   and :handlers — reducers over the extension's own browser slice at
   [:user-ext/state <id>], applied when its server half pushes one of its
   events to this user (:user-ext/push, see xi.server.ws). App handlers/fx
   are baked in at startup — the logic of a user extension lives in its
   server half."
  (:require [clojure.string :as str]
            [shadow.lazy :as lazy]
            [xi.core.state :as state]
            [xi.web.palette-items :as palette-items]
            [xi.web.router :as router]
            [xi.web.tool-views :as tool-views]
            [xi.web.user-ext.guard :as guard]))

(def ^:private evaluator (lazy/loadable xi.web.user-ext.sci/load!))

(defonce ^:private requested? (atom false))

(defonce ^:private reducers
  ;; ext-id → {event-type (fn [slice event] → slice')}, the guarded :handlers
  ;; of every loaded web half (xi.web.user-ext.guard/wrap)
  (atom {}))

(defn register-handlers!
  "Install a loaded web half's (guarded) :handlers for `:user-ext/push`."
  [ext-id handlers]
  (swap! reducers assoc ext-id handlers))

(defn request-tap
  "App tap: on the first connect, ask the server for user web halves."
  [dispatch!]
  (fn [event _state]
    (when (and (= :connection/status (:type event))
               (:connected? event)
               (compare-and-set! requested? false true))
      (dispatch! {:type :user-ext/request}))))

(def handlers
  "Local (client-only) handlers."
  {:user-ext/request
   (fn [_st _ev] {:effects [[:ws/send {:type :user-ext/web-sources}]]})

   :user-ext/web-sources-result
   (fn [_st {:keys [extensions]}]
     (when (seq extensions)
       {:effects [[:user-ext/load {:bundles extensions}]]}))

   ;; always a state change: tool blocks already on screen re-render with the
   ;; tool views that just registered
   :user-ext/loaded
   (fn [st {:keys [nav-items sidebar-groups session-menu-items]}]
     {:state (cond-> (assoc st :user-ext/loaded? true)
               (seq nav-items)          (update :web/nav-items (fnil into []) nav-items)
               (seq sidebar-groups)     (update :web/sidebar-groups (fnil into []) sidebar-groups)
               (seq session-menu-items) (update :web/session-menu-items (fnil into [])
                                                session-menu-items))})

   ;; a user extension's room slice changed server-side (xi.ext.user.guard);
   ;; the browser can't replay that extension's server reducer, so the slice
   ;; arrives whole
   :user-ext/sync
   (fn [st {:keys [room-id ext-id state]}]
     (when (get-in st [:rooms room-id])
       {:state (assoc-in st [:rooms room-id :ext ext-id] state)}))

   ;; a user extension's server half pushed one of its own events to this
   ;; user (:to-users / :to-client, xi.server.ws): the web half's reducer for
   ;; it updates the extension's browser slice, which its pages render
   :user-ext/push
   (fn [st {:keys [ext-id event]}]
     (when-let [f (get-in @reducers [ext-id (:type event)])]
       (let [path   [:user-ext/state ext-id]
             before (get-in st path)
             after  (f before event)]
         (when (not= before after)
           {:state (assoc-in st path after)}))))

   ;; a page's browser-only UI state (xi.web.user-ext.guard, "Per-extension
   ;; UI state"): a bound input's text, or an :ext-ui/set from the page
   :user-ext/ui-set
   (fn [st {:keys [ext-id path value]}]
     {:state (assoc-in st (into [:user-ext/ui ext-id] path) value)})

   :user-ext/forward
   (fn [st ev]
     (when-let [out (guard/forward-event ev (:id (state/active-room st)))]
       {:effects [[:ws/send out]]}))})

(defn- first-segment [path]
  (first (filter seq (str/split (or path "/") #"/"))))

(defn fx
  "Effects, closed over the web client's live registries: `pages-ref` /
   `routes-ref` (atoms the renderer / router read) and `app-ref` (for
   add-tap!). `taken` → {:taken-ids :taken-segments :taken-pages}."
  [{:keys [pages-ref routes-ref app-ref builtin-ids]}]
  {:user-ext/load
   (fn [{:keys [dispatch!]} {:keys [bundles]}]
     (lazy/load
      evaluator
      (fn [load!]
        (let [results (load! bundles {:taken-ids      (set builtin-ids)
                                      :taken-segments (set (keys @routes-ref))
                                      :taken-pages    (set (keys @pages-ref))})
              ok      (keep :web-ext results)
              added   (set (mapcat (comp keys :routes) ok))]
          (doseq [{:keys [id error]} results :when error]
            (js/console.error (str "[user-ext] rejected web half of " (name id) ": " error)))
          (swap! pages-ref merge (apply merge {} (map :pages ok)))
          (swap! routes-ref merge (apply merge {} (map :routes ok)))
          (doseq [{:keys [id tool-views]} ok :when tool-views]
            (tool-views/register! id tool-views))
          (doseq [{:keys [id palette-items]} ok :when palette-items]
            (palette-items/register! id palette-items))
          (doseq [{:keys [id handlers]} ok :when handlers]
            (register-handlers! id handlers))
          (when-let [add-tap! (:add-tap! @app-ref)]
            (doseq [make-tap (mapcat :taps ok)]
              (add-tap! (make-tap dispatch!))))
          (dispatch! {:type :user-ext/loaded
                      :nav-items (vec (mapcat :nav-items ok))
                      :sidebar-groups (vec (mapcat :sidebar-groups ok))
                      :session-menu-items (vec (mapcat :session-menu-items ok))})
          ;; a deep link into a user page landed before its route existed
          (let [path (.-pathname js/window.location)]
            (when (contains? added (first-segment path))
              (dispatch! (assoc (router/parse-path routes-ref path)
                                :type :route/navigate :replace? true))))))
      (fn [err]
        (js/console.error "[user-ext] loading the extension sandbox failed" err))))})
