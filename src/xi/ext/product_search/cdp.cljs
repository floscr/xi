(ns xi.ext.product-search.cdp
  "Shared headless-Chrome DevTools-Protocol client for the product-search
   extension (amazon / willhaben / geizhals).

   Several shopping sites (Amazon especially) fully block scripted HTTP
   requests, and others (willhaben) render their results client-side, so plain
   fetch/scrape does not work. A real headless Chrome, however, loads the pages
   normally. This namespace drives one headless Chrome over the DevTools
   Protocol (a hand-rolled newline-free WebSocket JSON-RPC client — no
   puppeteer/playwright dep, honoring xi's single-runtime-dep rule).

   Chrome is launched lazily on the first search and shared by ALL sites: the
   connection is memoized, one tab is reused, searches are serialized behind a
   lock, and Chrome is killed on shutdown.

   Env:
     XI_PRODUCT_SEARCH_CHROME   path to the Chrome/Chromium binary
     XI_AMAZON_CHROME           legacy fallback (same purpose)
                                otherwise a small candidate list +
                                `google-chrome-stable` on PATH is used"
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def LAUNCH_TIMEOUT_MS 45000)  ;; generous: SD-card cold start on a Pi is slow
(def RESULTS_TIMEOUT_MS 15000)

(defn- env [k] (aget js/process.env k))

;; ── Chrome discovery ──────────────────────────────────────────────────────────

(defn- file-exists? [p]
  (try (.existsSync fs p) (catch :default _ false)))

(defn- chrome-binary
  "Resolve a Chrome/Chromium binary: XI_PRODUCT_SEARCH_CHROME (then the legacy
   XI_AMAZON_CHROME), then common paths, then `google-chrome-stable` on PATH."
  []
  (or (some-> (env "XI_PRODUCT_SEARCH_CHROME") str/trim not-empty)
      (some-> (env "XI_AMAZON_CHROME") str/trim not-empty)
      (let [user (try (.-username (os/userInfo)) (catch :default _ nil))]
        (first (filter file-exists?
              (cond-> ["/opt/google/chrome/chrome"
                       "/usr/bin/google-chrome-stable"
                       "/usr/bin/google-chrome"
                       "/usr/bin/chromium"
                       "/usr/bin/chromium-browser"
                       "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]
                user (conj (str "/etc/profiles/per-user/" user "/bin/google-chrome-stable"))))))
      "google-chrome-stable"))

;; ── Minimal CDP WebSocket client ────────────────────────────────────────────

(defn- read-devtools-port
  "Chrome writes the chosen debug port to <user-data-dir>/DevToolsActivePort
   (first line). Returns the port int or nil."
  [user-dir]
  (try
    (let [raw (.readFileSync fs (path/join user-dir "DevToolsActivePort") "utf8")
          line (first (str/split-lines (str/trim raw)))
          n (js/parseInt line 10)]
      (when (pos? n) n))
    (catch :default _ nil)))

(defn- wait-for-port
  "Poll for the DevToolsActivePort file until Chrome is listening. Rejects
   early if Chrome exits/errors before writing the port, and appends any
   captured chromium stderr so the failure is diagnosable."
  [user-dir deadline exit* stderr*]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(stderr-tail []
               (let [e (str/trim @stderr*)]
                 (when (seq e)
                   (str "\nchromium: "
                        (->> (str/split-lines e)
                             (remove str/blank?)
                             (take-last 6)
                             (str/join "\n"))))))
             (fail [why]
               (reject (js/Error. (str "Chrome did not start (" why ")" (stderr-tail)))))
             (tick []
               (if-let [port (read-devtools-port user-dir)]
                 (resolve port)
                 (cond
                   @exit*                     (fail @exit*)
                   (> (js/Date.now) deadline) (fail "no DevToolsActivePort")
                   :else                      (js/setTimeout tick 150))))]
       (tick)))))

