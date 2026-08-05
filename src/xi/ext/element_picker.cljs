(ns xi.ext.element-picker
  "Visual browser element picker → prompt.

   `/pick` (or Ctrl+Shift+I) injects a picker overlay into the browser. The
   user hovers to highlight elements, clicks to select one, types a message,
   and the extension sends that element's HTML, CSS selector, computed styles,
   and a page screenshot to the agent as the next prompt — so the model gets
   direct context about exactly what the user means, without describing it.

   Targeting: the picker drives the *same* chrome-devtools-mcp the agent uses
   (via its `evaluate_script` / `take_screenshot` tools). When several tabs are
   open it first asks (via the dialog `ask!`) which one to pick from and
   switches to it with `select_page`; with a single tab (or headless, no
   `ask!`) it acts on the MCP's currently *selected* page. This namespace is
   not a standalone extension; it's installed into xi.ext.chrome-mcp (which owns
   the shared MCP client) via `install`.

     /pick                  open the picker on the selected page
     /pick fix this layout  open with a pre-filled message

   Server-side: chrome-devtools-mcp + Chrome live on the server host; the
   picker result is submitted as an ordinary prompt (text + a screenshot)
   into the room."
  (:require [clojure.string :as str]
            [xi.image :as image])
  (:require-macros [xi.ext.element-picker-js :refer [inline-picker-js]]))

;; ── Config ───────────────────────────────────────────────────────────────────

(def ^:private picker-timeout-ms 120000)
(def ^:private poll-interval-ms 400)
(def ^:private max-html-length 10000)

(def ^:private picker-js (inline-picker-js))

(def ^:private colors
  {:primary "#6366f1"
   :primaryBg "rgba(99, 102, 241, 0.12)"
   :primaryBorder "rgba(99, 102, 241, 0.8)"
   :bg "#1e1e2e"
   :bgLight "#313244"
   :text "#cdd6f4"
   :textMuted "#a6adc8"
   :textDim "#6c7086"
   :accent "#89b4fa"
   :success "#a6e3a1"
   :successBg "rgba(166, 227, 161, 0.12)"
   :border "#45475a"})

(def ^:private cleanup-js
  (str "(function() {"
       "var ids = ['__xi-picker-overlay','__xi-picker-highlight','__xi-picker-tooltip',"
       "'__xi-picker-panel','__xi-picker-badge','__xi-picker-banner'];"
       "for (var i = 0; i < ids.length; i++) { var el = document.getElementById(ids[i]); if (el) el.remove(); }"
       "delete window.__xiPickerActive; delete window.__xiPickerResult; delete window.__xiPickerCancelled;"
       "})();"))

;; ── JS the picker runs in the page (via chrome-devtools-mcp evaluate_script) ──

(defn- config-json [prefill]
  (js/JSON.stringify (clj->js {:prefillMessage prefill
                               :maxHTML 15000
                               :colors colors})))

(defn- injection-fn
  "A JS arrow-function declaration for evaluate_script: clear any stale picker,
   set the config, run the picker, and return true."
  [prefill]
  (str "() => {\n"
       cleanup-js "\n"
       "window.__XI_PICKER_CFG__ = " (config-json prefill) ";\n"
       picker-js "\n"
       "return true;\n}"))

(def ^:private poll-fn
  "() => ({ r: window.__xiPickerResult, c: window.__xiPickerCancelled })")

(def ^:private cleanup-fn
  (str "() => { " cleanup-js " return true; }"))

;; ── Parsing chrome-devtools-mcp tool results ─────────────────────────────────

