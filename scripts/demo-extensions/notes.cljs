;; Demo user extension — installed into .demo-home/.config/xi/extensions by
;; scripts/demo-seed.mjs. See docs/user-extensions.md.
(ns notes
  (:require [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(defn- notes-file [ctx] (str (fs/data-dir ctx) "/notes.md"))

(def extension
  {:id :notes
   :init {:room {:text nil}}
   :tool-definitions
   [{:name "notes_add"
     :description "Append a line to the user's notes file."
     :input_schema {:type "object"
                    :properties {:text {:type "string"}}
                    :required ["text"]}}]
   :tool-registry
   {"notes_add"
    (fn [{:keys [text]} ctx]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] ""))
          (p/then #(fs/write ctx (notes-file ctx) (str % text "\n")))
          (p/then (fn [_] {:content [{:type "text" :text "added"}]}))))}
   :commands
   [{:name "notes"
     :description "Show my notes"
     :handler (fn [_st {:keys [room-id]}]
                {:effects [[:ext.notes/read {:room-id room-id :show? true}]]})}]
   ;; the web page's Refresh button sends :ext.notes/refresh (xi.web.user-ext
   ;; forwards it here); the text lands in the room's [:ext :notes] slice,
   ;; which mirrors back to every client
   :handlers
   {:ext.notes/refresh (fn [_st {:keys [room-id]}]
                         {:effects [[:ext.notes/read {:room-id room-id}]]})
    :ext.notes/loaded  (fn [st {:keys [room-id text]}]
                         {:state (assoc-in st [:rooms room-id :ext :notes :text] text)})}
   :fx
   {:ext.notes/read
    (fn [{:keys [dispatch!] :as ctx} {:keys [room-id show?]}]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] ""))
          (p/then (fn [text]
                    (dispatch! {:type :ext.notes/loaded :room-id room-id :text text})
                    (when show?
                      (dispatch! {:type :ui/status :room-id room-id
                                  :text (if (seq text) text "(no notes yet)")}))))))}})
