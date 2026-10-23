(ns xi.ext.device.camera-test
  "The pure half of the browser's camera: query opts, facing, frame sizing."
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.device.camera :as camera]))

(deftest query-opts-are-clamped-and-defaulted
  (is (= {:count 1 :interval-ms 1000 :max-width 1280 :quality 0.75}
         (camera/clamp-opts nil))
      "no opts: one frame at the defaults, no facing (the open camera's wins)")
  (is (= {:count 8 :interval-ms 200 :max-width 1920 :quality 0.3 :facing "user"}
         (camera/clamp-opts {:count 50 :interval-ms 10 :max-width 9000 :quality 0 :facing "user"})))
  (is (= 3 (:count (camera/clamp-opts {:count 2.6}))) "a whole number of frames")
  (testing "a bad value falls back to its default, an unknown facing is dropped"
    (is (= {:count 1 :interval-ms 1000 :max-width 1280 :quality 0.75}
           (camera/clamp-opts {:count "3" :facing "left"})))))

(deftest the-facing-asked-for-wins-then-the-open-one-then-the-rear
  (is (= "user" (camera/effective-facing "user" "environment")))
  (is (= "user" (camera/effective-facing nil "user")) "a shared front camera stays")
  (is (= "environment" (camera/effective-facing "sideways" nil)))
  (is (= "environment" (camera/effective-facing nil nil))))

(deftest frames-scale-down-to-the-max-width-only
  (is (= [1280 720] (camera/scaled-size 1920 1080 1280)))
  (is (= [640 480] (camera/scaled-size 640 480 1280)) "never up"))

(deftest a-data-url-is-reduced-to-its-payload
  (is (= "/9j/4AAQ" (camera/strip-data-url "data:image/jpeg;base64,/9j/4AAQ")))
  (is (= "abc" (camera/strip-data-url "abc"))))

(deftest the-device-gets-a-short-name
  (is (= "iPhone" (camera/device-label "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit")))
  (is (= "Android device" (camera/device-label "Mozilla/5.0 (Linux; Android 14; Pixel 8)")))
  (is (= "Linux PC" (camera/device-label "Mozilla/5.0 (X11; Linux x86_64)")))
  (is (= "browser" (camera/device-label nil))))
