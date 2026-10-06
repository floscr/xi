(ns xi.ext.core-test
  (:require [cljs.test :refer [deftest is testing async]]
            [xi.core.state :as state]
            [xi.ext.core :as ext]))

;; ── compose ──────────────────────────────────────────────────────────────────

(deftest compose-splits-init-state
  (let [composed (ext/compose
                  [{:id :a :init {:room {:enabled? false}}}
                   {:id :b :init {:process {:recording? false}}}
                   {:id :c :init {:room {:n 1} :process {:m 2}}}
                   {:id :d}
                   nil])]
    (testing "room-scoped init keyed by ext id (nils dropped)"
      (is (= {:a {:enabled? false} :c {:n 1}} (:room-ext-init composed))))
    (testing "process-local init keyed by ext id"
      (is (= {:b {:recording? false} :c {:m 2}} (:process-ext-init composed))))
    (testing "nil extensions are removed"
      (is (= 4 (count (:extensions composed)))))))

(deftest compose-merges-and-chains
  (let [order    (atom [])
        ext-a    {:id :a
                  :handlers     {:foo (fn [st _] (swap! order conj :a) {:state st})}
                  :fx           {:fx/x (fn [_ _] :a)}
                  :commands     [{:name "ca"}]
                  :tool-definitions [{:name "ta"}]
                  :tool-registry {"ta" (fn [_ _] :a)}
                  :keybindings  [{:key "alt+a"}]
                  :prompt-badge (fn [_] "A")
                  :system-prompt "sysA"
                  :on-shutdown  (fn [] :a)}
        ext-b    {:id :b
                  :handlers {:foo (fn [st _] (swap! order conj :b) {:state st})}
                  :commands [{:name "cb"}]}
        composed (ext/compose [ext-a ext-b])]
    (testing "handlers for same type chain in extension order"
      ((get-in composed [:handlers :foo]) {} {:type :foo})
      (is (= [:a :b] @order)))
    (testing "commands concatenate in order"
      (is (= ["ca" "cb"] (mapv :name (:commands composed)))))
    (testing "tool defs / registry / badges / keybindings collected"
      (is (= ["ta"] (mapv :name (:tool-definitions composed))))
      (is (= 1 (count (:tool-registry composed))))
      (is (= 1 (count (:badges composed))))
      (is (= 1 (count (:keybindings composed))))
      (is (= 1 (count (:on-shutdown-fns composed)))))))

(deftest compose-collects-web-sidebar-contributions
  (let [composed (ext/compose
                  [{:id :a :sidebar-groups [{:id :ga}] :session-menu-items [{:label "A"}]}
                   {:id :b :sidebar-groups [{:id :gb}] :session-menu-items [{:label "B"}]}
                   {:id :c}])]
    (testing "in extension order"
      (is (= [:ga :gb] (mapv :id (:sidebar-groups composed))))
      (is (= ["A" "B"] (mapv :label (:session-menu-items composed)))))
    (testing "none declared → empty, not nil"
      (is (= [] (:sidebar-groups (ext/compose [{:id :c}]))))
      (is (= [] (:session-menu-items (ext/compose [{:id :c}])))))))

(deftest merge-handlers-chains-after-base
  (let [order (atom [])
        base  {:foo (fn [st _] (swap! order conj :base) {:state st})}
        composed (ext/compose
                  [{:id :a :handlers {:foo (fn [st _] (swap! order conj :ext) {:state st})}}])
        merged (ext/merge-handlers base composed)]
    ((get merged :foo) {} {:type :foo})
    (is (= [:base :ext] @order) "base runs before extension handler")))

;; ── transform-event ──────────────────────────────────────────────────────────

(deftest transform-event-transforms-and-blocks
  (let [composed (ext/compose
                  [{:id :a :event-hooks {:input/submit (fn [ev _] (assoc ev :tag :a))}}
                   {:id :b :event-hooks {:input/submit (fn [ev _] (update ev :text str "!"))}}
                   {:id :c :event-hooks {:block/it (fn [_ _] nil)}}])
        tx (ext/transform-event composed)]
    (testing "hooks run in order, each sees prior transform"
      (is (= {:type :input/submit :text "hi!" :tag :a}
             (tx {} {:type :input/submit :text "hi"}))))
    (testing "a hook returning nil blocks the event"
      (is (nil? (tx {} {:type :block/it}))))
    (testing "events with no hook pass through untouched"
      (is (= {:type :other} (tx {} {:type :other}))))))

