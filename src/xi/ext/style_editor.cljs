(ns xi.ext.style-editor
  "Live style editor tool → committed CSS values.

   An agent-driven counterpart to the element picker. The model calls the
   `style_editor` tool with a CSS selector and a list of controls (sliders /
   color pickers); the extension injects a floating panel into the
   MCP-controlled page, the user tunes the element's styles visually (each
   control live-applies to the element's inline style), and on commit the final
   CSS values are returned as the tool result so the model can apply them to the
   source. On cancel the inline styles are restored.

   Like the element picker, this is not a standalone extension — it's installed
   into xi.ext.chrome-mcp (which owns the shared chrome-devtools-mcp client) via
   `install`, and drives the MCP's currently selected page through its
   `evaluate_script` tool.

     model → style_editor {selector, controls}
           → inject panel → poll for commit/cancel
           → return committed {property → cssValue}"
  (:require [clojure.string :as str])
  (:require-macros [xi.ext.style-editor-js :refer [inline-style-editor-js]]
                   [xi.ext.dialkit-css :refer [inline-dialkit-css]]))

;; ── Config ───────────────────────────────────────────────────────────────────

(def ^:private editor-timeout-ms 300000)
(def ^:private poll-interval-ms 400)

(def ^:private editor-js (inline-style-editor-js))
(def ^:private dialkit-css (inline-dialkit-css))

;; A <style> tag carrying the dialkit stylesheet, injected once before the
;; panel mounts (the target page doesn't load clj-ui-framework's CSS).
(def ^:private css-js
  (str "(function(){var id='__xi-dialkit-css';"
       "if(!document.getElementById(id)){"
       "var s=document.createElement('style');s.id=id;"
       "s.textContent=" (js/JSON.stringify dialkit-css) ";"
       "document.head.appendChild(s);}})();"))

(def ^:private cleanup-js
  (str "(function() {"
       "if (window.__xiStyleEditorDial) { try { window.__xiStyleEditorDial.destroy(); } catch (e) {} }"
       "delete window.__xiStyleEditorDial;"
       "delete window.__xiStyleEditorActive; delete window.__xiStyleEditorResult;"
       "delete window.__xiStyleEditorCancelled;"
       "})();"))

;; ── JS injected into the page (via chrome-devtools-mcp evaluate_script) ───────

(defn- config-json [selector title controls]
  (js/JSON.stringify (clj->js {:selector (or (not-empty selector) nil)
                               :title (or (not-empty title) "Style editor")
                               :controls controls})))

(defn- injection-fn
  "A JS arrow-function for evaluate_script: clear any stale editor, set the
   config, run the editor, and return true."
  [selector title controls]
  (str "() => {\n"
       cleanup-js "\n"
       css-js "\n"
       "window.__XI_STYLE_EDITOR_CFG__ = " (config-json selector title controls) ";\n"
       editor-js "\n"
       "return true;\n}"))

(def ^:private poll-fn
  "() => ({ r: window.__xiStyleEditorResult, c: window.__xiStyleEditorCancelled })")

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

;; ── Result formatting (pure) ─────────────────────────────────────────────────

