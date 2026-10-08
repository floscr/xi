(ns xi.keys
  "Keyboard shortcuts shared by the TUI and the web client — pure data and
   pure functions, no I/O.

   Three pieces of data:

   - Actions: what a key can do. `catalog` names every built-in action
     (`:chat/new`, `:pager/down`, …) with its label; a surface attaches the
     behaviour (xi.web.keymap registers a :run fn per id, xi.client.tui
     dispatches an event). Extensions add actions through their
     `:keybindings` — the event type is the action id unless they set `:id`.
   - Layers: where a key applies. A keymap is `{layer {key action-id}}`; the
     surface says which layers are active right now, inner → outer:
       transient  :permission-pending :agent-busy
       buffer     :buffer/diff :buffer/file :buffer/prompt :buffer/pager …
       page       :page/chat :page/home :page/git-status … (web only)
       mode       :mode/compose (a text field owns the keys) | :mode/navigate
       :global
     `lookup` walks them in that order: the first layer mentioning the key
     wins, an explicit nil unbinds it, and an action whose guard fails lets
     the key fall through to the next layer.
   - Keymaps: `defaults` (shared keys plus :web / :tui sections), the
     extensions' default keys, and the user's `:keys` from config.edn
     (xi.user-config), merged by `effective-keymap` in that order so the
     user has the last word. `:defaults? false` drops the built-in keys.

   Key notation: modifiers joined with `+` before the key — \"alt+n\",
   \"ctrl+shift+p\", \"mod+k\" (mod = ctrl on the TUI, ctrl or cmd in the
   browser). Letters are case-insensitive once a modifier is present
   (\"alt+N\" is \"alt+n\"); a bare uppercase letter means shift (\"G\" is
   \"shift+g\"). Named keys: escape enter tab backspace delete space insert
   up down left right home end pageup pagedown f1…f12. A sequence of chords
   is written with spaces: \"g g\", \"] c\". Sequences make leader keys:
   \"space g g\" in :mode/navigate is SPC g g.

   A binding's value is an action id, nil (unbind), or a string: the text to
   send in the current chat, e.g. \"space g c\" → \"/commit\" (web only)."
  (:require [clojure.string :as str]))

;; ── Chords ───────────────────────────────────────────────────────────────────

(def ^:private modifier-aliases
  {"ctrl" :ctrl "control" :ctrl
   "alt" :alt "opt" :alt "option" :alt
   "shift" :shift
   "meta" :meta "cmd" :meta "command" :meta "super" :meta "win" :meta
   "mod" :mod})

