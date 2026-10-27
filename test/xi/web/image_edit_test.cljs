(ns xi.web.image-edit-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.image-edit :as ie]))

(defn- handle [st ev]
  ((get ie/handlers (:type ev)) st ev))

(def att {:data "ORIG" :media-type "image/jpeg" :name "shot.jpg"})
(def rect {:x 10 :y 20 :w 100 :h 50})
(def shape (assoc rect :color "#e5484d" :width 4))

(defn- st-with [a]
  {:web/compose-images {:new [{:data "PDF" :media-type "application/pdf"} a]}
   :web/lightbox       {:srcs ["x"] :index 0 :edit {:draft-key :new :idxs [1]}}})

(defn- shown [st] (get-in st [:web/compose-images :new 1]))

(deftest edits-undo-redo
  (let [a (ie/add-shape (ie/crop att rect) shape)]
    (is (ie/modified? a))
    (is (= {:data "ORIG" :media-type "image/jpeg"} (:original (ie/edit-of a))))
    (is (= att a) "the attachment's own keys are untouched")
    (testing "undo steps back one edit at a time"
      (let [u1 (ie/undo a)
            u2 (ie/undo u1)]
        (is (= rect (:crop (ie/edit-of u1))))
        (is (empty? (:shapes (ie/edit-of u1))))
        (is (not (ie/modified? u2)))
        (is (nil? (ie/undo u2)))
        (is (= (ie/edit-of a) (ie/edit-of (ie/redo (ie/redo u2)))))))
    (testing "a new edit drops the redo stack"
      (is (not (ie/can-redo? (ie/add-shape (ie/undo a) shape)))))
    (testing "removing the edits is undoable"
      (let [r (ie/remove-edits a)]
        (is (not (ie/modified? r)))
        (is (= (ie/edit-of a) (assoc (ie/edit-of (ie/undo r)) :redo [])))))
    (is (nil? (ie/remove-edits att)) "nothing to remove")
    (is (nil? (ie/crop (ie/crop att rect) rect)) "the same crop again changes nothing")))

(deftest edit-bakes-the-shown-image
  (let [{:keys [state effects]} (handle (st-with att) {:type :lightbox/edit :shape shape})
        [[fx req]] effects]
    (is (= :image-edit/bake fx))
    (is (= {:draft-key :new :idx 1} (select-keys req [:draft-key :idx])))
    (is (= [shape] (get-in req [:snapshot :shapes])))
    (testing "the bake lands while the edit is current"
      (let [st (:state (handle state (assoc req :type :compose/image-baked
                                            :data "BAKED" :media-type "image/jpeg")))]
        (is (= "BAKED" (:data (shown st))))
        (is (ie/modified? (shown st)) "the edit survives in metadata")))
    (testing "a stale bake is dropped"
      (let [st (:state (handle state {:type :lightbox/undo}))]
        (is (nil? (handle st (assoc req :type :compose/image-baked
                                    :data "BAKED" :media-type "image/jpeg"))))))))

(deftest back-to-unedited-restores-the-original
  (let [st (-> (st-with att)
               (handle {:type :lightbox/edit :crop rect}) :state
               (assoc-in [:web/compose-images :new 1 :data] "BAKED"))
        {:keys [state effects]} (handle st {:type :lightbox/reset})]
    (is (empty? effects))
    (is (= "ORIG" (:data (shown state))))
    (is (ie/can-undo? (shown state)))))

(deftest tools
  (let [st (st-with att)]
    (is (= :crop (get-in (:state (handle st {:type :lightbox/tool :tool :crop}))
                         [:web/lightbox :tool])))
    (is (nil? (-> (handle st {:type :lightbox/tool :tool :crop}) :state
                  (handle {:type :lightbox/tool :tool :crop}) :state
                  (get-in [:web/lightbox :tool])))
        "the same key again puts the tool down")
    (let [st (:state (handle st {:type :lightbox/color :color "#fff"}))]
      (is (= :rect (get-in st [:web/lightbox :tool])) "picking a color picks the highlighter")
      (is (= "#fff" (:color (ie/view-props st)))))
    (testing "a message's image is not editable"
      (is (nil? (handle (update st :web/lightbox dissoc :edit) {:type :lightbox/tool :tool :crop})))
      (is (not (ie/editable? (update st :web/lightbox dissoc :edit)))))))
