(ns xi.web.layout-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.layout :as layout]
            [xi.web.layout :as wlayout]))

(def room
  {:id "r1"
   :session {:id "s1"}
   :ui {:active-buffer :chat
        :buffers {"diff:git" {:kind :diff :title "Git"}
                  "file:a"   {:kind :file :title "a"}}}
   :ext {:subagents {:agents [{:id "sa-1" :status :done}]}}})

(def st
  {:web/route {:page :chat :session-id "s1"}
   :active-room "r1"
   :web/wide? true
   :rooms {"r1" room}})

(defn- run [st ev]
  (let [h (get wlayout/handlers (:type ev))]
    (or (:state (h st ev)) st)))

(defn- active [st] (get-in st [:rooms "r1" :ui :active-buffer]))

(deftest view-open
  (is (wlayout/view-open? room :chat))
  (is (wlayout/view-open? room :empty))
  (is (wlayout/view-open? room "diff:git"))
  (is (wlayout/view-open? room "sa-1"))
  (is (not (wlayout/view-open? room "file:b"))))

(deftest room-ctx-only-for-the-joined-chat
  (is (= "s1" (:key (wlayout/room-ctx st))))
  (is (nil? (wlayout/room-ctx (assoc-in st [:web/route :session-id] "s2"))) "mid-switch")
  (is (nil? (wlayout/room-ctx (assoc-in st [:web/route :page] :home))))
  (is (nil? (wlayout/room-ctx (assoc-in st [:web/route :session-id] nil))) "a virtual new chat"))

(deftest implicit-single-layout
  (is (nil? (wlayout/stored st)))
  (is (= :chat (layout/focused-view (wlayout/current st))))
  (is (not (wlayout/windows? st)))
  (is (= "diff:git" (layout/focused-view
                     (wlayout/current (assoc-in st [:rooms "r1" :ui :active-buffer] "diff:git"))))))

