(ns xi.web.palette-items-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.palette-items :as palette-items]))

(deftest menu-page-resolves-named-menus-and-drilled-rows
  (palette-items/register-menus! :pr {"prs" {:title "PRs" :placeholder "Pick a PR…"
                                            :items (fn [st] [{:label (:n st)}])}})
  (testing "a named menu's rows follow the state"
    (is (= {:title "PRs" :placeholder "Pick a PR…" :rows [{:label "#1"}]}
           (palette-items/menu-page {:n "#1"} {:kind :ext-menu :ext :pr :menu "prs"}))))
  (testing "a menu that isn't registered yet resolves to nil"
    (is (nil? (palette-items/menu-page {} {:kind :ext-menu :ext :other :menu "prs"}))))
  (testing "a drilled page carries its rows"
    (is (= {:title "#1" :placeholder nil :rows [{:label "Viewer"}]}
           (palette-items/menu-page {} {:kind :ext-menu :title "#1"
                                        :rows [{:label "Viewer"}]}))))
  (palette-items/register-menus! :pr nil))
