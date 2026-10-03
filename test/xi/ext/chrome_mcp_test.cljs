(ns xi.ext.chrome-mcp-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.chrome-mcp :as cm]))

(def ^:private coerce-emulate-args #'cm/coerce-emulate-args)
(def ^:private viewport-has-dims? #'cm/viewport-has-dims?)

(deftest viewport-has-dims?-detects-numeric-dimensions
  (testing "valid WxH strings"
    (is (viewport-has-dims? "1280x720"))
    (is (viewport-has-dims? "375x667x2"))
    (is (viewport-has-dims? "375x667,mobile,touch")))
  (testing "missing or non-numeric dimensions"
    (is (not (viewport-has-dims? "mobile")))
    (is (not (viewport-has-dims? "1280")))
    (is (not (viewport-has-dims? "widthxheight")))
    (is (not (viewport-has-dims? "")))
    (is (not (viewport-has-dims? nil)))))

(deftest coerce-passes-through-valid-viewport
  (testing "a canonical viewport string is left untouched"
    (is (= {:args {:viewport "1280x720"}}
           (coerce-emulate-args {:viewport "1280x720"})))
    (is (= {:args {:viewport "375x667,mobile,touch"}}
           (coerce-emulate-args {:viewport "375x667,mobile,touch"})))))

(deftest coerce-assembles-from-separate-dimensions
  (testing "width/height as separate fields become a viewport string"
    (is (= {:args {:viewport "1280x720"}}
           (coerce-emulate-args {:width 1280 :height 720}))))
  (testing "numeric strings are accepted"
    (is (= {:args {:viewport "1280x720"}}
           (coerce-emulate-args {:width "1280" :height "720"}))))
  (testing "device scale factor and mobile flags are folded in"
    (is (= {:args {:viewport "375x667x2,mobile,touch"}}
           (coerce-emulate-args {:width 375 :height 667
                                 :deviceScaleFactor 2 :mobile true :touch true})))
    (is (= {:args {:viewport "375x667,mobile"}}
           (coerce-emulate-args {:width 375 :height 667 :isMobile true}))))
  (testing "other emulate params survive alongside the assembled viewport"
    (is (= {:args {:colorScheme "dark" :viewport "1280x720"}}
           (coerce-emulate-args {:colorScheme "dark" :width 1280 :height 720})))))

(deftest coerce-rejects-dimensionless-viewport-intent
  (testing "a bare tag like \"mobile\" is rejected with guidance"
    (let [{:keys [error]} (coerce-emulate-args {:viewport "mobile"})]
      (is (:is-error error))
      (is (re-find #"numeric dimensions" (-> error :content first :text)))))
  (testing "flags with no dimensions are rejected"
    (is (:error (coerce-emulate-args {:mobile true}))))
  (testing "a lone width with no height is rejected"
    (is (:error (coerce-emulate-args {:width 375})))))

(deftest coerce-leaves-non-viewport-calls-alone
  (testing "calls that set only non-viewport params are forwarded unchanged"
    (is (= {:args {:networkConditions "Slow 3G"}}
           (coerce-emulate-args {:networkConditions "Slow 3G"})))
    (is (= {:args {:colorScheme "dark"}}
           (coerce-emulate-args {:colorScheme "dark"})))
    (is (= {:args {}}
           (coerce-emulate-args {})))))

(deftest saved-screenshot-path-parses-the-caption
  (is (= "/tmp/x.png" (cm/saved-screenshot-path "Took a screenshot of the current page's viewport.\nSaved screenshot to /tmp/x.png.")))
  (is (= "/tmp/a-b.jpeg" (cm/saved-screenshot-path "Saved screenshot to /tmp/a-b.jpeg.")))
  (is (nil? (cm/saved-screenshot-path "Took a screenshot of the current page's viewport."))))

(deftest attach-saved-screenshot-passes-through-when-nothing-to-attach
  (testing "errors, missing files and results that already carry an image are untouched"
    (let [saved {:content [{:type "text" :text "Saved screenshot to /tmp/xi-does-not-exist.png."}]}]
      (is (= saved (cm/attach-saved-screenshot saved "/tmp")))
      (is (= (assoc saved :is-error true)
             (cm/attach-saved-screenshot (assoc saved :is-error true) "/tmp")))
      (let [with-img (update saved :content conj {:type "image" :data "x" :mimeType "image/png"})]
        (is (= with-img (cm/attach-saved-screenshot with-img "/tmp")))))))
