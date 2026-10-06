(ns xi.web.user-state-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.user-state :as us]))

(defn- run [st ev] ((get us/handlers (:type ev)) st ev))

(defn- fx-types [result] (mapv first (:effects result)))

(def ^:private browser
  "A browser that predates per-user state: values cached, no recorded user."
  {:web/theme-mode "dark"
   :web/appearance {:viewer-mode? false}
   :web/sidebar-collapsed #{:recent}
   :web/preferred-model "opus"
   :web/command-usage ["model"]
   :web/recent-skills ["review"]})

(deftest server-state-wins-over-the-cache
  (let [{:keys [state effects]}
        (run browser {:type :user-state/state :user "alice"
                      :state {:theme "light" :appearance {:tool-blocks :open}
                              :sidebar-collapsed [:projects] :preferred-model "sonnet"
                              :recent-commands ["clear"] :recent-skills ["x"]}})]
    (is (= "light" (:web/theme-mode state)))
    (is (= {:tool-blocks :open} (:web/appearance state)))
    (is (= #{:projects} (:web/sidebar-collapsed state)) "vectors become the set the views expect")
    (is (= "sonnet" (:web/preferred-model state)))
    (is (= ["clear"] (:web/command-usage state)))
    (is (= ["clear"] (:web/recent-commands state)) "a connect may reorder the frozen quick bar")
    (is (some #{[:theme/apply "light"]} effects) "the theme is applied to the page")
    (is (some #{[:cache/user {:user "alice"}]} effects) "the cache records whose it is")
    (is (not-any? #(= :ws/send (first %)) effects) "nothing to seed: the server had it all")))

(deftest keys-the-server-lacks-are-seeded-from-the-cache
  (let [{:keys [state effects]}
        (run browser {:type :user-state/state :user "root" :state {:theme "light"}})
        sent (into {} (keep (fn [[fx ev]] (when (= :ws/send fx) [(:key ev) (:value ev)]))) effects)]
    (is (= "light" (:web/theme-mode state)) "what the server has is not overwritten")
    (is (= {:appearance {:viewer-mode? false}
            :sidebar-collapsed [:recent]
            :preferred-model "opus"
            :recent-commands ["model"]
            :recent-skills ["review"]}
           sent)
        "everything else migrates up (sets travel as vectors); the theme does not")))

(deftest defaults-are-not-seeded
  (let [{:keys [effects]} (run {:web/theme-mode "auto" :web/appearance {}
                                :web/sidebar-collapsed #{} :web/command-usage []
                                :web/recent-skills []}
                               {:type :user-state/state :user "root" :state {}})]
    (is (not-any? #(= :ws/send (first %)) effects))))

(deftest another-user-does-not-inherit-the-cache
  (let [st (assoc browser :web/cached-user "alice")
        {:keys [state effects]} (run st {:type :user-state/state :user "bob"
                                         :state {:sidebar-collapsed [:projects]}})]
    (is (= "auto" (:web/theme-mode state)) "alice's theme is dropped")
    (is (= {} (:web/appearance state)))
    (is (nil? (:web/preferred-model state)))
    (is (= [] (:web/command-usage state)))
    (is (= [] (:web/recent-commands state)))
    (is (= #{:projects} (:web/sidebar-collapsed state)) "bob's own state is applied")
    (is (some #{[:theme/apply "auto"]} effects) "the page leaves alice's theme")
    (is (some #{[:cache/preferred-model {:model nil}]} effects) "and the cache is cleared")
    (is (not-any? #(= :ws/send (first %)) effects) "alice's values are never seeded into bob")
    (is (some #{[:cache/user {:user "bob"}]} effects))))

(deftest the-same-user-keeps-the-cache
  (let [st (assoc browser :web/cached-user "alice")
        {:keys [state]} (run st {:type :user-state/state :user "alice" :state {}})]
    (is (= "dark" (:web/theme-mode state)))))

(deftest changed-applies-one-key
  (let [{:keys [state effects]} (run browser {:type :user-state/changed :key :theme :value "light"})]
    (is (= "light" (:web/theme-mode state)))
    (is (= [[:theme/apply "light"]] effects)))
  (testing "the echo of our own write changes nothing (no theme transition replay)"
    (is (nil? (run browser {:type :user-state/changed :key :theme :value "dark"}))))
  (testing "a later change never reorders the frozen quick bar"
    (let [st (assoc browser :web/recent-commands ["model"])
          {:keys [state]} (run st {:type :user-state/changed :key :recent-commands :value ["clear"]})]
      (is (= ["clear"] (:web/command-usage state)))
      (is (= ["model"] (:web/recent-commands state)))))
  (testing "unknown keys are ignored"
    (is (nil? (run browser {:type :user-state/changed :key :favorites :value ["a"]})))))

(deftest set-effect-sends-sets-as-vectors
  (is (= [:ws/send {:type :user-state/set :key :sidebar-collapsed :value [:projects :recent]}]
         (us/set-effect :sidebar-collapsed #{:recent :projects})))
  (is (= [:ws/send {:type :user-state/set :key :theme :value "dark"}]
         (us/set-effect :theme "dark"))))
