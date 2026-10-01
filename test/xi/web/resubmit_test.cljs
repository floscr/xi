(ns xi.web.resubmit-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.resubmit :as resubmit]))

;; ── fork-effects (bubble Retry / Edit-save) ──────────────────────────────────

(def ^:private imgs
  [{:media-type "image/png" :data "AAAA" :name "shot.png"}])

(defn- submit-event [effects]
  (let [[[_ nav] [_ submit]] effects]
    (is (= :tree/navigate (:type nav)))
    submit))

(deftest resubmit-carries-images-into-joined-room
  (let [fx (resubmit/fork-effects {:room-id "r" :sid "s" :index 3
                                   :text "look" :images imgs})
        submit (submit-event fx)]
    (is (= {:type :tree/navigate :room-id "r" :index 3} (second (first fx))))
    (is (= :input/submit (:type submit)))
    (is (= "r" (:room-id submit)))
    (is (= "look" (:text submit)))
    (is (= imgs (:images submit)) "attachments ride along, not just the text")
    (is (not (contains? submit :model)) "no model unless picked")))

(deftest resubmit-carries-images-when-room-not-joined
  (let [submit (submit-event
                (resubmit/fork-effects {:room-id nil :sid "s" :index 1
                                        :text "look" :images imgs
                                        :model "claude-opus-5-5"}))]
    (is (= :submit/pending (:type submit)))
    (is (= "s" (:session-id submit)))
    (is (= imgs (:images submit)))
    (is (= "claude-opus-5-5" (:model submit)))))

(deftest resubmit-without-images-stays-text-only
  (testing "nil and empty image seqs don't add an :images key"
    (doseq [images [nil []]]
      (let [submit (submit-event
                    (resubmit/fork-effects {:room-id "r" :sid "s" :index 0
                                            :text "hi" :images images}))]
        (is (= {:type :input/submit :room-id "r" :text "hi"} submit))))))

(deftest resubmit-images-only-prompt
  (testing "a blank-text, images-only message still resubmits its images"
    (let [submit (submit-event
                  (resubmit/fork-effects {:room-id "r" :sid "s" :index 2
                                          :text "" :images (seq imgs)}))]
      (is (= "" (:text submit)))
      (is (vector? (:images submit)) "seqs are normalised to a vector for the wire")
      (is (= imgs (:images submit))))))