(defn- format-result
  "Human-readable tool result from a committed editor result. `:changes` is a
   list of {:selector :property :css}; render them grouped by selector."
  [{:keys [changes refine missing] apply-to-class :applyToClass
    candidates :classCandidates}]
  (let [changes (or changes [])
        sels    (distinct (map :selector changes))
        cands   (seq (remove str/blank? (or candidates [])))
        block   (fn [s]
                  (str "`" s "`:\n"
                       (->> changes
                            (filter #(= (:selector %) s))
                            (map (fn [{:keys [property css]}]
                                   (str "- `" property "`: `" css "`")))
                            (str/join "\n"))))]
    (str "Committed style values"
         (when (> (count sels) 1) (str " across " (count sels) " elements"))
         ":\n\n"
         (if (seq changes)
           (str/join "\n\n" (map block sels))
           "(no changes)")
         "\n\n"
         (if apply-to-class
           (str "**Scope: apply to shared CSS classes/rules, NOT single nodes.**\n"
                "For each change, find the common class the styling should belong to "
                "and edit that class's rule (or the component that renders it) so "
                "every instance updates. "
                (if cands
                  (str "Candidate classes from the picked elements and their ancestors "
                       "(closest first): "
                       (str/join ", " (map #(str "`" % "`") cands))
                       ". Pick the most semantically appropriate shared class per "
                       "change (usually a component / card / item class) and edit its rule.")
                  (str "The picked elements and their ancestors expose no class — look "
                       "at the component source and add/extend a shared class rather "
                       "than targeting these nodes alone."))
                "\n\n⚠️ The selectors above are positional DOM paths and are brittle — "
                "do NOT hardcode them in the source; resolve to the shared "
                "class/component instead.")
           (str "**Scope: apply to just these specific elements.** "
                "Target only these nodes in the source; do not change shared rules "
                "for other elements."))
         (when (seq missing)
           (str "\n\n(no element matched: "
                (str/join ", " (map (fn [m] (str "`" (:property m) "` on `"
                                                 (:selector m) "`")) missing))
                ")"))
         (when (not-empty refine)
           (str "\n\n**Further instructions from the user:**\n" refine)))))

(defn- text-result [text & [error?]]
  {:content [{:type "text" :text text}] :is-error (boolean error?)})

;; ── Orchestration ────────────────────────────────────────────────────────────

(defn- selected-page-id
  "Resolve the pageId to operate on: the currently-selected page, else the
   first page. chrome-devtools-mcp 1.8.0 requires an explicit pageId on all
   page-scoped tools (evaluate_script), so we can no longer rely on an implicit
   selected page."
  [call]
  (-> (call "list_pages" {})
      (.then (fn [res]
               (let [pages (->> (str/split-lines (result-text res))
                                (keep (fn [line]
                                        (when-let [[_ id rest] (re-matches #"\s*(\d+):\s*(.*)" line)]
                                          {:id (js/parseInt id 10)
                                           :selected? (str/includes? rest "[selected]")}))))]
                 (or (some #(when (:selected? %) (:id %)) pages)
                     (:id (first pages))))))))

(defn- poll-loop
  "Poll `page-id` for the editor result until it resolves, is cancelled,
   errors, or the deadline passes. `call` is the shared chrome-devtools-mcp
   caller (fn [tool args] → Promise<normalized-result>). chrome-devtools-mcp
   1.8.0 requires an explicit `:pageId` on page-scoped tools."
  [call deadline page-id]
  (js/Promise.
   (fn [resolve _reject]
     (letfn [(step []
               (if (> (js/Date.now) deadline)
                 (resolve {:timeout true})
                 (-> (call "evaluate_script" {:function poll-fn :pageId page-id})
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

(defn- cleanup! [call page-id]
  (when page-id
    (-> (call "evaluate_script" {:function cleanup-fn :pageId page-id})
        (.catch (fn [_] nil)))))

(defn- run-editor
  "Inject the editor into the selected page, poll for a result, and resolve a
   tool result map."
  [call {:keys [selector title controls]}]
  (cond
    (empty? controls)
    (js/Promise.resolve (text-result "style_editor: at least one control is required." true))

    (and (str/blank? selector)
         (some #(str/blank? (:selector %)) controls))
    (js/Promise.resolve
     (text-result (str "style_editor: every control needs a `selector`, or set a "
                       "top-level `selector` as the default for controls that omit one.")
                  true))

    :else
    (-> (selected-page-id call)
        (.then
         (fn [page-id]
           (-> (call "evaluate_script" {:function (injection-fn selector title controls) :pageId page-id})
               (.then (fn [res]
                        (when (:is-error res)
                          (throw (js/Error. (str "inject failed — " (result-text res)))))))
               (.then (fn [_] (poll-loop call (+ (js/Date.now) editor-timeout-ms) page-id)))
               (.then (fn [poll]
                        (cond
                          (:result poll)
                          (let [r (:result poll)]
                            (if (:notFound r)
                              (text-result (str "style_editor: no element matched any control's "
                                                "selector"
                                                (when-let [m (seq (:missing r))]
                                                  (str " (" (str/join ", " (map #(str "`" (:selector %) "`") m)) ")"))
                                                ".") true)
                              (text-result (format-result r))))

                          (:cancelled poll)
                          (text-result "style_editor: the user cancelled without committing changes.")

                          :else
                          (do (cleanup! call page-id)
                              (text-result (if (:timeout poll)
                                             "style_editor: timed out waiting for the user (5 min)."
                                             "style_editor: browser error.")
                                           true)))))
               (.catch (fn [e]
                         (cleanup! call page-id)
                         (text-result (str "style_editor error: " (.-message e)) true))))))
        (.catch (fn [e]
                  (text-result (str "style_editor error: " (.-message e)) true))))))

;; ── Install (into xi.ext.chrome-mcp, which owns the shared MCP client) ────────────

(def ^:private tool-def
  {:name "style_editor"
   :description
   (str "Open ONE live style editor panel in the browser (via the "
        "chrome-devtools-mcp-controlled page) with sliders / color pickers so the "
        "user can visually tune CSS on the real page; the panel live-applies each "
        "change and returns the committed values so you can edit the source. "
        "IMPORTANT: put ALL the properties you want to tune in a SINGLE call — do "
        "NOT open multiple panels. Each control can target its OWN element via its "
        "`selector` (falling back to the top-level `selector`), so one panel can "
        "tune several elements and properties at once. Blocks until the user "
        "clicks Apply or Cancel. Requires the chrome browser tools "
        "(XI_CHROME_TOOLS). Use camelCase CSS property names (e.g. borderRadius, "
        "backgroundColor). A property may also be a CSS custom property (e.g. "
        "--text-primary, usually paired with selector :root). IMPORTANT: when "
        "the visual value you want to tune is authored as var(--x) in the "
        "site's CSS (theme tokens — very common for colors), tune the VARIABLE "
        "itself, not a consuming property on an ancestor: setting e.g. `color` "
        "on body does nothing when descendants each resolve var(--x) "
        "themselves. Mind CSS units on range controls: when `unit` is "
        "omitted it is inferred from the element's current computed value "
        "(unitless properties like fontWeight/opacity/zIndex compute to bare "
        "numbers → no unit; lengths → px), and lineHeight always defaults to "
        "the unitless ratio — so a lineHeight slider needs ratio-scale "
        "min/max/step (e.g. min 1, max 2.5, step 0.05), NOT px values. Pass "
        "`unit` explicitly (\"em\", \"%\", \"rem\", \"px\", \"\") to override "
        "the inference, and always scale min/max/step to the unit in effect.")
   :input_schema
   {:type "object"
    :properties
    {"selector" {:type "string"
                 :description "Default CSS selector for controls that don't set their own (e.g. \"#app .card\"). Optional if every control has its own `selector`."}
     "title"    {:type "string"
                 :description "Optional panel title shown to the user."}
     "controls" {:type "array"
                 :description "The controls to render. Put every property you want to tune here — one panel, many controls. Each may target a different element."
                 :items
                 {:type "object"
                  :properties
                  {"property" {:type "string"
                               :description "CSS property in camelCase (e.g. borderRadius, backgroundColor, opacity), or a CSS custom property (e.g. --text-primary — pair it with the selector where the variable is defined, usually :root). Prefer the custom property whenever the site derives the visual value from var(--x)."}
                   "selector" {:type "string"
                               :description "CSS selector of the element THIS control tunes. Defaults to the top-level `selector`."}
                   "label"    {:type "string"
                               :description "Optional human label (defaults to the property name)."}
                   "type"     {:type "string"
                               :description "Control type. Omit to infer from the property."
                               :enum ["range" "color" "opacity"]}
                   "min"      {:type "number" :description "Range minimum (range only)."}
                   "max"      {:type "number" :description "Range maximum (range only)."}
                   "step"     {:type "number" :description "Range step (range only)."}
                   "unit"     {:type "string"
                               :description "Range unit appended to the value (range only). If omitted, inferred from the element's computed value (bare number → no unit, \"12px\" → px, \"50%\" → %); lineHeight always defaults to the unitless ratio. Pass explicitly (\"em\", \"rem\", \"%\", \"px\", \"\") to override. min/max/step must match the unit in effect — a unitless lineHeight wants e.g. min 1 max 2.5 step 0.05."}}
                  :required ["property"]}}}
    :required ["controls"]}})

(defn install
  "Return {:tool-definitions :tool-registry} for the style editor, wired to
   `call` — the shared chrome-devtools-mcp caller (fn [tool-name args] →
   Promise<normalized-result {:content :is-error}>)."
  [call]
  {:tool-definitions [tool-def]
   :tool-registry    {"style_editor"
                      ;; Thread the turn's tool ctx (carries :client-pid) into
                      ;; every browser call so the chrome guard scopes to this
                      ;; session's workspace (see xi.ext.chrome-mcp.guard).
                      (fn [args ctx]
                        (run-editor (fn [tool targs] (call tool targs ctx)) args))}})
