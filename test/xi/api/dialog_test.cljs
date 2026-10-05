(ns xi.api.dialog-test
  "xi.api.dialog — dialogs open in the app through the installed host, are
   labelled with the extension, and need a proven caller and a room."
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [xi.api.core :as core]
            [xi.api.dialog :as dialog]))

(def ^:private opened (atom []))
(def ^:private answer (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! opened [])
             (reset! answer nil)
             (core/set-dialog-host!
              {:dispatch! (fn [_])
               :get-state (fn [] {:rooms {"r1" {}}})
               :ask!      (fn [_fx-ctx req]
                            (swap! opened conj req)
                            (js/Promise.resolve @answer))}))
   :after #(core/set-dialog-host! nil)})

(defn- ctx [id]
  {:extension id :xi.api/token (core/issue-token! id) :room-id "r1"})

(defn- rejection [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

(deftest select-opens-a-labelled-dialog-in-the-room
  (async done
    (reset! answer "p2")
    (-> (dialog/select (ctx :design) "Which tab?" [{:label "Docs" :value "p1"} "p2"])
        (.then (fn [v]
                 (is (= "p2" v))
                 (is (= [{:room-id "r1"
                          :dialog  {:type    :select
                                    :message "[extension design] Which tab?"
                                    :options [{:label "Docs" :value "p1"}
                                              {:label "p2" :value "p2"}]}}]
                        @opened))))
        (.finally done))))

(deftest confirm-is-a-boolean-and-opts-pick-the-room
  (async done
    (-> (dialog/confirm (dissoc (ctx :design) :room-id) "Sure?" {:room-id "r1"})
        (.then (fn [v]
                 (is (false? v) "a dismissed / headless confirm is false")
                 (is (= {:type :confirm :message "[extension design] Sure?" :options [:yes :no]}
                        (:dialog (first @opened))))))
        (.finally done))))

(deftest form-passes-fields
  (async done
    (reset! answer {"msg" "hi"})
    (-> (dialog/form (ctx :design) "Commit" [{:name "msg" :label "Message" :value "x" :evil 1}])
        (.then (fn [v]
                 (is (= {"msg" "hi"} v))
                 (is (= [{:name "msg" :label "Message" :value "x"}]
                        (get-in (first @opened) [:dialog :fields])))))
        (.finally done))))

(deftest refusals
  (async done
    (-> (js/Promise.all
         #js [(rejection (dialog/alert {:extension :design :room-id "r1"} "hi"))
              (rejection (dialog/alert (dissoc (ctx :design) :room-id) "hi"))
              (rejection (dialog/alert (assoc (ctx :design) :room-id "nope") "hi"))
              (rejection (dialog/select (ctx :design) "Which?" []))
              (rejection (dialog/alert (ctx :design) " "))])
        (.then (fn [[forged no-room unknown no-opts blank]]
                 (is (re-find #"not an extension ctx" forged))
                 (is (re-find #"no room" no-room))
                 (is (re-find #"unknown room nope" unknown))
                 (is (re-find #"needs options" no-opts))
                 (is (re-find #"needs a message" blank))
                 (is (empty? @opened) "nothing was opened")))
        (.finally done))))

(deftest no-host-no-dialogs
  (async done
    (core/set-dialog-host! nil)
    (-> (rejection (dialog/confirm (ctx :design) "Sure?"))
        (.then #(is (re-find #"aren't available" %)))
        (.finally done))))
