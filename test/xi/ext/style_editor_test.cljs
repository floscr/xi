(ns xi.ext.style-editor-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.style-editor :as se]))

(def ^:private injection-fn #'se/injection-fn)
(def ^:private config-json #'se/config-json)
(def ^:private parse-eval-return #'se/parse-eval-return)
(def ^:private format-result #'se/format-result)

(deftest config-json-serializes-selector-title-and-controls
  (let [json (config-json "#app .card"
                          "Card styles"
                          [{:property "borderRadius" :type "range" :min 0 :max 40 :unit "px"}
                           {:property "backgroundColor" :type "color"}])
        m    (js->clj (js/JSON.parse json) :keywordize-keys true)]
    (testing "carries the selector and title"
      (is (= "#app .card" (:selector m)))
      (is (= "Card styles" (:title m))))
    (testing "carries the controls with camelCase properties"
      (is (= "borderRadius" (get-in m [:controls 0 :property])))
      (is (= "color" (get-in m [:controls 1 :type]))))
    (testing "defaults a blank title"
      (let [m2 (js->clj (js/JSON.parse (config-json "a" "" [{:property "opacity"}]))
                        :keywordize-keys true)]
        (is (= "Style editor" (:title m2)))))))

(deftest injection-fn-embeds-config-and-editor
  (let [js (injection-fn "#el" "T" [{:property "borderRadius" :type "range"}])]
    (testing "sets the config with the selector and controls"
      (is (str/includes? js "__XI_STYLE_EDITOR_CFG__"))
      (is (str/includes? js "#el"))
      (is (str/includes? js "borderRadius")))
    (testing "is an arrow function that returns"
      (is (str/starts-with? (str/trim js) "() =>"))
      (is (str/includes? js "return true")))
    (testing "includes the editor script"
      (is (str/includes? js "__xiStyleEditorResult")))))

(deftest parse-eval-return-extracts-fenced-json
  (testing "parses the committed values from evaluate_script's fenced wrapper"
    (let [res {:content [{:type "text"
                          :text (str "Script ran on page and returned:\n"
                                     "```json\n"
                                     "{\"r\":{\"notFound\":false,"
                                     "\"changes\":[{\"selector\":\"#a\",\"property\":\"borderRadius\",\"css\":\"8px\"}]},\"c\":false}\n"
                                     "```")}]
               :is-error false}]
      (is (= "8px" (get-in (parse-eval-return res) [:r :changes 0 :css])))
      (is (false? (get-in (parse-eval-return res) [:r :notFound])))))
  (testing "returns nil on unparseable text"
    (is (nil? (parse-eval-return {:content [{:type "text" :text "boom"}]})))))

(deftest format-result-lists-changes-grouped-by-selector
  (let [text (format-result {:changes [{:selector ".card" :property "borderRadius" :css "12px"}
                                       {:selector ".card" :property "backgroundColor"
                                        :css "rgba(0, 0, 0, 0.5)"}
                                       {:selector ".sidebar" :property "padding" :css "8px"}]})]
    (testing "names each selector"
      (is (str/includes? text "`.card`"))
      (is (str/includes? text "`.sidebar`")))
    (testing "lists each committed property and value"
      (is (str/includes? text "`borderRadius`: `12px`"))
      (is (str/includes? text "`backgroundColor`: `rgba(0, 0, 0, 0.5)`"))
      (is (str/includes? text "`padding`: `8px`")))
    (testing "notes when several elements were tuned"
      (is (str/includes? text "across 2 elements"))))
  (testing "handles no changes gracefully"
    (is (str/includes? (format-result {:changes []}) "(no changes)"))))

(deftest format-result-scope-and-refine
  (testing "apply-to-class instructs editing a shared rule"
    (let [text (format-result {:changes [{:selector ".card" :property "padding" :css "8px"}]
                               :applyToClass true})]
      (is (str/includes? text "shared CSS class"))))
  (testing "apply-to-class lists class candidates"
    (let [text (format-result {:changes [{:selector "#app > div:nth-of-type(2)"
                                          :property "padding" :css "8px"}]
                               :applyToClass true
                               :classCandidates [".project-card" ".sidebar"]})]
      (is (str/includes? text "Candidate classes"))
      (is (str/includes? text "`.project-card`"))
      (is (str/includes? text "`.sidebar`"))
      (testing "warns the positional selector is brittle"
        (is (str/includes? text "brittle")))))
  (testing "apply-to-class with no candidates tells the model to add a shared class"
    (let [text (format-result {:changes [{:selector "body > div" :property "padding" :css "8px"}]
                               :applyToClass true :classCandidates []})]
      (is (str/includes? text "no class"))))
  (testing "element scope instructs editing just these nodes"
    (let [text (format-result {:changes [{:selector ".card" :property "padding" :css "8px"}]
                               :applyToClass false})]
      (is (str/includes? text "just these specific elements"))
      (is (not (str/includes? text "shared CSS class")))))
  (testing "missing controls are reported"
    (let [text (format-result {:changes [{:selector ".card" :property "padding" :css "8px"}]
                               :missing [{:selector ".ghost" :property "opacity"}]})]
      (is (str/includes? text "no element matched"))
      (is (str/includes? text "`.ghost`"))))
  (testing "a refine message is appended as further instructions"
    (let [text (format-result {:changes [{:selector ".card" :property "padding" :css "8px"}]
                               :applyToClass true :refine "tighten the gap too"})]
      (is (str/includes? text "Further instructions"))
      (is (str/includes? text "tighten the gap too"))))
  (testing "a blank refine adds no instructions section"
    (is (not (str/includes? (format-result {:changes [{:selector ".card" :property "padding" :css "8px"}]
                                            :refine ""})
                            "Further instructions")))))

(deftest install-registers-the-tool
  (let [{:keys [tool-definitions tool-registry]} (se/install (fn [_ _] (js/Promise.resolve {})))
        def (first tool-definitions)]
    (testing "exposes a single style_editor tool"
      (is (= 1 (count tool-definitions)))
      (is (= "style_editor" (:name def))))
    (testing "schema requires only controls"
      (is (= ["controls"] (get-in def [:input_schema :required]))))
    (testing "registry has the style_editor handler"
      (is (fn? (get tool-registry "style_editor")))))
  (testing "a control without any selector is rejected without calling the browser"
    (async done
      (let [called (atom false)
            {:keys [tool-registry]} (se/install (fn [_ _] (reset! called true) (js/Promise.resolve {})))
            f (get tool-registry "style_editor")
            res (f {:selector "" :controls [{:property "opacity"}]} {})]
        (is (:is-error res))
        (is (false? @called))
        (done)))))
