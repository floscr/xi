(ns xi.web.workspaces-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.workspaces :as w]))

(defn- run [st ev] ((get w/handlers (:type ev)) st ev))

(defn- sent [result]
  (into {} (keep (fn [[fx ev]] (when (= :ws/send fx) [(:key ev) (:value ev)])))
        (:effects result)))

(defn- window [result]
  (some (fn [[fx v]] (when (= :cache/window-workspace fx) v)) (:effects result)))

(def ^:private state
  {:lobby {:workspaces [{:id "penpot" :label "Penpot" :dirs ["/c/penpot"]}]}
   :workspace/user [{:id "w-1" :label "Home" :dirs ["/c/home"]}]})

(deftest selecting-a-tab-stays-in-the-window-and-remembers-the-previous-one
  (let [r (run state {:type :workspace/select :id "penpot"})]
    (is (= "penpot" (get-in r [:state :workspace/selected])))
    (is (= "all" (get-in r [:state :workspace/previous])))
    (is (= {} (sent r)) "never sent to the server: other windows keep their tab")
    (is (= {:selected "penpot" :chats {}} (window r)) "kept in this window's storage")
    (testing "other goes back and forth"
      (let [r2 (run (:state r) {:type :workspace/other})]
        (is (= "all" (get-in r2 [:state :workspace/selected])))
        (is (= "penpot" (get-in r2 [:state :workspace/previous])))))
    (is (= "all" (get-in (run state {:type :workspace/select :id "gone"}) [:state :workspace/selected]))
        "an unknown id selects All")))

(deftest numbered-tabs-count-from-all
  (is (= "w-1" (get-in (run state {:type :workspace/tab :n 3}) [:state :workspace/selected])))
  (is (= "w-1" (get-in (run state {:type :workspace/tab :n :last}) [:state :workspace/selected])))
  (is (nil? (run state {:type :workspace/tab :n 9})) "past the end does nothing"))

(deftest only-the-users-own-workspaces-are-renamed-or-removed
  (testing "config.edn's are left alone"
    (let [st (assoc state :workspace/selected "penpot")]
      (is (nil? (run st {:type :workspace/rename-current})))
      (is (nil? (run st {:type :workspace/remove-current})))
      (is (nil? (run st {:type :workspace/toggle-dir :id "penpot" :dir "/x"})))))
  (testing "the selected own one opens the rename dialog with its name"
    (is (= {:mode :rename :id "w-1" :dir nil :label "Home"}
           (get-in (run (assoc state :workspace/selected "w-1") {:type :workspace/rename-current})
                   [:state :workspace/editor]))))
  (testing "removing the selected one goes back to All and saves both"
    (let [r (run (assoc state :workspace/selected "w-1") {:type :workspace/remove-current})]
      (is (= [] (get-in r [:state :workspace/user])))
      (is (= "all" (get-in r [:state :workspace/selected])))
      (is (= {:workspaces []} (sent r)))
      (is (= "all" (:selected (window r)))))))

(def ^:private with-chats
  (-> state
      (assoc-in [:lobby :sessions] [{:session-id "pp" :cwd "/c/penpot/app"}
                                    {:session-id "hh" :cwd "/c/home"}])
      (assoc-in [:lobby :rooms] [{:session-id "live" :cwd "/c/penpot"}])))

(defn- on-chat [st sid] (assoc st :web/route {:page :chat :session-id sid}))

(defn- nav [result]
  (some (fn [[fx ev]] (when (= :app/dispatch fx) ev)) (:effects result)))

