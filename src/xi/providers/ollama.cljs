(ns xi.providers.ollama
  "Direct OpenAI-compatible streaming provider for Ollama (and similar).
   A thin wrapper over `xi.provider.openai-compat`: supplies the local base
   URL and an `ensure-ollama-running!` pre-flight that spawns `ollama serve`
   when the local API isn't reachable.

   Interface: (stream-messages opts) → {:promise :abort!}.
   Extension hooks are injected via :tool-gate — no ext/core dependency."
  (:require [clojure.string :as str]
            [xi.providers.openai-compat :as oai]))

;; ── Config ──────────────────────────────────────────────────────────────────

(def ^:private OLLAMA_BASE_URL
  (or (aget js/process.env "OLLAMA_BASE_URL")
      "http://localhost:11434"))

;; ── Server Auto-Start ─────────────────────────────────────────────────────────

(defn- local-ollama?
  "Only auto-start when the base URL points at a local Ollama; a remote host
   isn't ours to manage."
  []
  (or (str/includes? OLLAMA_BASE_URL "localhost")
      (str/includes? OLLAMA_BASE_URL "127.0.0.1")))

(defn- ollama-reachable?
  "Promise<bool> — is the Ollama HTTP API responding?"
  []
  (-> (js/fetch (str OLLAMA_BASE_URL "/api/version")
                #js {:signal (js/AbortSignal.timeout 1500)})
      (.then (fn [^js res] (.-ok res)))
      (.catch (fn [_] false))))

(defn- spawn-ollama-serve! []
  (js/Bun.spawn #js ["ollama" "serve"]
                #js {:stdin  "ignore"
                     :stdout "ignore"
                     :stderr "ignore"
                     :env    (unchecked-get js/process "env")}))

(defn- wait-until-reachable
  "Poll the API until it responds, up to `attempts` times spaced `delay-ms`.
   Resolves true when ready, rejects on timeout."
  [attempts delay-ms]
  (-> (ollama-reachable?)
      (.then (fn [ok?]
               (cond
                 ok?             true
                 (<= attempts 0) (js/Promise.reject
                                  (js/Error. "Ollama server did not become ready in time"))
                 :else           (js/Promise.
                                  (fn [resolve reject]
                                    (js/setTimeout
                                     (fn [] (.then (wait-until-reachable (dec attempts) delay-ms)
                                                   resolve reject))
                                     delay-ms))))))))

(defonce ^:private !server-starting (atom nil))

(defn- ensure-ollama-running!
  "Ensure the Ollama server is reachable, spawning `ollama serve` if not.
   Memoized so concurrent turns share one startup. Resolves when ready (or
   immediately for a remote/unmanaged endpoint, letting the request surface
   any error itself)."
  []
  (-> (ollama-reachable?)
      (.then (fn [ok?]
               (cond
                 ok?                  true
                 (not (local-ollama?)) true
                 :else
                 (or @!server-starting
                     (let [_ (js/console.error "[ollama] server not running — starting `ollama serve`")
                           _ (spawn-ollama-serve!)
                           p (-> (wait-until-reachable 40 500)
                                 (.finally (fn [] (reset! !server-starting nil))))]
                       (reset! !server-starting p)
                       p)))))))

(defn stream-messages
  "Run a full agent turn with tool-use loop against the local Ollama API.
   Returns {:promise p :abort! f}."
  [opts]
  (oai/stream-messages
   {:base-url   OLLAMA_BASE_URL
    :chat-path  "/v1/chat/completions"
    :label      "Ollama"
    :pre-flight ensure-ollama-running!}
   opts))

(defn list-models!
  "Promise of installed Ollama model names (empty on error)."
  []
  (-> (js/fetch (str OLLAMA_BASE_URL "/api/tags")
                #js {:signal (js/AbortSignal.timeout 4000)})
      (.then (fn [^js res] (.json res)))
      (.then (fn [^js data]
               (mapv :name (js->clj (.-models data) :keywordize-keys true))))
      (.catch (fn [_err] []))))

(def provider
  {:id :ollama
   :start-turn! stream-messages
   :list-models! list-models!})
