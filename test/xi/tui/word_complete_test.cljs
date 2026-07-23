(ns xi.tui.word-complete-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.word-complete :as wc]))

(defn- hist [& texts]
  (mapv (fn [t] {:kind :text :text t}) texts))

(deftest no-history
  (is (nil? (wc/complete "foo" [])))
  (is (nil? (wc/complete "" (hist "foobar")))))

(deftest single-match-inserts-suffix
  (is (= {:action :insert :text "bar"}
         (wc/complete "foo" (hist "foobar baz")))))

(deftest excludes-the-token-itself
  ;; "foo" appears verbatim but nothing extends it → no completion
  (is (nil? (wc/complete "foo" (hist "foo foo foo")))))

(deftest case-insensitive-prefix
  (is (= {:action :insert :text "obar"}
         (wc/complete "Fo" (hist "foobar")))))

(deftest multiple-matches-return-menu
  (let [r (wc/complete "co" (hist "complete config connection"))]
    (is (= :menu (:action r)))
    (is (= 3 (count (:items r))))))

(deftest frequency-beats-a-lone-recent-word
  ;; The reported bug: "complete" is mentioned repeatedly, "compiles" appears
  ;; once and most recently. Completing "comp" should surface "complete", not
  ;; the incidental recent "compiles".
  (let [h (hist "complete completion"
                "complete works"
                "the feature is complete"
                "everything compiles")
        labels (mapv :label (:items (wc/complete "comp" h)))]
    (is (= "complete" (first labels)))
    (is (some #{"compiles"} labels))))

(deftest ranks-recent-words-first
  ;; "connection" is in the newest entry, "complete" in the oldest → connection ranks first
  (let [r (wc/complete "co" (hist "complete" "config" "connection"))
        labels (mapv :label (:items r))]
    (is (= "connection" (first labels)))
    (is (= 3 (count labels)))))

(deftest recency-beats-length-and-alpha
  ;; both share prefix; the one in the later entry wins regardless of length
  (let [r (wc/complete "ap" (hist "apple" "apex"))
        labels (mapv :label (:items r))]
    (is (= "apex" (first labels)))))

(deftest ignores-non-text-entries
  (is (nil? (wc/complete "foo"
                         [{:kind :tool-call :tool "bash" :arguments {:command "foobar"}}
                          {:kind :error :error {:message "foobaz"}}]))))

(deftest pulls-from-user-and-thinking-entries
  (let [h [{:kind :user :text "deploy production"}
           {:kind :thinking :text "deployment plan"}]
        r (wc/complete "dep" h)]
    (is (= :menu (:action r)))
    ;; "deployment" (thinking, newer) ranks before "deploy" (user, older)
    (is (= "deployment" (:label (first (:items r)))))))

(deftest candidates-returns-ranked-vector
  ;; The inline-cycle contract: a plain vector of full words, most-recent first.
  (is (= [] (wc/candidates "foo" [])))
  (is (= [] (wc/candidates "" (hist "foobar"))))
  (is (= ["connection" "config" "complete"]
         (wc/candidates "co" (hist "complete" "config" "connection")))))

(deftest menu-item-insert-is-suffix-after-token
  (let [r (wc/complete "con" (hist "config" "connection"))]
    (is (every? (fn [{:keys [label insert]}]
                  (= label (str "con" insert)))
                (:items r)))))

(deftest candidates-returns-ranked-word-vector
  (is (= [] (wc/candidates "" (hist "foobar"))))
  (is (= [] (wc/candidates "zzz" (hist "foobar"))))
  ;; plain vector of full words, most-recent first
  (is (= ["connection" "config" "complete"]
         (wc/candidates "co" (hist "complete" "config" "connection"))))
  ;; the token itself is never a candidate
  (is (= [] (wc/candidates "foo" (hist "foo foo")))))
