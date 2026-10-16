(ns xi.blocks.hiccup-test
  "The hiccup allowlist against an escape corpus: nothing that can script,
   overlay, restyle or navigate survives."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.blocks.hiccup :as bh]))

(defn- tree [x] (:tree (bh/normalize x)))
(defn- dropped [x] (:dropped (bh/normalize x)))

(deftest json-and-edn-input
  (is (= [:ul {} [:li {} "a"] [:li {} "b"]] (tree ["ul" ["li" "a"] ["li" "b"]])))
  (is (= [:ul {} [:li {} "a"]] (tree "[:ul [:li \"a\"]]")))
  (is (= [:ul {} [:li {} "a"]] (tree "[\"ul\", [\"li\", \"a\"]]")))
  (is (= [:div {} [:h3 {} "x"] [:p {} "y"]] (tree [["h3" "x"] ["p" "y"]])) "a bare list is wrapped")
  (is (= [:p {} "hi"] (tree "hi")))
  (is (nil? (tree "[:div")) "unreadable")
  (is (= ["unreadable hiccup string"] (dropped "[:div"))))

(deftest semantic-attrs-and-ui-tags
  (is (= [:div {:layout "row" :gap "sm"} [:ui/badge {:variant "success"} "ok"]]
         (tree ["div" {:layout "row" :gap "sm"} ["ui/badge" {:variant "success"} "ok"]])))
  (is (= [:ui/button {:action "pick" :value 3 :send true} "Pick"]
         (tree ["ui.button" {:action "pick" :value 3 :send true} "Pick"])))
  (is (= [:div {}] (tree ["div" {:tone "rainbow"}])) "unknown semantic value"))

(def ^:private escapes
  "[hiccup what-must-not-survive]"
  [[["script" "alert(1)"] "script"]
   [["div" {:style "position:fixed;inset:0;z-index:99999"} "overlay"] "style"]
   [["div" {:class "confirm-dialog btn-primary"} "Allow"] "class"]
   [["div" {:id "app"} "x"] ":id"]
   [["div.confirm-dialog#app" "x"] "confirm-dialog"]
   [["div" {:data-xi-kind "permission"} "x"] "data-xi"]
   [["a" {:href "javascript:alert(1)"} "x"] "javascript"]
   [["a" {:href " JaVaScRiPt:alert(1)"} "x"] "JaVaScRiPt"]
   [["a" {:href "data:text/html,<script>"} "x"] "data:text"]
   [["img" {:src "data:image/svg+xml,<svg onload=alert(1)>"}] "svg+xml"]
   [["img" {:src "x" :onerror "alert(1)"}] "onerror"]
   [["button" {:onclick "alert(1)"} "x"] "onclick"]
   [["svg" ["foreignObject" ["div" "x"]]] "foreignObject"]
   [["svg" ["use" {:href "#a"}]] ":use"]
   [["iframe" {:srcdoc "<script>"}] "iframe"]
   [["div" {:srcdoc "<script>"}] "srcdoc"]
   [["object" {:data "x.swf"}] "object"]
   [["style" "body{display:none}"] "display:none"]
   [["link" {:rel "stylesheet" :href "https://evil"}] "stylesheet"]
   [["form" {:action "https://evil"} ["input"]] ":form"]
   [["rect" {:fill "url(#steal)"}] "url("]
   [["meta" {:http-equiv "refresh" :content "0;url=https://evil"}] "refresh"]
   [["base" {:href "https://evil"}] "base"]
   [["svg" ["a" {:xlink:href "javascript:x"} "x"]] "xlink"]
   [["input" {:type "file"}] "file"]
   [["input" {:type "submit"}] "submit"]])

(deftest escape-corpus
  (doseq [[h needle] escapes]
    (testing (pr-str h)
      (is (not (str/includes? (pr-str (tree h)) needle))))))

(deftest controls-declare-actions
  (let [t (tree ["div"
                 ["ui/button" {:action "approve" :send true} "OK"]
                 ["button" {:action "later"} "Later"]
                 ["input" {:type "checkbox" :action "flag"}]
                 ["button" {:action "bad name!"} "x"]])]
    (is (= #{"approve" "later" "flag"} (bh/actions t)))
    (is (= #{"approve"} (bh/send-actions t)))))

(deftest size-limits
  (let [{:keys [tree dropped]} (bh/normalize (into ["div"] (repeat 3000 ["span" "x"])))]
    (is (<= (count (filter vector? (tree-seq vector? seq tree))) (inc bh/max-nodes)))
    (is (some #{"nodes beyond the size limit"} dropped)))
  (let [deep (reduce (fn [acc _] ["div" acc]) "x" (range 60))]
    (is (some #{"nodes nested too deep"} (dropped deep)))))

(deftest text-form
  (is (= "Title\n- a\n- b\n[OK]"
         (bh/text (tree ["div" ["h3" "Title"] ["ul" ["li" "a"] ["li" "b"]] ["ui/button" "OK"]])))))

(deftest inline-components-stay-apart-in-text
  (is (= "a b\n[Nice] [Meh]"
         (bh/text (tree ["div" ["div" {:layout "row"} ["ui/badge" "a"] ["ui/badge" "b"]]
                         ["div" ["ui/button" "Nice"] ["ui/button" "Meh"]]])))))
