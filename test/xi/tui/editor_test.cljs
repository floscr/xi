(ns xi.tui.editor-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.editor :as editor]))

;; Stub out tui/request-render! since it's a no-op in tests
;; The editor calls it but we just need the state changes

(defn- make-test-editor []
  (editor/make-editor {:on-submit (fn [_])}))

(defn- type-chars
  "Simulate typing each character of a string into the editor."
  [editor s]
  (doseq [ch s]
    ((:handle-input editor) (str ch))))

(defn- ctrl-char [ch]
  (str (char (- (.charCodeAt ch 0) 64))))

(defn- send-ctrl [editor ch]
  ((:handle-input editor) (ctrl-char ch)))

(def ^:private ESC (str (char 27)))

(defn- send-key [editor key-data]
  ((:handle-input editor) key-data))

(defn- editor-text [editor]
  ((:get-text editor)))

;; ── Undo Tests ────────────────────────────────────────────────────────────────

(deftest undo-continuous-typing-is-one-step
  (testing "typing multiple chars then undo reverts all at once"
    (let [ed (make-test-editor)]
      (type-chars ed "hello")
      (is (= "hello" (editor-text ed)))
      ;; Ctrl+Z — undo
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed))))))

(deftest undo-with-cursor-movement-breaks-group
  (testing "typing, moving cursor, then typing creates two undo groups"
    (let [ed (make-test-editor)]
      (type-chars ed "abc")
      ;; Move left (arrow key) — breaks undo group
      (send-key ed (str ESC "[D"))
      (type-chars ed "X")
      (is (= "abXc" (editor-text ed)))
      ;; First undo: removes "X"
      (send-ctrl ed "Z")
      (is (= "abc" (editor-text ed)))
      ;; Second undo: removes "abc"
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed))))))

(deftest undo-delete-is-separate-group
  (testing "typing then backspace creates separate undo groups"
    (let [ed (make-test-editor)]
      (type-chars ed "hello")
      ;; Backspace (DEL char 127)
      (send-key ed (str (char 127)))
      (send-key ed (str (char 127)))
      (is (= "hel" (editor-text ed)))
      ;; Undo the deletes (grouped)
      (send-ctrl ed "Z")
      (is (= "hello" (editor-text ed)))
      ;; Undo the typing
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed))))))

(deftest redo-works
  (testing "Ctrl+Y redoes an undone action"
    (let [ed (make-test-editor)]
      (type-chars ed "hello")
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed)))
      ;; Ctrl+Y — redo
      (send-ctrl ed "Y")
      (is (= "hello" (editor-text ed))))))

(deftest redo-cleared-on-new-edit
  (testing "new edit after undo clears the redo stack"
    (let [ed (make-test-editor)]
      (type-chars ed "hello")
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed)))
      ;; Type something new — should clear redo
      (type-chars ed "world")
      ;; Redo should do nothing
      (send-ctrl ed "Y")
      (is (= "world" (editor-text ed))))))

(deftest undo-paste-is-one-step
  (testing "pasting multi-line text is a single undo step"
    (let [ed (make-test-editor)]
      (type-chars ed "before")
      ;; Move cursor to break group
      (send-key ed (str ESC "[F"))  ;; End key
      ;; Simulate paste (bracketed paste)
      (send-key ed (str ESC "[200~" "line1\nline2" ESC "[201~"))
      (is (= "beforeline1\nline2" (editor-text ed)))
      ;; Undo paste
      (send-ctrl ed "Z")
      (is (= "before" (editor-text ed)))
      ;; Undo typing
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed))))))

(deftest undo-kill-line-is-one-step
  (testing "Ctrl+U (kill line) is a single undo step"
    (let [ed (make-test-editor)]
      (type-chars ed "hello world")
      ;; Move to end to break group, then kill
      (send-key ed (str ESC "[F"))
      (send-ctrl ed "U")
      (is (= "" (editor-text ed)))
      ;; Undo kill
      (send-ctrl ed "Z")
      (is (= "hello world" (editor-text ed))))))

(deftest undo-empty-stack-is-noop
  (testing "undo on empty editor does nothing"
    (let [ed (make-test-editor)]
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed))))))

(deftest multiple-undo-redo-round-trip
  (testing "undo then redo preserves state"
    (let [ed (make-test-editor)]
      (type-chars ed "aaa")
      (send-key ed (str ESC "[D"))  ;; arrow left to break group
      (type-chars ed "bbb")
      ;; State: "aabbba"
      ;; Undo "bbb"
      (send-ctrl ed "Z")
      (is (= "aaa" (editor-text ed)))
      ;; Redo "bbb"
      (send-ctrl ed "Y")
      (is (= "aabbba" (editor-text ed)))
      ;; Undo "bbb" again
      (send-ctrl ed "Z")
      (is (= "aaa" (editor-text ed)))
      ;; Undo "aaa"
      (send-ctrl ed "Z")
      (is (= "" (editor-text ed))))))

(deftest on-backspace-can-claim-the-key
  (let [seen (atom [])
        ed   (editor/make-editor {:on-submit (fn [_])
                                  :on-backspace (fn [text]
                                                  (swap! seen conj text)
                                                  (= "ab" text))})]
    (type-chars ed "ab")
    (testing "a truthy return skips the deletion"
      (send-key ed (str (char 127)))
      (is (= "ab" (editor-text ed))))
    (testing "a falsy return deletes as usual"
      (type-chars ed "c")
      (send-key ed (str (char 127)))
      (is (= "ab" (editor-text ed))))
    (is (= ["ab" "abc"] @seen))))
