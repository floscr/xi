(ns xi.keys-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.keys :as keys]))

(deftest chord-notation
  (testing "modifiers, aliases and case"
    (is (= "alt+n" (keys/parse-chord "alt+n")))
    (is (= "alt+n" (keys/parse-chord "Alt+N")) "letters lose case next to a modifier")
    (is (= "ctrl+alt+shift+meta+k" (keys/parse-chord "meta+shift+alt+ctrl+k")) "fixed modifier order")
    (is (= "meta+k" (keys/parse-chord "cmd+k")))
    (is (= "mod+k" (keys/parse-chord "mod+k")))
    (is (= "alt+shift+p" (keys/parse-chord "alt+shift+p"))))
  (testing "a bare uppercase letter means shift"
    (is (= "shift+g" (keys/parse-chord "G")))
    (is (= "shift+g" (keys/parse-chord "shift+g")))
    (is (= "g" (keys/parse-chord "g"))))
  (testing "shifted punctuation already encodes its shift"
    (is (= "?" (keys/parse-chord "?")))
    (is (= "?" (keys/parse-chord "shift+?")))
    (is (= "+" (keys/parse-chord "+")))
    (is (= "ctrl++" (keys/parse-chord "ctrl++")))
    (is (= "+" (keys/parse-chord "plus"))))
  (testing "named keys and their aliases"
    (is (= "escape" (keys/parse-chord "Esc")))
    (is (= "enter" (keys/parse-chord "return")))
    (is (= "pageup" (keys/parse-chord "PgUp")))
    (is (= "up" (keys/parse-chord "ArrowUp")))
    (is (= "shift+tab" (keys/parse-chord "shift+tab")))
    (is (= "f5" (keys/parse-chord "F5"))))
  (testing "invalid specs"
    (is (nil? (keys/parse-chord "")))
    (is (nil? (keys/parse-chord "foo+n")) "unknown modifier")
    (is (nil? (keys/parse-chord "alt+")) "no key")
    (is (nil? (keys/parse-chord "ab")) "two characters, not a named key")
    (is (nil? (keys/parse-chord "alt+super+")))))

(deftest key-sequences
  (is (= "g g" (keys/parse-key "g g")))
  (is (= "] c" (keys/parse-key "] c")))
  (is (= "alt+n" (keys/parse-key "  alt+n ")))
  (is (nil? (keys/parse-key "g gg")))
  (is (nil? (keys/parse-key "")))
  (is (= ["g" "g"] (keys/chords "g g"))))

(deftest space-bar
  (is (= "space" (keys/chord {:key " "})))
  (is (= "ctrl+space" (keys/chord {:key " " :ctrl true})))
  (is (= "space" (keys/parse-chord "space")))
  (is (keys/bare-printable? "space") "the space bar types")
  (is (not (keys/bare-printable? "ctrl+space")))
  (testing "degenerate keys never throw"
    (is (not (keys/bare-printable? "")))
    (is (not (keys/bare-printable? " ")))))

(deftest bare-printable
  (is (keys/bare-printable? "i"))
  (is (keys/bare-printable? "shift+g"))
  (is (keys/bare-printable? "?"))
  (is (keys/bare-printable? "g g"))
  (is (not (keys/bare-printable? "alt+n")))
  (is (not (keys/bare-printable? "ctrl+shift+p")))
  (is (not (keys/bare-printable? "escape")))
  (is (not (keys/bare-printable? "enter"))))

(deftest mod-expansion
  (is (= ["ctrl+k" "meta+k"] (keys/expand-mod "mod+k" :web)))
  (is (= ["ctrl+k"] (keys/expand-mod "mod+k" :tui)))
  (is (= ["alt+n"] (keys/expand-mod "alt+n" :web))))