(deftest transform-event-remote-passthrough
  (let [seen (atom false)
        composed (ext/compose
                  [{:id :a :event-hooks {:foo (fn [ev _] (reset! seen true) ev)}}])
        tx (ext/transform-event composed)]
    (is (= {:type :foo :remote? true} (tx {} {:type :foo :remote? true})))
    (is (false? @seen) "mirrored (:remote?) events never run hooks")))

(deftest transform-event-hook-throw-recovers
  (let [composed (ext/compose
                  [{:id :a :event-hooks {:foo (fn [_ _] (throw (js/Error. "boom")))}}
                   {:id :b :event-hooks {:foo (fn [ev _] (assoc ev :ok true))}}])
        tx (ext/transform-event composed)]
    (testing "a throwing hook is skipped (event passes through to next hook)"
      (is (= {:type :foo :ok true} (tx {} {:type :foo}))))))

(deftest transform-event-nil-when-no-hooks
  (is (nil? (ext/transform-event (ext/compose [{:id :a}])))))

;; ── system-prompt / prompt-badges ────────────────────────────────────────────

(deftest system-prompt-str-and-fn-forms
  (let [composed (ext/compose
                  [{:id :a :system-prompt "static"}
                   {:id :b :system-prompt (fn [cwd] (str "cwd=" cwd))}
                   {:id :c :system-prompt (fn [_] nil)}
                   {:id :d :system-prompt (fn [_] "")}])]
    (is (= "static\n\ncwd=/tmp" (ext/system-prompt composed "/tmp"))
        "str + fn prompts joined; nil/empty dropped")
    (is (nil? (ext/system-prompt (ext/compose [{:id :a}]) "/tmp")))))

(deftest prompt-badges-concatenated
  (let [composed (ext/compose
                  [{:id :a :prompt-badge (fn [_] "🔔")}
                   {:id :b :prompt-badge (fn [_] nil)}
                   {:id :c :prompt-badge (fn [_] (throw (js/Error. "x")))}
                   {:id :d :prompt-badge (fn [st] (when (:flag st) "●"))}])]
    (is (= "🔔" (ext/prompt-badges composed {})))
    (is (= "🔔●" (ext/prompt-badges composed {:flag true})))
    (is (= "" (ext/prompt-badges (ext/compose [{:id :a}]) {})))))

;; ── dialogs ──────────────────────────────────────────────────────────────────

(deftest dialog-response-removes-dialog-and-resolves
  (let [{:keys [handlers]} (ext/create-dialogs)
        handler (get handlers :ui/dialog-response)
        st (-> (state/initial-state)
               (assoc-in [:rooms "r"]
                         (state/make-room "r" nil))
               (assoc-in [:rooms "r" :ui :dialogs] [{:id "dlg-1" :type :confirm}
                                                    {:id "dlg-2" :type :confirm}]))
        result (handler st {:room-id "r" :dialog-id "dlg-1" :value true})]
    (testing "the responded dialog is removed, others remain"
      (is (= [{:id "dlg-2" :type :confirm}]
             (get-in (:state result) [:rooms "r" :ui :dialogs]))))
    (testing "emits a :dialog/resolve effect with the value"
      (is (= [[:dialog/resolve {:dialog-id "dlg-1" :value true}]]
             (:effects result))))
    (testing "unknown dialog-id is a no-op"
      (is (nil? (handler st {:room-id "r" :dialog-id "nope" :value true}))))))

(deftest dialog-response-forwards-a-reason-only-with-a-deny
  (let [handler (get-in (ext/create-dialogs) [:handlers :ui/dialog-response])
        st (-> (state/initial-state)
               (assoc-in [:rooms "r"] (state/make-room "r" nil))
               (assoc-in [:rooms "r" :ui :dialogs] [{:id "dlg-1" :type :confirm}]))
        effect (fn [ev] (first (:effects (handler st (merge {:room-id "r" :dialog-id "dlg-1"} ev)))))]
    (is (= [:dialog/resolve {:dialog-id "dlg-1" :value false :reason "why"}]
           (effect {:value false :reason "why"})))
    (is (= [:dialog/resolve {:dialog-id "dlg-1" :value true}]
           (effect {:value true :reason "why"}))
        "an allow never carries a reason")
    (is (= [:dialog/resolve {:dialog-id "dlg-1" :value false}]
           (effect {:value false :reason "  "})))))

