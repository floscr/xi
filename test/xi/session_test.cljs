(ns xi.session-test
  (:require [cljs.test :refer [deftest is testing]]
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
