(ns xi.ext.terminal.socket
  "The Unix socket programs in a terminal reach their chat through (impure
   edge of xi.ext.terminal). One per server process, user-only (0600),
   started with the first terminal. A terminal's env carries the path
   (`XI_TERM_SOCK`) and its own token (`XI_TERM_TOKEN`, xi.ext.terminal.pty
   /by-token), so a request names its terminal and output can't forge one.

   `POST /send` with `Authorization: Bearer <token>` and a JSON body
   {text, to, label} (xi.ext.terminal.core/send-event) → 200 {ok: true}, or
   an error status with {error}. With `to: \"subagent\", stream: true` the
   200 is NDJSON instead, the sub-agent's core/stream-item lines (push!) up to
   its \"done\"."
  (:require [xi.ext.terminal.pty :as pty]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defonce ^:private server (atom nil))

;; sub-id → the ReadableStreamDefaultController of a streaming /send
(defonce ^:private streams (atom {}))

(defn new-token []
  (.toString (crypto/randomBytes 32) "hex"))

(defn- socket-path
  "In the user's runtime dir when there is one, so the path is private too."
  []
  (path/join (or (aget js/process.env "XDG_RUNTIME_DIR") (os/tmpdir))
             (str "xi-term-" js/process.pid ".sock")))

(defn- json-resp [status obj]
  (js/Response. (js/JSON.stringify (clj->js obj))
                #js {:status  status
                     :headers #js {"Content-Type" "application/json"}}))

(defn- bearer [^js req]
  (some-> (.get (.-headers req) "authorization")
          (.replace #"(?i)^bearer\s+" "")))

(def ^:private encoder (js/TextEncoder.))

(defn- end-stream! [sub-id]
  (when-let [^js ctrl (get @streams sub-id)]
    (swap! streams dissoc sub-id)
    (try (.close ctrl) (catch :default _ nil))))

(defn push!
  "Send `item` to the program streaming sub-agent `sub-id`, if one is; a
   \"done\" item ends its response."
  [sub-id item]
  (when-let [^js ctrl (get @streams sub-id)]
    (try (.enqueue ctrl (.encode encoder (str (js/JSON.stringify (clj->js item)) "\n")))
         (catch :default _ (swap! streams dissoc sub-id)))
    (when (= "done" (:type item))
      (end-stream! sub-id))))

(defn- send-streaming
  "A /send whose sub-agent the program watches: its id is ours, so the
   stream is registered before the turn can emit anything."
  [on-send term body]
  (let [sub-id (str "sa-term-" (subs (new-token) 0 12))
        rs     (js/ReadableStream.
                #js {:start  (fn [ctrl] (swap! streams assoc sub-id ctrl))
                     :cancel (fn [_] (swap! streams dissoc sub-id))})]
    (if-let [error (:error (on-send term (assoc body :sub-id sub-id)))]
      (do (end-stream! sub-id)
          (json-resp 409 {:error error}))
      (js/Response. rs #js {:headers #js {"Content-Type" "application/x-ndjson"}}))))

(defn- send [on-send term body]
  (if (and (true? (:stream body)) (= "subagent" (:to body)))
    (send-streaming on-send term body)
    (if-let [error (:error (on-send term body))]
      (json-resp 409 {:error error})
      (json-resp 200 {:ok true}))))

(defn- handle
  "`on-send` (fn [term body]) → {:error} or anything else for success."
  [on-send ^js req]
  (let [url (js/URL. (.-url req))]
    (cond
      (not= "/send" (.-pathname url)) (json-resp 404 {:error "not found"})
      (not= "POST" (.-method req))    (json-resp 405 {:error "method not allowed"})
      :else
      (if-let [term (pty/by-token (bearer req))]
        (-> (.json req)
            (.then (fn [body]
                     (send on-send term (dissoc (js->clj body :keywordize-keys true) :sub-id)))
                   (fn [_] (json-resp 400 {:error "the body is not JSON"}))))
        (json-resp 401 {:error "unknown terminal token"})))))

(defn ensure!
  "Start the socket unless it runs (`on-send` as in handle) → its path."
  [on-send]
  (or (:path @server)
      (let [p (socket-path)]
        (when (fs/existsSync p) (fs/unlinkSync p))
        ;; no idle timeout: a stream waits as long as the sub-agent thinks
        (let [srv (js/Bun.serve #js {:unix p :idleTimeout 0 :fetch #(handle on-send %)})]
          (fs/chmodSync p 384) ; 0600
          (reset! server {:server srv :path p})
          p))))

(defn stop! []
  (run! end-stream! (keys @streams))
  (when-let [{^js srv :server path :path} @server]
    (reset! server nil)
    (try (.stop srv true) (catch :default _ nil))
    (try (fs/unlinkSync path) (catch :default _ nil))))