(deftest display
  (is (= "Alt+N" (keys/format-key "alt+n")))
  (is (= "G" (keys/format-key "shift+g")))
  (is (= "Alt+Shift+P" (keys/format-key "alt+shift+p")))
  (is (= "Ctrl+K" (keys/format-key "ctrl+k")))
  (is (= "Esc" (keys/format-key "escape")))
  (is (= "Shift+Tab" (keys/format-key "shift+tab")))
  (is (= "g g" (keys/format-key "g g")))
  (is (= "?" (keys/format-key "?")))
  (testing "compact TUI form"
    (is (= "gg" (keys/format-key "g g" {:compact? true})))
    (is (= "]c" (keys/format-key "] c" {:compact? true})))
    (is (= "^d" (keys/format-key "ctrl+d" {:compact? true})))
    (is (= "M-x" (keys/format-key "alt+x" {:compact? true})))
    (is (= "G" (keys/format-key "shift+g" {:compact? true})))
    (is (= "⏎" (keys/format-key "enter" {:compact? true})))
    (is (= "Esc" (keys/format-key "escape" {:compact? true})))))

(deftest defaults-are-well-formed
  (testing "every default key parses and names a catalog action"
    (doseq [surface [:web :tui]
            [layer bindings] (merge (apply dissoc keys/defaults keys/surfaces)
                                    (get keys/defaults surface))
            [k a] bindings]
      (is (some? (keys/parse-key k)) (str layer " " k))
      (is (contains? keys/catalog a) (str layer " " k " → " a))
      (is (keyword? layer))))
  (testing "surface-only actions are not bound in shared layers"
    (doseq [[layer bindings] (apply dissoc keys/defaults keys/surfaces)
            [k a] bindings]
      (is (nil? (get-in keys/catalog [a :surface])) (str layer " " k " → " a))))
  (testing "the default keymap has no parse errors as a config"
    (is (nil? (keys/config-error keys/defaults)))))

