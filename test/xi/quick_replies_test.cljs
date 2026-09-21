(ns xi.quick-replies-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.config :as config]
            [xi.quick-replies :as qr]))

(deftest last-assistant-text-test
  (testing "returns the final :text entry's text"
    (is (= "second"
           (qr/last-assistant-text
            [{:kind :user :text "hi"}
             {:kind :text :text "first"}
             {:kind :tool-call :tool "bash"}
             {:kind :text :text "second"}]))))
  (testing "nil when no text entry"
    (is (nil? (qr/last-assistant-text [{:kind :user :text "hi"}
                                       {:kind :tool-call :tool "bash"}])))
    (is (nil? (qr/last-assistant-text [])))
    (is (nil? (qr/last-assistant-text nil))))
  (testing "empty text is treated as absent"
    (is (nil? (qr/last-assistant-text [{:kind :text :text ""}])))))

(deftest worth-suggesting?-test
  (testing "questions pass"
    (is (qr/worth-suggesting? "Do you want me to proceed?"))
    (is (qr/worth-suggesting? "Which one: A, B, or C?")))
  (testing "decision phrases pass without a question mark"
    (is (qr/worth-suggesting? "I can proceed with the migration now."))
    (is (qr/worth-suggesting? "Would you like the short or long version."))
    (is (qr/worth-suggesting? "SHOULD I delete the branch")))
  (testing "plain statements are skipped"
    (is (not (qr/worth-suggesting? "Done. I fixed the bug in foo.cljs.")))
    (is (not (qr/worth-suggesting? "Here is the summary of the changes.")))
    (is (not (qr/worth-suggesting? "")))
    (is (not (qr/worth-suggesting? "   ")))
    (is (not (qr/worth-suggesting? nil)))))

(deftest parse-chips-test
  (testing "plain JSON object"
    (is (= [{:label "Yes" :send "yes"} {:label "No" :send "no"}]
           (qr/parse-chips
            "{\"kind\":\"yes-no\",\"chips\":[{\"label\":\"Yes\",\"send\":\"yes\"},{\"label\":\"No\",\"send\":\"no\"}]}"))))
  (testing "tolerates code fences and surrounding whitespace"
    (is (= [{:label "A" :send "use A"}]
           (qr/parse-chips
            "```json\n{\"kind\":\"choice\",\"chips\":[{\"label\":\"A\",\"send\":\"use A\"}]}\n```"))))
  (testing "kind none / empty chips → []"
    (is (= [] (qr/parse-chips "{\"kind\":\"none\",\"chips\":[]}"))))
  (testing "garbage / non-JSON → []"
    (is (= [] (qr/parse-chips "sorry, I cannot do that")))
    (is (= [] (qr/parse-chips "")))
    (is (= [] (qr/parse-chips nil))))
  (testing "drops malformed chips (missing label/send)"
    (is (= [{:label "Ok" :send "ok"}]
           (qr/parse-chips
            "{\"chips\":[{\"label\":\"Ok\",\"send\":\"ok\"},{\"label\":\"\",\"send\":\"x\"},{\"label\":\"y\"}]}"))))
  (testing "caps at 4 chips"
    (is (= 4 (count (qr/parse-chips
                     "{\"chips\":[{\"label\":\"1\",\"send\":\"1\"},{\"label\":\"2\",\"send\":\"2\"},{\"label\":\"3\",\"send\":\"3\"},{\"label\":\"4\",\"send\":\"4\"},{\"label\":\"5\",\"send\":\"5\"}]}")))))
  (testing "drops over-long labels"
    (is (= [] (qr/parse-chips
              (str "{\"chips\":[{\"label\":\"" (apply str (repeat 80 "x")) "\",\"send\":\"ok\"}]}"))))))

(def ^:private suggested (:quick-replies/suggested qr/handlers))

