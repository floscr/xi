(ns xi.avatar-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.avatar :as avatar]))

(deftest only-http-urls-are-avatars
  (is (avatar/url? "https://example.com/a.png"))
  (is (avatar/url? "http://localhost:8000/a.png"))
  (testing "nothing that is not a plain web image address reaches an <img src>"
    (is (not (avatar/url? "javascript:alert(1)")))
    (is (not (avatar/url? "data:image/png;base64,AAAA")))
    (is (not (avatar/url? "/relative.png")))
    (is (not (avatar/url? "https://a.com/has space.png")))
    (is (not (avatar/url? (str "https://a.com/" (apply str (repeat 3000 "x"))))))
    (is (not (avatar/url? nil)))
    (is (not (avatar/url? 42)))))

(deftest public-profile-hides-meta-and-bad-avatars
  (is (= {:name "Alice" :avatar "https://a.com/a.png"}
         (avatar/public-profile {:name "Alice" :meta {:team "ops"} :avatar "https://a.com/a.png"})))
  (is (= {:name "Bob"} (avatar/public-profile {:name "Bob" :avatar "javascript:x"})))
  (is (= {} (avatar/public-profile nil))))

(deftest profiles-covers-undeclared-ids
  (is (= {"alice" {:name "Alice"} "bob" {}}
         (avatar/profiles {"alice" {:name "Alice" :meta {:x 1}}} ["alice" "bob"]))))

(deftest initials-from-name-else-id
  (is (= "AS" (avatar/initials "alice" "Alice Smith")))
  (is (= "A" (avatar/initials "alice" "Alice")))
  (is (= "A" (avatar/initials "alice" nil)))
  (is (= "BO" (avatar/initials "bob-ops" nil)))
  (is (= "R" (avatar/initials "root" " "))))

(deftest hue-is-stable-and-in-range
  (is (= (avatar/hue "alice") (avatar/hue "alice")))
  (is (not= (avatar/hue "alice") (avatar/hue "bob")))
  (doseq [id ["root" "alice" "x" "a-very.long_id"]]
    (is (<= 0 (avatar/hue id) 359))))
