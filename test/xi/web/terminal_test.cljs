(ns xi.web.terminal-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.terminal :as terminal]))

(deftest osc-names-a-view-action
  (testing "a payload naming an allowed action"
    (is (= :palette/open (terminal/osc-action "palette/open")))
    (is (= :buffers/switch (terminal/osc-action " buffers/switch\n"))))
  (testing "anything else is ignored: output can carry the sequence"
    (is (nil? (terminal/osc-action "chat/delete")))
    (is (nil? (terminal/osc-action "permission/allow")))
    (is (nil? (terminal/osc-action "")))
    (is (nil? (terminal/osc-action nil)))))

(deftest osc52-puts-text-on-the-clipboard
  (testing "base64 decodes as UTF-8, whichever selection is named"
    (is (= "hi" (terminal/osc52-text "c;aGk=")))
    (is (= "hi" (terminal/osc52-text ";aGk=")))
    (is (= "äö→" (terminal/osc52-text "c;w6TDtuKGkg=="))))
  (testing "a query or broken payload sets nothing"
    (is (nil? (terminal/osc52-text "c;?")))
    (is (nil? (terminal/osc52-text "c")))
    (is (nil? (terminal/osc52-text "c;!!!")))))

(defn- hint-payload [data]
  (js/btoa (apply str (map char (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js data)))))))

(deftest osc-hint-reads-a-tooltip
  (testing "a cell and its items; unknown severities read as info"
    (is (= {:row 3 :col 7
            :items [{:severity "error" :text "Unresolved symbol: fo→" :source "clj-kondo"}
                    {:severity "info" :text "line 1\nline 2" :source nil}]}
           (terminal/osc-hint
            (hint-payload {:row 3 :col 7
                           :items [{:severity "error" :text "Unresolved symbol: fo→" :source "clj-kondo"}
                                   {:severity "fatal" :text "line 1\nline 2" :source ""}]})))))
  (testing "items without text are dropped"
    (is (= ["kept"]
           (->> (terminal/osc-hint (hint-payload {:row 0 :col 0
                                                  :items [{:text ""} {:text 5} "x" {:text "kept"}]}))
                :items (map :text)))))
  (testing "empty, broken or itemless payloads hide it"
    (is (nil? (terminal/osc-hint "")))
    (is (nil? (terminal/osc-hint "!!!")))
    (is (nil? (terminal/osc-hint (js/btoa "not json"))))
    (is (nil? (terminal/osc-hint (hint-payload {:row 0 :col 0 :items []}))))
    (is (nil? (terminal/osc-hint (hint-payload {:row -1 :col 0 :items [{:text "x"}]}))))
    (is (nil? (terminal/osc-hint (hint-payload [1 2 3]))))))
