(ns xi.ext.diff.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.diff.core :as diff]))

(def ^:private diff-cmd
  (:handler (first (:commands diff/extension))))

(def ^:private diff-open
  (:ui/diff-open (:handlers diff/extension)))

(defn- room []
  (-> (state/initial-state)
      (assoc-in [:rooms "r"] (state/make-room "r" {:cwd "/tmp"}))))

(deftest diff-command-emits-diff-load
  (is (= [[:diff/load {:room-id "r" :args nil :engine :git}]]
         (:effects (diff-cmd (room) {:room-id "r"}))))
  (is (= [[:diff/load {:room-id "r" :args "staged" :engine :git}]]
         (:effects (diff-cmd (room) {:room-id "r" :args "staged"}))))
  (is (= [[:diff/load {:room-id "r" :args "staged" :engine :difft}]]
         (:effects (diff-cmd (room) {:room-id "r" :args "difft staged"}))))
  (is (= [[:diff/load {:room-id "r" :args "staged" :engine :difft :cols 120}]]
         (:effects (diff-cmd (room) {:room-id "r" :args "difft:120 staged"})))))

(deftest diff-open-installs-buffer
  (let [{:keys [state]} (diff-open (room)
                                   {:room-id "r" :title "Session Changes"
                                    :text "diff --git a/x b/x" :event/ts 5})]
    (is (= {:kind :diff :title "Session Changes" :text "diff --git a/x b/x"
            :engine :git :diff? true :commit nil :source nil :opened-at 5}
           (get-in state [:rooms "r" :ui :buffers "diff:session-edits"])))
    (is (= "diff:session-edits" (get-in state [:rooms "r" :ui :active-buffer])))))

(deftest diff-buffers-are-per-source
  (let [st  (:state (diff-open (room) {:room-id "r" :title "Session" :text "a" :event/ts 1}))
        st  (:state (diff-open st {:room-id "r" :title "Staged" :text "b" :source "staged" :event/ts 2}))
        st' (:state (diff-open st {:room-id "r" :title "Staged" :text "b2" :source "staged"
                                   :engine :difft :event/ts 3}))]
    (is (= #{"diff:session-edits" "diff:staged"} (set (keys (get-in st [:rooms "r" :ui :buffers]))))
        "two sources, two buffers")
    (is (= #{"diff:session-edits" "diff:staged"} (set (keys (get-in st' [:rooms "r" :ui :buffers]))))
        "re-rendering a source replaces its buffer")
    (is (= [:difft 2] ((juxt :engine :opened-at) (get-in st' [:rooms "r" :ui :buffers "diff:staged"])))
        "…keeping its place in the order")))

(deftest diff-open-switches-only-the-originator
  (let [own (assoc-in (room) [:connection :client-id] "me")]
    (testing "meant for another client: installed, not shown"
      (let [{:keys [state]} (diff-open own {:room-id "r" :title "t" :text "x" :client-id "other"})]
        (is (contains? (get-in state [:rooms "r" :ui :buffers]) "diff:session-edits"))
        (is (= :chat (get-in state [:rooms "r" :ui :active-buffer])))))
    (testing "meant for us"
      (let [{:keys [state]} (diff-open own {:room-id "r" :title "t" :text "x" :client-id "me"})]
        (is (= "diff:session-edits" (get-in state [:rooms "r" :ui :active-buffer])))))))
