(ns xi.tools.view-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tools.view :as view]))

(deftest image-mime-supported-extensions
  (testing "maps supported extensions to MIME types (case-insensitive)"
    (is (= "image/png"  (view/image-mime "shot.png")))
    (is (= "image/png"  (view/image-mime "SHOT.PNG")))
    (is (= "image/jpeg" (view/image-mime "photo.jpg")))
    (is (= "image/jpeg" (view/image-mime "photo.jpeg")))
    (is (= "image/webp" (view/image-mime "art.webp")))
    (is (= "image/gif"  (view/image-mime "anim.gif")))
    (is (= "image/png"  (view/image-mime "/abs/path/to/diagram.png")))))

(deftest image-mime-unsupported
  (testing "returns nil for unsupported or missing extensions"
    (is (nil? (view/image-mime "notes.txt")))
    (is (nil? (view/image-mime "vector.svg")))
    (is (nil? (view/image-mime "noext")))
    (is (nil? (view/image-mime "archive.tar.gz")))))

(deftest execute-missing-file
  (testing "returns an error result for a non-existent file"
    (let [result (view/execute {:path "does-not-exist-xyz.png"} {:cwd "/tmp"})]
      (is (:is-error result))
      (is (re-find #"File not found" (-> result :content first :text))))))

(deftest execute-unsupported-type
  (testing "returns an error result for an unsupported extension"
    (let [result (view/execute {:path "readme.md"} {:cwd "/tmp"})]
      (is (:is-error result))
      (is (re-find #"Unsupported image type" (-> result :content first :text))))))