(defn- result-text
  "Concatenated text content from a normalized MCP result."
  [result]
  (->> (:content result)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

(defn- image-data
  "First image's base64 data from a normalized MCP result, or nil."
  [result]
  (some #(when (= "image" (:type %)) (:data %)) (:content result)))

(defn- fenced-body
  "The body of the last ```…``` fenced block in `text`, else the whole text.
   evaluate_script wraps its JSON return value in a fenced block."
  [text]
  (let [blocks (re-seq #"(?s)```(?:json)?\n?(.*?)```" text)]
    (if (seq blocks)
      (second (last blocks))
      text)))

(defn- parse-eval-return
  "Parse the JSON value an evaluate_script call returned, or nil."
  [result]
  (let [body (str/trim (fenced-body (result-text result)))]
    (try (js->clj (js/JSON.parse body) :keywordize-keys true)
         (catch :default _ nil))))

(defn- selected-page-line
  "The `[selected]` line from a list_pages result, trimmed, or nil."
  [result]
  (some (fn [line]
          (when (str/includes? line "[selected]")
            (-> line (str/replace "[selected]" "") str/trim not-empty)))
        (str/split-lines (result-text result))))

(defn- parse-pages
  "Parse a list_pages result into a vector of {:id int :label str :selected? bool}.
   chrome-devtools-mcp prints each page under a `## Pages` header as
   `<id>: <label>[ [selected]][ isolatedContext=…]`."
  [result]
  (->> (str/split-lines (result-text result))
       (keep (fn [line]
               (when-let [[_ id rest] (re-matches #"\s*(\d+):\s*(.*)" line)]
                 (let [selected? (str/includes? rest "[selected]")
                       label (-> rest
                                 (str/replace "[selected]" "")
                                 (str/replace #"\s*isolatedContext=\S+" "")
                                 str/trim)]
                   {:id (js/parseInt id 10) :label label :selected? selected?}))))
       vec))

;; ── Message building (pure) ──────────────────────────────────────────────────

(defn- element-block [i multi? prefill el]
  (let [msg    (or (not-empty (:message el)) (not-empty prefill) "Analyze this element")
        styles (:computedStyles el)
        html   (let [h (or (:outerHTML el) "")]
                 (if (> (count h) max-html-length)
                   (str (subs h 0 max-html-length) "\n<!-- truncated -->")
                   h))]
    (str (if multi? (str "\n---\n### Element " (inc i) "\n") "\n---\n")
         "**Message:** " msg "\n"
         "**Selector:** `" (:selector el) "`\n"
         "**Tag:** `<" (:tagName el) ">`\n"
         (when (seq styles)
           (str "\n**Key computed styles:**\n```json\n"
                (js/JSON.stringify (clj->js styles) nil 2) "\n```\n"))
         "\n**HTML:**\n```html\n" html "\n```\n")))

(defn- build-message [prefill result]
  (let [elements (:elements result)
        multi?   (> (count elements) 1)]
    (str "**Picked from:** " (:url result) "\n"
         (when multi? (str "**Elements:** " (count elements) "\n"))
         (str/join "" (map-indexed (fn [i el] (element-block i multi? prefill el)) elements)))))

(defn- style-edit-message
  "Prompt that asks the agent to open the live `style_editor` on the picked
   element, tailoring its controls to what the user typed, then apply the
   committed CSS back to the source."
  [prefill result]
  (let [el (first (:elements result))]
    (str "Open a live style editor on the element I just picked so I can tune it "
         "visually, then apply my committed changes to the source code.\n\n"
         "Call the `style_editor` tool with:\n"
         "- `selector`: prefer a stable **class selector** derived from the "
         "element (e.g. `.project-card`) over the brittle positional selector "
         "below. The panel's \"apply to a shared class rule\" toggle live-"
         "previews the change across every element sharing that class, and the "
         "class rule is what you'll edit in the source — so a class selector is "
         "almost always right. Use the positional selector `" (:selector el) "` "
         "only as a fallback when the element has no usable class. Give each "
         "control its own `selector` when the properties live on different "
         "elements (e.g. wrapper padding on `.project-card`, the gap between "
         "title and subtitle on `.project-card-info`).\n"
         "- `controls`: choose the CSS properties that fit what I want to tune "
         "(one control per property; use `range` for lengths like padding / "
         "border-radius, `color` for colors, `opacity` for opacity). Pick "
         "sensible min/max/step/unit for each.\n\n"
         "After I apply, use the returned values to edit the source. "
         "Do NOT ask me which properties — infer them from my request below.\n\n"
         (build-message prefill result))))

;; ── Feedback + submission ────────────────────────────────────────────────────

(defn- status! [dispatch! room-id text]
  (dispatch! {:type :ui/status :room-id room-id :text text}))

(defn- submit! [dispatch! room-id prefill result shots]
  (let [style? (= "style-editor" (:mode result))
        text   (if style?
                 (style-edit-message prefill result)
                 (build-message prefill result))
        images (mapv (fn [data] {:data data :media-type "image/png"}) shots)
        n      (count (:elements result))
        label  (if style?
                 "Style editor request"
                 (if (> n 1) (str n " elements") "Element context"))]
    ;; :prompt/submit takes :images directly; :image/process is an effect, not
    ;; an event, so we resize inline (as :image/process would) and dispatch the
    ;; prompt ourselves.
    (dispatch! {:type :prompt/submit :room-id room-id :text text
                :images (image/process-images images)})
    (status! dispatch! room-id (str (if style? "🎨 " "🎯 ") label " sent to xi."))))

;; ── Orchestration ────────────────────────────────────────────────────────────

(defn- poll-loop
  "Poll the selected page for the picker result until it resolves, is
   cancelled, errors, or the deadline passes. `call` is the shared
   chrome-devtools-mcp caller (fn [tool args] → Promise<normalized-result>)."
  [call deadline]
  (js/Promise.
   (fn [resolve _reject]
     (letfn [(step []
               (if (> (js/Date.now) deadline)
                 (resolve {:timeout true})
                 (-> (call "evaluate_script" {:function poll-fn})
                     (.then (fn [res]
                              (if (:is-error res)
                                (resolve {:error true})
                                (let [v (parse-eval-return res)]
                                  (cond
                                    (:r v) (resolve {:result (:r v)})
                                    (:c v) (resolve {:cancelled true})
                                    :else  (js/setTimeout step poll-interval-ms))))))
                     (.catch (fn [_] (resolve {:error true}))))))]
       (step)))))

(defn- capture-screenshot
  "One viewport PNG of the (now clean) page, as base64, or nil. Best-effort."
  [call]
  (-> (call "take_screenshot" {:format "png"})
      (.then (fn [res] (when-not (:is-error res) (image-data res))))
      (.catch (fn [_] nil))))

(defn- cleanup! [call]
  (-> (call "evaluate_script" {:function cleanup-fn})
      (.catch (fn [_] nil))))

(defn- page-options
  "Turn parsed pages into :select dialog options {:label :value}. The
   currently selected page is tagged so the user knows the default target."
  [pages]
  (mapv (fn [{:keys [id label selected?]}]
          {:label (str label (when selected? "  [current]")) :value id})
        pages))

(defn- choose-page!
  "With multiple tabs open, ask the user which to pick from and switch to it
   via select_page. Resolves to :cancelled if the user dismisses, else :ok.
   With one page (or no ask! available) it's a no-op resolving :ok."
  [ask! fx-ctx call room-id pages]
  (if (and ask! (> (count pages) 1))
    (-> (ask! fx-ctx
              {:room-id room-id
               :dialog {:type :select
                        :message "Which browser tab do you want to pick from?"
                        :options (page-options pages)}})
        (.then (fn [page-id]
                 (if (nil? page-id)
                   :cancelled
                   (-> (call "select_page" {:pageId page-id})
                       (.then (constantly :ok)))))))
    (js/Promise.resolve :ok)))

(defn- pick-on-selected!
  "Inject the picker into the currently selected page, poll for a result, and
   submit it (or report cancel/timeout)."
  [{:keys [dispatch! room-id prefill call]}]
  (-> (call "list_pages" {})
      (.then (fn [res]
               (let [line (selected-page-line res)]
                 (status! dispatch! room-id
                          (str "🎯 Element picker: pick an element in the browser"
                               (when line (str " (" line ")")) "…")))))
      (.then (fn [_] (call "evaluate_script" {:function (injection-fn prefill)})))
      (.then (fn [res]
               (when (:is-error res)
                 (throw (js/Error. (str "inject failed — " (result-text res)))))))
      (.then (fn [_] (poll-loop call (+ (js/Date.now) picker-timeout-ms))))
      (.then (fn [poll]
               (cond
                 (:cancelled poll)
                 (status! dispatch! room-id "Element picker cancelled.")

                 (:result poll)
                 (-> (capture-screenshot call)
                     (.then (fn [shot]
                              (submit! dispatch! room-id prefill (:result poll)
                                       (if shot [shot] [])))))

                 :else
                 (do (cleanup! call)
                     (status! dispatch! room-id
                              (if (:timeout poll)
                                "Element picker timed out (2 min)."
                                "Element picker: browser error."))))))))

(defn- run-picker
  "Drive the picker. When multiple tabs are open, first ask which one to pick
   from (and switch to it); then inject into the selected page."
  [{:keys [dispatch! get-state room-id call ask!] :as ctx}]
  (status! dispatch! room-id "Element picker: connecting to browser…")
  (-> (call "list_pages" {})
      (.then (fn [res]
               (when (:is-error res)
                 (throw (js/Error. (str "no browser page — " (result-text res)))))
               (choose-page! ask! {:dispatch! dispatch! :state (get-state)}
                             call room-id (parse-pages res))))
      (.then (fn [choice]
               (if (= choice :cancelled)
                 (status! dispatch! room-id "Element picker cancelled.")
                 (pick-on-selected! ctx))))
      (.catch (fn [e]
                (cleanup! call)
                (status! dispatch! room-id (str "Element picker error: " (.-message e)))))))

;; ── Install (into xi.ext.chrome-mcp, which owns the shared MCP client) ────────────

(defn install
  "Return {:commands :keybindings :fx} for the element picker, wired to `call`
   — the shared chrome-devtools-mcp caller (fn [tool-name args] →
   Promise<normalized-result {:content :is-error}>). `ask!` (from
   ext.core/create-dialogs) powers the multi-tab picker dialog; when absent
   (headless / no dialogs) the picker falls back to the selected page."
  [call & [ask!]]
  {:commands
   [{:name        "pick"
     :description "Pick a browser element and send its context + your message to xi"
     :handler     (fn [_st {:keys [room-id args]}]
                    {:effects [[:ext.element-picker/run
                                {:room-id room-id :prefill (or args "")}]]})}]
   :keybindings
   [{:key   "ctrl+shift+i"
     :event {:type :command/run :name "pick"}}]
   :fx
   {:ext.element-picker/run
    (fn [{:keys [dispatch! get-state]} {:keys [room-id prefill]}]
      (run-picker {:dispatch! dispatch! :get-state get-state
                   :room-id room-id :prefill prefill :call call :ask! ask!}))}})
