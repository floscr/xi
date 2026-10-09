(ns xi.web.appearance-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.appearance :as appearance]))

(deftest defaults-are-super-collapsed
  (testing "no overrides → super collapsed on, tool + thinking blocks collapsed"
    (let [app (appearance/effective nil)]
      (is (true? (:super-collapsed? app)))
      (is (= :collapsed (:tool-blocks app)))
      (is (= :collapsed (:thinking-blocks app)))))
  (testing "the retired viewer-mode toggle is gone: viewer mode is always on"
    (is (not (contains? (appearance/effective nil) :viewer-mode?)))
    (is (= {} (appearance/normalize {:viewer-mode? false}))
        "a stored override from before the toggle was removed is dropped"))
  (testing "every default key is present"
    (is (= (set (keys appearance/defaults))
           (set (keys (appearance/effective {})))))))

(deftest overrides-win
  (let [app (appearance/effective {:super-collapsed? false :tool-blocks :open})]
    (is (false? (:super-collapsed? app)))
    (is (= :open (:tool-blocks app)))
    (is (= :collapsed (:thinking-blocks app)) "untouched keys keep their default")))

(deftest config-layer-sits-between-defaults-and-overrides
  (let [configured {:tool-blocks :open :thinking-blocks :open}]
    (testing "configured values replace the defaults"
      (is (= :open (:tool-blocks (appearance/effective configured nil)))))
    (testing "browser overrides still win over configured values"
      (is (= :collapsed (:tool-blocks (appearance/effective configured {:tool-blocks :collapsed})))))
    (testing "invalid configured values are dropped"
      (is (= :collapsed (:tool-blocks (appearance/effective {:tool-blocks :sideways} nil)))))
    (testing "effective-in reads both layers from state"
      (is (= {:super-collapsed? true
              :tool-blocks :collapsed :thinking-blocks :open}
             (appearance/effective-in {:web/appearance-config configured
                                       :web/appearance {:tool-blocks :collapsed}}))))))

(deftest normalize-drops-garbage
  (testing "unknown keys and disallowed values are dropped"
    (is (= {:tool-blocks :open}
           (appearance/normalize {:tool-blocks :open
                                  :thinking-blocks :sideways
                                  :super-collapsed? "yes"
                                  :bogus 1}))))
  (testing "non-map input is treated as empty"
    (is (= {} (appearance/normalize nil)))
    (is (= {} (appearance/normalize "junk")))
    (is (= {} (appearance/normalize [:tool-blocks :open]))))
  (testing "a bad override never leaks into the effective map"
    (is (= :collapsed (:tool-blocks (appearance/effective {:tool-blocks :sideways}))))))

(deftest block-collapsed
  (let [open-all  {:tool-blocks :open :thinking-blocks :open}
        collapsed {:tool-blocks :collapsed :thinking-blocks :collapsed}]
    (is (false? (appearance/block-collapsed? open-all :tool-call)))
    (is (false? (appearance/block-collapsed? open-all :thinking)))
    (is (true? (appearance/block-collapsed? collapsed :tool-call)))
    (is (true? (appearance/block-collapsed? collapsed :thinking)))
    (testing "text / user / status entries are never collapsible"
      (is (false? (appearance/block-collapsed? collapsed :text)))
      (is (false? (appearance/block-collapsed? collapsed :user))))))

(deftest overridden
  (is (false? (appearance/overridden? nil)))
  (is (false? (appearance/overridden? {})))
  (is (false? (appearance/overridden? {:bogus 1})) "garbage-only overrides count as none")
  (is (true? (appearance/overridden? {:super-collapsed? false}))))
