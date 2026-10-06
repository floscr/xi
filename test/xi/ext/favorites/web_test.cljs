(ns xi.ext.favorites.web-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.core :as ext]
            [xi.ext.favorites.web :as favorites]))

(def ^:private composed (ext/compose [favorites/extension]))

(deftest contributes-a-sidebar-group-of-the-five-latest-favorites
  (let [[group :as groups] (:sidebar-groups composed)]
    (is (= 1 (count groups)))
    (is (= {:id :favorites :label "Favorites" :where :favorite? :limit 5}
           (select-keys group [:id :label :where :limit])))
    (testing "the trailing row opens the full list"
      (is (= {:type :route/navigate :page :home :dir :favorites}
             (get-in group [:more :event]))))))

(deftest contributes-the-session-context-menu-item
  (is (= [{:label    "Add to favorites"
           :label-on "Remove from favorites"
           :flag     :favorite?
           :icon     :star
           :event    {:type :favorites/toggle}}]
         (:session-menu-items composed))))

(deftest the-overflow-entry-opens-the-view-without-the-menu-ctx-clobbering-it
  (let [item (first (:nav-items composed))
        open (get-in composed [:handlers :favorites/open])]
    (is (= :overflow (:menu item)))
    (testing "the overflow menu merges :cwd/:room-id into a nav event, so the
              entry carries only its own event, which a handler turns into the route"
      (is (= {:type :favorites/open} (:event item)))
      (is (= [[:app/dispatch {:type :route/navigate :page :home :dir :favorites}]]
             (:effects (open {} {:type :favorites/open :cwd "/x" :room-id "r"})))))))
