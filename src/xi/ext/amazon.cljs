(ns xi.ext.amazon
  "Amazon.de product search for the personal agent.

   Amazon fully blocks scripted HTTP requests (202/503, empty bodies) and even
   the Jina reader only gets Amazon's bot page, so plain fetch/scrape does not
   work. A real headless Chrome, however, loads the search page normally and
   returns product cards with no CAPTCHA. This extension therefore drives a
   headless Chrome over the DevTools Protocol (a hand-rolled newline-free
   WebSocket JSON-RPC client — no puppeteer/playwright dep, honoring xi's
   single-runtime-dep rule) to run `https://www.amazon.de/s?k=<query>` and
   extract the results.

   Chrome is launched lazily on the first search, the connection is memoized,
   searches are serialized (one shared tab), and Chrome is killed on shutdown —
   mirroring xi.ext.chrome-mcp.

   Env:
     XI_AMAZON_CHROME   path to the Chrome/Chromium binary (otherwise a small
                        candidate list + `google-chrome-stable` on PATH is used)

   Tool:
     amazon_search      search amazon.de and return matching product entries"
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private HOST "https://www.amazon.de")
(def ^:private LAUNCH_TIMEOUT_MS 15000)
(def ^:private RESULTS_TIMEOUT_MS 15000)
(def ^:private MAX_LIMIT 20)
(def ^:private DEFAULT_LIMIT 10)

(defn- env [k] (aget js/process.env k))

;; ── Chrome discovery ──────────────────────────────────────────────────────────

(defn- file-exists? [p]
  (try (.existsSync fs p) (catch :default _ false)))

