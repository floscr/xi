(ns xi.tui.tree-selector
  "Tree selector component for navigating session history.
   Renders an interactive tree view of conversation entries."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]))

;; ── Key Detection ─────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))

(defn- is-escape? [data]
  (or (= data ESC)
      (= data (str ESC "[27u"))))

(defn- is-enter? [data]
  (or (= data "\r") (= data "\n")))

(defn- is-ctrl-enter? [data]
  ;; Kitty keyboard protocol: CSI 13;5u
  (= data (str ESC "[13;5u")))

(defn- is-arrow-up? [data]
  (= data (str ESC "[A")))

(defn- is-arrow-down? [data]
  (= data (str ESC "[B")))

(defn- is-backspace? [data]
  (or (= data "\u007f") (= data "\b")))

(defn- is-printable? [data]
  (let [code (.charCodeAt data 0)]
    (and (= (count data) 1)
         (>= code 32)
         (not= code 127))))

(defn- ctrl? [data ch]
  (and (= (count data) 1)
       (= (.charCodeAt data 0) (- (.charCodeAt ch 0) 64))))

;; ── Tree Flattening ───────────────────────────────────────────────────────────

(defn- flatten-tree
  "Flatten tree nodes into a display list.
   Returns [{:node node :indent n :is-last bool :has-siblings bool} ...]"
  [tree-nodes leaf-id]
  ;; Build set of IDs on active path (leaf → root)
  (let [active-ids (atom #{})
        by-id (atom {})]
    ;; Index all entries
    (letfn [(index-node [node]
              (swap! by-id assoc (get-in node [:entry :id]) node)
              (doseq [child (:children node)]
                (index-node child)))]
      (doseq [root tree-nodes]
        (index-node root)))
    ;; Walk from leaf to root to build active path
    (loop [id leaf-id]
      (when id
        (swap! active-ids conj id)
        (when-let [node (get @by-id id)]
          (recur (get-in node [:entry :parentId])))))
    ;; Flatten with DFS
    (let [result (atom [])
          ;; Check which subtrees contain the active leaf (for ordering)
          contains-active (atom {})]
      (letfn [(mark-active [node]
                (let [id (get-in node [:entry :id])
                      children (:children node)
                      has-active (or (= id leaf-id)
                                     (some #(mark-active %) children))]
                  (swap! contains-active assoc id has-active)
                  has-active))
              (flatten-node [node indent gutters]
                (let [children (:children node)
                      ;; Sort: active branch first, then by timestamp
                      sorted-children
                      (sort-by (fn [c]
                                 [(if (get @contains-active (get-in c [:entry :id])) 0 1)
                                  (get-in c [:entry :timestamp] "")])
                               children)
                      has-branch (> (count children) 1)]
                  (swap! result conj
                         {:node node
                          :indent indent
                          :has-branch has-branch
                          :on-active-path (contains? @active-ids (get-in node [:entry :id]))})
                  (doseq [[i child] (map-indexed vector sorted-children)]
                    (let [is-last (= i (dec (count sorted-children)))
                          child-indent (if has-branch (inc indent) indent)
                          child-gutters (if has-branch
                                          (conj gutters {:position indent :show (not is-last)})
                                          gutters)]
                      (flatten-node child child-indent child-gutters)))))]
        (doseq [root tree-nodes]
          (mark-active root))
        (doseq [root tree-nodes]
          (flatten-node root 0 [])))
      @result)))

;; ── Filter ────────────────────────────────────────────────────────────────────

(def ^:private filter-modes ["User" "User & Agent" "All"])

(defn- passes-filter? [entry mode]
  (let [t (:type entry)]
    (case mode
      "User"           (= t "user-message")
      "User & Agent"   (#{ "user-message" "assistant-text"} t)
      "All"            true
      (= t "user-message"))))

;; ── Display Text ──────────────────────────────────────────────────────────────

(defn- entry-display-text
  "Format an entry for display."
  [entry is-selected]
  (let [normalize (fn [s] (-> (str s) (str/replace #"[\n\t]+" " ") str/trim))
        t (:type entry)
        text (case t
               "user-message"
               (str (ansi/fg :accent "you: ") (normalize (:text entry)))

               "assistant-text"
               (str (ansi/fg :green "assistant: ") (normalize (:text entry)))

               "tool-use"
               (let [name (:name entry)
                     args (:arguments entry)
                     summary (case name
                               "bash" (str (get args "command" (get args :command "")))
                               "read" (str (get args "path" (get args :path "")))
                               "write" (str (get args "path" (get args :path "")))
                               "edit" (str (get args "path" (get args :path "")))
                               (str (js/JSON.stringify (clj->js args))))]
                 (str (ansi/fg :dim (str "[" name ": " (subs summary 0 (min 60 (count summary)))
                                         (when (> (count summary) 60) "...") "]"))))

               "tool-result"
               (let [content (normalize (:content entry))]
                 (str (ansi/fg :dim (str "[result: " (subs content 0 (min 60 (count content)))
                                         (when (> (count content) 60) "...") "]"))))

               "turn-end"
               (ansi/fg :dim "[turn end]")

               "model-change"
               (ansi/fg :dim (str "[model: " (:model entry) "]"))

               "compaction"
               (ansi/fg :dim "[compaction]")

               (ansi/fg :dim (str "[" t "]")))]
    (if is-selected (ansi/fg :bold text) text)))

;; ── Component ─────────────────────────────────────────────────────────────────

(defn make-tree-selector
  "Create a tree selector component.

   opts:
     :tree-nodes  — result of session.tree/get-tree
     :leaf-id     — current leaf entry id
     :on-select   — (fn [entry-id]) called when user confirms
     :on-cancel   — (fn []) called when user presses ESC
     :max-visible — max visible items (default: half terminal height)"
  [opts]
  (let [tree-nodes (:tree-nodes opts)
        leaf-id (:leaf-id opts)
        on-select (:on-select opts)
        on-cancel (:on-cancel opts)
        max-visible (or (:max-visible opts) 15)

        flat-nodes (flatten-tree tree-nodes leaf-id)

        state (atom {:selected 0
                     :filter-mode "User"
                     :search-query ""
                     :filtered []})

        apply-filter!
        (fn []
          (let [{:keys [filter-mode search-query]} @state
                search-tokens (when (seq search-query)
                                (str/split (str/lower-case search-query) #"\s+"))
                filtered
                (->> flat-nodes
                     (filter (fn [{:keys [node]}]
                               (passes-filter? (:entry node) filter-mode)))
                     (filter (fn [{:keys [node]}]
                               (if (seq search-tokens)
                                 (let [text (str/lower-case
                                             (str (:type (:entry node)) " "
                                                  (:text (:entry node)) " "
                                                  (:name (:entry node))))]
                                   (every? #(str/includes? text %) search-tokens))
                                 true)))
                     vec)]
            (swap! state assoc
                   :filtered filtered
                   :selected (min (:selected @state)
                                  (max 0 (dec (count filtered)))))))

        ;; Initialize with default leaf selected
        _ (do (apply-filter!)
              ;; Find the leaf in filtered nodes
              (let [idx (->> (:filtered @state)
                             (keep-indexed (fn [i {:keys [node]}]
                                            (when (= leaf-id (get-in node [:entry :id]))
                                              i)))
                             first)]
                (when idx
                  (swap! state assoc :selected idx))))

        move-selection
        (fn [delta]
          (let [{:keys [filtered selected]} @state
                n (count filtered)]
            (when (pos? n)
              (swap! state assoc :selected (mod (+ selected delta n) n))))
          (tui/request-render!))

        cycle-filter!
        (fn [direction]
          (let [idx (.indexOf filter-modes (:filter-mode @state))
                next-idx (mod (+ idx direction (count filter-modes))
                              (count filter-modes))]
            (swap! state assoc :filter-mode (nth filter-modes next-idx))
            (apply-filter!)
            (tui/request-render!)))

        handle-input
        (fn [data]
          (cond
            (is-escape? data)
            (if (seq (:search-query @state))
              (do (swap! state assoc :search-query "")
                  (apply-filter!)
                  (tui/request-render!))
              (when on-cancel (on-cancel)))

            (or (is-enter? data) (is-ctrl-enter? data))
            (let [{:keys [filtered selected]} @state
                  mode (if (is-ctrl-enter? data) :edit :navigate)]
              (when (and (seq filtered) on-select)
                (on-select (get-in (nth filtered selected)
                                   [:node :entry :id])
                           mode)))

            (is-arrow-up? data)   (move-selection -1)
            (is-arrow-down? data) (move-selection 1)

            ;; Tab cycles filter forward
            (= data "\t")
            (cycle-filter! 1)

            ;; Backspace in search
            (is-backspace? data)
            (when (seq (:search-query @state))
              (swap! state update :search-query #(subs % 0 (dec (count %))))
              (apply-filter!)
              (tui/request-render!))

            ;; Ctrl+U clears search
            (ctrl? data "U")
            (do (swap! state assoc :search-query "")
                (apply-filter!)
                (tui/request-render!))

            ;; Printable chars → search
            (is-printable? data)
            (do (swap! state update :search-query str data)
                (apply-filter!)
                (tui/request-render!))

            ;; Multi-byte printable (unicode)
            (and (> (count data) 1)
                 (not (str/starts-with? data ESC)))
            (do (swap! state update :search-query str data)
                (apply-filter!)
                (tui/request-render!))

            :else nil))]

    {:type :tree-selector
     :handle-input handle-input
     :invalidate (fn [])
     :render
     (fn [width]
       (let [{:keys [selected filtered filter-mode search-query]} @state
             border (ansi/fg :border (apply str (repeat width "─")))
             n (count filtered)

             ;; Scroll window
             visible-count (min max-visible n)
             scroll-start (cond
                            (<= n max-visible) 0
                            (< selected (quot max-visible 2)) 0
                            (> selected (- n (quot max-visible 2))) (- n max-visible)
                            :else (- selected (quot max-visible 2)))
             scroll-end (+ scroll-start visible-count)

             ;; Render items
             item-lines
             (into []
                   (map
                    (fn [idx]
                      (let [{:keys [node indent on-active-path has-branch]} (nth filtered idx)
                            entry (:entry node)
                            is-selected (= idx selected)
                            cursor (if is-selected (ansi/fg :accent "› ") "  ")
                            ;; Build indent with connectors
                            indent-str (apply str (repeat (* indent 2) " "))
                            path-marker (if on-active-path
                                          (ansi/fg :accent "• ")
                                          "  ")
                            content (entry-display-text entry is-selected)
                            line (str cursor indent-str path-marker content)]
                        (ansi/truncate-to-width line width)))
                    (range scroll-start scroll-end)))

             ;; Empty state
             item-lines (if (empty? filtered)
                          [(str "  " (ansi/fg :dim "(no entries)"))]
                          item-lines)

             ;; Header
             title (str (ansi/fg :bold "  Session Tree")
                        (ansi/fg :dim (str " [" filter-mode "]")))

             ;; Search line
             search-line (if (seq search-query)
                           (str "  " (ansi/fg :dim "search: ") (ansi/fg :accent search-query))
                           (str "  " (ansi/fg :dim "type to search")))

             ;; Help
             help (ansi/fg :dim "  ↑/↓: move  Enter: go  C-Enter: edit  Tab: filter  Esc: close")

             count-line (ansi/fg :dim (str "  (" (inc selected) "/" n ")"))]

         (into []
               (concat
                [border title help search-line border]
                [""]
                item-lines
                [""]
                [count-line border]))))}))
