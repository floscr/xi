(ns xi.web.views-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.block-menu :as block-menu]
            [xi.web.timeline-posts :as timeline-posts]
            [xi.web.views :as views]))

;; ── timeline post memo (typing lag on long chats) ─────────────────────────────

(defn- posts
  "Timeline post nodes of a chat-view render, by :replicant/key \"h-N\"."
  [hiccup]
  (into {}
        (keep (fn [x]
                (when-let [k (and (vector? x) (map? (second x))
                                  (:replicant/key (second x)))]
                  (when (and (string? k) (re-find #"^h-\d+$" k)) [k x]))))
        (tree-seq #(or (vector? %) (seq? %)) seq hiccup)))

(deftest timeline-posts-memoized
  (let [chat-view @#'views/chat-view
        history   [{:kind :user :text "hi"}
                   {:kind :text :text "**hello**"}
                   {:kind :tool-call :id "t1" :tool "Read" :status :done
                    :arguments {:file_path "a.txt"} :result "x"}]
        state     {:web/route {:session-id "s"}
                   :web/cache {"s" {:history history}}}
        dispatch! (fn [_])
        a         (posts (chat-view state dispatch!))
        b         (posts (chat-view (assoc-in state [:web/drafts "s"] "typing") dispatch!))]
    (is (= 3 (count a)))
    (testing "unrelated state (the composer draft) reuses every post as is"
      (is (every? (fn [[k node]] (identical? node (get b k))) a)))
    (testing "a changed entry rebuilds only its own post"
      (let [c (posts (chat-view (assoc-in state [:web/cache "s" :history 1]
                                          {:kind :text :text "bye"})
                                dispatch!))]
        (is (identical? (get a "h-0") (get c "h-0")))
        (is (not (identical? (get a "h-1") (get c "h-1"))))))))

(deftest a-timeline-post-can-stand-in-for-its-tool-call
  (let [chat-view @#'views/chat-view
        history   [{:kind :user :text "show it"}
                   {:kind :tool-call :id "t1" :tool "ui_block" :status :done :result "Showed"}]
        state     {:web/route {:session-id "s"}
                   :active-room "r1"
                   :rooms {"r1" {:id "r1" :session {:id "s"} :history history}}}
        render    #(posts (chat-view % (fn [_])))]
    (timeline-posts/register! ::block (fn [_ _ _ _] [{:pos 2 :key "b" :node [:div] :hides 1}]))
    (try
      (is (= #{"h-0"} (set (keys (render state)))) "the tool call's row is left out")
      (testing "a tool call holding a permission answer stays"
        (is (contains? (render (assoc state :web/resolved-dialogs
                                      {"r1" [{:key "d1" :anchor 1 :tool-id "t1" :value true}]}))
                       "h-1")))
      (finally (timeline-posts/register! ::block (constantly nil))))))

;; ── diff-visible-rows (size limit of the diff view) ─────────────────────────

(defn- group
  "A file group: the :file row plus `n` body rows."
  [filename n]
  (into [{:row :file :filename filename}] (repeat n {:row :line})))

(deftest diff-visible-rows
  (let [f @#'views/diff-visible-rows]
    (testing "files that fit the budget render in full"
      (is (= [nil nil] (f [(group "a" 10) (group "b" 20)] #{} #{} 100))))
    (testing "files draw from one budget in order; later files start closed"
      (is (= [nil 20 0] (f [(group "a" 10) (group "b" 50) (group "c" 5)] #{} #{} 30))))
    (testing "expanded and collapsed files are unlimited and spend nothing"
      (is (= [nil nil nil]
             (f [(group "a" 500) (group "b" 500) (group "c" 10)] #{"b"} #{"a"} 10))))))

;; ── command-while-busy? ──────────────────────────────────────────────────────

(deftest command-while-busy
  (testing "non-interrupting read-only commands"
    (is (true? (views/command-while-busy? "/diff")))
    (is (true? (views/command-while-busy? "/help")))
    (is (true? (views/command-while-busy? "/debug"))))
  (testing "subcommands inherit the parent's flag"
    (is (true? (views/command-while-busy? "/diff staged")))
    (is (true? (views/command-while-busy? "  /diff session-commits  "))))
  (testing "session-mutating commands are not while-busy"
    (is (false? (boolean (views/command-while-busy? "/new"))))
    (is (false? (boolean (views/command-while-busy? "/clear"))))
    (is (false? (boolean (views/command-while-busy? "/truncate")))))
  (testing "plain prompts and unknown commands"
    (is (false? (boolean (views/command-while-busy? "hello world"))))
    (is (false? (boolean (views/command-while-busy? "/bogus"))))
    (is (false? (boolean (views/command-while-busy? nil))))))

;; ── format-elapsed (run timer / sub-agent duration) ─────────────────────────

(deftest nav-badge-reads-a-positive-number-at-the-path
  (let [st {:user-ext/state {:chat {:unread 3 :zero 0 :text "x"}}}]
    (is (= "3" (views/nav-badge st {:badge-path [:user-ext/state :chat :unread]})))
    (is (nil? (views/nav-badge st {:badge-path [:user-ext/state :chat :zero]})))
    (is (nil? (views/nav-badge st {:badge-path [:user-ext/state :chat :text]})))
    (is (nil? (views/nav-badge st {:badge-path [:user-ext/state :chat :missing]})))
    (is (nil? (views/nav-badge st {:label "no badge"})))))

(deftest format-elapsed-test
  (is (= "0s" (views/format-elapsed 0)))
  (is (= "59s" (views/format-elapsed 59)))
  (is (= "1m 00s" (views/format-elapsed 60)))
  (is (= "4m 05s" (views/format-elapsed 245)))
  (is (= "12m 30s" (views/format-elapsed 750))))

;; ── code-focus-segments (permission ask → muted / focused code) ───────────

(deftest code-focus-segments-splits-around-ranges
  (let [text "(def a 1)\n(spit p 1)\n(cp a b)"]
    (testing "text outside the ranges is muted, inside is not"
      (is (= [[true "(def a 1)\n"] [false "(spit p 1)"] [true "\n(cp a b)"]]
             (views/code-focus-segments text [[10 20]]))))
    (testing "several ranges, given in any order"
      (is (= [[true "(def a 1)\n"] [false "(spit p 1)"] [true "\n"] [false "(cp a b)"]]
             (views/code-focus-segments text [[21 29] [10 20]]))))
    (testing "ranges past the (truncated) text are clamped"
      (is (= [[true "(def a 1)\n"] [false "(spit p 1)\n(cp a b)"]]
             (views/code-focus-segments text [[10 999]])))
      (is (= [[true text]] (views/code-focus-segments text [[500 600]]))))
    (testing "a range covering everything mutes nothing"
      (is (= [[false text]] (views/code-focus-segments text [[0 (count text)]]))))))

(deftest code-block-segments-mark-the-whole-block
  (let [text "(def a 1)\n(spit p 1)\n(cp a b)"]
    (testing "the ask's own call is focused; every block call is marked"
      (is (= [[true false "(def a 1)\n"] [false true "(spit p 1)"]
              [true false "\n"] [true true "(cp a b)"]]
             (views/code-block-segments text [[10 20]] [[10 20] [21 29]]))))
    (testing "a block range splits a muted piece in the middle"
      (is (= [[true false "(def "] [true true "a"] [true false " 1)\n"]
              [false false "(spit p 1)"] [true false "\n(cp a b)"]]
             (views/code-block-segments text [[10 20]] [[5 6]]))))
    (testing "no ask ranges → nothing muted"
      (is (= [[false false "(def a 1)\n"] [false true "(spit p 1)"]
              [false false "\n(cp a b)"]]
             (views/code-block-segments text nil [[10 20]]))))))

;; ── error-retry-target (Retry on an error card) ─────────────────────────────

(deftest error-retry-target-finds-the-unanswered-user-message
  (let [target @#'views/error-retry-target
        err    {:kind :error :error {:message "requires usage credits"}}]
    (testing "an error right after a user message retries it"
      (is (= {:index 1 :text "/commit" :images nil}
             (target [{:kind :text :text "a"} {:kind :user :text "/commit"} err] 2))))
    (testing "earlier errors in a row are skipped"
      (is (= 0 (:index (target [{:kind :user :text "hi"} err err] 2)))))
    (testing "no retry after assistant output, or for non-error entries"
      (is (nil? (target [{:kind :user :text "hi"} {:kind :text :text "ok"} err] 2)))
      (is (nil? (target [{:kind :user :text "hi"} {:kind :text :text "ok"}] 1)))
      (is (nil? (target [err] 0))))))

;; ── file-header menu ────────────────────────────────────────────────────────

(deftest file-menu-items-offer-extension-entries
  (let [items     @#'views/file-menu-items
        registry  @#'block-menu/registry
        sent      (atom [])
        dispatch! #(swap! sent conj %)
        labels    #(mapv :label %)]
    (block-menu/register! :test/vim
                          (fn [{:keys [path text]} _]
                            (when path
                              [{:label "Open in vim" :event {:type :vim/open :path path}}])))
    (try
      (testing "View file, Copy path, then the extensions' entries for {:path}"
        (let [menu (items dispatch! {} "a.clj" {})]
          (is (= ["View file" "Copy path" "Open in vim"] (labels menu)))
          ((:on-click (last menu)))
          (is (= [{:type :vim/open :path "a.clj"}] @sent))))
      (testing "the file's own viewer drops View file"
        (is (= ["Copy path" "Open in vim"]
               (labels (items dispatch! {} "a.clj" {:view? false})))))
      (testing "the caller's extra entries sit between the core and extension ones"
        (is (= ["View file" "Copy path" "View diff" "Open in vim"]
               (labels (items dispatch! {} "a.clj"
                              {:extra [{:label "View diff" :icon :code :on-click identity}]})))))
      (testing "a file gone from disk only offers Copy path"
        (is (= ["Copy path"] (labels (items dispatch! {} "a.clj" {:gone? true})))))
      (finally (swap! registry dissoc :test/vim)))))

;; ── slash-command suggestions ────────────────────────────────────────────────

(deftest command-usage-name
  (is (= "yolo sane" (views/command-usage-name "yolo" "sane")))
  (is (= "yolo" (views/command-usage-name "yolo" nil)))
  (is (= "yolo" (views/command-usage-name "yolo" "nonsense"))))

(deftest match-commands-ordering
  (let [names   (fn [q usage counts]
                  (mapv :name (views/match-commands q {:usage usage :counts counts})))
        base    (fn [n] (first (.split n " ")))
        default (names "" [] {})
        [a b]   [(base (last default)) (base (nth default (quot (count default) 2)))]]
    (testing "without use, declaration order"
      (is (= default (names "" nil nil))))
    (testing "families by their latest use"
      (let [ranked (names "" [a b] {})]
        (is (= [a b] (distinct (map base (take-while #(#{a b} (base %)) ranked)))))
        (is (= (set default) (set ranked)))))
    (testing "run counts beat recency"
      (is (= b (base (first (names "" [a b] {a 1 b 3}))))))
    (testing "within the exactly typed family, the most run entry first"
      (is (= ["yolo sane" "yolo"]
             (take 2 (names "yolo" ["yolo" "yolo sane"] {"yolo" 2 "yolo sane" 9})))))
    (testing "the exactly typed family beats a more used one"
      (is (= b (first (names b [a] {a 50})))))))

(deftest match-commands-skills
  (let [skills [{:name "design-directions" :description "Generate directions"}]
        match  (fn [q opts] (views/match-commands q (assoc opts :skills skills)))]
    (testing "recent skills are offered, marked, after unused commands"
      (let [[e] (match "design" {})]
        (is (= {:name "design-directions" :skill "design-directions"
                :description "Skill · Generate directions"}
               e)))
      (is (= "design-directions" (:name (last (match "" {}))))))
    (testing "ranked by their skill:<name> use like commands"
      (is (= "design-directions"
             (:name (first (match "" {:counts {"skill:design-directions" 3}}))))))))

(deftest slash-ctx-skills
  (let [state {:web/recent-skills ["a" "gone"]}]
    (testing "before the skill list arrives: recent names only"
      (is (= [{:name "a"} {:name "gone"}] (:skills (views/slash-ctx state)))))
    (testing "with it: described, deleted ones dropped, every skill runnable"
      (let [ctx (views/slash-ctx (assoc state :web/skill-list
                                        [{:name "a" :description "A"} {:name "b"}]))]
        (is (= [{:name "a" :description "A"}] (:skills ctx)))
        (is (= #{"a" "b" "gone"} (:skill-names ctx)))))))
