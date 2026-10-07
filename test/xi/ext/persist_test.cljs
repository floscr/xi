(ns xi.ext.persist-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.events :as events]
            [xi.ext.core :as ext]
            [xi.ext.manager :as manager]
            [xi.ext.persist :as persist]
            [xi.ext.rules :as rules-ext]
            [xi.session :as session]
            ["node:fs" :as fs]
            ["node:os" :as os]))

(def ^:private rule {:match {:tool :sh :cli "tmux"} :action {:type :allow}})

(deftest persisted-room-state-keeps-declared-keys-only
  (let [specs {:a true :b [:keep] :c [:keep]}
        room  {:ext {:a {:x 1 :empty []}
                     :b {:keep "k" :drop "d"}
                     :c {:keep nil}
                     :other {:y 2}}}]
    (is (= {:a {:x 1} :b {:keep "k"}}
           (ext/persisted-room-state specs room))
        "blank values and extensions left with nothing are not saved")))

(deftest compose-hydrates-saved-slices
  (let [composed (ext/compose [{:id :rules :persist-room true :init {:room {:rules []}}}
                               {:id :plain :init {:room {:n 1}}}])
        handlers (:handlers composed)
        st       {:rooms {"r" {:ext {:rules {:rules [{:fresh 1}]} :plain {:n 1}}}}}
        out      (events/handle-event handlers st
                                      {:type :ext.persist/hydrate :room-id "r"
                                       :slices {:rules {:rules [{:fresh 1} {:old 2}]}
                                                :plain {:n 9}}})]
    (is (= [{:fresh 1} {:old 2}] (get-in out [:state :rooms "r" :ext :rules :rules]))
        "saved entries join the room's, current first, without duplicates")
    (is (= {:n 1} (get-in out [:state :rooms "r" :ext :plain]))
        "an extension that doesn't declare :persist-room is not touched")
    (is (= {:rooms {}}
           (:state (events/handle-event handlers {:rooms {}}
                                        {:type :ext.persist/hydrate :room-id "gone"
                                         :slices {:rules {:rules [{:old 2}]}}})))
        "a room that is gone is left alone")))

(deftest room-state-survives-a-restart
  (let [dir     (fs/mkdtempSync (str (os/tmpdir) "/xi-persist-"))
        sess    {:id "s1" :_dir dir}
        mgr     (manager/seed! (manager/create) [(rules-ext/create {})])
        taps    (atom [])
        dispatched (atom [])
        app     {:dispatch! #(swap! dispatched conj %)
                 :add-tap!  #(swap! taps conj %)}
        _       (persist/install! app mgr)
        tap     (first @taps)
        fresh   {:rooms {"r" {:session sess :ext {:rules {:rules []}}}}}
        granted (:state (rules-ext/add-rule fresh {:room-id "r" :scope :session :rule rule}))
        file    (session/ext-state-sidecar-path sess)]
    ;; a room first seen only gets a baseline: nothing is written over the
    ;; saved state before the chat is resumed
    (tap {:type :room/create :room-id "r"} fresh)
    (is (not (fs/existsSync file)))
    ;; a grant is saved with the chat
    (tap {:type :ext.rules/add :room-id "r"} granted)
    (is (fs/existsSync file))
    ;; after a restart the resumed room gets it back
    (persist/install! app mgr)
    (let [tap2 (last @taps)]
      (reset! dispatched [])
      (tap2 {:type :session/resumed :room-id "r"} fresh)
      (is (= [{:type :ext.persist/hydrate :room-id "r"
               :slices {:rules {:rules [rule]}}}]
             @dispatched)))
    (testing "revoking the last grant removes the file"
      (tap {:type :ext.rules/revoke :room-id "r"} fresh)
      (is (not (fs/existsSync file))))))
