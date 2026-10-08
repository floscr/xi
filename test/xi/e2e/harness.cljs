(ns xi.e2e.harness
  "End-to-end harness: runs the real `bun target/main.js` against a throwaway
   HOME and the scripted fake LLM (xi.providers.fake).

   Every run gets its own temp root:

     <root>/home      HOME (and XDG_*): the profile's ~/.config/xi files
     <root>/project   a fresh git repo the chat runs in (README.md, AGENTS.md)
     <root>/script.edn  the fake LLM script (XI_FAKE_LLM)
     <root>/llm.jsonl   what each turn saw (XI_FAKE_LLM_LOG)

   The child env drops every XI_* variable and provider credential of the
   calling shell and points OLLAMA_BASE_URL and XI_PORT at a closed port, so
   a turn that somehow bypasses the fake fails instead of reaching a real
   model, and a client never knocks on the developer's own server."
  (:require [cljs.test :refer [is]]
            [clojure.string :as str]
            [xi.wire :as wire]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def main-js
  "The xi build under test (the main watch keeps it fresh)."
  (node-path/resolve "target/main.js"))

(def client-key
  "Seeded as the env's ~/.config/xi/client-key, which the server trusts."
  "e2e-local-client-key-0123456789abcdef")

;; ── Environments ─────────────────────────────────────────────────────────────

(defn- write-file! [file content]
  (fs/mkdirSync (node-path/dirname file) #js {:recursive true})
  (fs/writeFileSync file content))

(defn- git! [dir & args]
  (cp/execFileSync "git" (clj->js args) #js {:cwd dir :stdio "ignore"}))

(defn create-env!
  "A fresh temp root seeded with `files` (path relative to HOME → content
   string; see xi.e2e.profiles) and the fake LLM `script` (a map)."
  [files script]
  (let [root    (fs/realpathSync (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-e2e-")))
        home    (node-path/join root "home")
        project (node-path/join root "project")]
    (fs/mkdirSync home #js {:recursive true})
    (doseq [[rel content] files]
      (write-file! (node-path/join home rel) content))
    (write-file! (node-path/join home ".config/xi/client-key") client-key)
    (write-file! (node-path/join project "README.md") "# e2e project\n")
    (write-file! (node-path/join project "AGENTS.md") "E2E-PROJECT-INSTRUCTIONS\n")
    (git! project "init" "-q")
    (let [env {:root root :home home :project project
               :script (node-path/join root "script.edn")
               :log    (node-path/join root "llm.jsonl")}]
      (write-file! (:script env) (pr-str script))
      env)))

(defn set-script!
  "Replace the env's fake LLM script (read fresh by every turn)."
  [env script]
  (write-file! (:script env) (pr-str script)))

(defn destroy-env! [{:keys [root]}]
  (fs/rmSync root #js {:recursive true :force true}))

(defn path [env & parts]
  (apply node-path/join (:project env) parts))

(defn home-path [env & parts]
  (apply node-path/join (:home env) parts))

(defn slurp [file]
  (when (fs/existsSync file) (str (fs/readFileSync file "utf8"))))

(defn files-under
  "Relative paths of every file under `dir` (none when it doesn't exist)."
  [dir]
  (if (fs/existsSync dir)
    (->> (fs/readdirSync dir #js {:recursive true :withFileTypes true})
         (filter #(.isFile ^js %))
         (map (fn [^js d] (node-path/relative dir (node-path/join (.-parentPath d) (.-name d)))))
         sort
         vec)
    []))

(defn llm-log
  "Every turn the fake LLM ran in this env, oldest first, keywordized."
  [{:keys [log]}]
  (if-let [text (slurp log)]
    (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)
          (remove str/blank? (str/split-lines text)))
    []))

(defn main-turns
  "The log without side turns (titles, summaries)."
  [env]
  (filterv (complement :side?) (llm-log env)))

(defn child-env
  "The environment for an xi child process of `env`."
  [{:keys [home project script log]} extra]
  (let [base (js->clj (js/Object.assign #js {} js/process.env))
        drop? (fn [k] (or (str/starts-with? k "XI_")
                          (#{"ANTHROPIC_API_KEY" "ANTHROPIC_BASE_URL" "CLAUDE_CONFIG_DIR"
                             "OPENCODE_API_KEY" "OPENCODE_ZEN_API_KEY" "CODEX_HOME"
                             "OPENAI_API_KEY"} k)))]
    (clj->js
     (merge (into {} (remove (comp drop? key)) base)
            {"HOME"            home
             "XDG_DATA_HOME"   (node-path/join home ".local/share")
             "XDG_CONFIG_HOME" (node-path/join home ".config")
             "XDG_STATE_HOME"  (node-path/join home ".local/state")
             "XDG_CACHE_HOME"  (node-path/join home ".cache")
             "XI_CWD"          project
             "XI_FAKE_LLM"     script
             "XI_FAKE_LLM_LOG" log
             ;; tripwires: anything that reaches past the fake, or for a
             ;; server on the default port (the real one on :7474), hits a
             ;; closed port; start-server! and scenarios pass --port
             "OLLAMA_BASE_URL" "http://127.0.0.1:9"
             "XI_PORT"         "9"}
            extra))))

;; ── xi prompt ────────────────────────────────────────────────────────────────

(defn run-xi!
  "Run `xi <args…>` in the env's project dir. Resolves to {:code :stdout
   :stderr :json} (:json parsed from stdout when it is JSON), or rejects when
   it outlives `:timeout-ms` (default 20s). `:env` adds variables, `:stdin`
   is piped in."
  ([env args] (run-xi! env args nil))
  ([env args {:keys [timeout-ms stdin] extra :env :or {timeout-ms 20000}}]
   (js/Promise.
    (fn [resolve reject]
      (let [proc  (cp/spawn "bun" (clj->js (into [main-js] args))
                            #js {:cwd (:project env) :env (child-env env extra)})
            out   #js []
            err   #js []
            timer (js/setTimeout
                   (fn []
                     (.kill proc "SIGKILL")
                     (reject (js/Error. (str "xi " (str/join " " args) " timed out\nstderr: "
                                             (.join err "")))))
                   timeout-ms)]
        (.on (.-stdout proc) "data" #(.push out (str %)))
        (.on (.-stderr proc) "data" #(.push err (str %)))
        (if stdin
          (.end (.-stdin proc) stdin)
          (.end (.-stdin proc)))
        (.on proc "close"
             (fn [code]
               (js/clearTimeout timer)
               (let [stdout (.join out "")]
                 (resolve {:code   code
                           :stdout stdout
                           :stderr (.join err "")
                           :json   (try (js->clj (js/JSON.parse stdout) :keywordize-keys true)
                                        (catch :default _ nil))})))))))))

(defn prompt!
  "`xi prompt <flags…> <text>`."
  [env text & flags]
  (run-xi! env (-> ["prompt"] (into flags) (conj text))))

;; ── xi server ────────────────────────────────────────────────────────────────

(defn- free-port []
  (js/Promise.
   (fn [resolve reject]
     (let [srv (net/createServer)]
       (.on srv "error" reject)
       (.listen srv 0 "127.0.0.1"
                (fn []
                  (let [port (.-port (.address srv))]
                    (.close srv #(resolve port)))))))))

(defn wait-until
  "Poll `(pred)` every 50ms until truthy (resolves to it) or `timeout-ms`."
  [pred timeout-ms what]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(tick []
                 (if-let [v (pred)]
                   (resolve v)
                   (if (> (js/Date.now) deadline)
                     (reject (js/Error. (str "timed out waiting for " what)))
                     (js/setTimeout tick 50))))]
         (tick))))))

(defn start-server!
  "Start `xi server --headless` on a free loopback port. Resolves once it
   listens to {:port :proc :output (fn [] stdout+stderr) :stop! (fn → Promise)}."
  ([env] (start-server! env nil))
  ([env {extra :env args :args}]
   (-> (free-port)
       (.then
        (fn [port]
          (let [proc (cp/spawn "bun" (clj->js (into [main-js "server" "--headless"
                                                     "--port" (str port) "--host" "127.0.0.1"]
                                                    args))
                               #js {:cwd (:project env) :env (child-env env extra)})
                buf  #js {:s ""}
                exit #js {:code nil}
                _    (.on (.-stdout proc) "data" #(set! (.-s buf) (str (.-s buf) %)))
                _    (.on (.-stderr proc) "data" #(set! (.-s buf) (str (.-s buf) %)))
                _    (.on proc "exit" #(set! (.-code exit) (or % :signal)))
                server {:port   port
                        :proc   proc
                        :output (fn [] (.-s buf))
                        :stop!  (fn []
                                  (if (some? (.-code exit))
                                    (js/Promise.resolve nil)
                                    (js/Promise.
                                     (fn [resolve]
                                       (.on proc "exit" resolve)
                                       (.kill proc "SIGTERM")
                                       (js/setTimeout #(.kill proc "SIGKILL") 3000)))))}]
            (-> (wait-until #(or (str/includes? (.-s buf) "Headless server on")
                                 (some? (.-code exit)))
                            20000 "the server to listen")
                (.then (fn [_]
                         (if (some? (.-code exit))
                           (throw (js/Error. (str "server exited: " (.-s buf))))
                           server))))))))))

(defn http!
  "Fetch `path` on the server. Resolves to {:status :body} (body parsed JSON)."
  [{:keys [port]} path opts]
  (-> (js/fetch (str "http://127.0.0.1:" port path) (clj->js opts))
      (.then (fn [^js res]
               (-> (.text res)
                   (.then (fn [text]
                            {:status (.-status res)
                             :body   (try (js->clj (js/JSON.parse text) :keywordize-keys true)
                                          (catch :default _ text))})))))))

;; ── WS client ────────────────────────────────────────────────────────────────

(defonce ^:private open-sockets (atom #{}))

(defn close-all!
  "Close every WS client `connect!` opened."
  []
  (doseq [^js ws @open-sockets] (.close ws))
  (reset! open-sockets #{}))

(defn connect!
  "Open a WS client to `server` and say hello with `:key` (default the
   seeded local key) as `:user`. Resolves to a client map once the server
   answers the hello (:auth/ok, :auth/pending or :auth/denied):
     :events  atom of every decoded event received, in order
     :send!   (fn [event])
     :close!  (fn [])"
  [{:keys [port]} & [{:keys [key user] :or {key client-key}}]]
  (let [ws     (js/WebSocket. (str "ws://127.0.0.1:" port))
        events (atom [])
        client {:events events
                :send!  (fn [ev] (.send ws (wire/encode ev)))
                :close! (fn [] (swap! open-sockets disj ws) (.close ws))}]
    (swap! open-sockets conj ws)
    (set! (.-onmessage ws) (fn [^js e]
                             (when-let [ev (wire/decode (.-data e))]
                               (swap! events conj ev))))
    (js/Promise.
     (fn [resolve reject]
       (set! (.-onerror ws) (fn [_] (reject (js/Error. "WS connection failed"))))
       (set! (.-onopen ws)
             (fn [_]
               ((:send! client) (cond-> {:type :auth/hello :client-key key
                                         :client-name "e2e" :platform "e2e"}
                                  user (assoc :user user)))
               (-> (wait-until #(some (comp #{:auth/ok :auth/pending :auth/denied} :type)
                                      @events)
                               10000 "the auth answer")
                   (.then (fn [_] (resolve client)))
                   (.catch reject))))))))

(defn await-event
  "Resolves to the first event in the client's stream after index `from`
   (default 0) that satisfies `pred`, waiting up to `timeout-ms`."
  ([client pred] (await-event client pred {}))
  ([{:keys [events]} pred {:keys [from timeout-ms] :or {from 0 timeout-ms 15000}}]
   (-> (wait-until #(some (fn [ev] (when (pred ev) ev)) (drop from @events))
                   timeout-ms "a matching WS event")
       (.catch (fn [e]
                 (throw (js/Error. (str (.-message e) "; received since: "
                                        (pr-str (map (juxt :type :room-id) (drop from @events)))))))))))

(defn of-type [t & [room-id]]
  (fn [ev] (and (= t (:type ev)) (or (nil? room-id) (= room-id (:room-id ev))))))

;; ── Async test helper ────────────────────────────────────────────────────────

(defn with-env!
  "Run the promise-returning `f` against a fresh env (files, script) and
   destroy it afterwards; an unexpected rejection fails the test with the
   message. Call inside `async`: `done` runs at the end."
  [files script done f]
  (let [env (create-env! files script)]
    (-> (js/Promise.resolve)
        (.then #(f env))
        (.catch (fn [e]
                  (is false (str "unexpected: " (or (.-stack e) e)))))
        (.finally (fn []
                    (close-all!)
                    (destroy-env! env)
                    (done))))))
