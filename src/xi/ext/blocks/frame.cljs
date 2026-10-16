(ns xi.ext.blocks.frame
  "MCP Apps (SEP-1865) view host in the browser, for agent blocks of the
   internal kind \"app\" (xi.ext.blocks.app has the server side).

   The view is the resource's HTML in an iframe with `sandbox=\"allow-scripts\"`
   and `srcdoc`: an opaque origin with no access to Xi's page, storage or
   cookies, which stands in for the spec's separate-origin sandbox proxy (the
   view only ever posts to its parent, so it can't tell). The resource's CSP
   goes in as the document's first <meta http-equiv>, restrictive by default.

   The bridge speaks JSON-RPC over postMessage with frames this host created
   only (by event.source): `ui/initialize` → host context (theme, Xi's tokens
   as the spec's style variables, display mode, sizes); `initialized` → the
   tool input and result; `tools/call`, `resources/read` and
   `ui/update-model-context` go to the server (`:ext.blocks/app-rpc`);
   `ui/message` prefills the message box (never sends); `ui/open-link` asks
   first; `ui/request-display-mode` toggles a host-drawn full-screen frame;
   `size-changed` resizes within bounds."
  (:require [clojure.string :as str]
            [xi.blocks :as b]
            [xi.blocks.csp :as csp]))

;; contentWindow → {:block-id :room-id :iframe :record :modes}. A js/Map: a
;; ClojureScript hash map would hash the window, which sets a property on it,
;; and an opaque-origin window refuses that.
(defonce ^:private frames (js/Map.))

(defn- frame-entries [] (vec (es6-iterator-seq (.entries frames))))
;; block-id → {:html s} | {:error s}
(defonce ^:private htmls (atom {}))
;; rpc-id → {:win :id}
(defonce ^:private pending (atom {}))
;; the web app's dispatch!, set when a frame mounts
(defonce ^:private dispatch-ref (atom nil))

(def ^:private protocol-version "2026-01-26")
(def ^:private max-height 900)
(def ^:private min-height 60)

;; ── Host context ─────────────────────────────────────────────────────────────

(def ^:private style-vars
  "Spec style variable → the Xi token it reads."
  {"--color-background-primary"   "--bg-0"
   "--color-background-secondary" "--bg-1"
   "--color-background-tertiary"  "--bg-2"
   "--color-text-primary"         "--fg-0"
   "--color-text-secondary"       "--fg-1"
   "--color-text-tertiary"        "--fg-2"
   "--color-text-info"            "--accent"
   "--color-text-danger"          "--danger"
   "--color-text-success"         "--success"
   "--color-text-warning"         "--warning"
   "--color-border-primary"       "--border-0"
   "--color-ring-primary"         "--accent"
   "--font-mono"                  "--font-mono"
   "--border-radius-sm"           "--radius-sm"
   "--border-radius-md"           "--radius-md"
   "--border-radius-lg"           "--radius-lg"})

(defn- host-styles []
  (let [cs (js/getComputedStyle js/document.documentElement)]
    (into {"--font-sans" (.-fontFamily (js/getComputedStyle js/document.body))}
          (keep (fn [[k token]]
                  (let [v (str/trim (.getPropertyValue cs token))]
                    (when (seq v) [k v]))))
          style-vars)))

(defn- theme []
  (let [t (.getAttribute js/document.documentElement "data-theme")]
    (cond
      (#{"light" "dark"} t) t
      (.-matches (js/matchMedia "(prefers-color-scheme: dark)")) "dark"
      :else "light")))

(defn- host-context [{:keys [record fullscreen?]}]
  {:theme                 (theme)
   :styles                {:variables (host-styles)}
   :displayMode           (if fullscreen? "fullscreen" "inline")
   :availableDisplayModes ["inline" "fullscreen"]
   :containerDimensions   (if fullscreen? {:height (.-innerHeight js/window)} {:maxHeight max-height})
   :locale                (.-language js/navigator)
   :timeZone              (.. (js/Intl.DateTimeFormat) resolvedOptions -timeZone)
   :platform              (if (.-matches (js/matchMedia "(pointer: coarse)")) "mobile" "web")
   :userAgent             "xi"
   :deviceCapabilities    {:touch (.-matches (js/matchMedia "(pointer: coarse)"))
                           :hover (.-matches (js/matchMedia "(hover: hover)"))}
   :toolInfo              {:tool {:name (or (get-in record [:spec :tool :name]) "")}}})

;; ── Messages ─────────────────────────────────────────────────────────────────

(defn- post! [^js win msg]
  ;; a srcdoc view in a sandbox without allow-same-origin has the opaque
  ;; origin "null": "*" is the only target that reaches it
  (.postMessage win (clj->js (assoc msg :jsonrpc "2.0")) "*"))

(defn- respond! [win id result] (post! win {:id id :result result}))
(defn- fail! [win id code message] (post! win {:id id :error {:code code :message message}}))

(defn- notify-input-and-result! [win {:keys [record]}]
  (let [{:keys [tool-input tool-result]} (:spec record)]
    (post! win {:method "ui/notifications/tool-input" :params {:arguments (or tool-input {})}})
    (post! win {:method "ui/notifications/tool-result"
                :params (cond-> {:content (vec (:content tool-result)) :isError false}
                          (:structured-content tool-result)
                          (assoc :structuredContent (:structured-content tool-result)))})))

(defn- message-text [{:keys [content]}]
  (->> (if (sequential? content) content [content])
       (keep #(when (= "text" (:type %)) (:text %)))
       (str/join "\n")))

(defn- open-link! [win id {:keys [record]} {:keys [url]}]
  (let [u (str url)]
    (if-not (re-matches #"(?i)https?://\S+" u)
      (fail! win id -32000 "Invalid URL")
      (if (js/confirm (str "The app view \"" (or (:title record) "app") "\" wants to open:\n\n" u))
        (do (.open js/window u "_blank" "noopener,noreferrer")
            (respond! win id {}))
        (fail! win id -32000 "Link opening denied by user")))))

(defn- forward! [win id {:keys [block-id room-id]} method params]
  (let [rpc-id (str (random-uuid))]
    (swap! pending assoc rpc-id {:win win :id id})
    (when-let [d @dispatch-ref]
      (d {:type :ext.blocks/app-rpc :room-id room-id :block-id block-id
          :rpc-id rpc-id :method method :params params}))))

(defn- set-mode! [win id {:keys [block-id modes iframe]} {:keys [mode]}]
  (let [mode (if (and (#{"inline" "fullscreen"} mode)
                      (or (empty? modes) (some #{mode} modes)))
               mode
               (if (some-> iframe (.closest ".agent-block--fullscreen")) "fullscreen" "inline"))]
    (when-let [d @dispatch-ref]
      (d {:type :ext.blocks/app-display :block-id block-id :fullscreen? (= "fullscreen" mode)}))
    (respond! win id {:mode mode})
    (post! win {:method "ui/notifications/host-context-changed" :params {:displayMode mode}})))

(defn- resize! [{:keys [iframe]} {:keys [height]}]
  (when (and iframe (number? height) (not (.closest iframe ".agent-block--fullscreen")))
    (set! (.. iframe -style -height)
          (str (max min-height (min max-height (js/Math.ceil height))) "px"))))

(defn- handle! [^js win frame {:keys [id method params]}]
  (case method
    "ui/initialize"
    (do (.set frames win (assoc (.get frames win) :modes (vec (get-in params [:appCapabilities :availableDisplayModes]))))
        (respond! win id {:protocolVersion  protocol-version
                          :hostInfo         {:name "xi" :version "1"}
                          :hostCapabilities {:openLinks {} :serverTools {} :serverResources {} :logging {}
                                             :sandbox {:csp (get-in frame [:record :spec :csp] {})}}
                          :hostContext      (host-context frame)}))

    "ui/notifications/initialized"
    (notify-input-and-result! win frame)

    "ping" (respond! win id {})

    ("tools/call" "resources/read" "ui/update-model-context")
    (forward! win id frame method params)

    "ui/message"
    (if-let [text (not-empty (message-text params))]
      (do (some-> @dispatch-ref (apply [{:type :ext.blocks/app-message :text text}]))
          (respond! win id {}))
      (fail! win id -32000 "Invalid message format"))

    "ui/open-link" (open-link! win id frame params)

    "ui/request-display-mode" (set-mode! win id frame params)

    "ui/notifications/size-changed" (resize! frame params)

    "notifications/message" (js/console.debug "[app view]" (pr-str params))

    (when (some? id) (fail! win id -32601 (str "Method not found: " method)))))

(defn- on-message [^js e]
  (when-let [frame (.get frames (.-source e))]
    (let [data (.-data e)]
      (when (and (some? data) (= "2.0" (.-jsonrpc data)))
        (handle! (.-source e) frame (js->clj data :keywordize-keys true))))))

(defonce ^:private listening
  (do (.addEventListener js/window "message" on-message) true))

(defonce ^:private last-theme (atom nil))
(defonce ^:private theme-check (atom nil))

(defn- theme-changed!
  "Xi's theme may have switched: every open view gets the new theme and
   tokens, but only when they changed. The `<html>` attributes this watches
   also move for other reasons: on iOS --app-height is rewritten on every
   visual-viewport scroll, and re-sending the tokens then made every view
   restyle itself mid-scroll."
  []
  (reset! theme-check nil)
  (let [ctx {:theme (theme) :styles {:variables (host-styles)}}]
    (when (not= ctx @last-theme)
      (reset! last-theme ctx)
      (doseq [[w _] (frame-entries)]
        (post! w {:method "ui/notifications/host-context-changed" :params ctx})))))

(defn- schedule-theme-check!
  "At most one theme check per animation frame."
  []
  (when-not @theme-check
    (reset! theme-check (js/requestAnimationFrame theme-changed!))))

(defonce ^:private watching-theme
  (do (.observe (js/MutationObserver. (fn [_] (schedule-theme-check!)))
                js/document.documentElement #js {:attributes true :attributeFilter #js ["data-theme" "class" "style"]})
      (.addEventListener (js/matchMedia "(prefers-color-scheme: dark)") "change" schedule-theme-check!)
      true))

(defn notify-mode!
  "Tell block `block-id`'s view the host changed its display mode."
  [block-id mode]
  (doseq [[w f] (frame-entries) :when (= block-id (:block-id f))]
    (post! w {:method "ui/notifications/host-context-changed" :params {:displayMode mode}})))

(defn reply!
  "The server answered a forwarded request (`:ext.blocks/app-reply`)."
  [{:keys [rpc-id result error]}]
  (when-let [{:keys [win id]} (get @pending rpc-id)]
    (swap! pending dissoc rpc-id)
    (if error
      (fail! win id (:code error -32000) (:message error "error"))
      (respond! win id result))))

(defn loaded!
  "The server sent a view's HTML (`:ext.blocks/app-html`)."
  [{:keys [block-id html error]}]
  (swap! htmls assoc block-id (if error {:error error} {:html html})))

;; ── The frame ────────────────────────────────────────────────────────────────

(defn- mount! [dispatch! room-id record ui]
  (fn [{:replicant/keys [^js node life-cycle]}]
    (let [win (.-contentWindow node)]
      (if (= life-cycle :replicant.life-cycle/unmount)
        (do (doseq [[w f] (frame-entries) :when (identical? node (:iframe f))]
              (try (post! w {:id "teardown" :method "ui/resource-teardown" :params {:reason "unmounted"}})
                   (catch :default _ nil))
              (.delete frames w)))
        (do (reset! dispatch-ref dispatch!)
            (.set frames win (merge (.get frames win)
                                           {:block-id (:id record) :room-id room-id :iframe node
                                            :record record :fullscreen? (boolean (:fullscreen? ui))})))))))

(defn- request-html [dispatch! room-id block-id]
  (fn [_]
    (when-not (contains? @htmls block-id)
      (dispatch! {:type :ext.blocks/app-load :room-id room-id :block-id block-id}))))

(defn view
  "The body of an app block: its frame once the HTML is here."
  [dispatch! room-id {:keys [id spec] :as record} ui]
  (let [{:keys [html error]} (get @htmls id)]
    (cond
      error
      [:div {:class ["ab-app-error"]} (str "The view could not be loaded: " error)
       [:pre {:class ["ab-raw"]} (b/body-text record)]]

      (nil? html)
      [:div {:class ["ab-app-loading"]
             :replicant/on-mount (request-html dispatch! room-id id)}
       "Loading the app view…"]

      :else
      [:iframe {:class ["ab-app-frame"]
                :title (str "App view: " (:resource-uri spec))
                :sandbox "allow-scripts"
                :referrerpolicy "no-referrer"
                :srcdoc (csp/srcdoc html (csp/csp (:csp spec)))
                :replicant/on-mount (mount! dispatch! room-id record ui)
                :replicant/on-update (mount! dispatch! room-id record ui)
                :replicant/on-unmount (mount! dispatch! room-id record ui)}])))
