(ns xi.web.views
  "Replicant view layer — pure (state → hiccup) over the same room state the
   TUI renders. Event handlers dispatch events; there is no view-local atom.

   Phase 7a: the online chat view (topbar, timeline of history entries,
   compose input, abort, permission dialogs). Home view + router land in 7b."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.markdown.hiccup :as md]
            [xi.highlight.core :as hl]
            [xi.highlight.bundle :as grammars]
            [xi.highlight.theme-css :as theme]
            [xi.util :as util]
            [ui.icon :as icon]
            [ui.form :as form]))

;; ── Spinner ──────────────────────────────────────────────────────────────────

(defn- spinner [] [:div {:class ["agent-status-spinner"]}])

;; ── Tool rendering ───────────────────────────────────────────────────────────

(defn- get-arg [args k]
  (or (get args (name k)) (get args k)))

(defn- tool-summary
  "Short one-line argument summary shown in the tool header."
  [tool args]
  (case tool
    ("Bash" "bash") (get-arg args :command)
    ("Read" "read")  (or (get-arg args :file_path) (get-arg args :path))
    ("Write" "write") (or (get-arg args :file_path) (get-arg args :path))
    ("Edit" "edit")  (or (get-arg args :file_path) (get-arg args :path))
    ("Grep" "grep")  (get-arg args :pattern)
    ("Glob" "find")  (get-arg args :pattern)
    ("ls")           (get-arg args :path)
    (let [v (some (fn [k] (let [x (get-arg args k)]
                            (when (and (string? x) (seq x)) x)))
                  [:command :file_path :path :pattern :query :url :prompt :description])]
      v)))

(defn- file-ext [path]
  (when (and (string? path) (str/includes? path "."))
    (-> path (str/split #"\.") last str/lower-case)))

(defn- tool-grammar
  "Highlighting grammar for a tool's output, or nil."
  [tool args]
  (let [path (case tool
               ("Read" "Write" "Edit") (get-arg args :file_path)
               ("read" "write" "edit") (get-arg args :path)
               nil)]
    (when path (grammars/get-grammar (file-ext path)))))

(defn- highlight-code
  "Tokenize + class-wrap text against a grammar → hiccup [:code ...]."
  [grammar text]
  (let [tokens (hl/merge-adjacent (hl/tokenize grammar text))]
    (into [:code]
          (mapv (fn [{:keys [type value]}]
                  (if-let [cls (theme/token-class type)]
                    [:span {:class cls} value]
                    value))
                tokens))))

(defn- truncate-lines [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) n)
      text
      (str (str/join "\n" (take n lines))
           "\n… (" (- (count lines) n) " more lines)"))))

(def ^:private expanded-tools
  "Tools whose output is shown expanded by default."
  #{"Bash" "bash" "Edit" "edit" "Write" "write" "web_search" "fetch"})

(defn- tool-post [{:keys [tool arguments result is-error status]}]
  (let [name      (util/strip-mcp-prefix tool)
        summary   (tool-summary name arguments)
        running?  (= :running status)
        text      (util/extract-text-content result)
        grammar   (when (and text (not is-error)) (tool-grammar name arguments))
        label     (str name (when (seq summary) (str " " (util/truncate (first (str/split-lines (str summary))) 80))))]
    [:div {:class ["post" "post--tool"]}
     [:details {:class ["tool-call-block"] :open (boolean (expanded-tools name))}
      [:summary {:class ["tool-call-toggle"]}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:class ["tool-call-toggle-label"]} label]
       (cond
         running? (spinner)
         is-error [:span {:class ["error-text"]} " error"])]
      (when (seq text)
        [:div {:class ["tool-call-content"]}
         [:pre {:class ["tool-call-code"]}
          (let [shown (truncate-lines text 30)]
            (if grammar (highlight-code grammar shown) shown))]])]]))

;; ── History entry → post ─────────────────────────────────────────────────────

(defn- entry->post [entry]
  (case (:kind entry)
    :user
    [:div {:class ["post" "post--user"]}
     [:div {:class ["post-body"]}
      (when-let [n (:image-count entry)]
        (when (pos? n)
          [:div {:class ["status-text"]} (str "📎 " n " image" (when (> n 1) "s"))]))
      [:div {:class ["post-content"]} (:text entry)]]]

    :text
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-body"]}
      (md/render (:text entry))]]

    :thinking
    [:div {:class ["post" "post--assistant"]}
     [:details {:class ["thinking-block"] :open false}
      [:summary {:class ["thinking-toggle"]}
       [:span {:class ["tool-call-toggle-icon"]}
        (icon/icon {:icon-name :chevron-right :size :sm})]
       [:span {:style {:font-weight "500"}} "Thinking"]]
      [:pre {:class ["thinking-text"]} (:text entry)]]]

    :tool-call
    (tool-post entry)

    :status
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} (:text entry)]]

    :error
    (let [msg (or (:message (:error entry)) (pr-str (:error entry)))]
      (when-not (str/includes? (str msg) "null is not an object")
        [:div {:class ["post" "post--assistant"]}
         [:div {:class ["post-content" "error-text"]} (str "[Error] " msg)]]))

    :aborted
    [:div {:class ["post" "post--assistant"]}
     [:div {:class ["post-content" "status-text"]} "Interrupted."]]

    nil))

