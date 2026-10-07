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
  "Web-half keys a user extension may declare. No :fx; :handlers are reducers
   over the extension's own browser slice only (see `wrap`) — logic lives in
   the server half."
  #{:id :routes :pages :nav-items :palette-items :taps :tool-views :sidebar-groups
    :session-menu-items :handlers})

(def builtin-segments
  "First URL segments the web client routes itself."
  #{"chat" "projects" "git-status"})

(defn- own-ns? [id kw]
  (and (keyword? kw) (= (namespace kw) (name id))))

(defn- own-event-type? [id kw]
  (and (keyword? kw) (= (namespace kw) (str "ext." (name id)))))

(defn- own-state-path?
  "A `:badge-path` may only read the extension's own browser slice."
  [id path]
  (and (vector? path) (= :user-ext/state (first path)) (= id (second path))))

(defn- valid-group?
  "A `:sidebar-groups` entry: a map with an :id namespaced by the extension (so
   it can't take over a core group's collapsed state), a string :label, a
   keyword :where, an optional positive integer :limit and an optional :more
   row {:label …}."
  [id g]
  (and (map? g)
       (own-ns? id (:id g))
       (string? (:label g))
       (keyword? (:where g))
       (or (nil? (:limit g)) (pos-int? (:limit g)))
       (or (nil? (:more g))
           (and (map? (:more g)) (string? (:label (:more g)))))))

(defn- valid-menu-item?
  "A `:session-menu-items` entry: a map with a string :label, an optional
   string :label-on and keyword :flag, and an :event map."
  [m]
  (and (map? m)
       (string? (:label m))
       (or (nil? (:label-on m)) (string? (:label-on m)))
       (or (nil? (:flag m)) (keyword? (:flag m)))
       (map? (:event m))))

