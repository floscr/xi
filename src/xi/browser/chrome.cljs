(ns xi.browser.chrome
  "Headless Chrome driven over the DevTools Protocol, behind xi.api.chrome.

   One browser per extension, launched on its first visit and closed after
   IDLE_MS without one (and when xi exits). Each browser gets:
     - a throwaway profile in the temp dir, so no cookies or logins of the
       user's own Chrome are visible to it
     - `--remote-debugging-pipe` (CDP over fds 3/4) instead of a debug port,
       so no other local process can attach to it
     - a xi.browser.proxy allowlist proxy as its only network path
   Visits are serialized per browser; each one opens a fresh tab and closes it.

   Binary: XI_CHROME_BINARY, else common install paths, else
   `google-chrome-stable` on PATH."
  (:require [clojure.string :as str]
            [xi.browser.proxy :as proxy]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private IDLE_MS (* 5 60 1000))
(def ^:private LAUNCH_TIMEOUT_MS 45000)  ;; generous: SD-card cold start on a Pi is slow
(def ^:private POLL_MS 300)
(def ^:private DEFAULT_TIMEOUT_MS 15000)

(defn- chrome-binary []
  (or (some-> (aget js/process.env "XI_CHROME_BINARY") str/trim not-empty)
      (first (filter #(fs/existsSync %)
                     ["/opt/google/chrome/chrome"
                      "/usr/bin/google-chrome-stable"
                      "/usr/bin/google-chrome"
                      "/usr/bin/chromium"
                      "/usr/bin/chromium-browser"
                      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]))
      "google-chrome-stable"))

(defn chrome-args
  "Chrome's argv for a browser whose only network path is the proxy on `port`."
  [user-dir port]
  ["--headless=new" "--remote-debugging-pipe" (str "--user-data-dir=" user-dir)
   "--no-first-run" "--no-default-browser-check" "--disable-extensions"
   "--disable-background-networking" "--disable-component-update" "--disable-sync"
   "--disable-gpu" "--disable-dev-shm-usage"
   (str "--proxy-server=http://127.0.0.1:" port)
   ;; route loopback through the proxy too (Chrome bypasses it by default),
   ;; and keep QUIC, WebRTC UDP and DNS prefetch from going around it
   "--proxy-bypass-list=<-loopback>" "--disable-quic"
   "--force-webrtc-ip-handling-policy=disable_non_proxied_udp" "--dns-prefetch-disable"
   "about:blank"])

;; ── CDP over the pipe ────────────────────────────────────────────────────────

(defn- connect
  "Wire CDP onto a Chrome spawned with --remote-debugging-pipe: messages are
   NUL-terminated JSON, written to fd 3 and read from fd 4.
   → {:send! (fn [method params session-id]) → Promise<result>}"
  [^js proc on-dead]
  (let [^js out   (aget (.-stdio proc) 3)
        ^js in    (aget (.-stdio proc) 4)
        pending   (js/Map.)
        next-id   (atom 0)
        buf       (atom "")
        fail-all! (fn [msg]
                    (.forEach pending (fn [^js p _] ((.-reject p) (js/Error. msg))))
                    (.clear pending))]
    (.on in "data"
         (fn [chunk]
           (swap! buf str chunk)
           (loop []
             (when-let [i (str/index-of @buf "\u0000")]
               (let [raw (subs @buf 0 i)]
                 (swap! buf subs (inc i))
                 (when-let [^js msg (try (js/JSON.parse raw) (catch :default _ nil))]
                   (when-let [^js p (and (.-id msg) (.get pending (.-id msg)))]
                     (.delete pending (.-id msg))
                     (if-let [err (.-error msg)]
                       ((.-reject p) (js/Error. (or (.-message err) "CDP error")))
                       ((.-resolve p) (.-result msg)))))
                 (recur))))))
    (.on proc "exit" (fn [_ _] (fail-all! "Chrome exited") (on-dead)))
    {:send! (fn [method params session-id]
              (js/Promise.
               (fn [resolve reject]
                 (let [id (swap! next-id inc)]
                   (.set pending id #js {:resolve resolve :reject reject})
                   (.write out (str (js/JSON.stringify
                                     (clj->js (cond-> {:id id :method method :params (or params {})}
                                                session-id (assoc :sessionId session-id))))
                                    "\u0000"))))))}))

(defn- with-timeout [p ms msg]
  (js/Promise.race
   #js [p (js/Promise. (fn [_ reject] (js/setTimeout #(reject (js/Error. msg)) ms)))]))

;; Cleanup fns of every launched browser, run synchronously on xi exit (an
;; `exit` handler can't wait for promises).
(defonce ^:private live (atom #{}))

(defn- launch!
  "Start the proxy + Chrome for `hosts`. → Promise<browser map>."
  [hosts on-dead]
  (-> (proxy/start! hosts)
      (.then
       (fn [{:keys [port] close-proxy! :close!}]
         (let [user-dir (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-chrome-"))
               ^js proc (cp/spawn (chrome-binary) (clj->js (chrome-args user-dir port))
                                  #js {:stdio #js ["ignore" "ignore" "pipe" "pipe" "pipe"]})
               stderr   (atom "")
               cleanup! (fn cleanup! []
                          (swap! live disj cleanup!)
                          (try (.kill proc) (catch :default _ nil))
                          (close-proxy!)
                          (try (fs/rmSync user-dir #js {:recursive true :force true})
                               (catch :default _ nil)))
               _ (swap! live conj cleanup!)
               spawn-error (js/Promise.
                            (fn [_ reject]
                              (.on proc "error" #(reject (js/Error. (str "cannot start Chrome ("
                                                                         (chrome-binary) "): "
                                                                         (.-message %)))))))
               _ (.on (.-stderr proc) "data"
                      (fn [c] (swap! stderr #(let [s (str % c)] (subs s (max 0 (- (count s) 2000)))))))
               {:keys [send!]} (connect proc (fn [] (cleanup!) (on-dead)))]
           (-> (js/Promise.race #js [(send! "Browser.getVersion" nil nil) spawn-error])
               (with-timeout LAUNCH_TIMEOUT_MS "Chrome did not start in time")
               (.then (fn [_] {:send! send! :cleanup! cleanup!}))
               (.catch (fn [e]
                         (cleanup!)
                         (throw (js/Error. (str (.-message e)
                                                (when-let [tail (not-empty (str/trim @stderr))]
                                                  (str "\nchrome: " (last (str/split-lines tail)))))))))))))))

;; ── Browsers per extension ───────────────────────────────────────────────────

;; id → {:hosts #{…} :ready Promise<browser> :lock Promise :timer}
(defonce ^:private browsers (atom {}))


(defn- close! [id]
  (when-let [{:keys [ready timer]} (get @browsers id)]
    (swap! browsers dissoc id)
    (some-> timer js/clearTimeout)
    (.then ready (fn [b] ((:cleanup! b))) (fn [_] nil))))

(defonce ^:private exit-hook
  (.on js/process "exit" (fn [] (run! #(%) @live))))

(defn- browser-for
  "The live browser entry for extension `id`, (re)launched when absent or when
   its declared `hosts` changed (an `/ext reload`)."
  [id hosts]
  (let [entry (get @browsers id)]
    (if (and entry (= hosts (:hosts entry)))
      entry
      (do (when entry (close! id))
          (let [token   #js {}
                ;; a dead browser forgets only its own entry, never a successor
                forget! (fn [& _]
                          (swap! browsers (fn [m] (cond-> m
                                                    (identical? token (get-in m [id :token]))
                                                    (dissoc id)))))
                entry   {:token token
                         :hosts hosts
                         :ready (launch! hosts forget!)
                         :lock  (js/Promise.resolve nil)}]
            (swap! browsers assoc id entry)
            (.catch (:ready entry) forget!)
            entry)))))

(defn- touch!
  "Restart extension `id`'s idle timer."
  [id]
  (when-let [{:keys [timer]} (get @browsers id)]
    (some-> timer js/clearTimeout)
    (swap! browsers assoc-in [id :timer] (js/setTimeout #(close! id) IDLE_MS))))

;; ── Visits ───────────────────────────────────────────────────────────────────

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- evaluate
  "Evaluate `expr` in the tab; a Promise result is awaited. → Promise<value>."
  [send! session expr]
  (-> (send! "Runtime.evaluate" {:expression expr :returnByValue true :awaitPromise true} session)
      (.then (fn [^js r]
               (if-let [^js ex (.-exceptionDetails r)]
                 (throw (js/Error. (str "page script failed: "
                                        (or (some-> ex .-exception .-description) (.-text ex)))))
                 (some-> r .-result .-value))))))

(defn- ready-expr
  "Polled until truthy. `about:blank` is the fresh tab before the navigation
   commits, so it never counts as ready."
  [wait]
  (str "(()=>{if(location.href==='about:blank')return false;"
       "return " (or wait "document.readyState==='complete'") ";})()"))

(defn- truthy? [v]
  (cond (number? v) (pos? v)
        (string? v) (seq v)
        :else       (boolean v)))

(defn- poll-ready [send! session wait deadline]
  (-> (evaluate send! session (ready-expr wait))
      (.catch (fn [_] false))
      (.then (fn [v]
               (cond (truthy? v)                 :ready
                     (> (js/Date.now) deadline) :timeout
                     :else (.then (sleep POLL_MS)
                                  #(poll-ready send! session wait deadline)))))))

(defn- run-visit
  [{:keys [send!]} url {:keys [wait eval timeout-ms]}]
  (-> (send! "Target.createTarget" {:url "about:blank"} nil)
      (.then
       (fn [^js t]
         (let [target (.-targetId t)
               close-tab! #(.catch (send! "Target.closeTarget" {:targetId target} nil) (fn [_] nil))]
           (-> (send! "Target.attachToTarget" {:targetId target :flatten true} nil)
               (.then
                (fn [^js a]
                  (let [session (.-sessionId a)]
                    (-> (send! "Page.navigate" {:url url} session)
                        (.then (fn [^js nav]
                                 (when-let [err (not-empty (.-errorText nav))]
                                   (throw (js/Error. (str "could not load " url ": " err
                                                          (when (= err "net::ERR_TUNNEL_CONNECTION_FAILED")
                                                            " (host not in the extension's :hosts)")))))
                                 (poll-ready send! session wait
                                             (+ (js/Date.now) (or timeout-ms DEFAULT_TIMEOUT_MS)))))
                        (.then (fn [waited]
                                 (-> (evaluate send! session (or eval "null"))
                                     (.then (fn [value]
                                              (-> (evaluate send! session "location.href")
                                                  (.then (fn [href]
                                                           {:url href
                                                            :ready? (= :ready waited)
                                                            :value (js->clj value :keywordize-keys true)}))))))))))))
               (.finally close-tab!)))))))

(defn visit!
  "Load `url` in extension `id`'s browser (allowed to reach `hosts` only), wait
   for the `:wait` JS expression to turn truthy (default: the page loaded) or
   `:timeout-ms` to pass, then evaluate `:eval` and return its value.
   → Promise<{:url final-url :ready? bool :value clj-data}>."
  [id hosts url opts]
  (let [entry (browser-for id hosts)
        run   (-> (:lock entry)
                  (.catch (fn [_] nil))
                  (.then (fn [_] (:ready entry)))
                  (.then (fn [b] (run-visit b url opts))))]
    (swap! browsers (fn [m] (cond-> m (get m id) (assoc-in [id :lock] run))))
    (.finally run #(touch! id))))