(def named-keys
  "Non-character keys a chord may name."
  (into #{"escape" "enter" "tab" "backspace" "delete" "space" "insert"
          "up" "down" "left" "right" "home" "end" "pageup" "pagedown"}
        (map #(str "f" %) (range 1 13))))

(def ^:private key-aliases
  {"esc" "escape" "return" "enter" "bs" "backspace" "del" "delete"
   "pgup" "pageup" "pgdn" "pagedown" "pgdown" "pagedown"
   "arrowup" "up" "arrowdown" "down" "arrowleft" "left" "arrowright" "right"
   "spacebar" "space" "ins" "insert" "plus" "+"})

(defn- letter? [s]
  (boolean (and (string? s) (re-matches #"[a-zA-Z]" s))))

(defn chord
  "Canonical chord string from parts `{:key :ctrl :alt :shift :meta :mod}`.
   `:key` is one printable character or a named key (see `named-keys`). A bare
   uppercase letter (no other modifier) means shift; a shifted non-letter
   character (\"?\") already encodes its shift, so the modifier is dropped.
   nil when the key is unusable."
  [{:keys [key ctrl alt shift meta mod]}]
  ;; the space character is the named key "space" (a chord is space-separated)
  (let [key (if (= key " ") "space" key)]
   (when (and (string? key)
             (or (contains? named-keys key) (= 1 (count key))))
    (let [modded? (or ctrl alt meta mod)
          k       (if (letter? key) (str/lower-case key) key)
          shift   (cond
                    (letter? key) (boolean (or shift (and (not modded?)
                                                         (not= key (str/lower-case key)))))
                    (contains? named-keys key) (boolean shift)
                    :else false)]
      (str (when ctrl "ctrl+") (when alt "alt+") (when shift "shift+")
           (when meta "meta+") (when mod "mod+") k)))))

(defn parse-chord
  "One chord spec (\"alt+shift+p\", \"Esc\", \"G\", \"ctrl++\") → its canonical
   string, or nil when it is not a chord."
  [s]
  (let [s (str/trim (str s))]
    (when (seq s)
      ;; "+" is a key too: alone, or doubled after the modifiers ("ctrl++").
      (let [plus-key? (or (= s "+")
                          (and (str/ends-with? s "++") (not (str/ends-with? s "+++"))))
            body      (if plus-key? (subs s 0 (dec (count s))) s)
            parts     (cond-> (vec (remove empty? (when (seq body) (str/split body #"\+"))))
                        plus-key? (conj "+"))
            mods      (butlast parts)
            key       (last parts)]
        (when (and key
                   (or plus-key? (not (str/ends-with? s "+")))
                   (not (contains? modifier-aliases (str/lower-case key)))
                   (every? #(contains? modifier-aliases (str/lower-case %)) mods))
          (let [lk   (str/lower-case key)
                key* (get key-aliases lk (if (contains? named-keys lk) lk key))
                ms   (into #{} (map #(modifier-aliases (str/lower-case %))) mods)
                ;; "alt+N" is alt+n: with a modifier present letters lose case
                key* (if (and (seq ms) (letter? key*)) (str/lower-case key*) key*)]
            (chord {:key key* :ctrl (:ctrl ms) :alt (:alt ms) :shift (:shift ms)
                    :meta (:meta ms) :mod (:mod ms)})))))))

(defn key-spec?
  "True for the two spellings of a binding's key: a string (\"g g\") or a
   non-empty vector of chord strings ([\"g\" \"g\"])."
  [k]
  (or (string? k)
      (and (sequential? k) (seq k) (every? string? k))))

(defn parse-key
  "A key spec — one chord or a sequence, written \"g g\" / \"] c\" or as a
   vector [\"g\" \"g\"] → canonical string, or nil when any chord is invalid."
  [s]
  (let [s      (if (sequential? s) (str/join " " s) s)
        chords (mapv parse-chord (str/split (str/trim (str s)) #"\s+"))]
    (when (and (seq chords) (every? some? chords))
      (str/join " " chords))))

(defn- split-chord
  "Canonical chord → {:mods #{\"ctrl\" …} :key \"n\"}."
  [c]
  (if (str/ends-with? c "+")
    {:mods (set (remove empty? (str/split (subs c 0 (dec (count c))) #"\+"))) :key "+"}
    (let [ps (str/split c #"\+")]
      {:mods (set (butlast ps)) :key (last ps)})))

(defn chords
  "The chords of a canonical key, in order."
  [k]
  (str/split k #" "))

(defn bare-printable?
  "True when the key's first chord types a character (no ctrl/alt/meta, a
   single-character key or the space bar) — never looked up while a text
   field has focus."
  [k]
  (when-let [c (not-empty (first (chords k)))]
    (let [{:keys [mods key]} (split-chord c)]
      (and (not (some mods ["ctrl" "alt" "meta" "mod"]))
           (or (= key "space") (not (contains? named-keys key)))))))

(defn expand-mod
  "`mod` is ctrl on the TUI and ctrl *or* meta in the browser: → the concrete
   canonical keys a spec stands for on `surface` (:web | :tui)."
  [k surface]
  (if (str/includes? k "mod+")
    (->> (if (= surface :web) ["ctrl+" "meta+"] ["ctrl+"])
         (map #(parse-key (str/replace k "mod+" %)))
         (remove nil?)
         distinct
         vec)
    [k]))

(def ^:private display-names
  {"escape" "Esc" "enter" "Enter" "tab" "Tab" "backspace" "Backspace"
   "delete" "Del" "space" "Space" "insert" "Ins" "up" "↑" "down" "↓"
   "left" "←" "right" "→" "home" "Home" "end" "End" "pageup" "PgUp"
   "pagedown" "PgDn"})

(def ^:private compact-names
  (assoc display-names "enter" "⏎" "space" "Spc"))

(defn- format-chord
  "One chord for display. Letters are uppercase in the long form (\"Alt+N\");
   a shifted letter is written uppercase instead of carrying a Shift+ prefix
   (\"G\"), except next to other modifiers in the long form (\"Alt+Shift+P\")
   where dropping it would read as a different key."
  [c {:keys [compact?]}]
  (let [{:keys [mods key]} (split-chord c)
        letter      (letter? key)
        shifted?    (contains? mods "shift")
        others?     (boolean (seq (disj mods "shift")))
        show-shift? (and shifted? (or (not letter) (and (not compact?) others?)))
        ;; a bare letter stays lowercase ("g" is not "G"); next to other
        ;; modifiers the long form capitalises it ("Alt+N")
        key-label   (cond
                      (contains? named-keys key)
                      (get (if compact? compact-names display-names) key key)
                      letter (if (or shifted? (and (not compact?) others?)) (str/upper-case key) key)
                      :else  key)
        labels      (if compact?
                      {"ctrl" "^" "alt" "M-" "shift" "S-" "meta" "Cmd-" "mod" "Mod-"}
                      {"ctrl" "Ctrl+" "alt" "Alt+" "shift" "Shift+" "meta" "Cmd+" "mod" "Mod+"})
        ordered     (filter (fn [m] (and (contains? mods m)
                                         (or (not= m "shift") show-shift?)))
                            ["ctrl" "alt" "shift" "meta" "mod"])]
    (str (apply str (map labels ordered)) key-label)))

(defn format-key
  "Human display of a canonical key: \"Alt+N\", \"Shift+G\" → \"G\", \"g g\".
   `{:compact? true}` renders the TUI help-bar form (\"^d\", \"M-x\", \"gg\",
   \"⏎\")."
  ([k] (format-key k {}))
  ([k opts]
   (str/join (if (:compact? opts) "" " ")
             (map #(format-chord % opts) (chords k)))))

;; ── Actions ──────────────────────────────────────────────────────────────────

(def catalog
  "Every built-in action: id → {:label :surface}. `:surface` (#{:web} /
   #{:tui}) marks the ones only one client implements; absent = both."
  {:chat/new                {:label "New chat"}
   :agent/abort             {:label "Stop the running turn"}
   :permission/allow        {:label "Allow the pending permission request"}
   :permission/always       {:label "Always allow requests like the pending one" :surface #{:web}}
   :permission/allow-repo   {:label "Allow repo writes for the pending permission request" :surface #{:web}}
   :permission/allow-block  {:label "Allow every request of the pending permission's block" :surface #{:web}}
   :permission/deny         {:label "Deny the pending permission request"}
   :keys/show               {:label "Keyboard shortcuts"}
   :buffer/close            {:label "Close this view, back to the chat"}
   :diff/next-file          {:label "Next file"}
   :diff/prev-file          {:label "Previous file"}
   ;; web
   :sidebar/toggle          {:label "Toggle the sidebar" :surface #{:web}}
   :session/jump-attention  {:label "Jump to the chat that needs you" :surface #{:web}}
   :session/next            {:label "Next chat" :surface #{:web}}
   :session/prev            {:label "Previous chat" :surface #{:web}}
   :sessions/prune          {:label "Prune: run every sidebar cleanup" :surface #{:web}}
   :files/find              {:label "Find a file" :surface #{:web}}
   :git/status              {:label "Git status" :surface #{:web}}
   :palette/open            {:label "Command palette" :surface #{:web}}
   :projects/pick           {:label "Pick a project" :surface #{:web}}
   :projects/open           {:label "All projects" :surface #{:web}}
   :chat/hide               {:label "Hide this chat from Recent" :surface #{:web}}
   :chat/delete             {:label "Delete this chat" :surface #{:web}}
   :prompt/prev             {:label "Previous message of yours" :surface #{:web}}
   :prompt/next             {:label "Next message of yours" :surface #{:web}}
   :diff/next-hunk          {:label "Next hunk" :surface #{:web}}
   :diff/prev-hunk          {:label "Previous hunk" :surface #{:web}}
   :buffers/switch          {:label "Switch buffer" :surface #{:web}}
   :compose/focus           {:label "Focus the message box" :surface #{:web}}
   :compose/blur            {:label "Leave the text field" :surface #{:web}}
   :timeline/bottom         {:label "Scroll to the bottom" :surface #{:web}}
   :scroll/down             {:label "Scroll down" :surface #{:web}}
   :scroll/up               {:label "Scroll up" :surface #{:web}}
   :scroll/half-down        {:label "Scroll half a page down" :surface #{:web}}
   :scroll/half-up          {:label "Scroll half a page up" :surface #{:web}}
   :dialog/close            {:label "Close the open dialog" :surface #{:web}}
   ;; tui
   :prompt/toggle           {:label "System prompt: full / overview" :surface #{:tui}}
   :pager/down              {:label "Cursor down" :surface #{:tui}}
   :pager/up                {:label "Cursor up" :surface #{:tui}}
   :pager/top               {:label "Top" :surface #{:tui}}
   :pager/bottom            {:label "Bottom" :surface #{:tui}}
   :pager/half-down         {:label "Half page down" :surface #{:tui}}
   :pager/half-up           {:label "Half page up" :surface #{:tui}}
   :pager/page-down         {:label "Page down" :surface #{:tui}}
   :pager/page-up           {:label "Page up" :surface #{:tui}}
   :pager/select            {:label "Start / cancel line selection" :surface #{:tui}}
   :pager/yank              {:label "Copy the line or selection" :surface #{:tui}}
   :pager/explain           {:label "Ask the agent to explain the selection" :surface #{:tui}}
   :pager/prompt            {:label "Put the selection in the editor" :surface #{:tui}}
   :pager/close             {:label "Close (Esc cancels a selection first)" :surface #{:tui}}
   :pager/command           {:label "Command mode: focus the editor" :surface #{:tui}}
   :pager/next-change       {:label "Next change" :surface #{:tui}}
   :pager/prev-change       {:label "Previous change" :surface #{:tui}}
   :diff/edit               {:label "Open the file under the cursor in $EDITOR" :surface #{:tui}}
   :diff/fold               {:label "Fold / unfold the file under the cursor" :surface #{:tui}}})

(defn label
  "Display label of an action: from `actions` (a map id → {:label}) or the
   catalog, else the id's name."
  ([id] (label id nil))
  ([id actions]
   (or (get-in actions [id :label])
       (get-in catalog [id :label])
       (when (keyword? id) (name id)))))

;; ── Layers ───────────────────────────────────────────────────────────────────

(def layer-labels
  "Display names of the built-in layers, in listing order."
  [[:global             "Everywhere"]
   [:mode/navigate      "Navigating (no text field focused)"]
   [:mode/compose       "In a text field"]
   [:page/chat          "Chat page"]
   [:page/home          "Projects page"]
   [:page/git-status    "Git status page"]
   [:buffer/pager       "Any buffer viewer"]
   [:buffer/diff        "Diff view"]
   [:buffer/file        "File view"]
   [:buffer/prompt      "System prompt view"]
   [:buffer/keys        "Shortcut list"]
   [:buffer/subagents   "Sub-agents view"]
   [:agent-busy         "While the agent is working"]
   [:permission-pending "While a permission request is pending"]])

(def ^:private layer-rank
  (into {} (map-indexed (fn [i [l _]] [l i])) layer-labels))

(defn layer-label [l]
  (or (some (fn [[k v]] (when (= k l) v)) layer-labels)
      (if (= "page" (namespace l))
        (str (str/capitalize (name l)) " page")
        (name l))))

;; ── Default keymap ───────────────────────────────────────────────────────────

(def defaults
  "Built-in keys. Shared layers apply to both clients; the :web / :tui
   sections hold one client's own. Every action id here is in `catalog`."
  {:global     {"alt+n" :chat/new
                "alt+/" :keys/show}
   :agent-busy {"alt+x" :agent/abort}
   :web {:global             {"alt+\\"      :sidebar/toggle
                              "alt+u"       :session/jump-attention
                              "alt+shift+p" :sessions/prune
                              "mod+p"       :files/find
                              "alt+b"       :buffers/switch
                              "alt+j"       :session/next
                              "alt+k"       :session/prev
                              "escape"      :dialog/close}
         :permission-pending {"alt+a"       :permission/allow
                              "alt+s"       :permission/always
                              "alt+shift+a" :permission/allow-repo
                              "alt+shift+b" :permission/allow-block
                              "alt+d"       :permission/deny}
         :mode/navigate      {"i" :compose/focus
                              "j" :scroll/down
                              "k" :scroll/up
                              "G" :timeline/bottom
                              "?" :keys/show}
         :mode/compose       {"escape" :compose/blur}
         :buffer/diff        {"q"      :buffer/close
                              "escape" :buffer/close
                              "] f"    :diff/next-file
                              "[ f"    :diff/prev-file}
         :buffer/file        {"q"      :buffer/close
                              "escape" :buffer/close}}
   :tui {:buffer/prompt {"ctrl+o" :prompt/toggle}
         :buffer/pager  {"j"        :pager/down
                         "down"     :pager/down
                         "k"        :pager/up
                         "up"       :pager/up
                         "g g"      :pager/top
                         "G"        :pager/bottom
                         "ctrl+d"   :pager/half-down
                         "ctrl+u"   :pager/half-up
                         "pagedown" :pager/page-down
                         "pageup"   :pager/page-up
                         "V"        :pager/select
                         "y"        :pager/yank
                         "e"        :pager/explain
                         "enter"    :pager/prompt
                         "escape"   :pager/close
                         "q"        :pager/close
                         ":"        :pager/command
                         "] c"      :pager/next-change
                         "[ c"      :pager/prev-change
                         "] f"      :diff/next-file
                         "[ f"      :diff/prev-file
                         "?"        :keys/show}
         :buffer/diff   {"v"   :diff/edit
                         "tab" :diff/fold}}})

(def surfaces #{:web :tui})

;; ── User config ──────────────────────────────────────────────────────────────

(defn- layer-error
  "nil when `bindings` is a usable {\"key\" :action|nil} map for `layer`, else
   the problem."
  [layer bindings]
  (cond
    (not (map? bindings))
    (str layer " must be a map of \"key\" → :action-id (or nil to unbind)")

    :else
    (some (fn [[k a]]
            (cond
              (not (key-spec? k))
              (str layer ": keys are strings like \"alt+n\" or vectors like [\"space\" \"g\"], got "
                   (pr-str k))
              (nil? (parse-key k))
              (str layer ": " (pr-str k) " is not a key — write modifiers as "
                   "ctrl/alt/shift/meta/mod, then one character or a named key "
                   "(escape, enter, tab, up, …), chords separated by spaces")
              (not (or (nil? a) (keyword? a) (and (string? a) (not (str/blank? a)))))
              (str layer ": " (pr-str k) " must map to an action keyword, a non-empty "
                   "string to send, or nil, got " (pr-str a))))
          bindings)))

(defn config-error
  "nil when `m` is a valid `:keys` config, else why not. Shape:
     {<layer> {\"key\" :action | nil} …
      :web {<layer> {…} … :defaults? bool}
      :tui {<layer> {…} … :defaults? bool}
      :defaults? bool}"
  [m]
  (cond
    (nil? m) nil

    (not (map? m))
    ":keys must be a map of layer → {\"key\" :action}"

    :else
    (some (fn [[k v]]
            (cond
              (= k :defaults?)
              (when-not (boolean? v) ":keys :defaults? must be true or false")

              (contains? surfaces k)
              (cond
                (not (map? v)) (str ":keys " k " must be a map of layer → {\"key\" :action}")
                :else (some (fn [[l b]]
                              (cond
                                (= l :defaults?)
                                (when-not (boolean? b) (str ":keys " k " :defaults? must be true or false"))
                                (contains? surfaces l)
                                (str ":keys " k " cannot nest " l)
                                (not (keyword? l))
                                (str ":keys " k ": layers are keywords like :global, got " (pr-str l))
                                :else (some->> (layer-error l b) (str ":keys " k " "))))
                            v))

              (not (keyword? k))
              (str ":keys: layers are keywords like :global or :buffer/diff, got " (pr-str k))

              :else
              (some->> (layer-error k v) (str ":keys "))))
          m)))

;; ── Merging ──────────────────────────────────────────────────────────────────

(defn normalize-layers
  "`{layer {\"key spec\" action}}` → the same with canonical keys, `mod`
   expanded for `surface`, invalid specs dropped. nil values (unbinds) are
   kept."
  [layers surface]
  (into {}
        (keep (fn [[l bindings]]
                (when (and (keyword? l) (map? bindings))
                  [l (into {}
                           (mapcat (fn [[k a]]
                                     (when-let [ck (and (key-spec? k) (parse-key k))]
                                       (for [k' (expand-mod ck surface)] [k' a]))))
                           bindings)])))
        layers))

(defn- sections
  "A keymap config → [shared-layers surface-layers] for `surface`."
  [m surface]
  [(apply dissoc m :defaults? surfaces)
   (dissoc (get m surface) :defaults?)])

(defn effective-keymap
  "The keymap one client runs: `defaults` (shared, then the surface's own
   section) ← each of `extensions` (a seq of {layer {key action}}) ← `user`
   (the config.edn `:keys` value, shared then surface section). Later layers
   win per key; a nil from the user masks everything below. `:defaults? false`
   at the user's top level or in its surface section drops the built-in keys."
  [{:keys [surface defaults extensions user]
    :or {defaults xi.keys/defaults}}]
  (let [no-defaults? (or (false? (:defaults? user))
                         (false? (get-in user [surface :defaults?])))
        [ds dsurf]   (sections defaults surface)
        [us usurf]   (sections user surface)
        parts        (concat (when-not no-defaults? [ds dsurf])
                             extensions
                             [us usurf])]
    (reduce (fn [acc m] (merge-with merge acc (normalize-layers m surface)))
            {}
            parts)))

;; ── Lookup ───────────────────────────────────────────────────────────────────

(defn lookup
  "Resolve `chord` (canonical, one keypress) after `pending` (the chords of a
   sequence in progress) against `keymap` in the ordered active `layers`.
   `enabled?` (fn [action-id] → bool) says whether an action may run right now
   (its guard); a disabled hit falls through to the next layer.
   → {:status :action :action id :layer l :key k}
     {:status :pending :pending [chords…]}   a sequence prefix matched
     {:status :unbound :masked? bool}"
  ([keymap layers pending chord] (lookup keymap layers pending chord (constantly true)))
  ([keymap layers pending chord enabled?]
   (let [full (str/join " " (conj (vec pending) chord))
         exact (loop [[l & more] layers]
                 (when l
                   (let [bindings (get keymap l)]
                     (if (contains? bindings full)
                       (let [a (get bindings full)]
                         (cond
                           (nil? a)     {:status :unbound :masked? true}
                           (enabled? a) {:status :action :action a :layer l :key full}
                           :else        (recur more)))
                       (recur more)))))]
     (or exact
         (when (some (fn [l]
                       (some (fn [[k a]] (and a (str/starts-with? k (str full " "))))
                             (get keymap l)))
                     layers)
           {:status :pending :pending (conj (vec pending) chord)})
         {:status :unbound}))))

(defn- key-rank
  "Sort key for display preference: plain characters before named keys
   (\"j\" before \"down\"), fewer chords first, then alphabetical."
  [k]
  (let [cs (chords k)
        {:keys [key]} (split-chord (first cs))]
    [(if (contains? named-keys key) 1 0) (count cs) k]))

(defn keys-for
  "The canonical keys bound to `action` in the active `layers` (inner layers
   first, preferred display form first within a layer), skipping keys an
   inner layer rebinds or masks."
  [keymap layers action]
  (let [taken (volatile! #{})]
    (vec (for [l layers
               [k a] (sort-by (comp key-rank first) (get keymap l))
               :let [seen? (contains? @taken k)
                     _ (vswap! taken conj k)]
               :when (and (not seen?) (= a action))]
           k))))

(defn shortcut
  "Display string of the first key bound to `action`, or nil."
  ([keymap layers action] (shortcut keymap layers action {}))
  ([keymap layers action opts]
   (some-> (first (keys-for keymap layers action)) (format-key opts))))

;; ── Listing ──────────────────────────────────────────────────────────────────

(defn listing
  "Rows for a shortcuts list. `keymap` is the effective one, `base` the
   defaults-only keymap (to flag what the user changed), `actions` the ids the
   surface implements → {:label}, `active` the layers active right now (listed
   first, in order). → [{:layer :label :active? :rows [{:action :keys
   :display :label :custom?}]}]: one row per action and layer with all its
   keys (\"Ctrl+P / Cmd+P\"); actions the surface does not implement are left
   out; a user's unbind of a default shows as an \"(unbound)\" row."
  [keymap base actions active]
  (let [active-set (set active)
        layers     (concat active
                           (sort-by #(get layer-rank % 999)
                                    (remove active-set (keys keymap))))]
    (vec
     (for [l layers
           :let [bindings (get keymap l)
                 bound    (->> bindings
                               (filter (fn [[_ a]] (and a (contains? actions a))))
                               (group-by val))
                 rows     (-> []
                              (into (for [[a kvs] bound
                                          :let [ks (vec (sort-by key-rank (map first kvs)))]]
                                      {:action a :keys ks
                                       :display (str/join " / " (map format-key ks))
                                       :label (label a actions)
                                       :custom? (not-every? #(= a (get-in base [l %])) ks)}))
                              (into (for [[k a] bindings
                                          :let [default (get-in base [l k])]
                                          :when (and (nil? a) default)]
                                      {:action nil :keys [k] :display (format-key k)
                                       :label (str "(unbound: was " (label default actions) ")")
                                       :custom? true}))
                              (->> (sort-by (juxt :label :display)) vec))]
           :when (seq rows)]
       {:layer l :label (layer-label l) :active? (contains? active-set l) :rows rows}))))

;; ── Extensions ───────────────────────────────────────────────────────────────

(defn ext-keybinding->action
  "An extension's `:keybindings` entry `{:key :event :when :id :label :layer}`
   → `{:id :label :event :when :layer :key}`: the action id defaults to the
   event's type, the layer to :global."
  [{:keys [key event id label layer] pred :when}]
  (let [id (or id (:type event))]
    (when (keyword? id)
      (cond-> {:id id :label (or label (name id)) :layer (or layer :global)}
        event (assoc :event event)
        pred  (assoc :when pred)
        key   (assoc :key key)))))

(defn ext-keymap
  "The default-keys layer map of a seq of extension actions
   (`ext-keybinding->action`): {layer {key id}} for the ones with a :key."
  [actions]
  (reduce (fn [m {:keys [layer key id]}]
            (if key (assoc-in m [layer key] id) m))
          {}
          actions))
