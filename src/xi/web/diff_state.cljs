(ns xi.web.diff-state
  "The diff viewer's UI state, one slot per view it belongs to — the
   `[room-id buffer-id]` in front (`view-key`; roomless pages like Git status
   get the nil slot) — so each diff buffer keeps its selection, open composer
   and draft, folded and expanded files while another buffer is shown.

   A slot under :web/diff-views: {:sel {:anchor :head} :intent kw :draft str
   :collapsed #{filename} :expanded #{filename}}. Handlers (xi.web.core), the
   viewer (xi.web.views) and the review canvas read and write it through this
   namespace only."
  (:require [xi.core.state :as state]))

(defn view-key [st]
  (when-let [room (state/active-room st)]
    [(:id room) (get-in room [:ui :active-buffer])]))

(defn view [st]
  (get-in st [:web/diff-views (view-key st)]))

(defn sel [st] (:sel (view st)))

(defn intent [st] (:intent (view st)))

(defn draft [st] (:draft (view st)))

(defn collapsed [st] (or (:collapsed (view st)) #{}))

(defn expanded [st] (or (:expanded (view st)) #{}))

(defn update-view
  "`(apply f slot args)` on the active view's slot."
  [st f & args]
  (apply update-in st [:web/diff-views (view-key st)] f args))

(defn assoc-view [st & kvs]
  (update-view st #(apply assoc % kvs)))

(defn update-view-of
  "`(apply f slot args)` on the slot of view `k` (a view-key), which need not
   be in front: a jump prepares the view it is about to switch to."
  [st k f & args]
  (apply update-in st [:web/diff-views k] f args))

(defn dissoc-view [st & ks]
  (update-view st #(apply dissoc % ks)))

(defn clear-selection
  "Drop the active view's selection and its composer (with the draft)."
  [st]
  (dissoc-view st :sel :intent :draft))

(defn clear-all-selections
  "Drop every view's selection and composer: their line indices belong to a
   layout that changed (xi.diff/as-layout)."
  [st]
  (update st :web/diff-views
          (fn [views] (update-vals (or views {}) #(dissoc % :sel :intent :draft)))))