(defn- chrome-binary
  "Resolve a Chrome/Chromium binary: XI_AMAZON_CHROME, then common paths, then
   fall back to `google-chrome-stable` on PATH."
  []
  (or (some-> (env "XI_AMAZON_CHROME") str/trim not-empty)
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
  "Poll for the DevToolsActivePort file until Chrome is listening."
  [user-dir deadline]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(tick []
               (if-let [port (read-devtools-port user-dir)]
                 (resolve port)
                 (if (> (js/Date.now) deadline)
                   (reject (js/Error. "Chrome did not start (no DevToolsActivePort)"))
                   (js/setTimeout tick 150))))]
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
  (let [user-dir (path/join (os/tmpdir) (str "xi-amazon-" (js/Date.now)))
        proc (.spawn child-process (chrome-binary)
                     #js ["--headless=new" "--disable-gpu" "--no-sandbox"
                          "--disable-dev-shm-usage"
                          "--remote-debugging-port=0"
                          "--lang=de-DE"
                          (str "--user-data-dir=" user-dir)]
                     #js {:stdio "ignore"})]
    (-> (wait-for-port user-dir (+ (js/Date.now) LAUNCH_TIMEOUT_MS))
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

;; ── Result extraction (runs in the page) ───────────────────────────────────────

(def ^:private EXTRACT_JS
  "(() => {
     if (document.querySelector('form[action*=\"validateCaptcha\"]')) {
       return JSON.stringify({captcha: true});
     }
     const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
     const cards = [...document.querySelectorAll('div[data-asin][data-component-type=\"s-search-result\"]')]
       .filter(c => c.getAttribute('data-asin'));
     const items = cards.map(c => {
       const asin = c.getAttribute('data-asin');
       // Newer amazon.de layout keeps the full product title in the
       // title-recipe block (brand line + title, optionally a 'Sponsored' line).
       const recipe = (c.querySelector('[data-cy=\"title-recipe\"]') || {}).innerText || '';
       const sponsored = /^\\s*(Sponsored|Gesponsert)/i.test(recipe);
       let title = clean(recipe.replace(/^\\s*(Sponsored|Gesponsert)\\s*/i, ''));
       if (!title) { const h2 = c.querySelector('h2'); title = clean(h2 ? h2.innerText : ''); }
       const price = clean((c.querySelector('.a-price .a-offscreen') || {}).innerText) || null;
       const iconAlt = (c.querySelector('.a-icon-alt') || {}).innerText || '';
       const rm = iconAlt.match(/([0-9]+[.,][0-9]+)\\s*(?:out of|von)/i);
       const rating = rm ? rm[1].replace(',', '.') : null;
       const revText = (c.querySelector('[data-cy=\"reviews-block\"]') || {}).innerText || '';
       const revm = revText.match(/\\(([0-9.,]+[KMkm]?)\\)/);
       const reviews = revm ? revm[1] : null;
       const prime = !!c.querySelector('.a-icon-prime, [aria-label*=\"Prime\"]');
       return {asin, title, price, rating, reviews, prime, sponsored};
     }).filter(i => i.title);
     return JSON.stringify({items});
   })()")

;; ── Search ────────────────────────────────────────────────────────────────────

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

(defn- kill-session! []
  (when-let [s @session*]
    (try (.terminate ^js (:ws s)) (catch :default _ nil))
    (try (.kill ^js (:proc s)) (catch :default _ nil))
    (try (.rmSync fs (:user-dir s) #js {:recursive true :force true})
         (catch :default _ nil)))
  (reset! session* nil)
  (reset! ready* nil))

(defn- wait-for-results
  "Poll the page until search-result cards exist (or a CAPTCHA / timeout)."
  [send! deadline]
  (js/Promise.
   (fn [resolve _reject]
     (letfn [(tick []
               (-> (send! "Runtime.evaluate"
                          {:expression "(()=>{if(document.querySelector('form[action*=\"validateCaptcha\"]'))return 'captcha';return document.querySelectorAll('div[data-component-type=\"s-search-result\"]').length})()"
                           :returnByValue true})
                   (.then (fn [r]
                             (let [v (some-> r .-result .-value)]
                               (cond
                                 (= v "captcha")       (resolve :captcha)
                                 (and (number? v) (pos? v)) (resolve :ready)
                                 (> (js/Date.now) deadline) (resolve :timeout)
                                 :else (js/setTimeout tick 400)))))
                   (.catch (fn [_]
                             (if (> (js/Date.now) deadline)
                               (resolve :timeout)
                               (js/setTimeout tick 400))))))]
       (tick)))))

(defn- run-search
  "Navigate the shared tab to the search URL, wait, and extract items.
   Returns Promise<{:items […]}|{:captcha true}|{:error msg}>."
  [query]
  (-> (ensure-session!)
      (.then
       (fn [{:keys [send!]}]
         (let [url (str HOST "/s?k=" (js/encodeURIComponent query))]
           (-> (send! "Page.navigate" {:url url})
               (.then (fn [_] (wait-for-results send! (+ (js/Date.now) RESULTS_TIMEOUT_MS))))
               (.then (fn [status]
                        (if (= status :captcha)
                          {:captcha true}
                          (-> (send! "Runtime.evaluate"
                                     {:expression EXTRACT_JS :returnByValue true})
                              (.then (fn [r]
                                       (let [raw (some-> r .-result .-value)
                                             parsed (when raw
                                                      (js->clj (js/JSON.parse raw) :keywordize-keys true))]
                                         (cond
                                           (:captcha parsed) {:captcha true}
                                           parsed            {:items (:items parsed)}
                                           :else             {:items []}))))))))))))
      (.catch (fn [e]
                ;; A dead connection shouldn't wedge future calls.
                (kill-session!)
                {:error (.-message e)}))))

(defn- with-lock
  "Serialize `f` (a 0-arg fn returning a Promise) behind the shared tab."
  [f]
  (let [p (-> @lock*
              (.catch (fn [_] nil))
              (.then (fn [_] (f))))]
    (reset! lock* p)
    p))

;; ── Formatting ─────────────────────────────────────────────────────────────────

(defn- format-item [i {:keys [asin title price rating reviews prime sponsored]}]
  (str/join "\n"
            (cond-> [(str (inc i) ". " title (when sponsored "  [Gesponsert]"))]
              price          (conj (str "   Preis: " price))
              rating         (conj (str "   Bewertung: " rating "/5"
                                        (when reviews (str " (" reviews " Rezensionen)"))))
              (and (not rating) reviews) (conj (str "   Rezensionen: " reviews))
              prime          (conj "   Prime: ja")
              asin           (conj (str "   ASIN: " asin))
              asin           (conj (str "   URL: " HOST "/dp/" asin)))))

(defn- format-results [query items]
  (if (empty? items)
    (str "Keine Treffer für \"" query "\" auf amazon.de.")
    (str "amazon.de — Suche: \"" query "\" (" (count items) " Treffer)\n\n"
         (str/join "\n\n" (map-indexed format-item items)))))

;; ── Tool ────────────────────────────────────────────────────────────────────────

(defn- amazon-search-tool [{:keys [query limit]} _ctx]
  (if (str/blank? query)
    (js/Promise.resolve
     {:content [{:type "text" :text "amazon_search requires a non-empty `query`."}]
      :is-error true})
    (let [n (max 1 (min MAX_LIMIT (or limit DEFAULT_LIMIT)))]
      (-> (with-lock #(run-search query))
          (.then (fn [{:keys [items captcha error]}]
                   (cond
                     error
                     {:content [{:type "text"
                                 :text (str "Amazon-Suche fehlgeschlagen: " error
                                            "\n(Prüfe, ob Chrome installiert ist; ggf. XI_AMAZON_CHROME setzen.)")}]
                      :is-error true}

                     captcha
                     {:content [{:type "text"
                                 :text "Amazon hat eine CAPTCHA-Abfrage angezeigt. Bitte später erneut versuchen."}]
                      :is-error true}

                     :else
                     {:content [{:type "text"
                                 :text (format-results query (take n items))}]})))
          (.catch (fn [e]
                    {:content [{:type "text"
                                :text (str "Amazon-Suche fehlgeschlagen: " (.-message e))}]
                     :is-error true}))))))

(def ^:private amazon-search-def
  {:name "amazon_search"
   :description "Search amazon.de for products and return matching entries (title, price, rating, review count, Prime, ASIN, product URL).

Use this for shopping / price-lookup tasks on the German Amazon marketplace. Results are live and locale-scoped to amazon.de (prices in EUR). Fetch a specific product's details by navigating to the returned URL (https://www.amazon.de/dp/<ASIN>)."
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "Search query, e.g. \"usb c kabel 2m\""}
                               :limit {:type "number"
                                       :description "Max entries to return (1-20, default 10)"}}
                  :required ["query"]}})

(def extension
  {:id               :amazon
   :tool-definitions [amazon-search-def]
   :tool-registry    {"amazon_search" amazon-search-tool}
   :on-shutdown      kill-session!})
