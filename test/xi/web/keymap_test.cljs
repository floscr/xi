(ns xi.web.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
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
