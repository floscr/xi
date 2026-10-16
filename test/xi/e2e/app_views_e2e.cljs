(ns xi.e2e.app-views-e2e
  "External app views (MCP Apps, docs/guide/agent-blocks.md \"App views\") end
   to end, with the `orbit` extension of xi.e2e.app-views: a three.js-style
   scene viewer whose view is a WebGL probe.

   Over the WebSocket: the app-only tool stays the agent's secret, the block
   carries the view's CSP, the view reaches its own tools and nothing else,
   the rules gate its calls, and the agent reads what it did with ui_state.
   In headless Chromium, with the real web client: the frame is sandboxed,
   purple and labelled, the view runs WebGL but can't reach Xi's page,
   storage, cookies or an undeclared host, and its message and link wait for
   the user."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.app-views :as app-views]
            [xi.e2e.browser :as browser]
            [xi.e2e.harness :as h :refer [of-type]]))

(def ^:private script
  {:rules [{:when {:prompt "orbit please"}
            :reply [{:tool "orbit_show" :args {:objects ["crate" "ball"]}} {:text "{{tool-result}}"}]}
           {:when {:prompt "guess the pick tool"}
            :reply [{:tool "orbit_pick" :args {:label "crate"}} {:text "{{tool-result}}"}]}]
   :side [{:text "Orbit"}]})

(defn- join! [env client]
  (let [from (count @(:events client))]
    ((:send! client) {:type :room/join :target "new" :cwd (:project env)})
    (h/await-event client (of-type :room/joined) {:from from})))

(defn- turn! [client room-id text]
  (let [from (count @(:events client))]
    ((:send! client) {:type :prompt/submit :text text})
    (h/await-event client (of-type :agent/turn-end room-id) {:from from :timeout-ms 20000})))

