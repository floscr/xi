(ns xi.server.files-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.server.files :as files]))

(deftest image-media-type-test
  (testing "the browser-viewable image extensions, case-insensitive"
    (is (= "image/png" (files/image-media-type "/tmp/streaks_blender.png")))
    (is (= "image/jpeg" (files/image-media-type "shot.JPG")))
    (is (= "image/jpeg" (files/image-media-type "a/b.jpeg")))
    (is (= "image/svg+xml" (files/image-media-type "icon.svg")))
    (is (= "image/webp" (files/image-media-type "x.webp"))))
  (testing "text, no extension and nil are not images"
    (is (nil? (files/image-media-type "src/xi/core.cljs")))
    (is (nil? (files/image-media-type "README")))
    (is (nil? (files/image-media-type ".png")))
    (is (nil? (files/image-media-type nil)))))