(deftest each-workspace-keeps-its-focused-chat
  (testing "leaving a tab records its chat, entering one opens its own"
    (let [st (-> with-chats
                 (assoc :workspace/selected "penpot" :workspace/chats {"w-1" "hh"})
                 (on-chat "pp"))
          r  (run st {:type :workspace/select :id "w-1"})]
      (is (= {"w-1" "hh" "penpot" "pp"} (get-in r [:state :workspace/chats])))
      (is (= {:type :route/navigate :page :chat :session-id "hh"} (nav r)))
      (is (= {"w-1" "hh" "penpot" "pp"} (:chats (window r))) "kept per window")
      (is (= {} (sent r)) "not synced")))
  (testing "a remembered chat the lobby no longer lists is not opened"
    (let [r (run (-> with-chats (assoc :workspace/chats {"w-1" "gone"}) (on-chat "pp"))
                 {:type :workspace/select :id "w-1"})]
      (is (= {:type :route/navigate :page :home} (nav r)) "pp is not in w-1: to the dashboard")))
  (testing "without a remembered chat, a chat that belongs stays"
    (is (nil? (nav (run (on-chat with-chats "live") {:type :workspace/select :id "penpot"}))) "a live room counts")
    (is (nil? (nav (run (-> with-chats (assoc :workspace/selected "penpot") (on-chat "pp"))
                        {:type :workspace/select :id "all"})))
        "All takes every chat"))
  (testing "off the chat page nothing moves"
    (is (nil? (nav (run (assoc with-chats :web/route {:page :home}) {:type :workspace/select :id "w-1"})))))
  (testing "leaving from a non-chat page forgets the tab's chat; gone tabs are pruned"
    (let [r (run (assoc with-chats :workspace/selected "w-1" :web/route {:page :home}
                        :workspace/chats {"w-1" "hh" "deleted-ws" "x"})
                 {:type :workspace/select :id "all"})]
      (is (= {} (get-in r [:state :workspace/chats])))))
  (testing "reselecting the same tab does not navigate"
    (is (nil? (nav (run (-> with-chats (assoc :workspace/selected "w-1") (on-chat "pp"))
                        {:type :workspace/select :id "w-1"}))))))

(deftest closing-a-tab-hides-it-and-selecting-reopens-it
  (testing "closing the selected tab hands over to the one on its left"
    (let [r (run (assoc state :workspace/selected "w-1") {:type :workspace/close :id "w-1"})]
      (is (= ["w-1"] (get-in r [:state :workspace/closed])))
      (is (= "penpot" (get-in r [:state :workspace/selected])))
      (is (= {:workspaces-closed ["w-1"]} (sent r)) "closed tabs are per user")
      (is (= (:workspace/user state) (get-in r [:state :workspace/user])) "nothing is deleted")))
  (testing "config.edn's tabs close too; the selection stays when another tab closes"
    (let [r (run (assoc state :workspace/selected "w-1") {:type :workspace/close :id "penpot"})]
      (is (= ["penpot"] (get-in r [:state :workspace/closed])))
      (is (= "w-1" (get-in r [:state :workspace/selected])))))
  (testing "All has no close"
    (is (nil? (run state {:type :workspace/close :id "all"})))
    (is (nil? (run state {:type :workspace/close-current}))))
  (testing "selecting a closed workspace reopens its tab"
    (let [r (run (assoc state :workspace/closed ["penpot"]) {:type :workspace/select :id "penpot"})]
      (is (= [] (get-in r [:state :workspace/closed])))
      (is (= "penpot" (get-in r [:state :workspace/selected])))
      (is (= {:workspaces-closed []} (sent r))))))

(deftest the-switcher-skips-the-current-workspace-and-switches-when-one-is-left
  (testing "the list never offers the workspace on screen; closed ones stay"
    (let [st (assoc state :workspace/selected "w-1" :workspace/closed ["penpot"])]
      (is (= ["all" "penpot"] (w/switch-targets st)))))
  (testing "several to pick from: the palette page opens"
    (let [r (run state {:type :palette/open-workspaces})]
      (is (= {:kind :workspaces} (get-in r [:state :web/palette-page])))
      (is (nil? (get-in r [:state :workspace/selected])) "nothing switched yet")))
  (testing "one other workspace: a plain switch, no palette"
    (let [st {:workspace/user [{:id "w-1" :label "Home" :dirs []}]}
          r  (run st {:type :palette/open-workspaces})]
      (is (= "w-1" (get-in r [:state :workspace/selected])))
      (is (nil? (get-in r [:state :web/palette-page])))
      (is (= "all" (get-in (run (:state r) {:type :palette/open-workspaces})
                           [:state :workspace/selected]))
          "and back again")))
  (testing "closed tabs don't count: two open tabs still toggle"
    (let [st (assoc state :workspace/selected "penpot" :workspace/closed ["w-1"])]
      (is (= "all" (get-in (run st {:type :palette/open-workspaces}) [:state :workspace/selected])))))
  (testing "All alone: the page, to reopen a closed one"
    (let [r (run (assoc state :workspace/closed ["penpot" "w-1"]) {:type :palette/open-workspaces})]
      (is (= {:kind :workspaces} (get-in r [:state :web/palette-page])))))
  (testing ":list? opens the page even with two tabs"
    (let [r (run {:workspace/user [{:id "w-1" :label "Home" :dirs []}]}
                 {:type :palette/open-workspaces :list? true})]
      (is (= {:kind :workspaces} (get-in r [:state :web/palette-page])))
      (is (nil? (get-in r [:state :workspace/selected]))))))