(deftest dialog-ask-with-on-reason-offers-and-delivers-it
  (async done
    (let [{:keys [ask! handlers fx]} (ext/create-dialogs)
          resolve-fx (get fx :dialog/resolve)
          opened     (atom nil)
          reason     (atom nil)
          st (-> (state/initial-state)
                 (assoc-in [:connection :clients "c"] {:room-id "r"})
                 (assoc-in [:rooms "r"] (state/make-room "r" nil)))
          dispatch! (fn [ev]
                      (when (= :ui/dialog-open (:type ev))
                        (reset! opened (:dialog ev))
                        (let [st'    (assoc-in st [:rooms "r" :ui :dialogs] [(:dialog ev)])
                              result ((get handlers :ui/dialog-response)
                                      st' {:room-id "r" :dialog-id (get-in ev [:dialog :id])
                                           :value false :reason "nope"})]
                          (resolve-fx {} (second (first (:effects result)))))))
          v (ask! {:dispatch! dispatch! :state st}
                  {:room-id "r" :dialog {:type :confirm :message "ok?"}
                   :on-reason #(reset! reason %)})]
      (is (false? v) "still resolves to the plain deny")
      (is (= "nope" @reason) "the reason reached the asker first")
      (is (true? (:deny-reason? @opened)) "clients are told to offer it")
      (is (not (contains? @opened :on-reason)) "the callback never enters the dialog data")
      (done))))

(deftest dialog-ask-clientless-resolves-default
  (async done
    (let [{:keys [ask!]} (ext/create-dialogs)
          opened (atom nil)
          st (-> (state/initial-state {:mode :server :clientless? true})
                 (assoc-in [:rooms "r"] (state/make-room "r" nil)))
          v  (ask! {:dispatch! #(reset! opened %) :state st}
                   {:room-id "r" :dialog {:type :confirm :prompt "ok?"}})]
      (is (false? v) "prompt mode (no client can attach) → safe default (false)")
      (is (nil? @opened) "without opening a dialog")
      (done))))

(deftest dialog-ask-server-no-clients-stays-open
  ;; a phone that went to sleep leaves the server with no clients; the ask
  ;; must still open so it shows when one reconnects
  (let [{:keys [ask!]} (ext/create-dialogs)
        opened (atom nil)
        st (-> (state/initial-state {:mode :server})
               (assoc-in [:rooms "r"] (state/make-room "r" nil)))
        settled (atom false)]
    (.then (ask! {:dispatch! #(when (= :ui/dialog-open (:type %)) (reset! opened %)) :state st}
                 {:room-id "r" :dialog {:type :confirm :prompt "ok?"}})
           (fn [_] (reset! settled true)))
    (is (= "r" (:room-id @opened)) "the dialog opens in the room")
    (is (false? @settled) "and waits for an answer")))

(deftest dialog-ask-resolves-on-response
  (async done
    ;; ask!'s executor dispatches :ui/dialog-open synchronously; this
    ;; dispatch! replays the response handler + resolve fx immediately, so
    ;; the awaited ask! promise is already settled when we assert.
    (let [{:keys [ask! handlers fx]} (ext/create-dialogs)
          resolve-fx (get fx :dialog/resolve)
          st (-> (state/initial-state)
                 (assoc-in [:connection :clients "c"] {:room-id "r"})
                 (assoc-in [:rooms "r"] (state/make-room "r" nil)))
          dispatch! (fn [ev]
                      (when (= :ui/dialog-open (:type ev))
                        (let [dlg-id (get-in ev [:dialog :id])
                              st'    (assoc-in st [:rooms "r" :ui :dialogs] [(:dialog ev)])
                              result ((get handlers :ui/dialog-response)
                                      st' {:room-id "r" :dialog-id dlg-id :value true})]
                          (resolve-fx {} (second (first (:effects result)))))))
          v (ask! {:dispatch! dispatch! :state st}
                  {:room-id "r" :dialog {:type :confirm :prompt "ok?"}})]
      (is (true? v) "resolves with the response value")
      (done))))
