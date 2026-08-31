(ns xi.ext.diff.core-test
  (:require [cljs.test :refer [deftest is]]
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
                                    :text "diff --git a/x b/x"})]
    (is (= {:title "Session Changes" :text "diff --git a/x b/x" :engine :git :diff? true :commit nil}
           (get-in state [:rooms "r" :ui :buffers :diff])))
    (is (= :diff (get-in state [:rooms "r" :ui :active-buffer])))))
