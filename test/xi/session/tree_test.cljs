(ns xi.session.tree-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session.tree :as tree]))

(defn- make-tree
  "Create an in-memory tree (no file persistence)."
  []
  (tree/create {:session-id "test-session" :cwd "/tmp" :filepath nil}))

(deftest create-tree
  (testing "creates empty tree with nil leaf"
    (let [t (make-tree)]
      (is (nil? (tree/get-leaf-id t)))
      (is (empty? (tree/get-entries t))))))

(deftest append-single
  (testing "appending sets leaf and stores entry"
    (let [t (make-tree)
          id (tree/append! t {:type "user-message" :text "hello"})]
      (is (some? id))
      (is (= id (tree/get-leaf-id t)))
      (is (= 1 (count (tree/get-entries t))))
      (let [entry (tree/get-entry t id)]
        (is (= "user-message" (:type entry)))
        (is (= "hello" (:text entry)))
        (is (nil? (:parentId entry)))))))

(deftest append-chain
  (testing "appending multiple creates a chain"
    (let [t (make-tree)
          id1 (tree/append! t {:type "user-message" :text "hello"})
          id2 (tree/append! t {:type "assistant-text" :text "hi"})
          id3 (tree/append! t {:type "user-message" :text "how are you"})]
      (is (= id3 (tree/get-leaf-id t)))
      (is (= 3 (count (tree/get-entries t))))
      ;; Check parent chain
      (is (nil? (:parentId (tree/get-entry t id1))))
      (is (= id1 (:parentId (tree/get-entry t id2))))
      (is (= id2 (:parentId (tree/get-entry t id3)))))))

(deftest get-branch-linear
  (testing "get-branch returns root→leaf path"
    (let [t (make-tree)
          id1 (tree/append! t {:type "user-message" :text "a"})
          id2 (tree/append! t {:type "assistant-text" :text "b"})
          id3 (tree/append! t {:type "user-message" :text "c"})
          branch (tree/get-branch t)]
      (is (= 3 (count branch)))
      (is (= [id1 id2 id3] (mapv :id branch))))))

(deftest branch-and-fork
  (testing "branching creates a fork"
    (let [t (make-tree)
          id1 (tree/append! t {:type "user-message" :text "a"})
          id2 (tree/append! t {:type "assistant-text" :text "b"})
          _id3 (tree/append! t {:type "user-message" :text "c"})]
      ;; Branch back to id2
      (tree/branch! t id2)
      (is (= id2 (tree/get-leaf-id t)))
      ;; New message creates a branch
      (let [id4 (tree/append! t {:type "user-message" :text "d"})
            branch (tree/get-branch t)]
        ;; Branch should be id1 → id2 → id4
        (is (= 3 (count branch)))
        (is (= [id1 id2 id4] (mapv :id branch)))
        ;; Original branch (id1 → id2 → id3) is still intact
        (is (= 4 (count (tree/get-entries t))))))))

(deftest get-tree-structure
  (testing "get-tree builds correct tree"
    (let [t (make-tree)
          id1 (tree/append! t {:type "user-message" :text "a"})
          id2 (tree/append! t {:type "assistant-text" :text "b"})
          _id3 (tree/append! t {:type "user-message" :text "c"})]
      ;; Branch from id1
      (tree/branch! t id1)
      (let [_id4 (tree/append! t {:type "user-message" :text "d"})
            tree-nodes (tree/get-tree t)]
        ;; One root (id1) with two children (id2, id4)
        (is (= 1 (count tree-nodes)))
        (let [root (first tree-nodes)]
          (is (= id1 (get-in root [:entry :id])))
          (is (= 2 (count (:children root)))))))))

(deftest get-children
  (testing "get-children returns direct children"
    (let [t (make-tree)
          id1 (tree/append! t {:type "user-message" :text "a"})
          _id2 (tree/append! t {:type "assistant-text" :text "b"})
          _ (tree/branch! t id1)
          _id3 (tree/append! t {:type "user-message" :text "c"})]
      ;; id1 has two children
      (is (= 2 (count (tree/get-children t id1)))))))

(deftest build-message-context
  (testing "builds LLM-compatible message list from branch"
    (let [t (make-tree)
          _ (tree/append! t {:type "user-message" :text "hello"})
          _ (tree/append! t {:type "assistant-text" :text "hi there"})
          _ (tree/append! t {:type "tool-use" :name "bash" :arguments {:command "ls"}})
          _ (tree/append! t {:type "tool-result" :content "file1\nfile2"})
          _ (tree/append! t {:type "user-message" :text "thanks"})
          ctx (tree/build-message-context t)]
      ;; Should only include user and assistant messages
      (is (= 3 (count ctx)))
      (is (= [{:role "user" :content "hello"}
              {:role "assistant" :content "hi there"}
              {:role "user" :content "thanks"}]
             ctx)))))

(deftest user-messages
  (testing "lists all user messages from tree"
    (let [t (make-tree)
          id1 (tree/append! t {:type "user-message" :text "first"})
          _ (tree/append! t {:type "assistant-text" :text "response"})
          id3 (tree/append! t {:type "user-message" :text "second"})
          msgs (tree/user-messages t)]
      (is (= 2 (count msgs)))
      (is (= [{:entry-id id1 :text "first"}
              {:entry-id id3 :text "second"}]
             msgs)))))

(deftest reset-leaf
  (testing "reset-leaf allows new root"
    (let [t (make-tree)
          _ (tree/append! t {:type "user-message" :text "a"})
          _ (tree/reset-leaf! t)]
      (is (nil? (tree/get-leaf-id t)))
      (let [id2 (tree/append! t {:type "user-message" :text "b"})]
        ;; New entry should have nil parentId (new root)
        (is (nil? (:parentId (tree/get-entry t id2))))))))

(deftest branch-invalid-id-throws
  (testing "branching to nonexistent id throws"
    (let [t (make-tree)]
      (is (thrown? js/Error (tree/branch! t "nonexistent"))))))
