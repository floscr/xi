(ns xi.buffers-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.buffers :as buffers]))

(deftest ids-and-kinds
  (is (= "file:src/a.cljs" (buffers/file-id "src/a.cljs")))
  (is (= "diff:git" (buffers/diff-id "git")))
  (is (= "diff:session-edits" (buffers/diff-id nil))
      "the default session diff has a stable id")
  (is (= :file (buffers/kind "file:x" {:kind :file})))
  (is (= :prompt (buffers/kind :prompt {:title "System Prompt"}))
      "a singleton's keyword id is its kind")
  (is (nil? (buffers/kind "x" {}))))

(deftest labels-and-order
  (let [bufs {"file:b" {:kind :file :title "b" :opened-at 20}
              "diff:git" {:kind :diff :title "All Git Changes" :opened-at 10}
              :prompt {:title "System Prompt"}}]
    (is (= [:prompt "diff:git" "file:b"] (map first (buffers/ordered bufs)))
        "opening order; a missing :opened-at sorts first")
    (is (= [{:id :prompt :kind :prompt :title "System Prompt"}
            {:id "diff:git" :kind :diff :title "All Git Changes"}
            {:id "file:b" :kind :file :title "b"}]
           (buffers/summaries bufs)))
    (is (= "keys" (buffers/label :keys {})) "no title → the id")))

(deftest install-close
  (let [room (-> {:ui {:buffers {} :active-buffer :chat}}
                 (buffers/install "file:a" {:kind :file :title "a"} 100)
                 (buffers/install "file:b" {:kind :file :title "b"} 200)
                 (assoc-in [:ui :active-buffer] "file:a"))]
    (is (= 100 (get-in (buffers/install room "file:a" {:kind :file :title "a2"} 300)
                       [:ui :buffers "file:a" :opened-at]))
        "a refresh keeps the buffer's place in the order")
    (is (= "a2" (get-in (buffers/install room "file:a" {:kind :file :title "a2"} 300)
                        [:ui :buffers "file:a" :title])))
    (testing "close"
      (let [r (buffers/close room "file:a")]
        (is (= ["file:b"] (keys (get-in r [:ui :buffers]))))
        (is (= :chat (get-in r [:ui :active-buffer])) "closing the viewed one shows the chat"))
      (let [r (buffers/close room "file:b")]
        (is (= "file:a" (get-in r [:ui :active-buffer])) "closing another leaves the view")))
    (testing "close-all"
      (let [r (buffers/close-all room)]
        (is (= {} (get-in r [:ui :buffers])))
        (is (= :chat (get-in r [:ui :active-buffer])))))))

(deftest switch-here
  (is (buffers/switch-here? {} {:client-id "c1"}) "no own id: standalone / server")
  (is (buffers/switch-here? {:connection {:client-id "c1"}} {}) "no originator")
  (is (buffers/switch-here? {:connection {:client-id "c1"}} {:client-id "c1"}))
  (is (not (buffers/switch-here? {:connection {:client-id "c1"}} {:client-id "c2"}))))


(deftest viewers-from-members
  (is (= {:chat ["alice"] "diff:git" ["bob"]}
         (buffers/viewers {:members {"c1" {:user "alice"}
                                     "c2" {:user "bob" :buffer "diff:git"}}}))
      "no :buffer means the chat")
  (is (= {"file:a" ["alice"]}
         (buffers/viewers {:members {"c1" {:user "alice" :buffer "file:a"}
                                     "c2" {:user "alice" :buffer "file:a"}}}))
      "two devices of one user count once")
  (is (= {} (buffers/viewers {}))))
