(ns xi.tui.keys-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.keys :as keys]
            [xi.tui.keys :as tui-keys]))

(def ^:private ESC (str (char 27)))

(deftest decode-plain-and-control
  (is (= "a" (tui-keys/decode "a")))
  (is (= "shift+g" (tui-keys/decode "G")))
  (is (= "?" (tui-keys/decode "?")))
  (is (= ":" (tui-keys/decode ":")))
  (is (= "ctrl+a" (tui-keys/decode (str (char 1)))))
  (is (= "ctrl+o" (tui-keys/decode (str (char 15)))))
  (is (= "ctrl+p" (tui-keys/decode (str (char 16)))))
  (is (= "ctrl+/" (tui-keys/decode (str (char 31)))))
  (is (= "enter" (tui-keys/decode "\r")))
  (is (= "enter" (tui-keys/decode "\n")))
  (is (= "tab" (tui-keys/decode "\t")))
  (is (= "backspace" (tui-keys/decode (str (char 127)))))
  (is (= "escape" (tui-keys/decode ESC))))

(deftest decode-legacy-sequences
  (is (= "up" (tui-keys/decode (str ESC "[A"))))
  (is (= "down" (tui-keys/decode (str ESC "[B"))))
  (is (= "up" (tui-keys/decode (str ESC "OA"))) "SS3 application mode")
  (is (= "home" (tui-keys/decode (str ESC "[H"))))
  (is (= "delete" (tui-keys/decode (str ESC "[3~"))))
  (is (= "pageup" (tui-keys/decode (str ESC "[5~"))))
  (is (= "pagedown" (tui-keys/decode (str ESC "[6~"))))
  (is (= "shift+tab" (tui-keys/decode (str ESC "[Z"))))
  (is (= "shift+enter" (tui-keys/decode (str ESC "OM"))))
  (is (= "ctrl+up" (tui-keys/decode (str ESC "[1;5A"))))
  (is (= "shift+pageup" (tui-keys/decode (str ESC "[5;2~")))))

(deftest decode-alt-prefix
  (is (= "alt+n" (tui-keys/decode (str ESC "n"))))
  (is (= "alt+x" (tui-keys/decode (str ESC "x"))))
  (is (= "alt+shift+p" (tui-keys/decode (str ESC "P"))))
  (is (= "alt+/" (tui-keys/decode (str ESC "/"))))
  (is (= "alt+enter" (tui-keys/decode (str ESC "\r"))))
  (is (= "alt+backspace" (tui-keys/decode (str ESC (char 127)))))
  (is (= "ctrl+alt+a" (tui-keys/decode (str ESC (char 1))))))

(deftest decode-kitty-csi-u
  (is (= "escape" (tui-keys/decode (str ESC "[27u"))))
  (is (= "alt+n" (tui-keys/decode (str ESC "[110;3u"))))
  (is (= "alt+a" (tui-keys/decode (str ESC "[97;3u"))))
  (is (= "ctrl+p" (tui-keys/decode (str ESC "[112;5u"))))
  (is (= "ctrl+o" (tui-keys/decode (str ESC "[111;5u"))))
  (is (= "ctrl+shift+n" (tui-keys/decode (str ESC "[110;6u"))))
  (is (= "ctrl+/" (tui-keys/decode (str ESC "[47;5u"))))
  (is (= "shift+enter" (tui-keys/decode (str ESC "[13;2u"))))
  (is (= "alt+enter" (tui-keys/decode (str ESC "[13;3u"))))
  (is (= "shift+tab" (tui-keys/decode (str ESC "[9;2u"))))
  (is (= "ctrl+i" (tui-keys/decode (str ESC "[105;5u"))))
  (is (= "shift+g" (tui-keys/decode (str ESC "[103:71;2u"))) "shifted-key alternate")
  (is (= "ctrl+alt+shift+meta+x" (tui-keys/decode (str ESC "[120;16u"))))
  (is (= "f5" (tui-keys/decode (str ESC "[57368u"))))
  (is (= "ctrl+space" (tui-keys/decode (str ESC "[32;5u")))))

(deftest decode-rejects-non-keys
  (is (nil? (tui-keys/decode nil)))
  (is (nil? (tui-keys/decode "")))
  (is (nil? (tui-keys/decode "ab")) "several characters at once")
  (is (nil? (tui-keys/decode (str ESC "[200~pasted" ESC "[201~"))) "bracketed paste")
  (is (nil? (tui-keys/decode (str ESC "[?1u")))))

(deftest keymap-install-and-helpers
  (let [ext [(keys/ext-keybinding->action {:key "alt+p" :label "Pick a project"
                                           :event {:type :project/open}})]]
    (tui-keys/set-keymap! {:global {"alt+n" nil "ctrl+shift+n" :chat/new}
                           :tui {:buffer/pager {"J" :pager/half-down}}}
                          ext)
    (try
      (testing "defaults ← extension keys ← user keys"
        (is (= :project/open (:action (tui-keys/lookup [:global] [] "alt+p"))))
        (is (= :chat/new (:action (tui-keys/lookup [:global] [] "ctrl+shift+n"))))
        (is (= {:status :unbound :masked? true} (tui-keys/lookup [:global] [] "alt+n")))
        (is (= :pager/half-down (:action (tui-keys/lookup tui-keys/pager-layers [] "shift+j"))))
        (is (= :pager/down (:action (tui-keys/lookup tui-keys/pager-layers [] "j"))))
        (is (= {:status :pending :pending ["g"]} (tui-keys/lookup tui-keys/pager-layers [] "g")))
        (is (= :pager/top (:action (tui-keys/lookup tui-keys/pager-layers ["g"] "g")))))
      (testing "help bars read the bound keys"
        (let [bar (tui-keys/help-bar tui-keys/pager-layers
                                     [[[:pager/down :pager/up] "move"]
                                      [[:pager/top :pager/bottom] "top/bottom"]
                                      [[:diff/edit] "edit"]])]
          (is (str/includes? bar "j/k"))
          (is (str/includes? bar "gg/G"))
          (is (not (str/includes? bar "edit")) "an unbound action is left out")))
      (testing "the listing names layers, labels and marks user changes"
        (let [text (tui-keys/listing-text [:mode/compose :global])]
          (is (str/includes? text "Everywhere"))
          (is (str/includes? text "Pick a project"))
          (is (str/includes? text "Ctrl+Shift+N"))
          (is (str/includes? text "*"))
          (is (str/includes? text "Cursor down"))))
      (finally
        (tui-keys/set-keymap! nil [])))))
