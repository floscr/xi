(ns xi.ext.blocks-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.blocks :as blocks]
            [xi.ext.blocks.handlers :as h]))

(defn- room-state [history]
  {:rooms {"r" {:id "r" :history history :ext {:blocks {:blocks {}}}}}})

(defn- harness
  "A tool ctx whose dispatch! runs the blocks reducers on an atom."
  [history]
  (let [st (atom (room-state history))]
    {:st  st
     :ctx {:room-id "r"
           :get-state (fn [] @st)
           :dispatch! (fn [ev]
                        (when-let [f (get h/handlers (:type ev))]
                          (when-let [{s :state} (f @st ev)] (reset! st s))))}}))

(defn- call [{:keys [ctx]} tool args & [call]]
  ((get (:tool-registry (blocks/create {})) tool) args (cond-> ctx call (assoc :call call))))

(defn- text [result] (get-in result [:content 0 :text]))

(deftest show-update-read
  (let [args  {:kind "checklist" :title "Release" :id "rel"
               :data {:items [{:id "a" :label "Tag"} {:id "b" :label "Push"}]}}
        hist  [{:kind :user :text "go"}
               {:kind :tool-call :id "toolu_1" :tool "ui_block" :arguments args :status :running}]
        {:keys [st] :as hs} (harness hist)
        r     (call hs "ui_block" args {:name "ui_block" :arguments args})
        block (get-in @st [:rooms "r" :ext :blocks :blocks "rel"])]
    (is (not (:is-error r)))
    (is (str/starts-with? (text r) "Showed checklist \"Release\" as block rel."))
    (testing "the record points at its own tool call and stamps it for the TUI"
      (is (= "toolu_1" (:call-id block)))
      (is (= 2 (:anchor block)))
      (is (str/includes? (get-in @st [:rooms "r" :history 1 :block-text]) "- [ ] Tag")))
    (testing "the same id twice is refused"
      (is (:is-error (call hs "ui_block" args))))
    (testing "a user action, then ui_state reads and clears it"
      (swap! st (fn [s] (:state (h/act s {:type :ext.blocks/act :room-id "r" :block-id "rel"
                                          :action "toggle" :target "a" :value true :user "alice"
                                          :client-id "c1"})))))
      (is (= 1 (get-in @st [:rooms "r" :ext :blocks :blocks "rel" :unread])))
      (is (str/includes? (text (call hs "ui_update" {:id "rel" :title "Release 2"})) "Updated block rel."))
      (let [s (text (call hs "ui_state" {:id "rel"}))]
        (is (str/includes? s "- [x] Tag"))
        (is (str/includes? s "alice: toggle a → true")))
      (is (zero? (get-in @st [:rooms "r" :ext :blocks :blocks "rel" :unread])))
      (is (str/includes? (text (call hs "ui_state" {})) "rel  checklist  \"Release 2\""))))

(deftest a-sub-agents-block-is-in-its-history
  (let [args  {:kind "checklist" :id "note" :data {:items ["Tag"]}}
        {:keys [st] :as hs} (harness [{:kind :user :text "go"}])
        _     (swap! st assoc-in [:rooms "r" :ext :subagents :agents]
                     [{:id "sa-1" :history [{:kind :text :text "on it"}
                                            {:kind :tool-call :id "toolu_9" :tool "ui_block"
                                             :arguments args :status :running}]}])
        ;; a sub-agent's tool ctx: the parent room, its own :sub-id (xi.subagent)
        r     (call (assoc-in hs [:ctx :sub-id] "sa-1") "ui_block" args {:name "ui_block" :arguments args})
        block (get-in @st [:rooms "r" :ext :blocks :blocks "note"])]
    (is (not (:is-error r)))
    (is (= {:sub-id "sa-1" :call-id "toolu_9" :anchor 2}
           (select-keys block [:sub-id :call-id :anchor]))
        "its call and anchor are the sub-agent's")
    (is (some? (get-in @st [:rooms "r" :ext :subagents :agents 0 :history 1 :block-text]))
        "the sub-agent's call is stamped")
    (is (nil? (get-in @st [:rooms "r" :history 0 :block-text])))))

(deftest errors-are-worded-for-the-model
  (let [hs (harness [])]
    (is (str/includes? (text (call hs "ui_block" {:kind "pie" :data {}})) "unknown kind"))
    (is (str/includes? (text (call hs "ui_block" {:kind "choice"})) "Pass the choice payload as `data`."))
    (is (str/includes? (text (call hs "ui_update" {:id "x" :title "t"})) "No block \"x\""))
    (is (str/includes? (text (call hs "ui_state" {:id "x"})) "No block"))))

(deftest hiccup-reports-what-it-dropped
  (let [hs (harness [])
        r  (call hs "ui_block" {:kind "hiccup" :hiccup ["div" {:style "position:fixed"} ["script" "x"] "hi"]})]
    (is (not (:is-error r)))
    (is (str/includes? (text r) "Dropped from the hiccup (not allowed): attribute style on div; tag script."))))

(deftest clients-cannot-add-blocks
  (let [st (room-state [])]
    (is (nil? (h/add-block st {:room-id "r" :block {:id "x" :kind "callout"} :client-id "c1"})))
    (is (some? (h/add-block st {:room-id "r" :block {:id "x" :kind "callout"}})))))

(deftest block-cap
  (let [{:keys [st] :as hs} (harness [])]
    (swap! st assoc-in [:rooms "r" :ext :blocks :blocks]
           (into {} (for [i (range 64)] [(str "b" i) {:id (str "b" i)}])))
    (is (str/includes? (text (call hs "ui_block" {:kind "checklist" :data {:items ["x"]}})) "64 blocks"))))