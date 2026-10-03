(ns xi.dialog
  "Dialogs as data.

   A :confirm dialog carries :options — a vector of option keywords, e.g.
   [:yes :no :allow-repo] — instead of per-option boolean flags. The TUI and
   web renderers iterate the normalized options generically, so adding a new
   confirm choice means adding one entry to `confirm-option` (and emitting it
   from the gate that wants it), not editing every dialog template.

   A :form dialog carries :fields — a vector of {:name … :label …} maps —
   and resolves to a map of field name → entered text (or nil on cancel).
   Emitters only need a field's :name; :label defaults to a humanized name."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [xi.util :as util]))

(def confirm-option
  "Canonical confirm options, keyed by the keyword that appears in a dialog's
   :options vector. :value is what the dialog resolves to (callers branch on
   it), :key the TUI shortcut, :label the button/hint text, :resolved-label
   the text of the decision pill once answered."
  {:yes        {:value true    :key "y" :label "Allow"
                :resolved-label "Allowed"}
   :no         {:value false   :key "n" :label "Deny"
                :resolved-label "Denied"}
   :always     {:value :always :key "a" :label "Always"
                :resolved-label "Always allowed"}
   :allow-repo {:value :repo   :key "r" :label "Allow repo writes"
                :resolved-label "Repo writes allowed"}
   :recommend-rule {:value :recommend :key "?" :label "Recommend a rule"
                    :resolved-label "Recommending a rule…"}})

(def ^:private option-alias
  "Other spellings of a confirm option: rules files name the repo grant after
   the answer it resolves to (`:options [:yes :no :repo]`)."
  {:repo :allow-repo})

(def default-confirm-options [:yes :no])

(defn confirm-options
  "Normalized options for a :confirm dialog: keywords are looked up in
   `confirm-option` (aliases resolved, unknown ones dropped), maps pass
   through as-is. A dialog without :options gets the plain yes/no pair."
  [dialog]
  (into []
        (keep #(if (keyword? %) (confirm-option (option-alias % %)) %))
        (or (seq (:options dialog)) default-confirm-options)))

(defn resolved-label
  "Decision-pill label for an answered :confirm dialog."
  [dialog value]
  (or (some #(when (= (:value %) value) (:resolved-label %))
            (confirm-options dialog))
      (if value "Allowed" "Denied")))

(def ^:private allow-arg-option
  "`/allow <arg>` spellings → the confirm option they pick."
  {nil      :yes
   "always" :always
   "a"      :always
   "repo"   :allow-repo
   "r"      :allow-repo})

(defn answer-option
  "Confirm option an answer command picks — `verb` is :allow or :deny,
   `args` the command's trailing text (nil → plain allow). nil for an
   unknown /allow argument."
  [verb args]
  (case verb
    :deny  :no
    :allow (allow-arg-option (some-> args str/trim str/lower-case not-empty))
    nil))

(defn answer
  "Resolve answering the room's pending confirm with `option` (a
   `confirm-option` key). The answered dialog is the one every client
   renders — the first in the room's :ui :dialogs. Returns
   {:dialog-id :value} or {:error text} when there is nothing to answer or
   the dialog doesn't offer that choice (e.g. /allow repo on a plain ask)."
  [room option]
  (let [dialog (first (get-in room [:ui :dialogs]))
        value  (:value (confirm-option option))]
    (cond
      (not= :confirm (:type dialog))
      {:error "No pending permission request."}

      (not-any? #(= value (:value %)) (confirm-options dialog))
      {:error (str "This request doesn't offer \""
                   (:label (confirm-option option)) "\".")}

      :else {:dialog-id (:id dialog) :value value})))

(defn- same-call?
  "Does history `entry` (a :tool-call) belong to the gated `call`
   ({:name :arguments}) a confirm dialog was raised for? Tool names compare
   without their MCP prefix and arguments with string keys, since the entry's
   come from the provider stream and the call's from the runner frame."
  [{:keys [name arguments]} entry]
  (and (= (util/strip-mcp-prefix (str name))
          (util/strip-mcp-prefix (str (:tool entry))))
       (= (walk/stringify-keys (or arguments {}))
          (walk/stringify-keys (or (:arguments entry) {})))))

(defn permission-tool-index
  "Index in `entries` (≥ `start`) of the running tool call a pending :confirm
   `dialog` is gating, or nil. Several tool calls can be running at once while
   one waits on its permission ask, so the dialog's :call ({:name :arguments}
   of the gated call) picks the entry; the newest running match wins. A dialog
   without :call falls back to the newest running tool call. No match → nil,
   so the caller renders the dialog standalone and its buttons never vanish."
  [dialog entries start]
  (let [call (:call dialog)]
    (->> (range (dec (count entries)) (dec start) -1)
         (filter (fn [i]
                   (let [e (nth entries i)]
                     (and (= :tool-call (:kind e))
                          (= :running (:status e))
                          (or (nil? call) (same-call? call e))))))
         first)))

(defn humanize-name
  "\"commit-message\" → \"Commit message\"."
  [s]
  (let [t (str/replace (or s "") "-" " ")]
    (if (seq t)
      (str (str/upper-case (subs t 0 1)) (subs t 1))
      t)))

(defn form-fields
  "Normalized fields for a :form dialog: each entry gets a :label (defaulting
   to the humanized :name) and a :value (the prefilled/initial text, defaulting
   to \"\"). Entries without a :name are dropped."
  [dialog]
  (into []
        (keep (fn [{:keys [name label value] :as field}]
                (when (seq (str name))
                  (assoc field
                         :label (or label (humanize-name name))
                         :value (str value)))))
        (:fields dialog)))
