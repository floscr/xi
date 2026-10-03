(ns xi.ext.user-lifecycle-test
  "Live reload of user extensions: the old code unmounts (hook, owned resources,
   token), the new code mounts, and the composition the app reads late-bound
   (manager/live-view) follows."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.api.core :as api-core]
            [xi.ext.manager :as manager]
            [xi.ext.user :as user]
            [xi.ext.user.guard :as guard]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- tmp-dir []
  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-user-life-")))

(defn- write! [dir name src]
  (fs/writeFileSync (node-path/join dir name) src))

(defn- remove! [dir name]
  (fs/rmSync (node-path/join dir name)))

(use-fixtures :each
  {:before (fn [] (user/set-enabled-override! (fn [] #{"life.cljs" "other.cljs"})))
   :after  (fn [] (user/stop!) (user/set-enabled-override! nil))})

(defn- ext-source
  "A `life` extension whose ping handler stamps `v` and whose hooks announce
   themselves with `v`. `extra` is spliced into the extension map."
  [v extra]
  (str "(ns life)
        (def extension
          {:id :life
           :init {:room {:v " v " :count 0}}
           :handlers {:ext.life/ping (fn [st _] {:state (assoc-in st [:ext :life :v] " v ")})}
           :on-mount   (fn [{:keys [dispatch!]}] (dispatch! {:type :ext.life/mounted :v " v "}))
           :on-unmount (fn [{:keys [dispatch!]}] (dispatch! {:type :ext.life/unmounted :v " v "}))
           " extra "})"))

(defn- recording-app []
  (let [log (atom [])]
    {:log log
     :app {:dispatch! (fn [ev] (swap! log conj ev))
           :state     (atom {})}}))

(defn- ping-handler [mgr]
  (get-in (manager/composed mgr) [:handlers :ext.life/ping]))

(deftest reload-swaps-the-composition-the-app-reads-late
  (let [dir  (tmp-dir)
        mgr  (doto (manager/create) (manager/seed! []))
        view (manager/live-view mgr :handlers)]
    (write! dir "life.cljs" (ext-source 1 ""))
    (user/install! mgr dir)
    (is (= 1 (get-in ((get (view) :ext.life/ping) {:ext {:life {}}} {}) [:state :ext :life :v])))
    (write! dir "life.cljs" (ext-source 2 ""))
    (is (= [:life] (:loaded (user/reload! mgr dir))))
    (testing "the same live view now yields the new handler"
      (is (= 2 (get-in ((get (view) :ext.life/ping) {:ext {:life {}}} {}) [:state :ext :life :v]))))
    (testing "a handler that was removed is gone"
      (write! dir "life.cljs" (str "(ns life) (def extension {:id :life :handlers {}})"))
      (user/reload! mgr dir)
      (is (nil? (get (view) :ext.life/ping))))))

(deftest live-view-rebuilds-only-when-the-composition-changes
  (let [mgr   (doto (manager/create) (manager/seed! []))
        built (atom 0)
        view  (manager/live-view mgr (fn [_] (swap! built inc)))]
    (view) (view)
    (is (= 1 @built) "memoised on the composed identity")
    (manager/register! mgr {:id :x})
    (view)
    (is (= 2 @built))))

(deftest mount-and-unmount-hooks-run-in-order
  (let [dir (tmp-dir)
        mgr (doto (manager/create) (manager/seed! []))
        {:keys [log app]} (recording-app)
        types #(mapv (juxt :type :v) @log)]
    (write! dir "life.cljs" (ext-source 1 ""))
    (user/install! mgr dir)
    (is (empty? @log) "nothing mounts before the app runs")
    (user/start! app)
    (is (= [[:user-ext/mounted nil] [:ext.life/mounted 1]] (types)))
    (reset! log [])
    (write! dir "life.cljs" (ext-source 2 ""))
    (user/reload! mgr dir)
    (is (= [[:ext.life/unmounted 1] [:user-ext/mounted nil] [:ext.life/mounted 2]] (types))
        "old code unmounts first, then the new code mounts")
    (reset! log [])
    (remove! dir "life.cljs")
    (user/reload! mgr dir)
    (is (= [[:ext.life/unmounted 2]] (types)) "a removed file unmounts without remounting")
    (is (false? (manager/known? mgr :life)))))

(deftest mounting-seeds-new-init-keys-and-keeps-existing-state
  (let [dir (tmp-dir)
        mgr (doto (manager/create) (manager/seed! []))
        {:keys [log app]} (recording-app)
        handler (get-in user/server-extension [:handlers :user-ext/mounted])
        st {:rooms {"r1" {:ext {:life {:v 9}}}
                    "r2" {}}}]
    (write! dir "life.cljs" (ext-source 1 ""))
    (user/install! mgr dir)
    (user/start! app)
    (let [ev (first @log)
          {:keys [state effects]} (handler st ev)]
      (is (= :user-ext/mounted (:type ev)))
      (is (= {:v 9 :count 0} (get-in state [:rooms "r1" :ext :life]))
          "an existing value wins, a missing init key is added")
      (is (= {:v 1 :count 0} (get-in state [:rooms "r2" :ext :life])))
      (is (= #{"r1" "r2"} (set (map #(get-in % [1 :room-id]) effects)))
          "every changed slice is synced to clients"))
    (is (nil? (handler {:rooms {"r1" {:ext {:life {:v 9 :count 3}}}}} (first @log)))
        "nothing to seed → no state change")))

(deftest unmount-releases-owned-resources-and-revokes-the-token
  (let [dir      (tmp-dir)
        mgr      (doto (manager/create) (manager/seed! []))
        {:keys [app]} (recording-app)
        released (atom 0)]
    (write! dir "life.cljs" (ext-source 1 ""))
    (user/install! mgr dir)
    (user/start! app)
    (let [{:keys [token]} (first (user/loaded-entries))
          ctx {:xi.api/token token}]
      (is (api-core/active? token))
      (api-core/own! ctx #(swap! released inc))
      (api-core/own! ctx :shared #(swap! released inc))
      (api-core/own! ctx :shared #(swap! released inc)) ; same key: replaces
      (user/reload! mgr dir)
      (is (= 2 @released) "each owned resource is disposed once")
      (is (not (api-core/active? token)))
      (testing "closures of the old code can't act any more"
        (is (thrown? js/Error (api-core/own! ctx #(swap! released inc))))
        (is (thrown? js/Error (api-core/caller ctx)))))))

(deftest a-released-resource-is-not-disposed-again
  (let [token    (api-core/issue-token! :rel)
        ctx      {:xi.api/token token}
        released (atom 0)
        release  (api-core/own! ctx #(swap! released inc))]
    (release)
    (is (= 0 (api-core/dispose! :rel)))
    (is (= 0 @released))))

(deftest a-failing-disposer-does-not-block-the-others
  (let [token (api-core/issue-token! :boom)
        ctx   {:xi.api/token token}
        ran   (atom 0)]
    (api-core/own! ctx (fn [] (throw (js/Error. "nope"))))
    (api-core/own! ctx (fn [] (swap! ran inc)))
    (is (= 2 (api-core/dispose! :boom)))
    (is (= 1 @ran))))

(deftest dispatch-from-unmounted-code-is-dropped
  (let [token (api-core/issue-token! :late)
        seen  (atom [])
        ext   (guard/wrap
               {:id :late
                :fx {:ext.late/go (fn [{:keys [dispatch!]} _]
                                    (dispatch! {:type :ext.late/done}))}}
               token)
        run   #((get-in ext [:fx :ext.late/go]) {:dispatch! (fn [ev] (swap! seen conj (:type ev)))} {})]
    (run)
    (is (= [:ext.late/done] @seen))
    (api-core/revoke! token)
    (run)
    (is (= [:ext.late/done] @seen) "after the token is revoked the dispatch goes nowhere")))

(deftest a-throwing-hook-does-not-break-the-reload
  (let [dir (tmp-dir)
        mgr (doto (manager/create) (manager/seed! []))
        {:keys [app]} (recording-app)]
    (write! dir "life.cljs"
            "(ns life)
             (def extension
               {:id :life
                :on-mount   (fn [_] (throw (js/Error. \"mount\")))
                :on-unmount (fn [_] (throw (js/Error. \"unmount\")))})")
    (user/install! mgr dir)
    (user/start! app)
    (write! dir "life.cljs" (ext-source 2 ""))
    (is (= [:life] (:loaded (user/reload! mgr dir))))
    (is (some? (ping-handler mgr)))))

(deftest a-syntax-error-rejects-the-file-and-unmounts-the-old-one
  (let [dir (tmp-dir)
        mgr (doto (manager/create) (manager/seed! []))]
    (write! dir "life.cljs" (ext-source 1 ""))
    (user/install! mgr dir)
    (is (some? (ping-handler mgr)))
    (write! dir "life.cljs" "(ns life) (def extension {:id :life :handlers {")
    (let [{:keys [loaded rejected]} (user/reload! mgr dir)]
      (is (empty? loaded))
      (is (= 1 (count rejected))))
    (is (nil? (ping-handler mgr)) "a broken file leaves nothing of the old code mounted")))
