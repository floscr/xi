(ns xi.tui.history-selector
  "Interactive selector for navigating session history.
   Shows user messages, assistant text, and tool calls from room history.
   Enter navigates to that point; Ctrl-Enter edits the user message."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]))

;; ── Key Detection ─────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))

(defn- escape? [d]   (or (= d ESC) (= d (str ESC "[27u"))))
(defn- enter? [d]    (or (= d "\r") (= d "\n")))
(defn- ctrl-enter? [d] (= d (str ESC "[13;5u")))
(defn- arrow-up? [d]   (= d (str ESC "[A")))
(defn- arrow-down? [d] (= d (str ESC "[B")))
(defn- backspace? [d]  (or (= d "\u007f") (= d "\b")))
(defn- printable? [d]
  (and (= (count d) 1)
       (let [c (.charCodeAt d 0)] (and (>= c 32) (not= c 127)))))
(defn- ctrl-u? [d]
  (and (= (count d) 1) (= (.charCodeAt d 0) 21)))
(defn- ctrl-n? [d] (and (= (count d) 1) (= (.charCodeAt d 0) 14)))
(defn- ctrl-p? [d] (and (= (count d) 1) (= (.charCodeAt d 0) 16)))

;; ── History → items ───────────────────────────────────────────────────────────

(defn- entry-text
  "Short display text for a history entry."
  [{:keys [kind text tool arguments]}]
  (let [trunc (fn [s n] (let [s (str/replace (str s) #"[\n\t]+" " ")]
                           (if (> (count s) n) (str (subs s 0 n) "…") s)))]
    (case kind
      :user      (str (ansi/fg :accent "you: ") (trunc text 80))
      :text      (str (ansi/fg :green "assistant: ") (trunc text 80))
      :tool-call (str (ansi/fg :dim (str "[" tool ": "
                                         (trunc (pr-str arguments) 60) "]")))
      :error     (ansi/fg :error "error")
      :aborted   (ansi/fg :dim "[aborted]")
      :status    (ansi/fg :dim (trunc text 80))
      (ansi/fg :dim (str "[" (name kind) "]")))))

(def ^:private filter-kinds
  [["User"          #{:user}]
   ["User & Agent"  #{:user :text}]
   ["All"           #{:user :text :tool-call :error :aborted :status}]])

;; ── Component ─────────────────────────────────────────────────────────────────

(defn make-history-selector
  "opts:
     :history     — room history vector
     :on-select   — (fn [index mode]) where mode is :navigate or :edit
     :on-cancel   — (fn [])
     :max-visible — max rows (default 15)"
  [{:keys [history on-select on-cancel max-visible]}]
  (let [max-visible (or max-visible 15)
        ;; Pre-index: [{:idx i :entry e}] for every history entry
        all-items (vec (map-indexed (fn [i e] {:idx i :entry e}) history))

        state (atom {:selected 0
                     :filter-idx 0
                     :search ""
                     :filtered []})

        apply-filter!
        (fn []
          (let [{:keys [filter-idx search]} @state
                [_ kinds] (nth filter-kinds filter-idx)
                tokens (when (seq search)
                         (str/split (str/lower-case search) #"\s+"))
                filtered (->> all-items
                              (filter #(contains? kinds (:kind (:entry %))))
                              (filter (fn [{:keys [entry]}]
                                        (if tokens
                                          (let [s (str/lower-case
                                                   (str (:text entry) " "
                                                        (:tool entry) " "
                                                        (pr-str (:arguments entry))))]
                                            (every? #(str/includes? s %) tokens))
                                          true)))
                              vec)]
            (swap! state assoc
                   :filtered filtered
                   :selected (min (:selected @state)
                                  (max 0 (dec (count filtered)))))))

        _ (do (apply-filter!)
              ;; Start at the last item (most recent)
              (swap! state assoc :selected (max 0 (dec (count (:filtered @state))))))

        move! (fn [delta]
                (let [{:keys [filtered selected]} @state
                      n (count filtered)]
                  (when (pos? n)
                    (swap! state assoc :selected (mod (+ selected delta n) n))))
                (tui/request-panel-render!))

        cycle-filter! (fn [dir]
                        (swap! state update :filter-idx
                               #(mod (+ % dir (count filter-kinds))
                                     (count filter-kinds)))
                        (apply-filter!)
                        (tui/request-panel-render!))]

    {:type :history-selector
     :handle-input
     (fn [data]
       (cond
         (escape? data)
         (if (seq (:search @state))
           (do (swap! state assoc :search "")
               (apply-filter!) (tui/request-panel-render!))
           (when on-cancel (on-cancel)))

         (or (enter? data) (ctrl-enter? data))
         (let [{:keys [filtered selected]} @state
               mode (if (ctrl-enter? data) :edit :navigate)]
           (when (and (seq filtered) on-select)
             (on-select (:idx (nth filtered selected)) mode)))

         (or (arrow-up? data) (ctrl-p? data))   (move! -1)
         (or (arrow-down? data) (ctrl-n? data)) (move! 1)
         (= data "\t")      (cycle-filter! 1)

         (backspace? data)
         (when (seq (:search @state))
           (swap! state update :search #(subs % 0 (dec (count %))))
           (apply-filter!) (tui/request-panel-render!))

         (ctrl-u? data)
         (do (swap! state assoc :search "")
             (apply-filter!) (tui/request-panel-render!))

         (or (printable? data)
             (and (> (count data) 1)
                  (not (str/starts-with? data ESC))))
         (do (swap! state update :search str data)
             (apply-filter!) (tui/request-panel-render!))

         :else nil))

     :invalidate (fn [])

     :render
     (fn [width]
       (let [{:keys [selected filtered filter-idx search]} @state
             [filter-label _] (nth filter-kinds filter-idx)
             n (count filtered)
             vis (min max-visible n)
             scroll-start (cond
                            (<= n max-visible) 0
                            (< selected (quot max-visible 2)) 0
                            (> selected (- n (quot max-visible 2))) (- n max-visible)
                            :else (- selected (quot max-visible 2)))
             scroll-end (+ scroll-start vis)

             border (ansi/fg :border (apply str (repeat width "─")))

             item-lines
             (if (empty? filtered)
               [(str "  " (ansi/fg :dim "(no entries)"))]
               (mapv (fn [i]
                       (let [{:keys [entry]} (nth filtered i)
                             sel? (= i selected)
                             cursor (if sel? (ansi/fg :accent "› ") "  ")
                             line (str cursor (entry-text entry))]
                         (ansi/truncate-to-width line width)))
                     (range scroll-start scroll-end)))

             title (str (ansi/fg :bold "  Session History")
                        (ansi/fg :dim (str " [" filter-label "]")))
             search-line (if (seq search)
                           (str "  " (ansi/fg :dim "search: ") (ansi/fg :accent search))
                           (str "  " (ansi/fg :dim "type to search")))
             help (ansi/fg :dim "  ↑/↓: move  Enter: go  C-Enter: edit  Tab: filter  Esc: close")
             count-line (ansi/fg :dim (str "  (" (inc selected) "/" n ")"))]

         (into []
               (concat [border title help search-line border ""]
                       item-lines
                       ["" count-line border]))))}))
