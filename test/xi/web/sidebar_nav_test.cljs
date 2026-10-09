(ns xi.web.sidebar-nav-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.sidebar-nav :as nav]))

(deftest route-keys-test
  (testing "a chat: its buffer row first, then its session row"
    (is (= ["s:a"] (nav/route-keys {:web/route {:page :chat :session-id "a"}})))
    (is (= ["b:a:d1" "s:a"]
           (nav/route-keys {:web/route {:page :chat :session-id "a"}
                            :rooms {"r" {:id "r" :session {:id "a"} :ui {:active-buffer "d1"}}}
                            :active-room "r"}))))
  (testing "the dashboard, the project list (and all sessions under it) and a project's page"
    (is (= ["home"] (nav/route-keys {:web/route {:page :home}})))
    (is (= ["projects"] (nav/route-keys {:web/route {:page :home :dir :projects}})))
    (is (= ["projects"] (nav/route-keys {:web/route {:page :home :dir :all}})))
    (is (= ["dir:/x"] (nav/route-keys {:web/route {:page :home :dir "/x"}}))))
  (testing "an extension page reached from a sidebar nav item"
    (is (= ["nav:Messages"]
           (nav/route-keys {:web/route {:page :messenger/inbox}
                            :web/nav-items [{:menu :palette :label "Other"
                                             :event {:type :route/navigate :page :messenger/inbox}}
                                            {:menu :sidebar :label "Messages"
                                             :event {:type :route/navigate :page :messenger/inbox}}]})))))

(deftest locate-test
  (let [ks ["home" "s:a" "s:b" "s:a" "s:c"]]
    (is (= {:idx 2 :exact? true} (nav/locate ks ["s:b"] nil)))
    (is (= {:idx 1 :exact? true} (nav/locate ks ["b:a:x" "s:a"] nil))
        "falls back to the session row when its buffers are folded")
    (is (= {:idx 3 :exact? true} (nav/locate ks ["s:a"] {:key "s:a" :idx 3}))
        "the cursor keeps a chat listed twice in its second place")
    (is (= {:idx 1 :exact? true} (nav/locate ks ["s:a"] {:key "s:b" :idx 2}))
        "a route moved elsewhere overrides the cursor")
    (is (= {:idx 4 :exact? true} (nav/locate ks [] {:key "s:c" :idx 0}))
        "no route row: the cursor's row")
    (is (= {:idx 2 :exact? false} (nav/locate ks [] {:key "draft:1" :idx 2}))
        "the cursor's row vanished")
    (is (nil? (nav/locate ks ["s:z"] nil))))
  (let [ks ["s:a" "b:a:sub1" "b:a:d1" "s:b"]]
    (is (= {:idx 1 :exact? true} (nav/locate ks ["s:a"] {:key "b:a:sub1" :idx 1}))
        "a sub-agent row keeps its place: revealing it leaves the route on the chat")
    (is (= {:idx 2 :exact? true} (nav/locate ks ["b:a:d1" "s:a"] {:key "b:a:sub1" :idx 1}))
        "an open buffer beats the cursor")
    (is (= {:idx 3 :exact? true} (nav/locate ks ["s:b"] {:key "b:a:sub1" :idx 1}))
        "another chat beats the cursor")))

(deftest enter-at-test
  (let [ks ["s:a" "s:b" "b:b:diff:session-edits" "b:b:sub1" "s:c"]]
    (is (= 1 (nav/enter-at ks 4 3))
        "walking up into another chat lands on its session row, not a buffer")
    (is (= 1 (nav/enter-at ks nil 2)) "no position: the session row too")
    (is (= 2 (nav/enter-at ks 1 2)) "inside a chat its buffer rows are steps")
    (is (= 3 (nav/enter-at ks 2 3)))
    (is (= 4 (nav/enter-at ks 3 4)) "session rows are left alone")))

(deftest step-test
  (is (= 3 (nav/step 5 {:idx 2 :exact? true} :next)))
  (is (= 1 (nav/step 5 {:idx 2 :exact? true} :prev)))
  (is (= 4 (nav/step 5 {:idx 4 :exact? true} :next)) "clamps at the end")
  (is (= 0 (nav/step 5 {:idx 0 :exact? true} :prev)) "clamps at the start")
  (is (= 2 (nav/step 5 {:idx 2 :exact? false} :next)) "a vanished row's successor")
  (is (= 1 (nav/step 5 {:idx 2 :exact? false} :prev)))
  (is (= 0 (nav/step 5 nil :next)))
  (is (= 4 (nav/step 5 nil :prev)))
  (is (nil? (nav/step 0 nil :next))))
