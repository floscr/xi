(ns xi.diff.difft-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.diff :as diff]
            [xi.diff.difft :as difft]))

(def old-text "(ns foo\n  (:require [a :as b]))\n\n(defn x [y]\n  (+ y 1))\n\n(defn z [] :keep)\n")

(def new-text "(ns foo\n  (:require [a :as b]\n            [c :as d]))\n\n(defn x [y]\n  (+ y 2))\n\n(defn z [] :keep)\n(defn w [] 3)\n")

;; `difft --display json` of old-text → new-text (difftastic 0.70)
(def json
  (str "{\"aligned_lines\":[[0,0],[1,1],[null,2],[2,3],[3,4],[4,5],[5,6],[6,7],[null,8],[7,9]],"
       "\"chunks\":[[{\"rhs\":{\"line_number\":2,\"changes\":[{\"start\":12,\"end\":13},{\"start\":13,\"end\":14},"
       "{\"start\":15,\"end\":18},{\"start\":19,\"end\":20},{\"start\":20,\"end\":21}]}},"
       "{\"rhs\":{\"line_number\":8,\"changes\":[{\"start\":0,\"end\":13}]}},"
       "{\"lhs\":{\"line_number\":4,\"changes\":[{\"start\":7,\"end\":8}]},"
       "\"rhs\":{\"line_number\":5,\"changes\":[{\"start\":7,\"end\":8}]}}]],"
       "\"language\":\"Clojure\",\"path\":\"/tmp/b.clj\",\"status\":\"changed\"}"))

(defn- line-rows [rows] (filter #(= :line (:row %)) rows))

(deftest split-records-drops-the-preamble
  (is (= [{:path "a.clj" :json "{}" :old "b2xk" :new ""}
          {:path "b.clj" :json "{}" :old "" :new "bmV3"}]
         (difft/split-records
          (str "commit abc\n\n    msg\n"
               "\u001ea.clj\u001f{}\u001fb2xk\u001f\n"
               "\u001eb.clj\u001f{}\u001f\u001fbmV3\n")))))

(deftest file-rows-of-a-change
  (let [rows (difft/file-rows {:path "src/foo.clj" :json json :old old-text :new new-text} 1)
        ls   (mapv :line (line-rows rows))]
    (is (= {:row :file :filename "src/foo.clj" :status :modified :language "Clojure"}
           (first rows)))
    (testing "changes with one line of context, nearby runs joined into one hunk"
      (is (= ["@@ -2 +2 @@"] (map :header (filter #(= :hunk (:row %)) rows))))
      (is (= [[2 2] [nil 3] [3 4] [4 5] [5 6] [6 7] [7 8] [nil 9]]
             (mapv (juxt (comp :n :lhs) (comp :n :rhs)) ls))))
    (testing "without context every change is its own hunk"
      (is (= ["@@ +3 @@" "@@ -5 +6 @@" "@@ +9 @@"]
             (->> (difft/file-rows {:path "f" :json json :old old-text :new new-text} 0)
                  (filter #(= :hunk (:row %)))
                  (map :header)))))
    (testing "spans are joined and only on changed sides"
      (is (= [[12 21]] (:spans (:rhs (nth ls 1)))))
      (is (= [[7 8]] (:spans (:lhs (nth ls 4)))))
      (is (not (:changed? (:lhs (first ls))))))
    (testing "stacks like a unified diff; a line whose text moved counts as changed"
      (is (= [:delete :add :add :context :context :delete :add :context :context :add]
             (map (comp :type :line) (line-rows (diff/as-layout rows :stacked))))))))

(deftest file-rows-of-created-and-binary-files
  (let [created (difft/file-rows {:path "n.clj" :old ""
                                  :json "{\"language\":\"Clojure\",\"status\":\"created\"}"
                                  :new "a\nb\n"})]
    (is (= :added (:status (first created))))
    (is (= [[nil 1] [nil 2]]
           (mapv (comp (juxt (comp :n :lhs) (comp :n :rhs)) :line) (line-rows created)))))
  (is (= [{:row :file :filename "x.png" :status :binary :language "Text"}]
         (difft/file-rows {:path "x.png" :old "" :new "\u0000PNG"
                           :json "{\"language\":\"Text\",\"status\":\"created\"}"}))))

(deftest spans-over-the-whole-line-are-dropped
  (let [json (str "{\"aligned_lines\":[[0,0]],\"chunks\":[[{\"lhs\":{\"line_number\":0,"
                  "\"changes\":[{\"start\":0,\"end\":6},{\"start\":7,\"end\":13}]},"
                  "\"rhs\":{\"line_number\":0,\"changes\":[{\"start\":0,\"end\":3}]}}]],"
                  "\"status\":\"changed\"}")
        line (-> (difft/file-rows {:path "a.txt" :json json :old "héllo wörld\n" :new "new world\n"})
                 line-rows first :line)]
    (is (= {:n 1 :text "héllo wörld" :changed? true} (:lhs line))
        "every word changed: the tint says it, no spans")
    (is (= [[0 3]] (:spans (:rhs line))))))

(deftest spans-count-characters-not-bytes
  (let [json (str "{\"aligned_lines\":[[0,0]],\"chunks\":[[{\"lhs\":{\"line_number\":0,"
                  "\"changes\":[{\"start\":28,\"end\":29}]},\"rhs\":{\"line_number\":0,"
                  "\"changes\":[{\"start\":28,\"end\":29}]}}]],\"status\":\"changed\"}")
        line (-> (difft/file-rows {:path "u.js" :json json
                                   :old "const s = \"héllo\"; let x = 1;\n"
                                   :new "const s = \"héllo\"; let x = 2;\n"})
                 line-rows first :line)]
    (is (= [[27 28]] (:spans (:rhs line))))
    (is (= "2" (let [[s e] (first (:spans (:rhs line)))] (subs (:text (:rhs line)) s e))))
    (is (str/includes? (:text (:lhs line)) "é"))))
