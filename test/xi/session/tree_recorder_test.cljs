(ns xi.session.tree-recorder-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session.tree :as tree]
            [xi.session.tree-recorder :as recorder]))

(defn- make-recorder []
  (let [t (tree/create {:session-id "test" :cwd "/tmp" :filepath nil})]
    (recorder/create t)))

(defn- feed! [rec events]
  (doseq [e events]
    ((:on-event rec) e)))

(defn- current-tree [rec]
  @(:tree-ref rec))

(deftest records-user-message
  (testing "user-message event creates tree entry"
    (let [rec (make-recorder)]
      (feed! rec [{:type :user-message :text "hello"}])
      (let [entries (tree/get-entries (current-tree rec))]
        (is (= 1 (count entries)))
        (is (= "user-message" (:type (first entries))))
        (is (= "hello" (:text (first entries))))))))

(deftest records-full-turn
  (testing "full turn creates proper tree chain"
    (let [rec (make-recorder)]
      (feed! rec [{:type :user-message :text "help"}
                  {:type :turn-start}
                  {:type :text-delta :text "Sure, "}
                  {:type :text-delta :text "I can help."}
                  {:type :turn-end :usage {:input 10 :output 20} :cost 0.01}])
      (let [entries (tree/get-entries (current-tree rec))]
        ;; user-message, assistant-text (flushed at turn-end), turn-end
        (is (= 3 (count entries)))
        (is (= "user-message" (:type (nth entries 0))))
        (is (= "assistant-text" (:type (nth entries 1))))
        (is (= "Sure, I can help." (:text (nth entries 1))))
        (is (= "turn-end" (:type (nth entries 2))))))))

(deftest records-tool-calls
  (testing "tool calls flush text and create tool entries"
    (let [rec (make-recorder)]
      (feed! rec [{:type :user-message :text "list files"}
                  {:type :turn-start}
                  {:type :text-delta :text "Let me check."}
                  {:type :tool-start :id "tc1" :name "bash" :arguments {:command "ls"}}
                  {:type :tool-result :id "bash" :content "file1\nfile2" :is-error false}
                  {:type :turn-end :usage {} :cost nil}])
      (let [entries (tree/get-entries (current-tree rec))
            types (mapv :type entries)]
        ;; user-message, assistant-text, tool-use, tool-result, turn-end
        (is (= ["user-message" "assistant-text" "tool-use" "tool-result" "turn-end"]
               types))))))

(deftest reset-replaces-tree
  (testing "reset! replaces underlying tree"
    (let [rec (make-recorder)]
      (feed! rec [{:type :user-message :text "old"}])
      (is (= 1 (count (tree/get-entries (current-tree rec)))))
      ;; Reset to new tree
      ((:reset! rec) (tree/create {:session-id "new" :cwd "/tmp" :filepath nil}))
      (is (= 0 (count (tree/get-entries (current-tree rec)))))
      ;; New events go to new tree
      (feed! rec [{:type :user-message :text "new"}])
      (is (= 1 (count (tree/get-entries (current-tree rec)))))
      (is (= "new" (:text (first (tree/get-entries (current-tree rec)))))))))
