(ns xi.ext.chrome-mcp.launch
  "Launch the shared OS Chrome (attach mode) when it isn't running.

   In attach mode xi drives an *external* Chrome over its remote-debugging URL
   (`XI_CHROME_BROWSER_URL`, e.g. http://127.0.0.1:9222). If that Chrome process
   isn't running, every chrome-devtools-mcp / CDP call fails with a connection
   error and the agent is stuck. So before forwarding any tool call, xi probes
   the CDP browser endpoint and — when it's unreachable — spawns Chrome itself
   (detached) and waits for the endpoint to come up. This lets an agent
   bootstrap the browser instead of requiring a human to start it first.

   The launcher is the dotfiles `browser` bin (google-chrome-stable with
   `--remote-debugging-port=9222`, a dedicated profile, etc.); it runs Chrome in
   the foreground, so it's spawned detached + unref'd. Overridable via
   `XI_CHROME_LAUNCH_BIN`."
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process]))

(defn- env [k] (aget js/process.env k))

(defn- launch-cmd
  "Command line `[bin & args]` for the Chrome launcher. Overridable via
   XI_CHROME_LAUNCH_BIN, which may include arguments (whitespace-split, no
   quoting — so paths in it must not contain spaces), e.g.
   `google-chrome-stable --remote-debugging-port=9333 --user-data-dir=…`.
   The binary should be absolute when the caller's PATH can't resolve it —
   the long-lived server's PATH doesn't include dotfiles/bin."
  []
  (let [s (or (some-> (env "XI_CHROME_LAUNCH_BIN") str/trim not-empty)
              "/home/floscr/.config/dotfiles/bin/browser")]
    (remove str/blank? (str/split s #"\s+"))))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn browser-reachable?
  "Probe `<browser-url>/json/version`; resolve true when the CDP browser
   endpoint answers (Chrome is up), false otherwise. Never rejects. A 1s abort
   keeps a hung socket from stalling the tool call."
  [browser-url]
  (let [url  (str (str/replace browser-url #"/+$" "") "/json/version")
        ctrl (js/AbortController.)
        t    (js/setTimeout #(.abort ctrl) 1000)]
    (-> (js/fetch url #js {:signal (.-signal ctrl)})
        (.then (fn [r] (js/clearTimeout t) (boolean (.-ok r))))
        (.catch (fn [_] (js/clearTimeout t) false)))))

(defn- spawn-detached!
  "Spawn the Chrome launcher detached (its own process group) with stdio
   ignored + unref'd, so it outlives the request and never blocks the event
   loop. Swallows a missing-binary error — the caller falls back to surfacing
   the normal connection error."
  []
  (try
    (let [[bin & args] (launch-cmd)
          child (.spawn child-process bin (into-array args)
                        #js {:detached true :stdio "ignore" :env js/process.env})]
      (.unref child)
      true)
    (catch :default _ false)))

(defn- wait-reachable
  "Poll the CDP endpoint every 250ms until it answers or `tries` run out."
  [browser-url tries]
  (-> (browser-reachable? browser-url)
      (.then (fn [up?]
               (cond
                 up?          (js/Promise.resolve true)
                 (pos? tries) (-> (sleep 250)
                                  (.then (fn [_] (wait-reachable browser-url (dec tries)))))
                 :else        (js/Promise.resolve false))))))

;; In-flight launch, so concurrent tool calls don't each spawn a Chrome.
(defonce ^:private launching* (atom nil))

(defn ensure-process!
  "Ensure the shared Chrome CDP endpoint is reachable at `browser-url`, and
   report what happened:

     :already-up   — Chrome was already reachable (no launch)
     :launched     — Chrome was down; we launched it and it came up
     :unreachable  — Chrome was down and didn't come up within ~10s

   When Chrome is down it's launched (detached) and polled until the endpoint
   answers (~10s cap). Concurrent calls share a single in-flight launch. Always
   resolves — the caller uses `:launched` to know it should place the fresh
   window(s) on the agent's workspace, and proceeds regardless so any real
   connection error surfaces downstream."
  [browser-url]
  (-> (browser-reachable? browser-url)
      (.then (fn [up?]
               (if up?
                 :already-up
                 (or @launching*
                     (let [p (-> (do (spawn-detached!)
                                     (wait-reachable browser-url 40))
                                 (.then (fn [ok] (reset! launching* nil)
                                          (if ok :launched :unreachable)))
                                 (.catch (fn [_] (reset! launching* nil) :unreachable)))]
                       (reset! launching* p)
                       p)))))))
