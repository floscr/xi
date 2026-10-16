(ns xi.e2e.browser
  "Headless Chromium for the scenarios that need the real web client, over
   the DevTools protocol with no npm dependency: `launch!` starts a browser
   (XI_E2E_CHROME, else chromium / google-chrome on PATH) in a throwaway
   profile; `open!` a page; `eval!` runs JS in it. JavaScript dialogs are
   recorded and dismissed (`dialogs`), so a confirm() can't wedge a scenario.

   WebGL runs on SwiftShader, so a scene draws without a GPU."
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- which [cmd]
  (some (fn [dir]
          (let [p (node-path/join dir cmd)]
            (when (fs/existsSync p) p)))
        (str/split (or (aget js/process.env "PATH") "") #":")))

(defn chrome-path
  "The browser to run, or nil when there is none (the scenario skips)."
  []
  (or (aget js/process.env "XI_E2E_CHROME")
      (some which ["chromium" "chromium-browser" "google-chrome-stable" "google-chrome"])))

(defn- connect [url]
  (let [ws        (js/WebSocket. url)
        next-id   (atom 0)
        pending   (atom {})
        listeners (atom [])]
    (set! (.-onmessage ws)
          (fn [^js e]
            (let [m (js->clj (js/JSON.parse (.-data e)) :keywordize-keys true)]
              (if-let [p (get @pending (:id m))]
                (do (swap! pending dissoc (:id m))
                    (if (:error m)
                      ((:reject p) (js/Error. (str (get-in m [:error :message]))))
                      ((:resolve p) (:result m))))
                (doseq [f @listeners] (f m))))))
    (js/Promise.
     (fn [resolve reject]
       (set! (.-onerror ws) (fn [_] (reject (js/Error. "DevTools connection failed"))))
       (set! (.-onopen ws)
             (fn [_]
               (resolve
                {:ws ws
                 :on! (fn [f] (swap! listeners conj f))
                 :call! (fn call!
                          ([method params] (call! method params nil))
                          ([method params session]
                           (let [id (swap! next-id inc)]
                             (js/Promise.
                              (fn [res rej]
                                (swap! pending assoc id {:resolve res :reject rej})
                                (.send ws (js/JSON.stringify
                                           (clj->js (cond-> {:id id :method method :params params}
                                                      session (assoc :sessionId session))))))))))})))))))

(defn launch!
  "Start the browser → Promise of {:call! :on! :close! :dialogs}."
  []
  (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-e2e-chrome-"))
        proc (cp/spawn (chrome-path)
                       #js ["--headless=new" "--remote-debugging-port=0" (str "--user-data-dir=" dir)
                            "--no-first-run" "--no-default-browser-check" "--disable-extensions"
                            "--use-angle=swiftshader" "--enable-unsafe-swiftshader"
                            "--window-size=1100,900" "about:blank"]
                       #js {:stdio #js ["ignore" "ignore" "pipe"]})
        out  (atom "")]
    (-> (js/Promise.
         (fn [resolve reject]
           (let [timer (js/setTimeout #(reject (js/Error. (str "Chromium did not start: " @out))) 20000)]
             (.on (.-stderr proc) "data"
                  (fn [chunk]
                    (swap! out str chunk)
                    (when-let [[_ url] (re-find #"DevTools listening on (ws://\S+)" @out)]
                      (js/clearTimeout timer)
                      (resolve url)))))))
        (.then connect)
        (.then (fn [c]
                 (assoc c
                        :dialogs (atom [])
                        :close! (fn []
                                  (.close (:ws c))
                                  (.kill proc)
                                  (try (fs/rmSync dir #js {:recursive true :force true})
                                       (catch :default _ nil)))))))))

(defn open!
  "A new page at `url` → Promise of the session id. Dialogs it opens are
   recorded in (:dialogs browser) and dismissed."
  [{:keys [call! on! dialogs]} url]
  (-> (call! "Target.createTarget" {:url "about:blank"})
      (.then (fn [{:keys [targetId]}] (call! "Target.attachToTarget" {:targetId targetId :flatten true})))
      (.then (fn [{:keys [sessionId]}]
               (on! (fn [{:keys [method params] :as m}]
                      (when (and (= "Page.javascriptDialogOpening" method) (= sessionId (:sessionId m)))
                        (swap! dialogs conj params)
                        (call! "Page.handleJavaScriptDialog" {:accept false} sessionId))))
               (-> (js/Promise.all #js [(call! "Page.enable" {} sessionId) (call! "Runtime.enable" {} sessionId)])
                   (.then #(call! "Page.navigate" {:url url} sessionId))
                   (.then (constantly sessionId)))))))

(defn eval!
  "Run `expr` (awaited when it's a promise) in page `session` → Promise of
   its JSON value."
  [{:keys [call!]} session expr]
  (-> (call! "Runtime.evaluate" {:expression expr :awaitPromise true :returnByValue true} session)
      (.then (fn [{:keys [result exceptionDetails]}]
               (when exceptionDetails
                 (throw (js/Error. (str "page threw: " (get-in exceptionDetails [:exception :description]
                                                               (:text exceptionDetails))))))
               (:value result)))))

(defn until!
  "Poll `expr` in page `session` until it is truthy → Promise of its value."
  [browser session expr timeout-ms what]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(tick []
                 (-> (eval! browser session expr)
                     (.catch (constantly nil))
                     (.then (fn [v]
                              (cond
                                v (resolve v)
                                (> (js/Date.now) deadline) (reject (js/Error. (str "timed out waiting for " what)))
                                :else (js/setTimeout tick 100))))))]
         (tick))))))
