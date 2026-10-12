(ns xi.ext.canvas-review-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.ext.diff.git :as git]
            [xi.session :as session]
            [xi.ext.canvas-review :as cr]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- tmp-session []
  (let [dir (.mkdtempSync fs (.join path (os/tmpdir) "xi-cr-test-"))]
    {:id "sess-1" :cwd "/tmp/proj" :_dir dir}))

(def sample-canvas
  {:source :session-git :title "T" :diff "diff --git a/a b/a\n@@ -1 +1 @@\n-a\n+b\n"
   :nodes  {"blk-1" {:id "blk-1" :kind :code :file "a.cljs" :start-line 1 :end-line 3 :x 40 :y 40 :w 540}
            "cmt-1" {:id "cmt-1" :kind :comment :text "note" :x 620 :y 40 :w 460}}
   :edges  {"e-1" {:id "e-1" :from "cmt-1" :to "blk-1"}}
   :plan   [{:node-id "blk-1" :title "look here"}]})

(defn- room-state [rid canvas]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms rid] (state/make-room rid {:ext {:canvas-review canvas}}))
      (assoc :active-room rid)))

(deftest sidecar-roundtrip
  (testing "canvas survives an EDN sidecar round-trip"
    (let [sess (tmp-session)]
      (session/save-canvas! sess sample-canvas)
      (is (.existsSync fs (session/canvas-sidecar-path sess)))
      (let [loaded (session/load-canvas sess)]
        (is (= sample-canvas loaded))
        (testing "keyword :kind values survive (JSON would stringify them)"
          (is (= :code (get-in loaded [:nodes "blk-1" :kind]))))
        (testing "string node-id map keys survive"
          (is (contains? (:nodes loaded) "blk-1"))
          (is (string? (ffirst (:nodes loaded))))))
      (testing "nil canvas removes the sidecar"
        (session/save-canvas! sess nil)
        (is (not (.existsSync fs (session/canvas-sidecar-path sess))))
        (is (nil? (session/load-canvas sess)))))))

(deftest hydrate-installs-and-guards
  (let [rid     "r1"
        base    (room-state rid {:nodes {} :edges {} :plan []})
        hydrate (get-in cr/extension [:handlers :canvas-review/hydrate])]
    (testing "hydrate installs a whole canvas into an empty room"
      (let [res (hydrate base {:room-id rid :canvas sample-canvas})]
        (is (= sample-canvas (get-in (:state res) [:rooms rid :ext :canvas-review])))))
    (testing "hydrate won't clobber a canvas that already has nodes"
      (let [populated (assoc-in base [:rooms rid :ext :canvas-review :nodes "x"] {:id "x"})]
        (is (nil? (hydrate populated {:room-id rid :canvas sample-canvas})))))))

