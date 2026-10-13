(ns xi.ext.canvas-review.web-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.canvas-review.web :as web]))

(def ^:private diff-text
  (str "diff --git a/a.clj b/a.clj\n--- a/a.clj\n+++ b/a.clj\n@@ -1,2 +1,3 @@\n"
       " (ns a)\n+(def x 1)\n (def y 2)\n"))

(def ^:private room
  {:ext {:canvas-review {:diff  diff-text
                         :nodes {"b" {:id "b" :kind :code :file "a.clj"
                                      :start 1 :end 3 :x 40 :y 40 :w 540}}
                         :edges {}
                         :plan  [{:node "b"}]}}})

(deftest node-diff-rows-are-stable
  (let [rows (#'web/node-diff-rows diff-text "a.clj" 1 3)]
    (is (= 4 (count rows)))
    (is (identical? rows (#'web/node-diff-rows diff-text "a.clj" 1 3)))))

(deftest canvas-view-skips-unrelated-renders
  (let [dispatch! (fn [_])
        view      (fn [st] (#'web/canvas-view st dispatch! room :canvas {}))
        first-h   (view {})]
    (testing "app state the canvas doesn't read hands back the identical hiccup"
      (is (identical? first-h (view {:web/palette-open? true}))))
    (testing "its own state re-renders"
      (is (not (identical? first-h (view {:web/cr {:cursor 0 :composing? true}})))))))
