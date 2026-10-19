(ns xi.web.diff-state-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.diff-state :as ds]))

(defn- viewing [st buffer-id]
  (-> st
      (assoc :active-room "r")
      (assoc-in [:rooms "r"] {:id "r" :ui {:active-buffer buffer-id}})))

(deftest each-buffer-keeps-its-own-state
  (let [st (-> {}
               (viewing "diff:git")
               (ds/assoc-view :sel {:anchor 3 :head 5} :intent :modify :draft "rename x"
                              :collapsed #{"a.clj"})
               (viewing "diff:staged"))]
    (testing "another buffer starts clean"
      (is (= ["r" "diff:staged"] (ds/view-key st)))
      (is (nil? (ds/sel st)))
      (is (= #{} (ds/collapsed st))))
    (testing "switching back finds it as left"
      (let [st (viewing (ds/assoc-view st :sel {:anchor 0 :head 0}) "diff:git")]
        (is (= {:anchor 3 :head 5} (ds/sel st)))
        (is (= :modify (ds/intent st)))
        (is (= "rename x" (ds/draft st)))
        (is (= #{"a.clj"} (ds/collapsed st)))))))

(deftest clearing-selections
  (let [st (-> {}
               (viewing "diff:git")
               (ds/assoc-view :sel {:anchor 1 :head 1} :intent :ask :draft "q" :collapsed #{"f"})
               (viewing "diff:staged")
               (ds/assoc-view :sel {:anchor 2 :head 2}))]
    (is (nil? (ds/sel (ds/clear-selection st))) "the view in front")
    (is (= {:anchor 1 :head 1} (ds/sel (viewing (ds/clear-selection st) "diff:git")))
        "other views keep theirs")
    (let [st (viewing (ds/clear-all-selections st) "diff:git")]
      (is (nil? (ds/sel st)))
      (is (nil? (ds/draft st)))
      (is (= #{"f"} (ds/collapsed st)) "folded files survive a layout switch"))))
