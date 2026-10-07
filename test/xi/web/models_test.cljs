(ns xi.web.models-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.models :as models]))

(def ^:private now 10000000)
(def ^:private fresh {:web/model-list ["a" "b"] :web/model-list-at (- now 1000)})
(def ^:private request [:ws/send {:type :models/web-list}])

(deftest stale?-test
  (is (models/stale? {} now) "no list")
  (is (models/stale? {:web/model-list [] :web/model-list-at now} now) "empty list")
  (is (models/stale? {:web/model-list ["a"]} now) "no timestamp")
  (is (models/stale? {:web/model-list ["a"]
                      :web/model-list-at (- now models/list-ttl-ms 1)} now) "past the TTL")
  (is (not (models/stale? fresh now))))

(deftest open-test
  (testing "a fresh cached list paints without asking the server"
    (let [{:keys [state effects]} (models/open (assoc fresh :web/model-error "x") now)]
      (is (= ["a" "b"] (:web/model-list state)))
      (is (= {:kind :model} (:web/palette-page state)))
      (is (:web/palette-open? state))
      (is (nil? (:web/model-error state)) "a fresh open clears an old error")
      (is (= [[:palette/reopen nil] [:palette/reset-filter nil]] effects)
          "re-filters, or rows a previous search hid stay hidden")))
  (testing "a stale or missing list is refetched, the stale one still painted"
    (let [stale (assoc fresh :web/model-list-at 0)
          {:keys [state effects]} (models/open stale now)]
      (is (= ["a" "b"] (:web/model-list state)))
      (is (some #{request} effects)))
    (is (some #{request} (:effects (models/open {} now))))))

(deftest list-result-test
  (testing "caches a fresh list with its timestamp"
    (let [{:keys [state effects]} (models/list-result {} {:models ["x" "y"]} now)]
      (is (= ["x" "y"] (:web/model-list state)))
      (is (= now (:web/model-list-at state)))
      (is (= [[:cache/model-list {:models ["x" "y"] :at now}]] effects))))
  (testing "an empty result (every provider failed) keeps the cached list"
    (let [{:keys [state effects]} (models/list-result fresh {:models []} now)]
      (is (= fresh state))
      (is (empty? effects))))
  (testing "an empty result with nothing cached settles on [] (not a spinner)"
    (is (= [] (:web/model-list (:state (models/list-result {} {:models []} now)))))))

(deftest pick-validation-test
  (let [picked (models/with-check {:state (assoc fresh :web/model-error "old")
                                   :effects [[:palette/close nil]]}
                                  "b")]
    (testing "a pick remembers the model and refetches"
      (is (= "b" (get-in picked [:state :web/model-check])))
      (is (nil? (get-in picked [:state :web/model-error])))
      (is (= [[:palette/close nil] request] (:effects picked))))
    (testing "still available: just cache the list"
      (let [{:keys [state effects]} (models/list-result (:state picked) {:models ["a" "b"]} now)]
        (is (nil? (:web/model-check state)))
        (is (nil? (:web/model-error state)))
        (is (not (:web/palette-open? state)))
        (is (= [[:cache/model-list {:models ["a" "b"] :at now}]] effects))))
    (testing "gone: re-open the picker on the fresh list with an error"
      (let [st (assoc (:state picked) :web/pending-room {:cwd "/p" :model "b"})
            {:keys [state effects]} (models/list-result st {:models ["a" "c"]} now)]
        (is (nil? (:web/model-check state)))
        (is (= ["a" "c"] (:web/model-list state)))
        (is (re-find #"^b is not available" (:web/model-error state)))
        (is (= {:kind :model} (:web/palette-page state)))
        (is (:web/palette-open? state))
        (is (= {:cwd "/p"} (:web/pending-room state)) "a new chat isn't born with it")
        (is (some #{[:palette/reopen nil]} effects))))
    (testing "an empty result can't decide: drop the check, no error"
      (let [{:keys [state]} (models/list-result (:state picked) {:models []} now)]
        (is (nil? (:web/model-check state)))
        (is (nil? (:web/model-error state)))))))
