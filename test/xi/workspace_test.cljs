(ns xi.workspace-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.workspace :as ws]))

(deftest config-workspaces-are-validated-and-normalized
  (is (= [] (ws/parse-config nil)))
  (is (= [{:id "penpot" :label "Penpot" :dirs ["~/Code/penpot" "~/Code/penpot-*"]}
          {:id "home" :label "home" :dirs ["~/Code/home"]}]
         (ws/parse-config [{:id :penpot :label "Penpot" :dirs ["~/Code/penpot" "~/Code/penpot-*"]}
                           {:id "home" :dirs ["~/Code/home"]}]))
      "keyword ids become strings; the label defaults to the id")
  (let [err (fn [data] (:error (ws/parse-config data)))]
    (is (re-find #"must be a vector" (err {:id :a})))
    (is (re-find #"entries must be" (err [{:id :a}])) "dirs are required")
    (is (re-find #"entries must be" (err [{:id :a :dirs []}])) "and non-empty")
    (is (re-find #"entries must be" (err [{:id :a :dirs ["~/x"] :icon :pen}])) "unknown keys")
    (is (re-find #"entries must be" (err [{:dirs ["~/x"]}])) "an id is required")
    (is (re-find #"distinct" (err [{:id :a :dirs ["~/x"]} {:id "a" :dirs ["~/y"]}])))
    (is (re-find #"root workspace" (err [{:id :all :dirs ["~/x"]}])))))

(deftest membership-is-by-directory-prefix
  (let [penpot {:dirs ["/home/u/Code/penpot"]}
        family {:dirs ["/home/u/Code/penpot-*/"]}]
    (is (ws/member? penpot "/home/u/Code/penpot"))
    (is (ws/member? penpot "/home/u/Code/penpot/frontend/src") "a chat in a subfolder belongs")
    (is (not (ws/member? penpot "/home/u/Code/penpot-old")) "a sibling with the same prefix does not")
    (is (ws/member? family "/home/u/Code/penpot-old/x") "a glob dir takes the siblings (trailing slash ignored)")
    (is (not (ws/member? family "/home/u/Code/penpot")))
    (is (not (ws/member? penpot "/home/u/Code/other")))
    (is (not (ws/member? penpot nil)) "a session without a cwd belongs to All only"))
  (testing "a * matches one path segment"
    (is (ws/dir-matches? "/home/u/Code/*" "/home/u/Code/x"))
    (is (ws/dir-matches? "/home/u/Code/*" "/home/u/Code/x/deep/er") "and everything below it")
    (is (not (ws/dir-matches? "/home/u/Code/*" "/home/u/Code")) "but not the parent itself")
    (is (not (ws/dir-matches? "/home/u/Code/*" "/home/u/Other/x")))
    (is (ws/dir-matches? "/home/u/Code/pen*ot" "/home/u/Code/penpot"))
    (is (not (ws/dir-matches? "/home/u/Code/pen.ot" "/home/u/Code/penpot")) "regex characters are literal")))

(def ^:private state
  {:lobby {:workspaces [{:id "penpot" :label "Penpot" :dirs ["/c/penpot"]}]
           :sessions   [{:session-id "p1" :cwd "/c/penpot/app"}
                        {:session-id "h1" :cwd "/c/home"}
                        {:session-id "n1"}]
           :rooms      [{:session-id "p1" :cwd "/c/penpot/app"}
                        {:session-id "h2" :cwd "/c/home"}]}
   :workspace/user [{:id "w-1" :label "Home" :dirs ["/c/home"]}]})

(deftest workspaces-list-config-first-then-the-users-own
  (is (= [{:id "penpot" :label "Penpot" :dirs ["/c/penpot"] :source :config}
          {:id "w-1" :label "Home" :dirs ["/c/home"] :source :user}]
         (ws/workspaces state)))
  (is (= [] (ws/workspaces {}))))

(deftest selection-falls-back-to-all
  (is (nil? (ws/selected state)) "nothing selected")
  (is (= "all" (ws/selected-id state)))
  (is (nil? (ws/selected (assoc state :workspace/selected "all"))))
  (is (nil? (ws/selected (assoc state :workspace/selected "gone"))) "a deleted workspace shows All")
  (is (= "Home" (:label (ws/selected (assoc state :workspace/selected "w-1"))))))

(deftest next-and-prev-wrap-around-the-tab-row
  (is (= "penpot" (ws/neighbor-id state 1)))
  (is (= "w-1" (ws/neighbor-id state -1)) "backwards from All wraps to the last tab")
  (is (= "all" (ws/neighbor-id (assoc state :workspace/selected "w-1") 1)))
  (is (= "penpot" (ws/neighbor-id (assoc state :workspace/selected "gone") 1)) "an unknown selection counts as All"))

(deftest closed-tabs-leave-the-row-but-not-the-list
  (let [st (assoc state :workspace/closed ["penpot"])]
    (is (= ["w-1"] (mapv :id (ws/open-workspaces st))))
    (is (= ["penpot" "w-1"] (mapv :id (ws/workspaces st))) "the palette still lists it")
    (is (= "w-1" (ws/nth-id st 2)) "numbering skips it")
    (is (= "w-1" (ws/neighbor-id st 1)) "and so does cycling")
    (is (nil? (ws/selected (assoc st :workspace/selected "penpot")))
        "a window on a tab closed elsewhere falls back to All"))
  (is (= ["penpot"] (ws/close-tab state [] "penpot")))
  (is (= ["penpot"] (ws/close-tab state ["penpot" "gone"] "penpot")) "pruned and distinct")
  (is (= [] (ws/reopen-tab ["penpot"] "penpot")))
  (is (ws/valid-closed? ["a" "b"]))
  (is (not (ws/valid-closed? [""])))
  (is (not (ws/valid-closed? "a"))))

(deftest a-workspace-remembers-the-chats-opened-in-it
  (let [s (-> {} (ws/visit "w" "a") (ws/visit "w" "b") (ws/visit "w" "a"))]
    (is (= {"w" ["a" "b"]} s) "once each, oldest first")
    (is (= {"w" ["b"]} (ws/forget s "w" "a")))
    (is (= {} (ws/forget {"w" ["a"]} "w" "a")) "an empty list goes")
    (is (= ["a" "b"] (ws/opened {:workspace/sessions s} "w")))
    (is (= [] (ws/opened {} "w")))
    (is (ws/valid-sessions? s))
    (is (not (ws/valid-sessions? {"w" [1]})))
    (is (not (ws/valid-sessions? ["a"]))))
  (testing "capped: the oldest drop out"
    (let [s (reduce #(ws/visit %1 "w" (str "s" %2)) {} (range (inc ws/max-workspace-sessions)))]
      (is (= ws/max-workspace-sessions (count (get s "w"))))
      (is (= "s1" (first (get s "w")))))))

(deftest collapsed-sidebar-groups-are-per-workspace
  (let [st (assoc state :web/sidebar-collapsed #{:earlier}
                  :workspace/collapsed {"w-1" [:recent :projects]})]
    (is (= #{:earlier} (ws/collapsed-groups st)) "All keeps its own")
    (is (= #{:recent :projects} (ws/collapsed-groups (assoc st :workspace/selected "w-1"))))
    (is (= #{:earlier} (ws/collapsed-groups (assoc st :workspace/selected "penpot")))
        "a workspace not changed yet starts from All's")
    (is (= #{} (ws/collapsed-groups {})))
    (is (ws/valid-collapsed? {"w-1" [:recent]}))
    (is (not (ws/valid-collapsed? {"w-1" ["recent"]})))))

(deftest tabs-are-numbered-from-all-like-doom
  (is (= "all" (ws/nth-id state 1)))
  (is (= "penpot" (ws/nth-id state 2)))
  (is (= "w-1" (ws/nth-id state 3)))
  (is (nil? (ws/nth-id state 4)) "past the end")
  (is (= "w-1" (ws/nth-id state :last)))
  (is (= "all" (ws/nth-id {} :last)) "without workspaces the last tab is All"))

(deftest scope-narrows-the-lobby-to-the-selected-workspace
  (let [scoped (ws/scope (assoc state :workspace/selected "penpot"))]
    (is (= ["p1"] (map :session-id (get-in scoped [:lobby :sessions]))))
    (is (= ["p1"] (map :session-id (get-in scoped [:lobby :rooms]))))
    (is (= (:lobby state) (:lobby (ws/scope state))) "All leaves everything")
    (is (= (:lobby state) (:lobby (ws/scope (ws/unscoped (assoc state :workspace/selected "penpot"))))))
    (is (= ["/c/penpot/x"] (ws/filter-dirs (ws/selected (assoc state :workspace/selected "penpot"))
                                           ["/c/penpot/x" "/c/home"])))
    (is (= ["a" "b"] (ws/filter-dirs nil ["a" "b"])))))

(deftest user-workspaces-are-edited-as-plain-data
  (let [user (ws/add-workspace [] {:id "w-1" :label "  Home " :dirs ["/c/home" "/c/home" nil]})]
    (is (= [{:id "w-1" :label "Home" :dirs ["/c/home"]}] user) "label trimmed, dirs distinct")
    (is (= user (ws/add-workspace user {:id "w-2" :label "   " :dirs []})) "a blank label adds nothing")
    (is (= user (ws/add-workspace user {:id "w-1" :label "Dup" :dirs []})) "nor a taken id")
    (is (= [{:id "w-1" :label "Work" :dirs ["/c/home"]}] (ws/rename-workspace user "w-1" "Work")))
    (is (= user (ws/rename-workspace user "w-1" "")) "a blank rename is ignored")
    (is (= [{:id "w-1" :label "Home" :dirs ["/c/home" "/c/x"]}] (ws/toggle-dir user "w-1" "/c/x")))
    (is (= [{:id "w-1" :label "Home" :dirs []}] (ws/toggle-dir user "w-1" "/c/home")) "toggling off removes")
    (is (= [] (ws/remove-workspace user "w-1")))
    (is (ws/valid-user-workspaces? user))
    (is (ws/valid-user-workspaces? []))
    (is (not (ws/valid-user-workspaces? [{:id "a" :label "A"}])) "dirs required")
    (is (not (ws/valid-user-workspaces? [{:id "a" :label "A" :dirs []} {:id "a" :label "B" :dirs []}])) "ids distinct")
    (is (not (ws/valid-user-workspaces? [{:id "a" :label "" :dirs []}])))
    (is (not (ws/valid-user-workspaces? {:id "a"})))))