(deftest maybe-suggest-test
  (with-redefs [config/quick-replies? true]
   (let [room-id "r"
        base    {:rooms {room-id {:cwd "/tmp"}}}]
    (testing "clears stale chips, bumps gen, emits gen-tagged generate for a decision"
      (let [st (-> base
                   (assoc-in [:rooms room-id :quick-replies] {:chips [{:label "old" :send "old"}]})
                   (assoc-in [:rooms room-id :history]
                             [{:kind :text :text "Should I proceed?"}]))
            {:keys [state effects]} (qr/maybe-suggest st {:room-id room-id})]
        (is (= {:gen 1 :pending? true} (get-in state [:rooms room-id :quick-replies])))
        (is (= [[:quick-replies/generate {:room-id room-id :gen 1 :text "Should I proceed?"}]]
               effects))))
    (testing "gen increments from the previous generation"
      (let [st (-> base
                   (assoc-in [:rooms room-id :quick-replies] {:gen 7})
                   (assoc-in [:rooms room-id :history]
                             [{:kind :text :text "Should I proceed?"}]))
            {:keys [state effects]} (qr/maybe-suggest st {:room-id room-id})]
        (is (= {:gen 8 :pending? true} (get-in state [:rooms room-id :quick-replies])))
        (is (= 8 (:gen (second (first effects)))))))
    (testing "clears chips (keeps bumped gen) and does not generate for a statement"
      (let [st (-> base
                   (assoc-in [:rooms room-id :quick-replies] {:chips [{:label "old" :send "old"}]})
                   (assoc-in [:rooms room-id :history]
                             [{:kind :text :text "All done, no issues."}]))
            {:keys [state effects]} (qr/maybe-suggest st {:room-id room-id})]
        (is (= {:gen 1} (get-in state [:rooms room-id :quick-replies])))
        (is (empty? effects))))
    (testing "aborted turns never generate"
      (let [st (assoc-in base [:rooms room-id :history]
                         [{:kind :text :text "Should I proceed?"}])
            {:keys [effects]} (qr/maybe-suggest st {:room-id room-id :aborted? true})]
        (is (empty? effects))))
    (testing "unknown room → nil"
      (is (nil? (qr/maybe-suggest base {:room-id "nope"})))))))

(deftest suggested-test
  (let [room-id "r"
        chips   [{:label "Yes" :send "yes"}]]
    (testing "stores chips when the gen still matches the pending detection"
      (let [st {:rooms {room-id {:quick-replies {:gen 3 :pending? true}}}}
            {:keys [state]} (suggested st {:room-id room-id :gen 3 :chips chips})]
        (is (= {:gen 3 :chips chips} (get-in state [:rooms room-id :quick-replies])))))
    (testing "empty chips with a matching gen clears to just the gen"
      (let [st {:rooms {room-id {:quick-replies {:gen 3 :pending? true}}}}
            {:keys [state]} (suggested st {:room-id room-id :gen 3 :chips []})]
        (is (= {:gen 3} (get-in state [:rooms room-id :quick-replies])))))
    (testing "a stale result (gen bumped by a newer turn/submit) is dropped"
      (let [st {:rooms {room-id {:quick-replies {:gen 5}}}}]
        ;; late detection from an earlier turn (gen 3) must not re-add chips
        (is (nil? (suggested st {:room-id room-id :gen 3 :chips chips})))))
    (testing "unknown room → nil"
      (is (nil? (suggested {:rooms {}} {:room-id "nope" :gen 1 :chips chips}))))))

(deftest clear-on-submit-test
  (let [room-id "r"]
    (testing "clears chips and bumps gen when present"
      (let [st {:rooms {room-id {:quick-replies {:gen 2 :chips [{:label "y" :send "y"}]}}}}
            {:keys [state]} (qr/clear-on-submit st {:room-id room-id})]
        (is (= {:gen 3} (get-in state [:rooms room-id :quick-replies])))))
    (testing "clears a pending detection and bumps gen (invalidates in-flight)"
      (let [st {:rooms {room-id {:quick-replies {:gen 4 :pending? true}}}}
            {:keys [state]} (qr/clear-on-submit st {:room-id room-id})]
        (is (= {:gen 5} (get-in state [:rooms room-id :quick-replies])))))
    (testing "no-op when absent"
      (is (nil? (qr/clear-on-submit {:rooms {room-id {}}} {:room-id room-id}))))))
