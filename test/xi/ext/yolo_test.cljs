(ns xi.ext.yolo-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.core :as ext]
            [xi.ext.yolo :as yolo]
            [xi.ext.yolo.web :as yolo-web]))

(defn- with-room [level]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms "r1"] (state/make-room "r1" {:ext {:yolo (if level {:level level} {})}}))
      (assoc :active-room "r1")))

(defn- command-handler [] (-> yolo/extension :commands first :handler))

(defn- set-handler [] (get-in yolo/extension [:handlers :ext.yolo/set]))

(deftest command-dispatches-the-level-as-an-event
  (let [handler (command-handler)
        level-of (fn [st args]
                   (let [[[_ ev]] (:effects (handler st {:room-id "r1" :args args}))]
                     (is (= :ext.yolo/set (:type ev)))
                     (:level ev)))]
    (testing "a bare /yolo toggles between off and sane"
      (is (= :sane (level-of (with-room nil) nil)))
      (is (nil? (level-of (with-room :sane) "")))
      (is (nil? (level-of (with-room :relentless) nil))))
    (testing "a level argument sets it"
      (is (= :relentless (level-of (with-room nil) "relentless")))
      (is (= :sane (level-of (with-room :relentless) " Sane ")))
      (is (nil? (level-of (with-room :relentless) "off"))))
    (testing "the command itself leaves the state alone — the event sets it"
      (let [st (with-room nil)]
        (is (= st (:state (handler st {:room-id "r1"}))))))
    (testing "an unknown argument shows the usage"
      (is (= :ui/status (get-in (handler (with-room nil) {:room-id "r1" :args "max"})
                                [:effects 0 1 :type]))))
    (testing "no room, no event"
      (is (nil? (handler (with-room nil) {:room-id "gone"}))))))

(deftest set-stores-the-level-and-reports-it
  (testing "sane, with a status entry"
    (let [{st' :state} ((set-handler) (with-room nil) {:room-id "r1" :level :sane})
          entry (last (get-in st' [:rooms "r1" :history]))]
      (is (= :sane (yolo/level st' "r1")))
      (is (= :status (:kind entry)))
      (is (re-find #"ON" (:text entry)))))
  (testing "relentless"
    (let [{st' :state} ((set-handler) (with-room :sane) {:room-id "r1" :level :relentless})]
      (is (= :relentless (yolo/level st' "r1")))
      (is (re-find #"RELENTLESS" (:text (last (get-in st' [:rooms "r1" :history])))))))
  (testing "off"
    (let [{st' :state} ((set-handler) (with-room :sane) {:room-id "r1" :level nil})]
      (is (not (yolo/enabled? st' "r1")))
      (is (not (contains? (get-in st' [:rooms "r1" :ext :yolo]) :level))
          "off leaves nothing to save")
      (is (re-find #"OFF" (:text (last (get-in st' [:rooms "r1" :history])))))))
  (testing "an unknown level is off"
    (is (nil? (yolo/level (:state ((set-handler) (with-room nil) {:room-id "r1" :level :max}))
                          "r1")))))

(deftest raising-the-level-allows-the-open-asks-it-answers
  (let [dialogs [{:id "d1" :type :confirm :message "write?"}
                 {:id "d2" :type :confirm :message "hardened" :hardened? true}
                 {:id "d3" :type :cwd-select :message "where?"}
                 {:id "d4" :type :confirm :message "outside?" :guard :sane}
                 {:id "d5" :type :confirm :message "restart?" :guard :always}]
        st      (fn [level] (assoc-in (with-room level) [:rooms "r1" :ui :dialogs] dialogs))
        answered (fn [st level]
                   (mapv #(get-in % [1 :dialog-id])
                         (:effects ((set-handler) st {:room-id "r1" :level level}))))]
    (is (= ["d1"] (answered (st nil) :sane)) "sane: the unguarded confirm")
    (is (= ["d1" "d4"] (answered (st nil) :relentless)) "relentless: all but :always")
    (is (= [] (answered (st :sane) nil)) "off answers nothing")
    (testing "the web mirror only takes the state; the server answers"
      (is (nil? (:effects ((get-in yolo-web/extension [:handlers :ext.yolo/set])
                           (st nil) {:room-id "r1" :level :sane :remote? true})))))))

(deftest level-survives-a-reaped-room
  (let [specs (:persist-room (ext/compose [yolo/extension]))
        on    (:state (yolo/set-level (with-room nil) {:room-id "r1" :level :relentless}))
        saved (ext/persisted-room-state specs (get-in on [:rooms "r1"]))]
    (is (= {:yolo {:level :relentless}} saved) "the level is saved with the chat")
    (testing "the fresh room resuming the chat gets it back, server and web mirror"
      (doseq [[label composed] [["server" (ext/compose [yolo/extension])]
                                ["web" (ext/compose [yolo-web/extension])]]]
        (let [fresh   (assoc-in (state/initial-state {:mode :standalone}) [:rooms "r2"]
                                (state/make-room "r2" {:ext (:room-ext-init composed)}))
              hydrate (get-in composed [:handlers :ext.persist/hydrate])]
          (is (= :relentless (yolo/level (:state (hydrate fresh {:room-id "r2" :slices saved})) "r2"))
              label))))
    (testing "off saves nothing"
      (let [off (:state (yolo/set-level on {:room-id "r1" :level nil}))]
        (is (= {} (ext/persisted-room-state specs (get-in off [:rooms "r1"]))))))))

(deftest web-half-applies-only-the-echo
  (let [h (get-in yolo-web/extension [:handlers :ext.yolo/set])]
    (is (= :sane (get-in (:state (h (with-room nil) {:room-id "r1" :level :sane :remote? true}))
                         [:rooms "r1" :ext :yolo :level]))
        "the server's broadcast is mirrored")
    (is (nil? (h (with-room nil) {:room-id "r1" :level :sane}))
        "a local dispatch is not applied (the server owns the level)")))

(deftest badge-shows-the-level
  (let [badge (:prompt-badge yolo/extension)]
    (is (nil? (badge (with-room nil))) "no badge when yolo is off")
    (is (= " 🔥" (badge (with-room :sane))))
    (is (= " 🔥🔥" (badge (with-room :relentless))))))

(deftest extension-has-no-tool-gate
  (testing "skipping the dialog lives in create-dialogs, not this ext"
    (is (nil? (:tool-gate yolo/extension)))))
