(ns xi.providers.zen.login-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.zen.login :as login]))

(deftest credentials-from-token-maps-the-response
  (let [now 1000000
        c   (login/credentials-from-token
             {:access_token "a" :refresh_token "r" :expires_in 3600 :org_id "org"} now nil)]
    (is (= {:access "a" :refresh "r" :expires (+ now 3600000) :org-id "org"} c))))

(deftest credentials-from-token-keeps-previous-on-refresh
  (testing "a refresh without a rotated refresh token, org or email keeps the previous ones"
    (let [prev {:access "old" :refresh "r0" :expires 1 :org-id "org" :email "me@example.com"}]
      (is (= {:access "a2" :refresh "r0" :expires 7000 :org-id "org" :email "me@example.com"}
             (login/credentials-from-token {:access_token "a2" :expires_in 7} 0 prev)))))
  (testing "a rotated refresh token replaces the previous one"
    (is (= "r1" (:refresh (login/credentials-from-token
                           {:access_token "a" :refresh_token "r1" :expires_in 1} 0 {:refresh "r0"}))))))

(deftest credentials-from-token-needs-an-access-token
  (is (thrown? js/Error (login/credentials-from-token {:error "authorization_pending"} 0 nil))))

(deftest expired-within-skew
  (testing "lapsed, or lapsing within the minute, counts as expired"
    (is (login/expired? {:expires 100000} 100000))
    (is (login/expired? {:expires 100000} 200000))
    (is (login/expired? {:expires 100000} (- 100000 30000))))
  (testing "a token with time left is not"
    (is (not (login/expired? {:expires 100000} (- 100000 120000)))))
  (testing "no expiry is expired"
    (is (login/expired? {} 0))))

(deftest verification-url-resolves-against-the-console
  (testing "an absolute URL is used as is"
    (is (= "https://opencode.ai/console/device?user_code=ABCD"
           (login/verification-url {:verification_uri_complete "https://opencode.ai/console/device?user_code=ABCD"}))))
  (testing "a relative path resolves under the console, like the OpenCode CLI"
    (is (= "https://opencode.ai/console/device?user_code=ABCD"
           (login/verification-url {:verification_uri_complete "device?user_code=ABCD"}))))
  (testing "verification_uri is the fallback"
    (is (= "https://opencode.ai/console/device"
           (login/verification-url {:verification_uri "device"})))))
