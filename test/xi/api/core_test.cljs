(ns xi.api.core-test
  "xi.api.core/caller — the extension behind a ctx is whoever holds the token
   the loader issued, never whatever :extension says."
  (:require [cljs.test :refer [deftest is testing]]
            [xi.api.core :as core]))

(deftest caller-is-resolved-from-the-token-not-the-id
  (let [t (core/issue-token! :mine)]
    (is (= :mine (core/caller {:extension :mine :xi.api/token t})))
    (is (= :mine (core/caller {:xi.api/token t})) "the :extension label is optional")
    (testing "no token — no capability"
      (is (thrown-with-msg? js/Error #"not an extension ctx"
                            (core/caller {:extension :mine}))))
    (testing "a ctx relabelled with another id is refused, never resolved to it"
      (is (thrown-with-msg? js/Error #"issued to extension mine, not other"
                            (core/caller {:extension :other :xi.api/token t}))))
    (testing "a look-alike object is not the token"
      (is (thrown-with-msg? js/Error #"not an extension ctx"
                            (core/caller {:extension :mine
                                          :xi.api/token (js/Object.freeze #js {})}))))
    (testing "a non-object token can't crash the lookup"
      (is (thrown-with-msg? js/Error #"not an extension ctx"
                            (core/caller {:extension :mine :xi.api/token "mine"}))))))

(deftest base-cwd-goes-through-the-verified-caller
  (let [mine (core/issue-token! :shop)]
    (is (= "/room" (core/base-cwd {:cwd "/room" :extension :shop :xi.api/token mine})))
    (is (thrown-with-msg? js/Error #"not an extension ctx"
                          (core/base-cwd {:extension :shop}))
        "without a room cwd the data dir is the caller's, so the caller must be proven")))
