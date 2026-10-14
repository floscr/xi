(ns xi.ext.subagent-context-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.subagent :as sa-ext]
            [xi.ext.subagent.handlers :as h]))

(defn- base-state []
  (-> (state/initial-state {:mode :server})
      (assoc-in [:rooms "r1"]
                {:id "r1" :cwd "/x" :agent {:model "m" :system "BASE"}
                 :history [{:kind :user :text "make config a defrecord"}
                           {:kind :thinking :text "hmm"}
                           {:kind :text :text "Writing the schemas namespace."}]
                 :ext {:subagents {:agents [] :collapsed? false}}})))

(defn- start-payload [res]
  (some (fn [[k p]] (when (= :subagent/start k) p)) (:effects res)))

(deftest every-sub-agent-gets-the-parent-conversation
  (let [system (:system (start-payload
                         (h/spawn (base-state)
                                  {:room-id "r1" :sub-id "sa-1" :task "t"
                                   :transcript "/t.jsonl"})))]
    (is (str/starts-with? system "BASE"))
    (is (str/includes? system "## The parent conversation"))
    (is (str/includes? system "[user]\nmake config a defrecord"))
    (is (str/includes? system "[assistant]\nWriting the schemas namespace."))
    (is (not (str/includes? system "hmm")) "thinking is dropped")
    (is (str/includes? system "/t.jsonl"))))

(deftest parent-context-opt-out
  (let [system (:system (start-payload
                         (h/spawn (base-state)
                                  {:room-id "r1" :sub-id "sa-1" :task "t"
                                   :parent-context? false :system "EXTRA"})))]
    (is (not (str/includes? system "parent conversation")))
    (is (str/ends-with? system "EXTRA"))))

(deftest no-history-and-no-transcript-adds-nothing
  (is (nil? (h/parent-context {:history []} nil))))

(deftest images-reach-the-turn
  (is (= [{:path "/a.png"}]
         (:images (start-payload
                   (h/spawn (base-state)
                            {:room-id "r1" :sub-id "sa-1" :task "t" :images [{:path "/a.png"}]}))))))

(deftest a-spawn-without-an-id-gets-one-before-it-applies
  ;; clients mirror the spawn: an id minted per process would differ
  (let [spawn (get-in sa-ext/extension [:handlers :subagent/spawn])
        res   (spawn (base-state) {:type :subagent/spawn :room-id "r1" :task "t"})
        [[fx ev]] (:effects res)]
    (is (nil? (:state res)))
    (is (= :app/dispatch fx))
    (is (str/starts-with? (:sub-id ev) "sa-"))
    (is (= "t" (:task ev)))
    (is (nil? (h/spawn (base-state) {:room-id "r1" :task "t"}))
        "the shared handler never mints one")))

(deftest subagent-command-spawns-the-task
  (let [cmd (some #(when (= "subagent" (:name %)) %) (:commands sa-ext/extension))
        run (:handler cmd)]
    (is (= ["sa"] (:aliases cmd)))
    (is (= [[:app/dispatch {:type :subagent/spawn :room-id "r1"
                            :task "check the docs" :prompt "check the docs"}]]
           (:effects (run (base-state) {:room-id "r1" :args "  check the docs "}))))
    (is (= :ui/status
           (-> (run (base-state) {:room-id "r1" :args nil}) :effects first second :type))
        "no task: usage")
    (testing "attached images are processed, then handed to the spawn"
      (let [st  (assoc-in (base-state) [:rooms "r1" :ui :pending-images] [{:data "x"}])
            res (run st {:room-id "r1" :args "what is this" :images [{:data "x"}]})]
        (is (= [[:image/process {:room-id "r1" :images [{:data "x"}]
                                 :event {:type :subagent/spawn :room-id "r1"
                                         :task "what is this" :prompt "what is this"}}]]
               (:effects res)))
        (is (empty? (get-in (:state res) [:rooms "r1" :ui :pending-images])))
        (is (= "Attachment"
               (-> (run st {:room-id "r1" :images [{:data "x"}]})
                   :effects first second :event :label))
            "images alone are a task")))))

(deftest run-queued-moves-the-prompt-into-a-sub-agent
  (let [run (get-in sa-ext/extension [:handlers :subagent/run-queued])
        st  (assoc-in (base-state) [:rooms "r1" :agent :queued]
                      [{:text "first"} {:text "  second  " :images [{:path "/i.png"}]}])]
    (testing "drops it from the queue, then spawns it"
      (is (= [[:app/dispatch {:type :prompt/queue-remove :room-id "r1" :index 1}]
              [:app/dispatch {:type :subagent/spawn :room-id "r1" :task "second"
                              :prompt "second" :images [{:path "/i.png"}]}]]
             (:effects (run st {:room-id "r1" :index 1})))))
    (testing "an image-only prompt gets a label"
      (is (= "Queued prompt"
             (-> (run (assoc-in st [:rooms "r1" :agent :queued] [{:images [{:path "/i.png"}]}])
                      {:room-id "r1" :index 0})
                 :effects second second :label))))
    (testing "a stale index is a no-op"
      (is (nil? (run st {:room-id "r1" :index 5}))))))