(deftest the-workspace-section-keeps-what-was-opened-until-pruned
  (testing "visiting a chat in a workspace adds it, per user"
    (let [r (run state {:type :workspace/visit :id "w-1" :session-id "s1"})]
      (is (= {"w-1" ["s1"]} (get-in r [:state :workspace/sessions])))
      (is (= {:workspace-sessions {"w-1" ["s1"]}} (sent r)))
      (is (nil? (run (:state r) {:type :workspace/visit :id "w-1" :session-id "s1"}))
          "a second visit changes nothing")))
  (is (nil? (run state {:type :workspace/visit :id "all" :session-id "s1"})) "All keeps no list")
  (let [st (assoc state :workspace/sessions {"w-1" ["s1" "s2"] "penpot" ["s3"]}
                  :workspace/selected "w-1")]
    (testing "one chat out"
      (is (= {"w-1" ["s2"] "penpot" ["s3"]}
             (get-in (run st {:type :workspace/forget :id "w-1" :session-id "s1"})
                     [:state :workspace/sessions]))))
    (testing "prune empties the selected workspace only"
      (is (= {"penpot" ["s3"]} (get-in (run st {:type :workspace/prune-current})
                                       [:state :workspace/sessions])))
      (is (nil? (run (assoc st :workspace/selected "all") {:type :workspace/prune-current}))))
    (testing "deleting a workspace drops its list"
      (is (= {"penpot" ["s3"]} (get-in (run st {:type :workspace/remove :id "w-1"})
                                       [:state :workspace/sessions]))))))

(deftest the-visit-tap-records-arriving-at-a-chat
  (let [seen   (atom [])
        tap    (w/visit-tap #(swap! seen conj %))
        st     (-> state
                   (assoc :workspace/selected "w-1" :web/route {:page :chat :session-id "s9"}))
        loaded (assoc st :web/user-state-loaded? true)]
    (tap {:type :lobby/state} st)
    (is (= [] @seen) "before :user-state/state, nothing is written")
    (tap {:type :lobby/state} loaded)
    (is (= [{:type :workspace/visit :id "w-1" :session-id "s9"}] @seen))
    (reset! seen [])
    (tap {:type :workspace/prune-current} loaded)
    (is (= [] @seen) "still on that chat: a prune or removal sticks")
    (tap {:type :route/navigate} (assoc-in loaded [:web/route :session-id] "s10"))
    (is (= [{:type :workspace/visit :id "w-1" :session-id "s10"}] @seen) "another chat is recorded")
    (reset! seen [])
    (tap {:type :route/navigate} (assoc loaded :workspace/sessions {"w-1" ["s9"]}))
    (is (= [] @seen) "back to one already in the list")
    (tap {:type :workspace/select} (assoc loaded :workspace/selected "all"))
    (is (= [] @seen) "All records nothing")))

(deftest the-dialog-creates-and-selects-a-workspace
  (let [st (:state (run state {:type :workspace/edit-open :mode :create :dir "/c/new"}))
        st (:state (run st {:type :workspace/edit-set :label "New one"}))
        r  (run st {:type :workspace/edit-save})
        nw (last (get-in r [:state :workspace/user]))]
    (is (= {:label "New one" :dirs ["/c/new"]} (select-keys nw [:label :dirs])))
    (is (= (:id nw) (get-in r [:state :workspace/selected])))
    (is (nil? (get-in r [:state :workspace/editor])) "the dialog closes"))
  (testing "a blank name only closes the dialog"
    (let [st (:state (run state {:type :workspace/edit-open :mode :create}))
          r  (run st {:type :workspace/edit-save})]
      (is (= (:workspace/user state) (get-in r [:state :workspace/user])))
      (is (empty? (:effects r))))))
