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
   :allow-block {:value :block :key "b" :label "Allow block"
                 :resolved-label "Block allowed"}
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
   "r"      :allow-repo
   "block"  :allow-block
   "b"      :allow-block})

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

(defn- drop-first
  "`coll` without its first item equal to `x`, as a vector."
  [x coll]
  (let [[before after] (split-with #(not= x %) coll)]
    (into (vec before) (rest after))))

(defn- block-opts
  "Confirm `opts` for an ask while `left` (its own :target first, then the
   call's other expected asks) is outstanding: with more than one, the ask
   also offers :allow-block and carries :block {:count n} plus {:arg :code
   :ranges […]} — the union of the outstanding asks' code ranges, i.e.
   everything answering it would allow."
  [opts left]
  (if-not (next left)
    opts
    (let [ranges (->> left
                      (filter #(= :code (:arg %)))
                      (mapcat :ranges)
                      distinct
                      sort
                      vec)]
      (assoc opts
             :options (conj (vec (or (seq (:options opts)) default-confirm-options))
                            :allow-block)
             :block (cond-> {:count (count left)}
                      (seq ranges) (assoc :arg :code :ranges ranges))))))

(defn scope-confirm-to-call
  "`ctx` with its :confirm! tagging every dialog with :call — the {:name
   :arguments} of `tool-call`, the call being gated — so a client renders the
   ask on that call's block rather than guessing among parallel running calls
   (`permission-tool-index`). Applied to both halves of a call's gating: the
   policy hook and the tool's own exec ctx (the clj gate asks from inside the
   tool), so every ask carries it. No :confirm! → ctx unchanged.

   It also makes the call's asks one *block*. A tool that will raise several
   asks for one call declares them up front through the ctx's :expect-asks!
   (fn [targets]) — the :target of each ask it may raise. While more than one
   is outstanding, each ask offers :allow-block (see `block-opts`); answering
   it allows that ask and resolves the call's later *declared* asks (matched
   by their non-nil :target) to `true` without a dialog — exactly what the
   block highlighted. Asks it didn't declare (a runtime path gate mid-eval)
   still open their own dialog. The caller sees a plain `true` for the ask
   itself."
  [ctx {:keys [name arguments]}]
  (if-let [confirm! (:confirm! ctx)]
    (let [call     {:name name :arguments arguments}
          expected (volatile! [])
          allowed? (volatile! false)]
      (assoc ctx
             :expect-asks! (fn [targets] (vreset! expected (vec targets)))
             :confirm!
             (fn scoped
               ([message] (scoped message nil))
               ([message opts]
                (let [target    (:target opts)
                      declared? (and (some? target) (some #{target} @expected))
                      left      (cons target (vswap! expected #(drop-first target %)))]
                  (cond
                    (and @allowed? declared?) (js/Promise.resolve true)
                    ;; an undeclared ask after the block was allowed: on its own
                    @allowed? (confirm! message (assoc opts :call call))
                    :else
                    (let [opts   (block-opts opts left)
                          answer (confirm! message (assoc opts :call call))]
                      (if-not (:block opts)
                        answer
                        (.then (js/Promise.resolve answer)
                               (fn [v]
                                 (if (= :block v)
                                   (do (vreset! allowed? true) true)
                                   v)))))))))))
    ctx))

(defn capture-deny-reason
  "`confirm!` whose asks offer *deny with reason* (the dialog gets
   :deny-reason?, see xi.ext.core/create-dialogs), recording the reason the
   user typed into the volatile `box`. The answer itself stays `false`, so
   every boolean caller keeps working; a gate reads `box` to tell the model
   why (`with-deny-reason`). nil `confirm!` → nil (headless)."
  [confirm! box]
  (when confirm!
    (fn capturing
      ([message] (capturing message nil))
      ([message opts] (confirm! message (assoc opts :on-reason #(vreset! box %)))))))

(defn with-deny-reason
  "A gate's denial text `base` plus the reason the user denied with — what the
   model reads in the tool result. Blank `reason` → `base` unchanged."
  [base reason]
  (if (str/blank? reason)
    base
    (str base "\nTo tell you how to proceed, the user said:\n" (str/trim reason))))

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
  "Index in `entries` (≥ `start`) of the tool call a pending :confirm `dialog`
   is gating, or nil. Several tool calls can be running at once while one
   waits on its permission ask, so the dialog's :call ({:name :arguments} of
   the gated call) picks the entry; the newest running match wins. When no
   match is running any more (the turn was interrupted under the ask), the
   newest settled match still hosts it — the ask belongs on that block, not
   in a standalone bubble. A dialog without :call falls back to the newest
   running tool call. No match → nil, so the caller renders the dialog
   standalone and its buttons never vanish."
  [dialog entries start]
  (let [call  (:call dialog)
        idxs  (range (dec (count entries)) (dec start) -1)
        match (fn [i]
                (let [e (nth entries i)]
                  (and (= :tool-call (:kind e))
                       (or (nil? call) (same-call? call e)))))]
    (or (first (filter #(and (= :running (:status (nth entries %))) (match %)) idxs))
        (when call (first (filter match idxs))))))

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