;; ── Compose ──────────────────────────────────────────────────────────────────

(defn- compose-textarea-el []
  (.querySelector js/document ".compose-input-wrapper textarea"))

(defn- submit-compose! [dispatch! room-id]
  (when-let [^js el (compose-textarea-el)]
    (let [text (str/trim (or (.-value el) ""))]
      (when (seq text)
        (set! (.-value el) "")
        (dispatch! {:type :input/submit :room-id room-id :text text})))))

(defn- compose-box [dispatch! room busy?]
  (let [room-id (:id room)]
    [:div {:class ["compose-box"]}
     [:div {:class ["compose-input-row"]}
      [:div {:class ["compose-input-wrapper"]}
       (form/form-textarea-auto
        {:placeholder (if busy? "Working…" "Message…")
         :max-rows 6
         :attrs {:on {:keydown
                      (fn [^js e]
                        (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                          (.preventDefault e)
                          (when-not busy?
                            (submit-compose! dispatch! room-id))))}}})]
      (if busy?
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :agent/abort :room-id room-id}))}}
         (icon/icon {:icon-name :circle-x :size :md})]
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (submit-compose! dispatch! room-id))}}
         (icon/icon {:icon-name :arrow-up :size :md})])]]))

;; ── Permission dialog ────────────────────────────────────────────────────────

(defn- dialog-overlay [dispatch! room]
  (when-let [{:keys [id type message text options]} (first (get-in room [:ui :dialogs]))]
    (let [room-id (:id room)
          answer! (fn [value]
                    (dispatch! {:type :ui/dialog-response
                                :room-id room-id :dialog-id id :value value}))]
      [:div {:class ["confirm-overlay"]}
       [:div {:class ["confirm-panel"]}
        [:div {:class ["confirm-message"]} (or message text)]
        [:div {:class ["confirm-actions"]}
         (case type
           :select
           (for [{:keys [label value]} options]
             [:button {:class ["confirm-btn" "confirm-btn--allow"]
                       :on {:click (fn [_] (answer! value))}} label])
           :alert
           [:button {:class ["confirm-btn" "confirm-btn--allow"]
                     :on {:click (fn [_] (answer! nil))}} "OK"]
           ;; :confirm (default)
           [:<>
            [:button {:class ["confirm-btn" "confirm-btn--deny"]
                      :on {:click (fn [_] (answer! false))}} "Deny"]
            [:button {:class ["confirm-btn" "confirm-btn--allow"]
                      :on {:click (fn [_] (answer! true))}} "Allow"]])]]])))

;; ── Chat view ────────────────────────────────────────────────────────────────

(defn- chat-view [state dispatch!]
  (let [room    (state/active-room state)
        history (:history room)
        busy?   (get-in room [:agent :busy?])
        model   (get-in room [:agent :model])]
    [:div {:class ["container"] :replicant/key "chat"}
     [:div {:class ["topbar"]}
      [:div {:class ["topbar-title"]}
       "Xi"
       (when model [:span {:class ["topbar-subtitle"]} (str " · " model)])]]
     [:div {:class ["timeline"]}
      [:div {:class ["timeline-content"]}
       (keep entry->post history)
       (when busy?
         [:div {:class ["post" "post--assistant"]}
          [:div {:class ["status-bubble"]}
           (spinner) [:span "Working…"]]])]]
     (dialog-overlay dispatch! room)
     (compose-box dispatch! room busy?)]))

(defn- connecting-view []
  [:div {:class ["container"] :replicant/key "connecting"}
   [:div {:class ["empty-state"]}
    (spinner)
    [:p "Connecting to server…"]]])

;; ── Root ─────────────────────────────────────────────────────────────────────

(defn root-view
  "Top-level view. Phase 7a: chat once a room is joined, else a connecting
   placeholder. Router + home view arrive in 7b."
  [state dispatch!]
  (if (state/active-room state)
    (chat-view state dispatch!)
    (connecting-view)))
