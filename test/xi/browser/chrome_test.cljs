(ns xi.browser.chrome-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.browser.chrome :as chrome]))

(deftest ua-override-presents-regular-chrome
  (let [ua "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/131.0.6778.85 Safari/537.36"
        {:keys [userAgent userAgentMetadata]} (chrome/ua-override ua)]
    (testing "the headless token and the Pi's arch are normalized"
      (is (= "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.85 Safari/537.36"
             userAgent)))
    (testing "client hints claim Google Chrome at the same version"
      (is (= [{:brand "Google Chrome" :version "131"}
              {:brand "Chromium" :version "131"}
              {:brand "Not_A Brand" :version "24"}]
             (:brands userAgentMetadata)))
      (is (= "131.0.6778.85" (:fullVersion userAgentMetadata)))
      (is (= "Linux" (:platform userAgentMetadata)))
      (is (false? (:mobile userAgentMetadata))))))

(deftest ua-override-keeps-a-real-chrome-and-skips-unknown
  (is (= "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
         (:userAgent (chrome/ua-override "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"))))
  (is (= "macOS" (get-in (chrome/ua-override "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/130.0.0.0 Safari/537.36")
                         [:userAgentMetadata :platform])))
  (is (nil? (chrome/ua-override "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0")))
  (is (nil? (chrome/ua-override nil))))