(deftest canvas-opens-as-a-buffer
  (let [rid     "r1"
        base    (room-state rid {:nodes {} :edges {} :plan []})
        load    (get-in cr/extension [:handlers :canvas-review/load])
        hydrate (get-in cr/extension [:handlers :canvas-review/hydrate])
        buf     (fn [res] (get-in (:state res) [:rooms rid :ui :buffers buffers/canvas]))]
    (testing "loading a review installs the room's :canvas buffer, titled by the source"
      (let [res (load base {:room-id rid :source "HEAD~1" :title "Diff: HEAD~1"
                            :text "diff" :event/ts 5})]
        (is (= {:kind :canvas :title "Canvas review · Diff: HEAD~1" :opened-at 5}
               (buf res)))
        (is (= :canvas (buffers/kind buffers/canvas (buf res))))
        (testing "reloading keeps its place in the buffer order"
          (is (= 5 (:opened-at (buf (load (:state res) {:room-id rid :title "Staged Changes"
                                                       :text "diff" :event/ts 9})))))))
    (testing "hydrating a persisted canvas brings its buffer back"
      (is (= "Canvas review · T" (:title (buf (hydrate base {:room-id rid :canvas sample-canvas}))))))
    (testing "a canvas without a diff gets no buffer"
      (is (nil? (buf (hydrate base {:room-id rid :canvas {:nodes {} :edges {} :plan []}})))))
    (testing "/canvas-review switches only the originating client to it"
      (let [seen  (atom [])
            st    (assoc-in base [:rooms rid :cwd] (.cwd js/process))
            start (get-in cr/extension [:fx :canvas-review/start])]
        (with-redefs [git/diff-against-ref (constantly {:ok "diff --git a/a b/a\n@@ -1 +1 @@\n-a\n+b\n"})
                      git/git-ref?         (constantly true)]
          (start {:dispatch! #(swap! seen conj %) :get-state (constantly st)}
                 {:room-id rid :args "HEAD~1" :client-id "c1"}))
        (is (= [:canvas-review/load :ui/buffer-switch :subagent/spawn] (mapv :type @seen)))
        (is (= {:type :ui/buffer-switch :room-id rid :buffer-id :canvas :client-id "c1"}
               (second @seen)))))
    (testing "canvas_review_load starts a canvas when the chat's own agent builds it"
      (let [seen (atom [])
            st   (assoc-in base [:rooms rid :cwd] (.cwd js/process))
            tool (get-in cr/extension [:tool-registry "canvas_review_load"])
            ctx  {:room-id rid :dispatch! #(swap! seen conj %) :get-state (constantly st)}]
        (with-redefs [git/diff-against-ref (constantly {:ok "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n+b\n"})
                      git/git-ref?         (constantly true)]
          (let [res (tool {:source "penpot/develop"} ctx)]
            (is (not (:is-error res)))
            (is (re-find #"Diff: penpot/develop" (get-in res [:content 0 :text])))
            (is (re-find #"\+b" (get-in res [:content 0 :text])) "returns the annotated diff")
            (is (= [{:type :canvas-review/load :room-id rid :source "penpot/develop"
                     :title "Diff: penpot/develop" :name "Canvas review: Diff: penpot/develop"}]
                   (mapv #(dissoc % :text) @seen)))
            (is (= "Canvas review · Diff: penpot/develop"
                   (:title (buf (load base (first @seen))))))))
        (testing "an empty diff is an error and loads nothing"
          (reset! seen [])
          (with-redefs [git/diff-against-ref (constantly {:ok ""})
                        git/git-ref?         (constantly true)]
            (is (:is-error (tool {:source "HEAD"} ctx)))
            (is (empty? @seen))))))))

(deftest mutation-emits-persist
  (let [rid "r1"
        st  (room-state rid {:nodes {} :edges {} :plan []})
        add (get-in cr/extension [:handlers :canvas-review/add-node])
        res (add st {:room-id rid :node {:id "blk-1" :kind :code}})]
    (testing "a wrapped mutation applies its state change"
      (is (= "blk-1" (get-in (:state res) [:rooms rid :ext :canvas-review :nodes "blk-1" :id]))))
    (testing "and emits a persist effect for this room"
      (is (some #(= % [:canvas-review/persist {:room-id rid}]) (:effects res))))))

(deftest highlight-tool-and-handler
  (let [rid  "r1"
        tool (get-in cr/extension [:tool-registry "canvas_review_highlight"])
        defs (:tool-definitions cr/extension)
        hl   (get-in cr/extension [:handlers :canvas-review/highlight])]
    (testing "the highlight tool is advertised"
      (is (some #(= "canvas_review_highlight" (:name %)) defs)))
    (testing "every advertised tool has a registry entry"
      (is (= (set (map :name defs)) (set (keys (:tool-registry cr/extension))))))
    (testing "the highlight tool dispatches through the tool ctx"
      (let [seen (atom nil)
            st   (room-state rid sample-canvas)
            res  (tool {:id "blk-1" :lines [402 403 404]}
                       {:room-id rid :dispatch! #(reset! seen %) :get-state (constantly st)})]
        (is (not (:is-error res)))
        (is (re-find #"Highlighted 3" (get-in res [:content 0 :text])))
        (is (= :canvas-review/highlight (:type @seen)))
        (is (= "blk-1" (:id @seen)))
        (is (= [402 403 404] (:lines @seen)))))
    (testing "the handler records highlighted lines on the node (and persists)"
      (let [st  (room-state rid sample-canvas)
            res (hl st {:room-id rid :id "blk-1" :lines [402 403]})]
        (is (= [402 403] (get-in (:state res) [:rooms rid :ext :canvas-review
                                               :nodes "blk-1" :highlight])))
        (is (some #(= % [:canvas-review/persist {:room-id rid}]) (:effects res)))))
    (testing "without a loaded diff the tools refuse and point at canvas_review_load"
      (let [seen (atom nil)
            res  (tool {:id "blk-1" :lines [1]}
                       {:room-id rid :dispatch! #(reset! seen %)
                        :get-state (constantly (room-state rid {:nodes {} :edges {} :plan []}))})]
        (is (:is-error res))
        (is (re-find #"canvas_review_load" (get-in res [:content 0 :text])))
        (is (nil? @seen))))
    (testing "highlighting an unknown node no-ops"
      (is (nil? (hl (room-state rid sample-canvas)
                    {:room-id rid :id "nope" :lines [1]}))))))

(deftest resume-emits-rehydrate
  (let [resumed (get-in cr/extension [:handlers :session/resumed])
        res     (resumed {} {:room-id "r1"})]
    (is (= [[:canvas-review/rehydrate {:room-id "r1"}]] (:effects res)))))

(deftest persist-and-rehydrate-fx
  (let [rid       "r1"
        sess      (tmp-session)
        st        (-> (state/initial-state {:mode :standalone})
                      (assoc-in [:rooms rid :session] sess)
                      (assoc-in [:rooms rid :ext :canvas-review] sample-canvas))
        persist   (get-in cr/extension [:fx :canvas-review/persist])
        rehydrate (get-in cr/extension [:fx :canvas-review/rehydrate])]
    (testing "persist-fx writes the room's canvas to its session sidecar"
      (persist {:get-state (constantly st)} {:room-id rid})
      (is (.existsSync fs (session/canvas-sidecar-path sess))))
    (testing "rehydrate-fx loads the sidecar and dispatches :canvas-review/hydrate"
      (let [seen (atom nil)]
        (rehydrate {:get-state (constantly st) :dispatch! #(reset! seen %)} {:room-id rid})
        (is (= :canvas-review/hydrate (:type @seen)))
        (is (= rid (:room-id @seen)))
        (is (= sample-canvas (:canvas @seen)))))))
)