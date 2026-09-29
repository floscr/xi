(ns xi.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.session :as session]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(deftest encode-cwd-xi-basic
  (testing "absolute path is encoded with leading dash"
    (is (= "-home-floscr-Code-Projects-xi"
           (session/encode-cwd-xi "/home/floscr/Code/Projects/xi")))))

(deftest encode-cwd-xi-root
  (testing "root path"
    (is (= "-"
           (session/encode-cwd-xi "/")))))

(deftest encode-cwd-xi-relative
  (testing "relative path gets leading dash"
    (is (= "-foo-bar"
           (session/encode-cwd-xi "foo/bar")))))

;; ── Helpers for JSONL tests ───────────────────────────────────────────────────

(defn- write-tmp-jsonl
  "Write lines to a temp .jsonl file. Returns filepath."
  [lines]
  (let [dir (.mkdtempSync fs (.join path (os/tmpdir) "xi-test-"))
        filepath (.join path dir "test.jsonl")]
    (fs/writeFileSync filepath (str (clojure.string/join "\n" lines) "\n") "utf8")
    filepath))

(defn- claude-summary
  "Build a minimal :claude summary for read-session-messages."
  [filepath]
  {:source :claude :filepath filepath})

(defn- make-user-line [text]
  (js/JSON.stringify
   #js {:type "user"
        :message #js {:content text}}))

(defn- make-assistant-line [text]
  (js/JSON.stringify
   #js {:type "assistant"
        :message #js {:content #js [#js {:type "text" :text text}]}}))

;; ── read-session-messages tests ───────────────────────────────────────────────

(deftest read-session-messages-whitespace-lines
  (testing "whitespace-only lines in JSONL don't cause 0 messages"
    (let [filepath (write-tmp-jsonl
                    [(make-user-line "hello")
                     (make-assistant-line "hi there")
                     "   " ;; whitespace-only line
                     ""])]
      (is (= 2 (count (session/read-session-messages (claude-summary filepath))))))))

(deftest read-session-messages-corrupt-line
  (testing "a corrupt JSON line is skipped, other messages still parse"
    (let [filepath (write-tmp-jsonl
                    [(make-user-line "first")
                     "{not valid json"
                     (make-assistant-line "second")])]
      (is (= 2 (count (session/read-session-messages (claude-summary filepath))))))))

(deftest read-session-messages-empty-file
  (testing "empty file returns empty vec"
    (let [filepath (write-tmp-jsonl [""])]
      (is (= [] (session/read-session-messages (claude-summary filepath)))))))

(defn- make-tool-result-with-image-line
  "A user message echoing a tool_result whose content is a text caption plus an
   API-shape image block (what view_image produces on disk)."
  [tool-use-id caption data]
  (js/JSON.stringify
   #js {:type "user"
        :message
        #js {:content
             #js [#js {:type "tool_result"
                       :tool_use_id tool-use-id
                       :content #js [#js {:type "text" :text caption}
                                     #js {:type "image"
                                          :source #js {:type "base64"
                                                       :media_type "image/png"
                                                       :data data}}]}]}}))

