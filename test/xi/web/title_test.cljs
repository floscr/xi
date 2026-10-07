(ns xi.web.title-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.title :as title]))

(defn- with-room
  "Web state attached to one room of session `sid` named `nm`, showing
   buffer `active` out of `bufs`."
  [route sid nm bufs active]
  {:web/route route
   :active-room "r1"
   :rooms {"r1" {:id "r1"
                 :session {:id sid :name nm}
                 :ui {:buffers bufs :active-buffer active}}}})

(deftest roomless-pages
  (is (= "Xi" (title/page-title {:web/route {:page :home}})))
  (is (= "Xi" (title/page-title {})))
  (is (= "All sessions · Xi" (title/page-title {:web/route {:page :home :dir :all}})))
  (is (= "xi · Xi" (title/page-title {:web/route {:page :home :dir "/home/u/Code/xi"}})))
  (is (= "Git status · xi · Xi"
         (title/page-title {:web/route {:page :git-status :cwd "/home/u/Code/xi"}}))))

(deftest chat
  (testing "the session's name, from the joined room"
    (is (= "Fix the router · Xi"
           (title/page-title (with-room {:page :chat :session-id "s1"}
                               "s1" "Fix the router" {} :chat)))))
  (testing "the open buffer comes first"
    (is (= "README.md · Fix the router · Xi"
           (title/page-title (with-room {:page :chat :session-id "s1"} "s1" "Fix the router"
                               {"file:/r/README.md" {:kind :file :title "README.md"}}
                               "file:/r/README.md"))))
    (is (= "events · Fix the router · Xi"
           (title/page-title (with-room {:page :chat :session-id "s1"} "s1" "Fix the router"
                               {:events {}} :events)))))
  (testing "mid-switch: the still-attached previous room names nothing"
    (is (= "Other · Xi"
           (title/page-title (assoc (with-room {:page :chat :session-id "s2"} "s1" "Fix the router"
                                      {:events {}} :events)
                                    :lobby {:sessions [{:session-id "s2" :name "Other"}]})))))
  (testing "unknown session / new chat"
    (is (= "Session · Xi" (title/page-title {:web/route {:page :chat :session-id "s9"}})))
    (is (= "New session · Xi" (title/page-title {:web/route {:page :chat}})))))

(deftest extension-page
  (is (= "Canvas review · Other · Xi"
         (title/page-title {:web/route {:page :canvas-review :session-id "s2"}
                            :lobby {:rooms [{:session-id "s2" :session-name "Other"}]}}))))
