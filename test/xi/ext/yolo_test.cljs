(ns xi.ext.yolo-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.core :as ext]
            [xi.ext.yolo :as yolo]
            [xi.ext.yolo.web :as yolo-web]))

(defn- with-room [enabled?]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms "r1"] (state/make-room "r1" {:ext {:yolo {:enabled? enabled?}}}))
      (assoc :active-room "r1")))

(defn- toggle-handler [] (-> yolo/extension :commands first :handler))

(deftest toggle-dispatches-the-flipped-flag-as-an-event
  (let [handler (toggle-handler)]
    (testing "off → on"
      (let [{st' :state fx :effects} (handler (with-room false) {:room-id "r1"})]
        (is (= [[:app/dispatch {:type :ext.yolo/set :room-id "r1" :enabled? true}]] fx))
        (is (false? (get-in st' [:rooms "r1" :ext :yolo :enabled?]))
            "the command itself leaves the state alone — the event flips it")))
    (testing "on → off"
      (let [{fx :effects} (handler (with-room true) {:room-id "r1"})]
        (is (= [[:app/dispatch {:type :ext.yolo/set :room-id "r1" :enabled? false}]] fx))))
    (testing "no room, no event"
      (is (nil? (handler (with-room false) {:room-id "gone"}))))))

(deftest set-flips-the-flag-and-reports-it
  (let [set-handler (get-in yolo/extension [:handlers :ext.yolo/set])]
    (testing "on, with a status entry"
      (let [{st' :state} (set-handler (with-room false) {:room-id "r1" :enabled? true})
            entry (last (get-in st' [:rooms "r1" :history]))]
        (is (true? (get-in st' [:rooms "r1" :ext :yolo :enabled?])))
        (is (= :status (:kind entry)))
        (is (re-find #"ON" (:text entry)))))
    (testing "off"
      (let [{st' :state} (set-handler (with-room true) {:room-id "r1" :enabled? false})]
        (is (not (yolo/enabled? st' "r1")))
        (is (not (contains? (get-in st' [:rooms "r1" :ext :yolo]) :enabled?))
            "off leaves nothing to save")
        (is (re-find #"OFF" (:text (last (get-in st' [:rooms "r1" :history])))))))))

(deftest flag-survives-a-reaped-room
  (let [specs (:persist-room (ext/compose [yolo/extension]))
        on    (:state (yolo/set-enabled (with-room false) {:room-id "r1" :enabled? true}))
        saved (ext/persisted-room-state specs (get-in on [:rooms "r1"]))]
    (is (= {:yolo {:enabled? true}} saved) "on is saved with the chat")
    (testing "the fresh room resuming the chat gets it back, server and web mirror"
      (doseq [[label composed] [["server" (ext/compose [yolo/extension])]
                                ["web" (ext/compose [yolo-web/extension])]]]
        (let [fresh   (assoc-in (state/initial-state {:mode :standalone}) [:rooms "r2"]
                                (state/make-room "r2" {:ext (:room-ext-init composed)}))
              hydrate (get-in composed [:handlers :ext.persist/hydrate])]
          (is (yolo/enabled? (:state (hydrate fresh {:room-id "r2" :slices saved})) "r2")
              label))))
    (testing "off saves nothing"
      (let [off (:state (yolo/set-enabled on {:room-id "r1" :enabled? false}))]
        (is (= {} (ext/persisted-room-state specs (get-in off [:rooms "r1"]))))))))

(deftest web-half-applies-only-the-echo
  (let [h (get-in yolo-web/extension [:handlers :ext.yolo/set])]
    (is (true? (get-in (:state (h (with-room false) {:room-id "r1" :enabled? true :remote? true}))
                       [:rooms "r1" :ext :yolo :enabled?]))
        "the server's broadcast is mirrored")
    (is (nil? (h (with-room false) {:room-id "r1" :enabled? true}))
        "a local dispatch is not applied (the server owns the flag)")))

(deftest badge-shows-only-when-on
  (let [badge (:prompt-badge yolo/extension)]
    (is (nil? (badge (with-room false))) "no badge when yolo is off")
    (is (string? (badge (with-room true))) "a badge when yolo is on")))

(deftest extension-has-no-tool-gate
  (testing "skipping the dialog lives in create-dialogs, not this ext"
    (is (nil? (:tool-gate yolo/extension)))))