(deftest split-sets-the-active-buffer-and-persists
  (let [{st' :state fx :effects} ((:layout/split wlayout/handlers) st {:type :layout/split :dir :row :event/ts 5})]
    (is (wlayout/windows? st'))
    (is (= :empty (active st')) "the new, focused pane waits for a pick")
    (is (= [:chat :empty] (mapv :view (layout/panes (wlayout/stored st')))))
    (is (= 5 (:at (wlayout/stored st'))))
    (is (= #{:cache/layouts :ws/send} (set (map first fx))))
    (testing "picking a view for the pane"
      (let [st2 (run st' {:type :layout/set-view :pane-id "p2" :view "diff:git"})]
        (is (= "diff:git" (active st2)))))))

(deftest handlers-outside-a-joined-chat-do-nothing
  (is (nil? ((:layout/split wlayout/handlers) (assoc-in st [:web/route :page] :home) {:type :layout/split}))))

(deftest sync-view-follows-the-active-buffer
  (let [st' (-> st
                (run {:type :layout/split :dir :row})
                (run {:type :layout/set-view :pane-id "p2" :view "diff:git"})
                (run {:type :layout/focus :pane-id "p1"}))]
    (is (= :chat (active st')))
    (testing "a buffer switch to a view in another pane focuses that pane"
      (let [st2 (run (assoc-in st' [:rooms "r1" :ui :active-buffer] "diff:git") {:type :layout/sync-view})]
        (is (= "p2" (:focus (wlayout/stored st2))))
        (is (= [:chat "diff:git"] (mapv :view (layout/panes (wlayout/stored st2)))))))
    (testing "a switch to a view shown nowhere shows it in the focused pane"
      (let [st2 (run (assoc-in st' [:rooms "r1" :ui :active-buffer] "file:a") {:type :layout/sync-view})]
        (is (= "p1" (:focus (wlayout/stored st2))))
        (is (= ["file:a" "diff:git"] (mapv :view (layout/panes (wlayout/stored st2)))))))))

(deftest prune-after-a-buffer-close
  (let [st' (-> st
                (run {:type :layout/split :dir :row})
                (run {:type :layout/set-view :pane-id "p2" :view "diff:git"})
                (update-in [:rooms "r1" :ui :buffers] dissoc "diff:git"))
        st2 (run st' {:type :layout/prune})]
    (is (= [:chat] (mapv :view (layout/panes (wlayout/stored st2)))))
    (is (= :chat (active st2)))))

(deftest the-tap-dispatches-what-is-needed
  (let [sent (atom [])
        tap  (wlayout/tap #(swap! sent conj (:type %)))
        st'  (-> st
                 (run {:type :layout/split :dir :row})
                 (run {:type :layout/set-view :pane-id "p2" :view "diff:git"}))]
    (tap {:type :agent/text-delta} st')
    (is (= [] @sent) "in step: nothing")
    (tap {:type :ui/buffer-switch} (assoc-in st' [:rooms "r1" :ui :active-buffer] :chat))
    (is (= [:layout/sync-view] @sent))
    (reset! sent [])
    (tap {:type :ui/buffer-close} (update-in st' [:rooms "r1" :ui :buffers] dissoc "diff:git"))
    (is (= [:layout/prune] @sent))
    (reset! sent [])
    (tap {:type :layout/focus} (assoc-in st' [:rooms "r1" :ui :active-buffer] :chat))
    (is (= [] @sent) "never after its own events")
    (tap {:type :ui/buffer-switch} (assoc-in st [:rooms "r1" :ui :active-buffer] "file:a"))
    (is (= [] @sent) "an unsplit session has nothing to keep in step")))

(deftest sidebars-wide-and-narrow
  (let [st' (-> st
                (run {:type :layout/split :dir :row})
                (run {:type :layout/set-view :pane-id "p2" :view "diff:git"})
                (run {:type :layout/dock :side :right}))
        l   (wlayout/stored st')]
    (is (= "diff:git" (:view (layout/side-pane l :right))))
    (is (= :right (wlayout/focused-side st')))
    (is (= "diff:git" (active st')))
    (testing "wide: toggling collapses, focus goes to the main area"
      (let [st2 (run st' {:type :layout/toggle-side :side :right})]
        (is (false? (get-in (wlayout/stored st2) [:right :open?])))
        (is (nil? (wlayout/focused-side st2)))
        (is (= :chat (active st2)))
        (testing "focusing the sidebar pane expands it again"
          (is (true? (get-in (wlayout/stored (run st2 {:type :layout/focus :pane-id "p2"})) [:right :open?]))))))
    (testing "narrow: the drawer is open while its pane has focus"
      (let [n (assoc st' :web/wide? false)]
        (is (wlayout/drawer-open? n :right))
        (let [n2 (run n {:type :layout/toggle-side :side :right})]
          (is (not (wlayout/drawer-open? n2 :right)))
          (is (= :chat (active n2)))
          (is (wlayout/drawer-open? (run n2 {:type :layout/toggle-side :side :right}) :right)))))
    (testing "grow on a sidebar changes its width"
      (is (= (+ layout/side-default (:px wlayout/resize-step))
             (get-in (wlayout/stored (run st' {:type :layout/grow :axis :width :sign 1})) [:right :size]))))
    (testing "open-view into the sidebar keeps the focus"
      (let [st2 (run (run st' {:type :layout/focus :pane-id "p1"})
                     {:type :layout/open-view :view "sa-1" :where :right})]
        (is (= "sa-1" (:view (layout/side-pane (wlayout/stored st2) :right))))
        (is (= :chat (active st2)))))))

(deftest reset-and-seed
  (let [st' (run st {:type :layout/split :dir :row})]
    (is (nil? (wlayout/stored (run st' {:type :layout/reset}))))
    (is (= :chat (active (run st' {:type :layout/reset}))))
    (testing "seed adopts unknown sessions only"
      (let [remote {"s1" (layout/split (layout/single :chat) "p1" :col "file:a")
                    "s9" (layout/split (layout/single :chat) "p1" :col "file:a")
                    "bad" {}}
            st2 (run st' {:type :layout/seed :layouts remote})]
        (is (= [:chat :empty] (mapv :view (layout/panes (get-in st2 [:web/layouts "s1"])))) "the chat on screen keeps its own")
        (is (= [:chat "file:a"] (mapv :view (layout/panes (get-in st2 [:web/layouts "s9"])))))
        (is (not (contains? (:web/layouts st2) "bad")))))))

(deftest restore-on-join
  (let [st' (run st {:type :layout/split :dir :row})
        st' (run st' {:type :layout/set-view :pane-id "p2" :view "diff:git"})
        ;; the room comes back with the chat active and the diff closed
        joined (-> st'
                   (assoc-in [:rooms "r1" :ui :active-buffer] :chat)
                   (update-in [:rooms "r1" :ui :buffers] dissoc "diff:git"))
        st2 (wlayout/restore joined "r1")]
    (is (= [:chat] (mapv :view (layout/panes (get-in st2 [:web/layouts "s1"])))))
    (is (= :chat (active st2)))
    (let [plain (update joined :web/layouts dissoc "s1")]
      (is (= plain (wlayout/restore plain "r1")) "no layout: untouched"))))