(defn validate
  "→ nil when `ext` is a usable web half, else a rejection reason.
   `own-tools` — the tool names its server half defines; :tool-views may only
   render those."
  [ext {:keys [taken-ids taken-segments taken-pages own-tools]}]
  (let [id    (:id ext)
        pages (keys (:pages ext))
        segs  (keys (:routes ext))
        views (:tool-views ext)
        alien (when (map? views) (remove (set own-tools) (keys views)))]
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
      (and (some? (:sidebar-groups ext))
           (not (and (sequential? (:sidebar-groups ext))
                     (every? #(valid-group? id %) (:sidebar-groups ext)))))
      ":sidebar-groups must be maps {:id :<ext>/… :label str :where kw [:limit n] [:more {:label str …}]}"
      (and (some? (:session-menu-items ext))
           (not (and (sequential? (:session-menu-items ext))
                     (every? valid-menu-item? (:session-menu-items ext)))))
      ":session-menu-items must be maps {:label str :event {…} [:label-on str] [:flag kw]}"
      (and (some? (:palette-items ext)) (not (fn? (:palette-items ext))))
      ":palette-items must be a fn of the app state → [{:label str :icon kw :event {…}}]"
      (and (some? views) (not (map? views)))
      ":tool-views must be a map of tool name → fn"
      (seq alien)
      (str ":tool-views may only render the extension's own tools: " (str/join ", " alien))
      (and (some? (:handlers ext))
           (not (and (map? (:handlers ext))
                     (every? (fn [[k f]] (and (own-event-type? id k) (fn? f)))
                             (:handlers ext)))))
      (str ":handlers must be a map of :ext." (name id) "/… event types to fns")
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

(defn- enter-handler
  "Enter in a bound field (`:on-enter event`): send the extension's own event
   with the field's text as :text, then clear the field. Shift+Enter keeps
   its newline. iOS Safari's Return fires no Enter keydown in a textarea but
   a `beforeinput` of type insertLineBreak, so both paths lead here."
  [id path event dispatch!]
  (fn [e]
    (.preventDefault e)
    (let [text (str (some-> e .-target .-value))]
      (dispatch! {:type :user-ext/forward :event (assoc event :text text)})
      (dispatch! {:type :user-ext/ui-set :ext-id id :path path :value ""}))))

(defn ui-binder
  "The `bind` fn for `sanitize`: turns an element's `:bind path` attribute into
   the current value of that path in extension `id`'s UI state (read from
   `state`) and an :input handler that writes it back through `dispatch!`
   (the host's, unfiltered). An invalid path just drops the attribute.

   A bound field may also carry `:on-enter {:type :ext.<id>/… …}`: Enter
   (without Shift) sends that own event to the server with the field's text
   as :text and clears the field — the sandbox can't read key events itself."
  [id state dispatch!]
  (fn [attrs]
    (if-not (contains? attrs :bind)
      (dissoc attrs :on-enter)
      (let [path     (:bind attrs)
            on-enter (:on-enter attrs)
            attrs    (dissoc attrs :bind :on-enter)]
        (if-not (ui-path? path)
          attrs
          (cond-> (-> attrs
                      (assoc :value (str (get-in state (into [:user-ext/ui id] path))))
                      (assoc-in [:on :input]
                                (fn [e]
                                  (dispatch! {:type :user-ext/ui-set :ext-id id :path path
                                              :value (some-> e .-target .-value)}))))
            (and (map? on-enter) (own-event-type? id (:type on-enter)))
            (as-> a
              (let [send! (enter-handler id path on-enter dispatch!)]
                (-> a
                    (assoc-in [:on :keydown]
                              (fn [e]
                                (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                                  (send! e))))
                    (assoc-in [:on :beforeinput]
                              (fn [e]
                                (when (= "insertLineBreak" (.-inputType e))
                                  (send! e)))))))))))))

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

(defn- forwardable
  "`event` as the core views may dispatch it for extension `id` (they add the
   menu or card ctx to it as-is): an own event becomes the forward wrapper,
   navigation passes, anything else is blocked — nil."
  [id event]
  (cond
    (own-event? id (:type event))
    {:type :user-ext/forward :event event}

    (contains? passthrough-events (:type event))
    event

    :else (do (log-blocked id (str "event " (:type event))) nil)))

(defn- error-box [id msg]
  [:div {:class "user-ext-error"}
   (str "Extension " (name id) " failed to render: " msg)])

(defn wrap
  "`ext` with every user fn confined: pages and tool views sanitized + guarded,
   taps guarded, route parse/path fns isolated, nav items limited to allowed
   events, handlers reduced to their own slice. A throwing tool view yields
   nil, so the block shows its plain text.

   `:handlers` are `(fn [slice event] → slice')` over the extension's browser
   slice at [:user-ext/state <id>]; the host (xi.web.user-ext) applies them to
   events its server half pushed (:user-ext/push). They see nothing else and
   get no dispatch!. A handler that throws or returns a non-map keeps the
   slice as it was."
  [ext]
  (let [id (:id ext)]
    (cond-> ext
      (:handlers ext)
      (update :handlers update-vals
              (fn [f]
                (fn [slice event]
                  (try (let [r (f slice event)]
                         (if (map? r) r slice))
                       (catch :default e
                         (log-blocked id (str "handler threw: " (.-message e)))
                         slice)))))

      (:pages ext)
      (update :pages update-vals
              (fn [f]
                (fn [state dispatch!]
                  (try (sanitize (f state (guard-dispatch id dispatch!))
                                 (ui-binder id state dispatch!))
                       (catch :default e (error-box id (.-message e)))))))

      (map? (:tool-views ext))
      (update :tool-views update-vals
              (fn [f]
                (fn [call slice]
                  (try (sanitize (f call slice))
                       (catch :default e
                         (log-blocked id (str "tool view threw: " (.-message e)))
                         nil)))))

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
                                ;; a badge may read the extension's own slice only
                                (and (contains? item :badge-path)
                                     (not (own-state-path? id (:badge-path item))))
                                (do (log-blocked id "nav item :badge-path outside [:user-ext/state <id> …]")
                                    (recur (dissoc item :badge-path)))
                                (nil? event) item
                                ;; the core views dispatch :event as-is (merging the
                                ;; menu ctx in), so an own event must already be the
                                ;; forward wrapper; forward! unwraps it + the ctx keys
                                (own-event? id (:type event))
                                (assoc item :event {:type :user-ext/forward :event event})
                                (contains? passthrough-events (:type event)) item
                                :else (do (log-blocked id (str "nav item event " (:type event))) nil))))
                      items)))

      (:palette-items ext)
      (update :palette-items
              (fn [f]
                (fn [state]
                  (try (into []
                             (keep (fn [item]
                                     (when (and (map? item) (string? (:label item)))
                                       (when-let [event (forwardable id (:event item))]
                                         (assoc item :event event)))))
                             (f state))
                       (catch :default e
                         (log-blocked id (str "palette items threw: " (.-message e)))
                         [])))))

      (:session-menu-items ext)
      (update :session-menu-items
              (fn [items]
                (into []
                      (keep (fn [item]
                              (when-let [event (forwardable id (:event item))]
                                (assoc item :event event))))
                      items)))

      (:sidebar-groups ext)
      (update :sidebar-groups
              (fn [groups]
                (mapv (fn [g]
                        (cond-> g
                          (:more g)
                          (update :more (fn [more]
                                          (when-let [event (forwardable id (:event more))]
                                            (assoc more :event event))))))
                      groups))))))

(defn forward-event
  "The event a :user-ext/forward wrapper sends to the server: the inner event,
   with any menu-ctx keys the core views merged into the wrapper and the
   active room id. nil unless the inner event is an extension event."
  [{:keys [event] :as wrapper} active-room-id]
  (let [ev (merge (dissoc wrapper :type :event) event)]
    (when (some-> (:type ev) namespace (str/starts-with? "ext."))
      (cond-> ev
        (and (nil? (:room-id ev)) active-room-id) (assoc :room-id active-room-id)))))
