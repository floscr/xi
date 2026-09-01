(ns xi.ext.resume-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.resume :as resume]))

;; ── Fixtures ─────────────────────────────────────────────────────────────────

(defn- jline [m] (js/JSON.stringify (clj->js m)))

(defn- assistant-line [& {:keys [tool-uses text]}]
  (jline {:type "assistant"
          :message {:role "assistant"
                    :content (vec (concat
                                   (for [[id name] tool-uses]
                                     {:type "tool_use" :id id :name name :input {}})
                                   (when text [{:type "text" :text text}])))}}))

(defn- tool-result-line [tool-use-id text & {:keys [string?]}]
  (jline {:type "user"
          :message {:role "user"
                    :content [{:type "tool_result"
                               :tool_use_id tool-use-id
                               :content (if string?
                                          text
                                          [{:type "text" :text text}])}]}}))

(def big (apply str (repeat 1000 "x")))
(def small "ok")

(def lines
  [(jline {:type "queue-operation" :operation "enqueue"})
   (assistant-line :tool-uses [["t1" "bash"] ["t2" "read"] ["t3" "grep"]])
   (tool-result-line "t1" big)                 ;; 2: long bash result (array)
   (tool-result-line "t2" big :string? true)   ;; 3: long read result (string)
   (tool-result-line "t3" small)               ;; 4: short grep result
   (assistant-line :text big)                  ;; 5: long assistant message
   (assistant-line :text small)                ;; 6: short assistant message
   (assistant-line :text big)])                ;; 7: long assistant message

;; ── parse-trim-args ──────────────────────────────────────────────────────────

(deftest parse-args
  (is (= {:threshold 500} (resume/parse-trim-args "")))
  (is (= {:threshold 800} (resume/parse-trim-args "800")))
  (is (= -20 (:assistant (resume/parse-trim-args "-20"))))
  (is (= 5 (:assistant (resume/parse-trim-args "+5"))))
  (is (= #{"bash" "read"} (:tools (resume/parse-trim-args "Bash,read"))))
  (testing "shape-based, order-free"
    (is (= {:threshold 800 :assistant -20 :tools #{"bash"}}
           (resume/parse-trim-args "bash 800 -20")))))

;; ── trim-lines preview ───────────────────────────────────────────────────────

(deftest preview-stats
  (let [{:keys [stats lines*]} (assoc (resume/trim-lines lines {:threshold 500} nil)
                                      :lines* nil)]
    (is (= {"bash" {:count 1 :chars 1000}
            "read" {:count 1 :chars 1000}}
           (:tools stats)))
    (is (nil? (:assistant stats)) "assistant untouched without -N/+N")
    (is (= 2000 (resume/stats-total stats)))
    (is (nil? lines*) "preview returns no rewritten lines")))

(deftest tool-filter
  (let [{:keys [stats]} (resume/trim-lines lines {:threshold 500 :tools #{"bash"}} nil)]
    (is (= ["bash"] (keys (:tools stats))))))

(deftest threshold-respected
  (let [{:keys [stats]} (resume/trim-lines lines {:threshold 2000} nil)]
    (is (zero? (resume/stats-total stats)))))

(deftest assistant-selection
  (testing "-N keeps the last N long assistant messages"
    (let [{:keys [stats modified]} (resume/trim-lines lines {:threshold 500 :assistant -1} nil)]
      (is (= {:count 1 :chars 1000} (:assistant stats)))
      (is (contains? modified 5) "older long message trimmed")
      (is (not (contains? modified 7)) "last long message kept")))
  (testing "+N trims the first N long assistant messages"
    (let [{:keys [stats]} (resume/trim-lines lines {:threshold 500 :assistant 2} nil)]
      (is (= 2 (get-in stats [:assistant :count]))))))

;; ── trim-lines apply ─────────────────────────────────────────────────────────

(deftest apply-rewrites
  (let [{:keys [lines modified]}
        (resume/trim-lines lines {:threshold 500} "/tmp/backup.jsonl.bak")]
    (is (= #{2 3} modified))
    (testing "trimmed lines carry a placeholder citing backup + line"
      (let [parsed (js->clj (js/JSON.parse (nth lines 2)) :keywordize-keys true)
            content (get-in parsed [:message :content 0 :content])]
        (is (string? content))
        (is (str/includes? content "/tmp/backup.jsonl.bak line 3"))
        (is (str/includes? content "bash"))
        (is (str/includes? content "1000 chars"))))
    (testing "untouched lines are byte-identical"
      (doseq [i [0 1 4 5 6 7]]
        (is (= (nth xi.ext.resume-test/lines i) (nth lines i)))))
    (testing "result still parses as JSONL"
      (doseq [l lines]
        (is (some? (js/JSON.parse l)))))))