(defn- open-ws
  "Fetch the browser's page-target WS endpoint and open a WebSocket to it."
  [port]
  (-> (js/fetch (str "http://127.0.0.1:" port "/json/new?about:blank")
                #js {:method "PUT"})
      (.then (fn [r] (.json r)))
      (.then (fn [^js tgt]
               (js/Promise.
                (fn [resolve reject]
                  (let [ws (js/WebSocket. (.-webSocketDebuggerUrl tgt))]
                    (.addEventListener ws "open" (fn [_] (resolve ws)))
                    (.addEventListener ws "error"
                                       (fn [_] (reject (js/Error. "CDP WebSocket error")))))))))))

(defn- make-session
  "Launch headless Chrome, connect CDP, enable Runtime+Page.
   Returns Promise<{:proc :ws :send! :user-dir}>."
  []
  (let [user-dir (path/join (os/tmpdir) (str "xi-product-search-" (js/Date.now)))
        proc (.spawn child-process (chrome-binary)
                     #js ["--headless=new" "--disable-gpu" "--no-sandbox"
                          "--disable-dev-shm-usage"
                          "--remote-debugging-port=0"
                          "--lang=de-DE"
                          (str "--user-data-dir=" user-dir)]
                     ;; Capture stderr so a launch crash is diagnosable instead
                     ;; of a silent timeout; stdin/stdout are dropped.
                     #js {:stdio #js ["ignore" "ignore" "pipe"]})
        stderr* (atom "")
        exit*   (atom nil)]
    (some-> (.-stderr proc)
            (.on "data" (fn [chunk]
                          (swap! stderr*
                                 (fn [s]
                                   (let [t (str s chunk)]
                                     (cond-> t
                                       (> (count t) 4000) (subs (- (count t) 4000)))))))))
    (.on proc "error" (fn [e] (reset! exit* (str "spawn error: " (.-message e)))))
    (.on proc "exit"  (fn [code signal]
                        (reset! exit* (str "chromium exited early ("
                                           (if signal (str "signal " signal)
                                               (str "code " code)) ")"))))
    (-> (wait-for-port user-dir (+ (js/Date.now) LAUNCH_TIMEOUT_MS) exit* stderr*)
        (.then open-ws)
        (.then
         (fn [ws]
           (let [pending (js/Map.)
                 next-id (atom 0)
                 send! (fn [method params]
                         (js/Promise.
                          (fn [resolve reject]
                            (let [id (swap! next-id inc)]
                              (.set pending id #js {:resolve resolve :reject reject})
                              (.send ws (js/JSON.stringify
                                         (clj->js (cond-> {:id id :method method}
                                                    (some? params) (assoc :params params)))))))))]
             (.addEventListener ws "message"
                                (fn [ev]
                                  (when-let [msg (try (js/JSON.parse (.-data ev))
                                                      (catch :default _ nil))]
                                    (let [id (.-id msg)]
                                      (when (and (some? id) (.has pending id))
                                        (let [p (.get pending id)]
                                          (.delete pending id)
                                          (if-let [err (.-error msg)]
                                            ((.-reject p) (js/Error. (or (.-message err) "CDP error")))
                                            ((.-resolve p) (.-result msg)))))))))
             (-> (send! "Runtime.enable" nil)
                 (.then #(send! "Page.enable" nil))
                 (.then (fn [_] {:proc proc :ws ws :send! send! :user-dir user-dir})))))))))

;; ── Session lifecycle ─────────────────────────────────────────────────────────

(defonce ^:private session* (atom nil))   ;; live {:proc :ws :send! …}
(defonce ^:private ready* (atom nil))      ;; memoized Promise<session>
(defonce ^:private lock* (atom (js/Promise.resolve nil)))  ;; serialize searches

(defn- ensure-session! []
  (or @ready*
      (let [p (make-session)]
        (reset! ready* p)
        (-> p
            (.then (fn [s] (reset! session* s)))
            (.catch (fn [_] (reset! ready* nil) (reset! session* nil))))
        p)))

(defn kill-session!
  "Terminate the shared Chrome (used as the extension :on-shutdown)."
  []
  (when-let [s @session*]
    (try (.terminate ^js (:ws s)) (catch :default _ nil))
    (try (.kill ^js (:proc s)) (catch :default _ nil))
    (try (.rmSync fs (:user-dir s) #js {:recursive true :force true})
         (catch :default _ nil)))
  (reset! session* nil)
  (reset! ready* nil))

;; ── Low-level page ops ─────────────────────────────────────────────────────────

(defn- evaluate
  "Run `expr` in the page and return Promise<value> (returnByValue)."
  [send! expr]
  (-> (send! "Runtime.evaluate" {:expression expr :returnByValue true})
      (.then (fn [r] (some-> r .-result .-value)))))

(defn- wait-until
  "Poll `ready-expr` (a JS expression) in the page until it evaluates to a
   truthy value (a positive number, a non-empty string, or true), or the
   deadline passes. Resolves :ready / :timeout — never rejects."
  [send! ready-expr deadline]
  (js/Promise.
   (fn [resolve _reject]
     (letfn [(ready? [v]
               (cond
                 (number? v)  (pos? v)
                 (string? v)  (seq v)
                 :else        (boolean v)))
             (tick []
               (-> (evaluate send! ready-expr)
                   (.then (fn [v]
                            (cond
                              (ready? v)                 (resolve :ready)
                              (> (js/Date.now) deadline) (resolve :timeout)
                              :else (js/setTimeout tick 400))))
                   (.catch (fn [_]
                             (if (> (js/Date.now) deadline)
                               (resolve :timeout)
                               (js/setTimeout tick 400))))))]
       (tick)))))

(defn- with-lock
  "Serialize `f` (a 0-arg fn returning a Promise) behind the shared tab."
  [f]
  (let [p (-> @lock*
              (.catch (fn [_] nil))
              (.then (fn [_] (f))))]
    (reset! lock* p)
    p))

(defn- run-search
  "Navigate the shared tab to `url`, wait for `ready-expr`, then evaluate
   `extract-expr`. Returns Promise<{:raw <string>}|{:error msg}>."
  [{:keys [url ready-expr extract-expr timeout-ms]}]
  (-> (ensure-session!)
      (.then
       (fn [{:keys [send!]}]
         (-> (send! "Page.navigate" {:url url})
             (.then (fn [_] (wait-until send! ready-expr
                                        (+ (js/Date.now) (or timeout-ms RESULTS_TIMEOUT_MS)))))
             (.then (fn [_] (evaluate send! extract-expr)))
             (.then (fn [raw] {:raw raw})))))
      (.catch (fn [e]
                ;; A dead connection shouldn't wedge future calls.
                (kill-session!)
                {:error (.-message e)}))))

;; ── Generic tool boilerplate ───────────────────────────────────────────────────

(defn- text-result [text] {:content [{:type "text" :text text}]})
(defn- error-result [text] {:content [{:type "text" :text text}] :is-error true})

(defn search-tool
  "Shared tool body for a single site.

   Opts:
     :tool-name     tool name (for messages)
     :query :limit  tool args
     :url           search URL (already built for the query)
     :ready-expr    JS expression that becomes truthy once results are present
     :extract-js    JS IIFE returning a JSON string
                    {items:[…]} | {captcha:true} | {error:msg}
     :format-fn     (fn [query items] -> string)
     :max-limit     default 20
     :default-limit default 10

   Returns Promise<tool-result>."
  [{:keys [tool-name query limit url ready-expr extract-js format-fn
           max-limit default-limit]
    :or {max-limit 20 default-limit 10}}]
  (if (str/blank? query)
    (js/Promise.resolve
     (error-result (str tool-name " requires a non-empty `query`.")))
    (let [n (max 1 (min max-limit (or limit default-limit)))]
      (-> (with-lock #(run-search {:url url
                                   :ready-expr ready-expr
                                   :extract-expr extract-js
                                   :timeout-ms RESULTS_TIMEOUT_MS}))
          (.then
           (fn [{:keys [raw error]}]
             (if error
               (error-result
                (str tool-name " fehlgeschlagen: " error
                     "\n(Prüfe, ob Chrome installiert ist; ggf. XI_PRODUCT_SEARCH_CHROME setzen.)"))
               (let [parsed (when raw
                              (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                                   (catch :default _ nil)))]
                 (cond
                   (:captcha parsed)
                   (error-result
                    (str tool-name ": CAPTCHA-Abfrage angezeigt. Bitte später erneut versuchen."))

                   (:error parsed)
                   (error-result (str tool-name " fehlgeschlagen: " (:error parsed)))

                   :else
                   (text-result (format-fn query (take n (:items parsed)))))))))
          (.catch (fn [e]
                    (error-result
                     (str tool-name " fehlgeschlagen: " (.-message e)))))))))