(defn- rpc!
  "Send one request of the view (as the browser host forwards it) → Promise
   of the server's answer."
  [client room-id block-id method params]
  (let [from   (count @(:events client))
        rpc-id (str (random-uuid))]
    ((:send! client) {:type :ext.blocks/app-rpc :room-id room-id :block-id block-id
                      :rpc-id rpc-id :method method :params params})
    (h/await-event client #(and ((of-type :ext.blocks/app-reply) %) (= rpc-id (:rpc-id %))) {:from from})))

(defn- app-block [client room-id]
  (h/await-event client #(and ((of-type :ext.blocks/add room-id) %) (= "app" (get-in % [:block :kind])))))

(defn- with-server!
  "Run (f env server client room) in a fresh env with a server, a connected
   client and a joined room (the `:room/joined` event); tears it all down."
  [files done f]
  (h/with-env! files script done
    (fn [env]
      (let [server (atom nil) client (atom nil)]
        (-> (h/start-server! env)
            (.then (fn [s] (reset! server s) (h/connect! s)))
            (.then (fn [c] (reset! client c) (join! env c)))
            (.then (fn [joined] (f env @server @client joined)))
            (.finally (fn []
                        (some-> @client :close! (apply []))
                        (some-> @server :stop! (apply [])))))))))

(deftest an-extension-app-view-over-the-websocket
  (async done
    (with-server! (app-views/profile) done
      (fn [env _server client {:keys [room-id]}]
        (let [block (atom nil)]
          (-> (turn! client room-id "orbit please")
              (.then (fn [_]
                       (testing "the agent sees the tool with a view, not the app-only one"
                         (let [tools (set (:tools (first (h/main-turns env))))]
                           (is (contains? tools "orbit_show"))
                           (is (not (contains? tools "orbit_pick")))))
                       (app-block client room-id)))
              (.then (fn [{b :block}]
                       (reset! block b)
                       (testing "the view's block: its source, call, CSP and the tool's data"
                         (is (= {:type :ext :id :orbit} (:source b)))
                         (is (some? (:call-id b)))
                         (is (= "ui://orbit/view" (get-in b [:spec :resource-uri])))
                         (is (= {:resourceDomains ["https://cdn.jsdelivr.net"]} (get-in b [:spec :csp])))
                         (is (= {:objects ["crate" "ball"]} (get-in b [:spec :tool-input])))
                         (is (= {:scene {:objects ["crate" "ball"]}}
                                (get-in b [:spec :tool-result :structured-content]))))
                       (let [from (count @(:events client))]
                         ((:send! client) {:type :ext.blocks/app-load :room-id room-id :block-id (:id b)})
                         (h/await-event client (of-type :ext.blocks/app-html) {:from from}))))
              (.then (fn [{:keys [html]}]
                       (is (str/includes? html "PROBE") "the view's HTML, to this client only")
                       (rpc! client room-id (:id @block) "tools/call" {:name "orbit_pick" :arguments {:label "crate"}})))
              (.then (fn [reply]
                       (is (= {:picked "crate"} (get-in reply [:result :structuredContent]))
                           "the view calls its app-only tool")
                       (rpc! client room-id (:id @block) "tools/call" {:name "write" :arguments {:path "x" :content "y"}})))
              (.then (fn [reply]
                       (is (= -32602 (get-in reply [:error :code])) "a tool that isn't the extension's: refused")
                       (is (nil? (h/slurp (h/path env "x"))) "and nothing was written")
                       (rpc! client room-id (:id @block) "resources/read" {:uri "ui://orbit/view"})))
              (.then (fn [reply]
                       (is (= -32601 (get-in reply [:error :code])) "resources/read is for MCP views only")
                       (rpc! client room-id (:id @block) "ui/update-model-context"
                             {:content [{:type "text" :text "the user selected the crate"}]})))
              (.then (fn [reply]
                       (is (= {} (:result reply)))
                       (h/set-script! env (assoc-in script [:rules 2]
                                                    {:when {:prompt "what did the view do"}
                                                     :reply [{:tool "ui_state" :args {:id (:id @block)}}
                                                             {:text "{{tool-result}}"}]}))
                       (turn! client room-id "what did the view do")))
              (.then (fn [_]
                       (testing "the agent reads the view's calls and context with ui_state"
                         (let [content (:content (first (:tool-calls (last (h/main-turns env)))))]
                           (is (str/includes? content "app: tools/call orbit_pick"))
                           (is (str/includes? content "app: context → \"the user selected the crate\""))))
                       (turn! client room-id "guess the pick tool")))
              (.then (fn [_]
                       (let [call (first (:tool-calls (last (h/main-turns env))))]
                         (is (:is-error call) "the agent can't call the app-only tool by name")
                         (is (str/includes? (:content call) "Unknown tool")))
                       (h/wait-until #(some (fn [f] (and (str/ends-with? f ".ext.edn")
                                                         (str/includes? (str (h/slurp (h/home-path env ".config" "xi" "sessions" f)))
                                                                        "ui://orbit/view")))
                                            (h/files-under (h/home-path env ".config" "xi" "sessions")))
                                     10000 "the view's block saved with the chat")))))))))

(deftest the-rules-gate-a-views-tool-calls
  (async done
    (with-server! (app-views/profile [{:match  {:tool-name "orbit_pick"}
                                       :action {:type :deny :message "No picking from views."}}])
      done
      (fn [_env _server client {:keys [room-id]}]
        (-> (turn! client room-id "orbit please")
            (.then (fn [_] (app-block client room-id)))
            (.then (fn [{b :block}]
                     (rpc! client room-id (:id b) "tools/call" {:name "orbit_pick" :arguments {:label "crate"}})))
            (.then (fn [reply]
                     (is (str/includes? (str (get-in reply [:error :message])) "No picking from views."))
                     (is (nil? (:result reply))))))))))

;; ── In the browser ───────────────────────────────────────────────────────────

(defn- composer-text []
  "(() => { const t = [...document.querySelectorAll('textarea')].find(x => (x.placeholder || '').startsWith('Message'));
            return t ? t.value : null; })()")

(deftest a-three-d-view-in-the-browser
  (async done
    (if-not (browser/chrome-path)
      (do (println "  skipped: no Chromium (set XI_E2E_CHROME)") (is true) (done))
      (with-server! (app-views/profile) done
        (fn [_env server client {:keys [room-id] :as joined}]
          (let [b    (atom nil)
                page (atom nil)
                after (atom nil)
                sid  (get-in joined [:room :session :id])
                base (str "http://127.0.0.1:" (:port server))
                ev!  (fn [expr] (browser/eval! @b @page expr))]
            (-> (turn! client room-id "orbit please")
                (.then (fn [_] (app-block client room-id)))
                (.then (fn [_] (browser/launch!)))
                (.then (fn [br] (reset! b br) (browser/open! br (str base "/"))))
                (.then (fn [s]
                         (reset! page s)
                         (browser/until! @b s "document.readyState === 'complete'" 20000 "the page")))
                (.then (fn [_]
                         (ev! (str "localStorage.setItem('xi-client-key', " (js/JSON.stringify h/client-key) ");"
                                   "location.href = " (js/JSON.stringify (str base "/chat/" sid)) "; true"))))
                (.then (fn [_]
                         (browser/until! @b @page "!!document.querySelector('.ab-app-frame')" 30000 "the app frame")))
                (.then (fn [_]
                         (ev! "(() => { const f = document.querySelector('.ab-app-frame');
                                        const blk = f.closest('.agent-block');
                                        return { sandbox: f.getAttribute('sandbox'),
                                                 doc: f.getAttribute('srcdoc').slice(0, 600),
                                                 border: getComputedStyle(blk).borderTopColor,
                                                 badge: blk.querySelector('.agent-block-badge').textContent }; })()")))
                (.then (fn [{:keys [sandbox doc border badge]}]
                         (testing "the host's frame: sandboxed, the CSP first, purple, labelled"
                           (is (= "allow-scripts" sandbox) "no allow-same-origin, no popups, no navigation")
                           (is (str/starts-with? doc "<!doctype html><meta http-equiv=\"Content-Security-Policy\""))
                           (is (str/includes? doc "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net"))
                           (is (str/includes? doc "connect-src 'none'"))
                           (is (= "rgb(139, 92, 246)" border))
                           (is (= "External app" badge)))
                         (h/await-event client #(and ((of-type :ext.blocks/note room-id) %)
                                                     (= "context" (:action %))
                                                     (str/starts-with? (str (:value %)) "PROBE "))
                                        {:timeout-ms 30000})))
                (.then (fn [{:keys [value]}]
                         (reset! after (count @(:events client)))
                         (let [r (js->clj (js/JSON.parse (subs value 6)) :keywordize-keys true)]
                           (testing "inside the view: isolated from Xi"
                             (is (= "null" (:origin r)) "an opaque origin")
                             (is (false? (:parentDoc r)) "can't touch Xi's page")
                             (is (false? (:storage r)) "no localStorage (the client key lives there)")
                             (is (false? (:cookie r)) "no cookies")
                             (is (= "blocked" (:fetch r)) "no request to a host its CSP doesn't declare")
                             (is (some #{"connect-src"} (:csp r))))
                           (testing "a 3D scene draws"
                             (is (true? (:webgl r)))
                             (let [[red green blue alpha] (:pixel r)]
                               (is (and (< 125 red 155) (< 80 green 105) (< 230 blue 255) (= 255 alpha))
                                   (str "the shader's purple at the center, got " (:pixel r)))))
                           (testing "the protocol: context in, data in, its own tool only"
                             (is (= "xi" (:host r)))
                             (is (#{"light" "dark"} (:theme r)))
                             (is (pos? (:vars r)) "Xi's theme tokens as the spec's style variables")
                             (is (= {:objects ["crate" "ball"]} (:input r)))
                             (is (= ["crate" "ball"] (:objects r)))
                             (is (= "crate" (:pick r)))
                             (is (= -32602 (:foreign r)))))
                         (browser/until! @b @page (str "(" (composer-text) ") === 'Tell me about the crate'")
                                         10000 "the view's message in the message box")))
                (.then (fn [_]
                         (browser/until! @b @page "true" 1000 "a tick")))
                (.then (fn [_]
                         (h/wait-until #(seq @(:dialogs @b)) 10000 "the open-link question")))
                (.then (fn [_]
                         (let [{:keys [type message]} (first @(:dialogs @b))]
                           (testing "a link asks first; the message waits for the user"
                             (is (= "confirm" type))
                             (is (str/includes? message "https://example.com/docs"))
                             (is (not-any? (of-type :prompt/submit) (drop @after @(:events client)))
                                 "nothing was sent for the user")))))
                (.finally (fn [] (some-> @b :close! (apply [])))))))))))
