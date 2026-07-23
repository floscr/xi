(ns xi.ext.element-picker-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.element-picker :as ep]))

(def ^:private build-message #'ep/build-message)
(def ^:private injection-fn #'ep/injection-fn)
(def ^:private parse-eval-return #'ep/parse-eval-return)
(def ^:private selected-page-line #'ep/selected-page-line)
(def ^:private parse-pages #'ep/parse-pages)
(def ^:private page-options #'ep/page-options)

(deftest injection-fn-embeds-config-and-picker
  (let [js (injection-fn "make it wider")]
    (testing "sets the config with the prefill message and colors"
      (is (str/includes? js "__XI_PICKER_CFG__"))
      (is (str/includes? js "make it wider"))
      (is (str/includes? js "#6366f1")))
    (testing "is an arrow function that returns"
      (is (str/starts-with? (str/trim js) "() =>"))
      (is (str/includes? js "return true")))
    (testing "includes the picker script"
      (is (str/includes? js "__xiPickerResult")))))

(deftest parse-eval-return-extracts-fenced-json
  (testing "parses the JSON value from evaluate_script's fenced wrapper"
    (let [res {:content [{:type "text"
                          :text "Script ran on page and returned:\n```json\n{\"r\":null,\"c\":true}\n```"}]
               :is-error false}]
      (is (= {:r nil :c true} (parse-eval-return res)))))
  (testing "parses a nested result object"
    (let [res {:content [{:type "text"
                          :text "```json\n{\"r\":{\"elements\":[{\"selector\":\"a\"}]},\"c\":false}\n```"}]}]
      (is (= "a" (get-in (parse-eval-return res) [:r :elements 0 :selector])))))
  (testing "returns nil on unparseable text"
    (is (nil? (parse-eval-return {:content [{:type "text" :text "boom"}]})))))

(deftest selected-page-line-finds-marked-tab
  (let [res {:content [{:type "text"
                        :text "## Pages\n0: http://a/ \n1: http://b/ [selected]\n2: http://c/"}]}]
    (testing "returns the selected line without the marker"
      (is (= "1: http://b/" (selected-page-line res))))
    (testing "nil when nothing is selected"
      (is (nil? (selected-page-line {:content [{:type "text" :text "0: http://a/"}]}))))))

(deftest parse-pages-extracts-id-label-and-selection
  (let [res {:content [{:type "text"
                        :text (str "## Pages\n"
                                   "0: Home (http://a/)\n"
                                   "1: Docs (http://b/) [selected]\n"
                                   "2: http://c/ isolatedContext=foo")}]}
        pages (parse-pages res)]
    (testing "parses each page line into id/label/selected?"
      (is (= [{:id 0 :label "Home (http://a/)" :selected? false}
              {:id 1 :label "Docs (http://b/)" :selected? true}
              {:id 2 :label "http://c/" :selected? false}]
             pages)))
    (testing "strips the isolatedContext suffix from the label"
      (is (= "http://c/" (:label (nth pages 2)))))
    (testing "skips non-page lines like the header"
      (is (= 3 (count pages)))))
  (testing "empty when there are no page lines"
    (is (= [] (parse-pages {:content [{:type "text" :text "no pages here"}]})))))

(deftest page-options-tags-the-current-tab
  (let [opts (page-options [{:id 0 :label "A" :selected? false}
                            {:id 1 :label "B" :selected? true}])]
    (testing "maps to {:label :value} with the id as value"
      (is (= 0 (:value (first opts))))
      (is (= 1 (:value (second opts)))))
    (testing "marks the selected page as current"
      (is (= "A" (:label (first opts))))
      (is (str/includes? (:label (second opts)) "[current]")))))

(deftest install-registers-command-and-fx
  (let [{:keys [commands keybindings fx]} (ep/install (fn [_ _] (js/Promise.resolve {})))
        cmd (first commands)]
    (testing "command is /pick"
      (is (= "pick" (:name cmd))))
    (testing "handler dispatches the run effect with room + prefill"
      (let [{:keys [effects]} ((:handler cmd) {} {:room-id "r1" :args "fix this"})]
        (is (= [[:ext.element-picker/run {:room-id "r1" :prefill "fix this"}]]
               effects))))
    (testing "no args → empty prefill"
      (let [{:keys [effects]} ((:handler cmd) {} {:room-id "r1"})]
        (is (= "" (get-in (first effects) [1 :prefill])))))
    (testing "keybinding runs the pick command"
      (is (= {:type :command/run :name "pick"} (:event (first keybindings)))))
    (testing "fx handler is registered"
      (is (fn? (get fx :ext.element-picker/run))))))

(deftest build-message-single-element
  (let [result {:url "http://localhost:3001/"
                :elements [{:selector "div#app > main"
                            :tagName "main"
                            :message "make it wider"
                            :outerHTML "<main>hi</main>"
                            :computedStyles {:display "flex"}}]}
        text (build-message "" result)]
    (testing "includes source url, message, selector, tag, styles and html"
      (is (str/includes? text "**Picked from:** http://localhost:3001/"))
      (is (str/includes? text "**Message:** make it wider"))
      (is (str/includes? text "**Selector:** `div#app > main`"))
      (is (str/includes? text "**Tag:** `<main>`"))
      (is (str/includes? text "\"display\""))
      (is (str/includes? text "<main>hi</main>")))
    (testing "single element has no per-element numbering"
      (is (not (str/includes? text "### Element"))))))

(deftest build-message-multi-element-and-prefill-fallback
  (let [result {:url "http://x/"
                :elements [{:selector "a" :tagName "a" :message "" :outerHTML "<a/>"}
                           {:selector "b" :tagName "b" :message "second" :outerHTML "<b/>"}]}
        text (build-message "global note" result)]
    (testing "multi element gets a count header and per-element sections"
      (is (str/includes? text "**Elements:** 2"))
      (is (str/includes? text "### Element 1"))
      (is (str/includes? text "### Element 2")))
    (testing "empty per-element message falls back to prefill"
      (is (str/includes? text "**Message:** global note")))
    (testing "per-element message overrides prefill"
      (is (str/includes? text "**Message:** second")))))
