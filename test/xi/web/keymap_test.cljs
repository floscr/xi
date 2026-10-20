(ns xi.web.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.keys :as keys]
            [xi.web.keymap :as keymap]))

(deftest event-actions
  (keymap/register-action! {:id :chat/new :event {:type :room/new}})
  (testing "an :event action runs by dispatching its event"
    (let [sent (atom [])]
      ((:run (get (keymap/registered-actions) :chat/new)) {} #(swap! sent conj %) nil)
      (is (= [{:type :room/new}] @sent))))
  (testing "event-shortcut finds the key of the action dispatching that event"
    (is (some? (keymap/shortcut {} :chat/new)))
    (is (= (keymap/shortcut {} :chat/new)
           (keymap/event-shortcut {} {:type :room/new}))))
  (testing "no match → nil"
    (is (nil? (keymap/event-shortcut {} {:type :room/new :extra 1})))
    (is (nil? (keymap/event-shortcut {} nil))))
  (keymap/unregister-action! :chat/new))

(defn- keydown [m]
  (clj->js (merge {:type "keydown" :ctrlKey false :altKey false :metaKey false
                   :shiftKey false}
                  m)))

(deftest terminal-keeps-only-its-layers-keys-for-xi
  (testing "Ctrl+` (the physical key left of 1, any layout) leaves by default"
    (is (keymap/terminal-key? {} (keydown {:key "`" :code "Backquote" :ctrlKey true})))
    (is (keymap/terminal-key? {} (keydown {:key "^" :code "Backquote" :ctrlKey true}))))
  (testing "so does Alt+\\, on the US key and on the ISO one next to Shift"
    (is (keymap/terminal-key? {} (keydown {:key "\\" :code "Backslash" :altKey true})))
    (is (keymap/terminal-key? {} (keydown {:key "<" :code "IntlBackslash" :altKey true}))))
  (testing "everything else is the program's, Xi's other keys included"
    (is (not (keymap/terminal-key? {} (keydown {:key "Escape" :code "Escape"}))))
    (is (not (keymap/terminal-key? {} (keydown {:key "b" :code "KeyB" :altKey true}))))
    (is (not (keymap/terminal-key? {} (keydown {:key "`" :code "Backquote" :ctrlKey true
                                                :type "keyup"})))))
  (testing "a user binding in :mode/terminal counts, an unbind removes it"
    (let [km {:mode/terminal {"alt+b" :buffers/switch "ctrl+`" nil}}]
      (is (keymap/terminal-key? {:web/keymap km} (keydown {:key "b" :code "KeyB" :altKey true})))
      (is (not (keymap/terminal-key? {:web/keymap km}
                                     (keydown {:key "`" :code "Backquote" :ctrlKey true})))))))

(deftest run-action-by-id
  (keymap/register-action! {:id :palette/open :event {:type :palette/open}})
  (keymap/register-action! {:id :themes/pick :event {:type :palette/open-themes}
                            :when (constantly false)})
  (let [sent (atom [])
        run-id! #(keymap/run-action! {} (fn [ev] (swap! sent conj ev)) %)]
    (testing "a registered action runs as if its key was pressed"
      (is (true? (run-id! :palette/open)))
      (is (= [{:type :palette/open}] @sent)))
    (testing "its guard and unknown ids keep it from running"
      (reset! sent [])
      (is (nil? (run-id! :themes/pick)))
      (is (nil? (run-id! :no/such-action)))
      (is (= [] @sent))))
  (keymap/unregister-action! :palette/open)
  (keymap/unregister-action! :themes/pick))

;; ── palette-hotkey (the command palette's own key) ───────────────────────────

(defn- with-user-keys [user]
  {:web/keymap (keys/effective-keymap {:surface :web :user user})})

(deftest palette-hotkey-follows-a-global-binding
  (testing "the built-in key without a binding"
    (is (= "mod+k" (keymap/palette-hotkey {}))))
  (testing "a :global modifier chord becomes the dialog's hotkey"
    (is (= "mod+x" (keymap/palette-hotkey (with-user-keys {:global {"ctrl+x" :palette/open}}))))
    (is (= "mod+x" (keymap/palette-hotkey (with-user-keys {:global {"meta+x" :palette/open}}))))
    (is (= "alt+p" (keymap/palette-hotkey (with-user-keys {:global {"alt+p" :palette/open}}))))
    (is (= "mod+shift+p" (keymap/palette-hotkey (with-user-keys {:global {"ctrl+shift+p" :palette/open}})))))
  (testing "sequences, bare keys and other layers keep the built-in key"
    (is (= "mod+k" (keymap/palette-hotkey (with-user-keys {:global {"g p" :palette/open}}))))
    (is (= "mod+k" (keymap/palette-hotkey (with-user-keys {:global {"p" :palette/open}}))))
    (is (= "mod+k" (keymap/palette-hotkey (with-user-keys {:mode/navigate {"ctrl+x" :palette/open}}))))))
