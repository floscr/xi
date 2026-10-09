(ns xi.web.views-test
  (:require [cljs.test :refer [deftest is testing]]
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