(deftest read-session-messages-preserves-tool-result-image
  (testing "a resumed view_image tool_result keeps its image block, not just text"
    (let [filepath (write-tmp-jsonl
                    [(make-tool-result-with-image-line
                      "t1" "Viewed image: /tmp/pic.png" "AAAA")])
          msgs     (session/read-session-messages (claude-summary filepath))
          result   (first (filter #(= :tool-result (:type %)) msgs))
          content  (:content result)]
      (is (sequential? content) "content is a vec of blocks, not a flat string")
      (is (some #(= "image" (:type %)) content) "the image block survives")
      (let [img (first (filter #(= "image" (:type %)) content))]
        (is (= "AAAA" (get-in img [:source :data])) "image data preserved")
        (is (= "image/png" (get-in img [:source :media_type])) "media type preserved"))
      (is (some #(and (= "text" (:type %))
                      (str/includes? (:text %) "Viewed image")) content)
          "the text caption is kept alongside the image"))))

(deftest truncate-message-results-keeps-image-blocks
  (testing "clipping a vec-content tool-result clips text but keeps image blocks"
    (let [long-text (str/join "\n" (map str (range 250)))
          messages  [{:type :tool-result :tool-use-id "t1"
                      :content [{:type "text" :text long-text}
                                {:type "image"
                                 :source {:type "base64" :media_type "image/png" :data "AAAA"}}]}]
          out       (session/truncate-message-results messages)
          content   (:content (first out))
          text-blk  (first (filter #(= "text" (:type %)) content))
          img-blk   (first (filter #(= "image" (:type %)) content))]
      (is (<= (count (str/split-lines (:text text-blk)))
              session/resume-result-line-cap)
          "text block clipped to the cap")
      (is (= "AAAA" (get-in img-blk [:source :data])) "image block untouched"))))

;; ── Favorites ─────────────────────────────────────────────────────────────────

(deftest annotate-favorites-tags-matching-ids
  (testing "summaries whose session-id is in the set get :favorite? true"
    (let [summaries [{:session-id "a" :name "one"}
                     {:session-id "b" :name "two"}
                     {:session-id "c" :name "three"}]
          tagged (session/annotate-favorites summaries #{"a" "c"})]
      (is (= [true false true] (mapv :favorite? tagged)))
      (is (= ["one" "two" "three"] (mapv :name tagged))
          "other keys are preserved"))))

(deftest annotate-favorites-empty-set
  (testing "empty favorites set tags everything false"
    (is (= [false false]
           (mapv :favorite?
                 (session/annotate-favorites
                  [{:session-id "x"} {:session-id "y"}] #{}))))))

(def ^:private favorites-file
  (.join path (or (aget js/process.env "HOME") (os/homedir)) ".config" "xi" "favorites.json"))

(defn- with-favorites-backup
  "Run f with the real favorites file snapshotted and restored afterwards, so
   the round-trip test can't clobber the user's bookmarks."
  [f]
  (let [existed? (fs/existsSync favorites-file)
        backup   (when existed? (fs/readFileSync favorites-file "utf8"))]
    (try
      (f)
      (finally
        (if existed?
          (fs/writeFileSync favorites-file backup "utf8")
          (when (fs/existsSync favorites-file)
            (fs/rmSync favorites-file)))))))

(deftest truncate-message-results-clips-tool-results
  (testing "tool-result text is clipped to the resume cap; other blocks pass through"
    (let [long-text (str/join "\n" (map str (range 250)))
          messages  [{:type :text :role "user" :text "hi"}
                     {:type :tool-use :name "bash" :tool-use-id "t1" :arguments {}}
                     {:type :tool-result :tool-use-id "t1" :content long-text}
                     {:type :text :role "assistant" :text "done"}]
          out       (session/truncate-message-results messages)
          result    (nth out 2)]
      (is (<= (count (str/split-lines (:content result)))
              session/resume-result-line-cap)
          "result clipped to the cap")
      (is (str/includes? (:content result) "more lines)") "marker present")
      (is (= (nth messages 0) (nth out 0)) "text block untouched")
      (is (= (nth messages 1) (nth out 1)) "tool-use block untouched")
      (is (= (nth messages 3) (nth out 3)) "assistant block untouched"))))

(deftest truncate-message-results-short-unchanged
  (testing "a short tool-result is returned unchanged"
    (let [messages [{:type :tool-result :tool-use-id "t1" :content "one\ntwo"}]]
      (is (= messages (session/truncate-message-results messages))))))

(deftest toggle-favorite-round-trip
  (testing "toggle adds then removes a session-id, load/favorite? reflect it"
    (with-favorites-backup
      (fn []
        (let [id (str "test-fav-" (js/Date.now))]
          (is (false? (session/favorite? id)) "not favorited initially")
          (is (true? (session/toggle-favorite! id)) "toggle on returns true")
          (is (true? (session/favorite? id)) "now favorited")
          (is (contains? (session/load-favorites) id) "present in the set")
          (is (false? (session/toggle-favorite! id)) "toggle off returns false")
          (is (false? (session/favorite? id)) "no longer favorited"))))))

;; ── Dismissed (hidden from Recent) ────────────────────────────────────────────

(deftest annotate-dismissed-tags-matching-ids
  (testing "summaries whose session-id is in the set get :dismissed? true"
    (let [summaries [{:session-id "a" :name "one"}
                     {:session-id "b" :name "two"}
                     {:session-id "c" :name "three"}]
          tagged (session/annotate-dismissed summaries #{"a" "c"})]
      (is (= [true false true] (mapv :dismissed? tagged)))
      (is (= ["one" "two" "three"] (mapv :name tagged))
          "other keys are preserved"))))

(deftest annotate-dismissed-empty-set
  (testing "empty dismissed set tags everything false"
    (is (= [false false]
           (mapv :dismissed?
                 (session/annotate-dismissed
                  [{:session-id "x"} {:session-id "y"}] #{}))))))

(deftest toggle-dismissed-round-trip
  (testing "toggle adds then removes a session-id, load-dismissed reflects it (in-memory)"
    (let [id (str "test-dismiss-" (js/Date.now))]
      (is (not (contains? (session/load-dismissed) id)) "not dismissed initially")
      (is (true? (session/toggle-dismissed! id)) "toggle on returns true")
      (is (contains? (session/load-dismissed) id) "present in the set")
      (is (false? (session/toggle-dismissed! id)) "toggle off returns false")
      (is (not (contains? (session/load-dismissed) id)) "no longer dismissed"))))

;; ── transcript-turn-complete? ─────────────────────────────────────────────────

(deftest transcript-turn-complete-end-turn
  (testing "a turn whose last assistant message ended with end_turn is complete, trailing metadata ignored"
    (is (session/transcript-turn-complete?
         [{:type "user" :message {:content "audit the docs"}}
          {:type "assistant" :message {:stop_reason "end_turn" :content [{:type "text" :text "Audit: …"}]}}
          {:type "last-prompt"}
          {:type "cost-state"}]))))

(deftest transcript-turn-complete-cut-off
  (testing "a turn cut off mid-flight is not complete"
    (is (not (session/transcript-turn-complete?
              [{:type "user" :message {:content "go"}}
               {:type "assistant" :message {:stop_reason "tool_use" :content [{:type "tool_use"}]}}])))
    (is (not (session/transcript-turn-complete?
              [{:type "assistant" :message {:stop_reason "tool_use"}}
               {:type "user" :message {:content [{:type "tool_result"}]}}
               {:type "attachment"}])))
    (is (not (session/transcript-turn-complete?
              [{:type "assistant" :message {:stop_reason "end_turn"}}
               {:type "user" :message {:content "next prompt"}}])))
    (is (not (session/transcript-turn-complete? [])))))
