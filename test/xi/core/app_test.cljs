(ns xi.core.app-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.log :as log]
            [xi.core.state :as state]))

(defn- test-app
  "App with synchronous render scheduling for deterministic tests."
  [& [{:keys [handlers effects on-render]}]]
  (app/create-app {:initial-state   (state/initial-state)
                   :handlers        (merge events/core-handlers handlers)
                   :effects         (or effects {})
                   :on-render       on-render
                   :schedule-render (fn [thunk] (thunk))
                   :ring            (log/create-ring 100)}))

(deftest dispatch-updates-state
  (let [{:keys [dispatch! state]} (test-app)]
    (dispatch! {:type :room/create :room-id "a"})
    (dispatch! {:type :history/append :room-id "a" :entry {:role :user :text "hi"}})
    (is (= "a" (:active-room @state)))
    (is (= 1 (count (:history (state/active-room @state)))))))

(deftest events-are-stamped
  (let [seen (atom [])
        {:keys [dispatch! add-tap!]} (test-app)]
    (add-tap! (fn [event _state] (swap! seen conj event)))
    (dispatch! {:type :room/create :room-id "a"})
    (dispatch! {:type :custom/thing})
    (is (= 2 (count @seen)))
    (is (= [1 2] (mapv :event/id @seen)))
    (is (number? (:event/ts (first @seen))))
    (is (= :custom/thing (:type (second @seen))))))

(deftest effects-run-and-dispatch-fifo
  ;; Handler for :ping returns an effect; the effect dispatches :pong.
  ;; Order must be strictly FIFO: ping processed fully before pong.
  (let [order (atom [])
        {:keys [dispatch! add-tap!]}
        (test-app {:handlers {:ping (fn [_ _] {:effects [[:fx/pong {:n 1}]]})
                              :pong (fn [_ _] nil)}
                   :effects  {:fx/pong (fn [{:keys [dispatch!]} payload]
                                         (dispatch! {:type :pong :n (:n payload)}))}})]
    (add-tap! (fn [ev _] (swap! order conj (:type ev))))
    (dispatch! {:type :ping})
    (is (= [:ping :pong] @order))))

(deftest late-bound-handlers-and-effects-follow-the-current-set
  ;; :handlers / :effects as 0-arg fns are re-read per event / effect, so a
  ;; live extension reload swaps the set without rebuilding the app.
  (let [said (atom [])
        hs   (atom {:ping (fn [_ _] {:effects [[:fx/say {:w "old"}]]})})
        fxs  (atom {:fx/say (fn [_ {:keys [w]}] (swap! said conj [:old w]))})
        {:keys [dispatch!]}
        (app/create-app {:initial-state   (state/initial-state)
                         :handlers        (fn [] (merge events/core-handlers @hs))
                         :effects         (fn [] @fxs)
                         :schedule-render (fn [thunk] (thunk))
                         :ring            (log/create-ring 100)})]
    (dispatch! {:type :ping})
    (is (= [[:old "old"]] @said))
    (reset! hs  {:ping (fn [_ _] {:effects [[:fx/say {:w "new"}]]})})
    (reset! fxs {:fx/say (fn [_ {:keys [w]}] (swap! said conj [:new w]))})
    (dispatch! {:type :ping})
    (is (= [[:old "old"] [:new "new"]] @said))
    (testing "the built-in :app/dispatch still works with a replaced effect set"
      (reset! hs {:bounce (fn [_ _] {:effects [[:app/dispatch {:type :landed}]]})
                  :landed (fn [_ _] {:effects [[:fx/say {:w "landed"}]]})})
      (dispatch! {:type :bounce})
      (is (= [:new "landed"] (peek @said))))))

(deftest reentrant-dispatch-is-queued
  ;; An effect dispatching two events mid-drain must not interleave.
  (let [order (atom [])
        {:keys [dispatch! add-tap!]}
        (test-app {:handlers {:a (fn [_ _] {:effects [[:fx/multi nil]]})}
                   :effects  {:fx/multi (fn [{:keys [dispatch!]} _]
                                          (dispatch! {:type :b})
                                          (dispatch! {:type :c}))}})]
    (add-tap! (fn [ev _] (swap! order conj (:type ev))))
    (dispatch! {:type :a})
    (is (= [:a :b :c] @order))))

(deftest render-coalesced-and-change-gated
  (let [renders (atom 0)
        app     (test-app {:on-render (fn [_ _] (swap! renders inc))
                           :handlers  {:burst (fn [st _]
                                                {:effects [[:fx/burst nil]]
                                                 :state   (assoc st :touched true)})}
                           :effects   {:fx/burst (fn [{:keys [dispatch!]} _]
                                                   (dispatch! {:type :room/create :room-id "x"})
                                                   (dispatch! {:type :room/create :room-id "y"}))}})
        {:keys [dispatch!]} app]
    (testing "one render per drained batch, not per event"
      (dispatch! {:type :burst})
      (is (= 1 @renders)))
    (testing "no re-render when state did not change"
      (dispatch! {:type :unknown/noop})
      (is (= 1 @renders))
      (dispatch! {:type :render/done :ms 3})
      (is (= 1 @renders)))
    (testing "next change renders again"
      (dispatch! {:type :room/switch :room-id "y"})
      (is (= 2 @renders)))))

(deftest handler-errors-do-not-corrupt-state
  (let [{:keys [dispatch! state]}
        (test-app {:handlers {:boom (fn [_ _] (throw (js/Error. "kaput")))}})]
    (dispatch! {:type :room/create :room-id "a"})
    (dispatch! {:type :boom})
    (dispatch! {:type :room/create :room-id "b"})
    (is (= ["a" "b"] (sort (state/room-ids @state))))))

(deftest runaway-drain-is-broken-and-server-recovers
  ;; A self-feeding dispatch cycle (handler → effect → same event) would peg
  ;; the single-threaded loop forever. The watchdog must break the drain, report
  ;; it via :on-runaway, and leave the app usable (processing reset, queue clear)
  ;; so new events — e.g. creating a session — still work.
  (let [reported (atom nil)
        {:keys [dispatch! state]}
        (app/create-app
         {:initial-state    (state/initial-state)
          :handlers         (merge events/core-handlers
                                   {:loop (fn [_ _] {:effects [[:fx/loop nil]]})})
          :effects          {:fx/loop (fn [{:keys [dispatch!]} _]
                                        (dispatch! {:type :loop}))}
          :schedule-render  (fn [thunk] (thunk))
          :runaway-batch-ms 20
          :on-runaway       (fn [msg] (reset! reported msg))
          :ring             (log/create-ring 100)})]
    (dispatch! {:type :loop})
    (testing "the livelock was detected and reported"
      (is (string? @reported))
      (is (re-find #"livelock" @reported))
      (is (re-find #":loop" @reported)))
    (testing "the app recovered — new events are still processed"
      (dispatch! {:type :room/create :room-id "after"})
      (is (= ["after"] (state/room-ids @state))))))

(deftest taps-can-unsubscribe
  (let [seen (atom 0)
        {:keys [dispatch! add-tap!]} (test-app)
        untap (add-tap! (fn [_ _] (swap! seen inc)))]
    (dispatch! {:type :x})
    (untap)
    (dispatch! {:type :y})
    (is (= 1 @seen))))
