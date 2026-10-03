(ns xi.web.user-ext.guard
  "Confines a user extension's web half in the browser. Pure: validation,
   wrapping, and hiccup sanitizing (xi.web.user-ext evaluates the code in the
   SCI sandbox, then applies this).

   A web half runs in your authenticated client, so beyond the SCI sandbox it
   must not be able to:
     - hijack built-in UI: page keywords are namespaced by the extension id
       (`:notes/list`), route segments can't shadow existing ones.
     - act as you: `dispatch!` only passes its own :ext.<id>/* events (sent to
       the server as :user-ext/forward) plus :route/navigate / :nav/back; nav
       items with any other :event are dropped.
     - inject script: page output is sanitized — dangerous tags, string event
       handlers, innerHTML/srcdoc and javascript:/vbscript:/data: URLs are
       removed before replicant renders it."
  (:require [clojure.string :as str]))

(def allowed-keys
  "Web-half keys a user extension may declare. No :handlers / :fx — those are
   baked into the web client at startup; logic lives in the server half."
  #{:id :routes :pages :nav-items :taps})

(def builtin-segments
  "First URL segments the web client routes itself."
  #{"chat" "projects" "git-status"})

(defn- own-ns? [id kw]
  (and (keyword? kw) (= (namespace kw) (name id))))

(defn validate
  "→ nil when `ext` is a usable web half, else a rejection reason."
  [ext {:keys [taken-ids taken-segments taken-pages]}]
  (let [id    (:id ext)
        pages (keys (:pages ext))
        segs  (keys (:routes ext))]
    (cond
      (not (map? ext))                "no `web-extension` map"
      (not (keyword? id))             "missing keyword :id"
      (contains? taken-ids id)        (str "id " id " already in use")
      (seq (remove allowed-keys (keys ext)))
      (str "disallowed keys: " (str/join ", " (sort (remove allowed-keys (keys ext)))))
      (seq (remove #(own-ns? id %) pages))
      (str "page keywords must be namespaced :" (name id) "/…: "
           (str/join ", " (remove #(own-ns? id %) pages)))
      (seq (filter #(or (contains? builtin-segments %) (contains? taken-segments %)) segs))
      (str "route segment already in use: "
           (str/join ", " (filter #(or (contains? builtin-segments %)
                                       (contains? taken-segments %)) segs)))
      (seq (filter #(contains? taken-pages %) pages))
      (str "page already in use: " (str/join ", " (filter #(contains? taken-pages %) pages)))
      :else nil)))

;; ── Hiccup sanitizing ────────────────────────────────────────────────────────

(def ^:private blocked-tags
  #{"script" "iframe" "frame" "frameset" "object" "embed" "applet" "portal"
    "link" "meta" "base" "style" "template" "noscript" "foreignobject"})

(def ^:private blocked-attrs
  #{"innerhtml" "outerhtml" "srcdoc" "dangerouslysetinnerhtml"})

(def ^:private url-attrs
  #{"href" "xlink:href" "src" "srcset" "action" "formaction" "data" "poster"
    "background" "cite" "codebase" "ping" "manifest"})

(def ^:private image-data-ok
  "Attributes where a data:image/… URL (non-SVG) may stand."
  #{"src" "srcset" "poster"})

(defn- tag-name [kw]
  (-> (name kw) (str/split #"[.#]" 2) first str/lower-case))

(defn- unsafe-url? [attr v]
  ;; browsers ignore control chars / whitespace inside the scheme
  (let [s (-> (str v) (str/replace #"[\x00-\x20]" "") str/lower-case)]
    (or (str/starts-with? s "javascript:")
        (str/starts-with? s "vbscript:")
        (and (str/starts-with? s "data:")
             (not (and (contains? image-data-ok attr)
                       (str/starts-with? s "data:image/")
                       (not (str/starts-with? s "data:image/svg"))))))))

(defn- safe-attr? [k v]
  (let [attr (str/lower-case (name k))]
    (cond
      (contains? blocked-attrs attr)                         false
      ;; replicant's `:on {:click f}` is the only event form allowed; any
      ;; other on* attribute (a string handler above all) is dropped
      (and (str/starts-with? attr "on") (not= attr "on"))    false
      (contains? url-attrs attr)                             (not (unsafe-url? attr v))
      :else                                                  true)))

(defn- sanitize-attrs [bind attrs]
  (let [attrs (into {} (filter (fn [[k v]] (safe-attr? k v))) (bind attrs))]
    (cond-> attrs
      (map? (:on attrs))
      (update :on #(into {} (remove (fn [[_ h]] (string? h))) %)))))

(defn sanitize
  "Strip anything script-capable from a hiccup tree (see ns doc). Blocked
   elements vanish with their children. `bind` (attrs → attrs, default
   identity) rewrites an element's attributes first (see `ui-binder`)."
  ([node] (sanitize node identity))
  ([node bind]
   (cond
     (and (vector? node) (keyword? (first node)))
     (when-not (contains? blocked-tags (tag-name (first node)))
       (let [[tag & more] node
             [attrs children] (if (map? (first more)) [(first more) (rest more)] [nil more])]
         (into (cond-> [tag] attrs (conj (sanitize-attrs bind attrs)))
               (map #(sanitize % bind))
               children)))

     (vector? node) (mapv #(sanitize % bind) node)
     (seq? node)    (map #(sanitize % bind) node)
     :else          node)))

;; ── Per-extension UI state ───────────────────────────────────────────────────

;; Page-local state that lives in the browser only, at [:user-ext/ui <id> & path]
;; of the app state (so a page reads it from the `state` it is rendered with).
;; It never reaches the server or other clients. The sandbox can't read a DOM
;; event, so the HOST wires an input to it: `:bind [:code]` among an input's
;; attributes becomes its :value plus an :input handler writing that path.
;; A page writes it itself with {:type :ext-ui/set :path [..] :value v}.

(defn- ui-path? [path]
  (and (vector? path) (seq path) (every? #(or (keyword? %) (string? %)) path)))

(defn- ui-value? [v]
  (or (nil? v) (string? v) (boolean? v) (number? v)))

(defn ui-binder
  "The `bind` fn for `sanitize`: turns an element's `:bind path` attribute into
   the current value of that path in extension `id`'s UI state (read from
   `state`) and an :input handler that writes it back through `dispatch!`
   (the host's, unfiltered). An invalid path just drops the attribute."
  [id state dispatch!]
  (fn [attrs]
    (if-not (contains? attrs :bind)
      attrs
      (let [path (:bind attrs)
            attrs (dissoc attrs :bind)]
        (if-not (ui-path? path)
          attrs
          (-> attrs
              (assoc :value (str (get-in state (into [:user-ext/ui id] path))))
              (assoc-in [:on :input]
                        (fn [e]
                          (dispatch! {:type :user-ext/ui-set :ext-id id :path path
                                      :value (some-> e .-target .-value)})))))))))

;; ── Wrapping ─────────────────────────────────────────────────────────────────

(def ^:private passthrough-events
  "Built-in web events a user web half may dispatch directly."
  #{:route/navigate :nav/back})

(defn- own-event? [id ev-type]
  (= (some-> ev-type namespace) (str "ext." (name id))))

(defn- log-blocked [id what]
  (js/console.error (str "[user-ext " (name id) "] blocked " what)))

(defn guard-dispatch
  "The dispatch! a web half gets: its own events go to the server via
   :user-ext/forward, navigation passes, anything else is dropped."
  [id dispatch!]
  (fn [ev]
    (let [t (:type ev)]
      (cond
        (own-event? id t)                  (dispatch! {:type :user-ext/forward :event ev})
        (= :ext-ui/set t)                  (if (and (ui-path? (:path ev)) (ui-value? (:value ev)))
                                             (dispatch! {:type :user-ext/ui-set :ext-id id
                                                         :path (:path ev) :value (:value ev)})
                                             (log-blocked id "ext-ui/set with a bad path or value"))
        (contains? passthrough-events t)   (dispatch! ev)
        :else                              (log-blocked id (str "dispatch " t))))))

(defn- error-box [id msg]
  [:div {:class "user-ext-error"}
   (str "Extension " (name id) " failed to render: " msg)])

(defn wrap
  "`ext` with every user fn confined: pages sanitized + guarded, taps guarded,
   route parse/path fns isolated, nav items limited to allowed events."
  [ext]
  (let [id (:id ext)]
    (cond-> ext
      (:pages ext)
      (update :pages update-vals
              (fn [f]
                (fn [state dispatch!]
                  (try (sanitize (f state (guard-dispatch id dispatch!))
                                 (ui-binder id state dispatch!))
                       (catch :default e (error-box id (.-message e)))))))

      (:taps ext)
      (update :taps
              (fn [taps]
                (mapv (fn [make-tap]
                        (fn [dispatch!]
                          (let [tap (make-tap (guard-dispatch id dispatch!))]
                            (fn [event state]
                              (try (tap event state)
                                   (catch :default e (log-blocked id (str "tap threw: " (.-message e)))))))))
                      taps)))

      (:routes ext)
      (update :routes update-vals
              (fn [entry]
                (cond-> entry
                  (:parse entry)
                  (update :parse (fn [p] (fn [segs]
                                           (try (p segs)
                                                (catch :default _ {:page :home})))))
                  (:path entry)
                  (update :path update-vals
                          (fn [pf] (fn [route] (try (pf route) (catch :default _ "/"))))))))

      (:nav-items ext)
      (update :nav-items
              (fn [items]
                (into []
                      (keep (fn [{:keys [event] :as item}]
                              (cond
                                (nil? event) item
                                ;; the core views dispatch :event as-is (merging the
                                ;; menu ctx in), so an own event must already be the
                                ;; forward wrapper; forward! unwraps it + the ctx keys
                                (own-event? id (:type event))
                                (assoc item :event {:type :user-ext/forward :event event})
                                (contains? passthrough-events (:type event)) item
                                :else (do (log-blocked id (str "nav item event " (:type event))) nil))))
                      items))))))

(defn forward-event
  "The event a :user-ext/forward wrapper sends to the server: the inner event,
   with any menu-ctx keys the core views merged into the wrapper and the
   active room id. nil unless the inner event is an extension event."
  [{:keys [event] :as wrapper} active-room-id]
  (let [ev (merge (dissoc wrapper :type :event) event)]
    (when (some-> (:type ev) namespace (str/starts-with? "ext."))
      (cond-> ev
        (and (nil? (:room-id ev)) active-room-id) (assoc :room-id active-room-id)))))