(deftest config-validation
  (is (nil? (keys/config-error nil)))
  (is (nil? (keys/config-error {})))
  (is (nil? (keys/config-error {:global {"alt+n" :chat/new "alt+x" nil}
                                :web {:mode/navigate {"?" :keys/show} :defaults? false}
                                :tui {:buffer/pager {"J" :pager/down}}
                                :defaults? true})))
  (is (re-find #"must be a map" (keys/config-error [])))
  (is (re-find #"layers are keywords" (keys/config-error {"global" {}})))
  (is (re-find #"must be a map of" (keys/config-error {:global ["alt+n"]})))
  (is (re-find #"keys are strings" (keys/config-error {:global {:alt-n :chat/new}})))
  (is (re-find #"\"alt\+\+\+\" is not a key" (keys/config-error {:global {"alt+++" :chat/new}})))
  (is (re-find #"must map to an action keyword" (keys/config-error {:global {"alt+n" 5}})))
  (is (re-find #"non-empty string" (keys/config-error {:global {"alt+n" "  "}})))
  (is (nil? (keys/config-error {:mode/navigate {"space g c" "/commit"}})) "a string sends text")
  (is (nil? (keys/config-error {:mode/navigate {["space" "g" "c"] "/commit"}})) "a vector is a sequence")
  (is (re-find #"is not a key" (keys/config-error {:mode/navigate {["space" "nope+x"] :files/find}})))
  (is (re-find #"keys are strings" (keys/config-error {:mode/navigate {[] :files/find}})))
  (is (re-find #"keys are strings" (keys/config-error {:mode/navigate {["space" 1] :files/find}})))
  (is (re-find #":defaults\? must be true or false" (keys/config-error {:defaults? 1})))
  (is (re-find #"cannot nest :tui" (keys/config-error {:web {:tui {}}})))
  (is (re-find #":keys :web :global" (keys/config-error {:web {:global {"nope+x" :a}}}))))

(deftest leader-keys
  (let [km (keys/effective-keymap
            {:surface :web
             :user {:web {:mode/navigate {["space" "space"]  :files/find
                                          ["space" "g" "c"]  "/commit"
                                          "space g g"         :git/status
                                          ["space" "b" "b"]  :buffers/switch}}}})
        layers [:mode/navigate :global]
        step   (fn [pending chord] (keys/lookup km layers pending chord))]
    (testing "space starts a sequence in navigate mode"
      (is (= {:status :pending :pending ["space"]} (step [] "space")))
      (is (= {:status :pending :pending ["space" "g"]} (step ["space"] "g"))))
    (testing "sequences resolve to actions and to text"
      (is (= :files/find (:action (step ["space"] "space"))))
      (is (= :buffers/switch (:action (step ["space" "b"] "b"))))
      (is (= :git/status (:action (step ["space" "g"] "g"))))
      (is (= "/commit" (:action (step ["space" "g"] "c")))))
    (testing "a key outside the tree is unbound"
      (is (= :unbound (:status (step ["space"] "z")))))
    (testing "the text action shows up as the key for its text"
      (is (= "Space g c" (keys/shortcut km layers "/commit"))))))

(deftest effective-keymap-merging
  (let [km (keys/effective-keymap {:surface :web})]
    (testing "defaults: shared and surface layers, mod expanded"
      (is (= :chat/new (get-in km [:global "alt+n"])))
      (is (= :sidebar/toggle (get-in km [:global "alt+\\"])))
      (is (= :files/find (get-in km [:global "ctrl+p"])))
      (is (= :files/find (get-in km [:global "meta+p"])))
      (is (nil? (get-in km [:buffer/pager "j"])) "TUI-only layer absent on the web")
      (is (= :keys/show (get-in km [:mode/navigate "?"])) "keys stored canonical")
      (is (empty? (select-keys (:mode/navigate km) ["i" "j" "k" "shift+g"]))
          "vim-style navigation is the user's to bind")))
  (testing "user overrides, unbinds and additions win over defaults"
    (let [km (keys/effective-keymap {:surface :web
                                     :user {:global {"alt+n" :sessions/prune
                                                     "alt+\\" nil
                                                     "ctrl+shift+n" :chat/new}
                                            :web {:mode/navigate {"i" nil}}}})]
      (is (= :sessions/prune (get-in km [:global "alt+n"])))
      (is (= :chat/new (get-in km [:global "ctrl+shift+n"])))
      (is (contains? (:global km) "alt+\\") "an unbind stays as an explicit nil")
      (is (nil? (get-in km [:global "alt+\\"])))
      (is (nil? (get-in km [:mode/navigate "i"])))))
  (testing "the surface section is applied to its surface only"
    (let [user {:tui {:global {"alt+n" :keys/show}}}]
      (is (= :keys/show (get-in (keys/effective-keymap {:surface :tui :user user}) [:global "alt+n"])))
      (is (= :chat/new (get-in (keys/effective-keymap {:surface :web :user user}) [:global "alt+n"])))))
  (testing ":defaults? false drops the built-in keys, globally or per surface"
    (is (= {:global {"alt+q" :chat/new}}
           (keys/effective-keymap {:surface :web :user {:defaults? false :global {"alt+q" :chat/new}}})))
    (is (= {:global {"alt+q" :chat/new}}
           (keys/effective-keymap {:surface :tui :user {:tui {:defaults? false} :global {"alt+q" :chat/new}}})))
    (is (= :chat/new
           (get-in (keys/effective-keymap {:surface :web :user {:tui {:defaults? false}}})
                   [:global "alt+n"]))
        "the other surface keeps them"))
  (testing "extension defaults sit between built-ins and the user"
    (let [ext {:global {"alt+p" :projects/pick}}
          km  (keys/effective-keymap {:surface :tui :extensions [ext]})
          km2 (keys/effective-keymap {:surface :tui :extensions [ext]
                                      :user {:global {"alt+p" nil "alt+o" :projects/pick}}})]
      (is (= :projects/pick (get-in km [:global "alt+p"])))
      (is (nil? (get-in km2 [:global "alt+p"])))
      (is (= :projects/pick (get-in km2 [:global "alt+o"]))))))

(deftest lookup-walks-layers
  (let [km {:buffer/diff {"q" :buffer/close "escape" nil}
            :mode/navigate {"i" :compose/focus "g g" :pager/top "] c" :pager/next-change}
            :global {"escape" :dialog/close "q" :chat/new "alt+n" :chat/new}}]
    (testing "inner layer wins"
      (is (= {:status :action :action :buffer/close :layer :buffer/diff :key "q"}
             (keys/lookup km [:buffer/diff :mode/navigate :global] [] "q")))
      (is (= {:status :action :action :chat/new :layer :global :key "q"}
             (keys/lookup km [:mode/navigate :global] [] "q"))))
    (testing "an explicit nil masks outer layers"
      (is (= {:status :unbound :masked? true}
             (keys/lookup km [:buffer/diff :global] [] "escape")))
      (is (= :dialog/close (:action (keys/lookup km [:global] [] "escape")))))
    (testing "a disabled action falls through"
      (is (= :chat/new
             (:action (keys/lookup km [:buffer/diff :global] [] "q" #(not= % :buffer/close))))))
    (testing "sequences"
      (is (= {:status :pending :pending ["g"]} (keys/lookup km [:mode/navigate] [] "g")))
      (is (= :pager/top (:action (keys/lookup km [:mode/navigate] ["g"] "g"))))
      (is (= {:status :unbound} (keys/lookup km [:mode/navigate] ["g"] "x")))
      (is (= :pager/next-change (:action (keys/lookup km [:mode/navigate] ["]"] "c")))))
    (testing "unknown key"
      (is (= {:status :unbound} (keys/lookup km [:global] [] "alt+z"))))))

(deftest keys-for-and-shortcut
  (let [km {:buffer/diff {"q" :buffer/close}
            :global {"alt+n" :chat/new "ctrl+shift+n" :chat/new "q" :chat/new}}]
    (is (= ["alt+n" "ctrl+shift+n"] (keys/keys-for km [:buffer/diff :global] :chat/new))
        "a key an inner layer takes is not reported for the outer action")
    (is (= ["alt+n" "ctrl+shift+n" "q"] (keys/keys-for km [:global] :chat/new)))
    (is (= "Alt+N" (keys/shortcut km [:global] :chat/new)))
    (is (nil? (keys/shortcut km [:global] :keys/show)))))

(deftest listing-rows
  (let [base    (keys/effective-keymap {:surface :web})
        km      (keys/effective-keymap {:surface :web
                                        :user {:global {"alt+n" nil "alt+q" :chat/new}}})
        actions {:chat/new {:label "New chat"} :sidebar/toggle {:label "Toggle the sidebar"}
                 :keys/show {:label "Keyboard shortcuts"}}
        rows    (keys/listing km base actions [:mode/navigate :global])
        global  (some #(when (= :global (:layer %)) %) rows)]
    (is (= [:mode/navigate :global] (map :layer (take 2 rows))) "active layers first")
    (is (every? :active? (take 2 rows)))
    (is (= "Everywhere" (:label global)))
    (is (some #(and (= ["alt+q"] (:keys %)) (:custom? %) (= "New chat" (:label %))) (:rows global)))
    (is (some #(and (= ["alt+n"] (:keys %)) (nil? (:action %)) (:custom? %)
                    (re-find #"unbound" (:label %)))
              (:rows global)))
    (is (not (some #(some #{"alt+u"} (:keys %)) (:rows global)))
        "actions the surface does not implement are left out")
    (testing "one row per action, all its keys joined"
      (let [rows (keys/listing km base {:files/find {:label "Find a file"}} [:global])
            row  (some #(when (= :files/find (:action %)) %) (:rows (first rows)))]
        (is (= ["ctrl+p" "meta+p"] (:keys row)))
        (is (= "Ctrl+P / Cmd+P" (:display row)))
        (is (not (:custom? row)))))
    (is (not (some #(= :buffer/diff (:layer %)) rows)) "layers with no implemented rows vanish")))

(deftest extension-keybindings-become-actions
  (is (= {:id :ext.ping/toggle :label "toggle" :layer :global
          :event {:type :ext.ping/toggle} :key "ctrl+shift+n"}
         (keys/ext-keybinding->action {:key "ctrl+shift+n" :event {:type :ext.ping/toggle}})))
  (is (= {:id :ping/ring :label "Ring" :layer :buffer/diff :event {:type :ext.ping/toggle} :key "r"}
         (keys/ext-keybinding->action {:id :ping/ring :label "Ring" :layer :buffer/diff
                                       :key "r" :event {:type :ext.ping/toggle}})))
  (is (nil? (keys/ext-keybinding->action {:key "x"})) "no id, no event type → dropped")
  (is (= {:global {"ctrl+shift+n" :ext.ping/toggle} :buffer/diff {"r" :ping/ring}}
         (keys/ext-keymap [(keys/ext-keybinding->action {:key "ctrl+shift+n" :event {:type :ext.ping/toggle}})
                           (keys/ext-keybinding->action {:id :ping/ring :layer :buffer/diff :key "r"})
                           (keys/ext-keybinding->action {:id :ping/silent})]))))
