(ns xi.ext.terminal-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.terminal :as terminal]
            [xi.ext.terminal.core :as core]))

;; ── core ─────────────────────────────────────────────────────────────────────

(deftest scrollback-keeps-the-newest-output
  (testing "below the cap every chunk is kept"
    (let [sb (-> core/empty-scrollback
                 (core/scrollback-append "ab")
                 (core/scrollback-append "cd"))]
      (is (= "abcd" (core/scrollback-text sb)))
      (is (= 4 (:size sb)))))
  (testing "past the cap the oldest output goes, cut after a line break"
    (let [sb (reduce #(core/scrollback-append %1 %2 40)
                     core/empty-scrollback
                     (map #(str "line " % "\n") (range 20)))
          text (core/scrollback-text sb)]
      (is (<= (count text) 40))
      (is (str/starts-with? text "line "))
      (is (str/ends-with? text "line 19\n"))
      (is (= (count text) (:size sb))))))

(deftest parse-args-splits-like-a-shell
  (is (nil? (core/parse-args nil)))
  (is (nil? (core/parse-args "   ")))
  (is (= ["nvim" "src/a b.clj"] (core/parse-args "nvim \"src/a b.clj\"")))
  (is (= ["git" "log" "--format=%h %s"] (core/parse-args "git log '--format=%h %s'"))))

(deftest argv-helpers
  (is (= ["/bin/zsh"] (core/shell-argv "/bin/zsh")))
  (is (= ["/bin/sh"] (core/shell-argv nil)))
  (is (= "nvim src/x.clj" (core/argv-title ["/usr/bin/nvim" "src/x.clj"])))
  (is (= 48 (count (core/argv-title ["cat" (apply str (repeat 80 "x"))]))))
  (is (core/valid-argv? ["ls" "-la"]))
  (is (not (core/valid-argv? [])))
  (is (not (core/valid-argv? "ls")))
  (is (not (core/valid-argv? ["ls" ""]))))

(deftest buffer-and-rules-request
  (is (= {:kind :terminal :term-id "t1" :title "nvim a.clj" :argv ["nvim" "a.clj"]
          :cwd "/p" :tag :vim}
         (core/buffer {:term-id "t1" :argv ["nvim" "a.clj"] :cwd "/p" :tag :vim})))
  (is (= "Edit" (:title (core/buffer {:term-id "t1" :argv ["nvim"] :title "Edit"}))))
  (is (= {:tool :terminal :cli "nvim" :command "nvim a.clj" :argv ["nvim" "a.clj"]
          :effective-cwd "/p"}
         (core/rules-request ["nvim" "a.clj"] "/p"))))

;; ── handlers ─────────────────────────────────────────────────────────────────

(def ^:private handlers (:handlers (terminal/create {})))

(defn- run [type st ev] ((get handlers type) st (assoc ev :type type)))

(def ^:private st
  {:rooms {"r1" {:id "r1" :cwd "/proj" :session {:id "s1"}
                 :ui {:buffers {"term:t1" {:kind :terminal :term-id "t1"
                                           :argv ["nvim" "a"] :cwd "/proj"}}}}}})

(deftest open-spawns-in-the-room-cwd
  (let [[[fx p]] (:effects (run :terminal/open st {:room-id "r1" :argv ["nvim" "a.clj"]
                                                   :cwd "sub" :tag :vim :client-id "c1"}))]
    (is (= :terminal/spawn fx))
    (is (= ["nvim" "a.clj"] (:argv p)))
    (is (= "/proj/sub" (:cwd p)))
    (is (= {:tag :vim :client-id "c1" :session-id "s1" :room-id "r1"}
           (select-keys p [:tag :client-id :session-id :room-id])))
    (is (string? (:term-id p))))
  (testing "no :argv → the user's shell (chosen by the effect)"
    (let [[[_ p]] (:effects (run :terminal/open st {:room-id "r1"}))]
      (is (nil? (:argv p)))
      (is (= "/proj" (:cwd p)))))
  (testing "a bad :argv is reported, nothing runs"
    (is (= :ui/status (-> (run :terminal/open st {:room-id "r1" :argv "nvim"})
                          :effects first second :type))))
  (testing "unknown room → nothing"
    (is (nil? (run :terminal/open st {:room-id "nope"})))))

(deftest restart-reuses-the-buffer
  (let [[[fx p]] (:effects (run :terminal/restart st {:room-id "r1" :buffer-id "term:t1"}))]
    (is (= :terminal/spawn fx))
    (is (= {:term-id "t1" :argv ["nvim" "a"] :cwd "/proj"}
           (select-keys p [:term-id :argv :cwd]))))
  (is (nil? (run :terminal/restart st {:room-id "r1" :buffer-id "file:x"}))))

(deftest exit-closes-or-marks-the-buffer
  (let [dispatched #(-> % :effects first second)]
    (testing "exit 0 closes the buffer"
      (is (= {:type :ui/buffer-close :room-id "r1" :buffer-id "term:t1"}
             (dispatched (terminal/terminal-exited st {:term-id "t1" :code 0})))))
    (testing "another code is kept on the buffer"
      (let [ev (dispatched (terminal/terminal-exited st {:term-id "t1" :code 2}))]
        (is (= :ui/buffer-set (:type ev)))
        (is (= 2 (get-in ev [:buffer :exit-code])))))
    (testing "a signal (nil code) is not a clean exit"
      (is (= :ui/buffer-set (:type (dispatched (terminal/terminal-exited st {:term-id "t1" :code nil}))))))
    (testing "a parked buffer closes through its session"
      (let [parked {:parked-buffers {"s9" {"term:t9" {:kind :terminal :term-id "t9"}}}}]
        (is (= {:type :session/buffer-close :session-id "s9" :buffer-id "term:t9"}
               (dispatched (terminal/terminal-exited parked {:term-id "t9" :code 0}))))
        (is (nil? (terminal/terminal-exited parked {:term-id "t9" :code 1}))
            "a failed one stays parked with its output")))
    (testing "no buffer anywhere → just forget the terminal"
      (is (= [[:terminal/kill {:term-ids ["t7"]}]]
             (:effects (terminal/terminal-exited {} {:term-id "t7" :code 0})))))))

(deftest closing-a-buffer-ends-its-terminal
  (is (= [[:terminal/kill {:term-ids ["t1"]}]]
         (:effects (run :ui/buffer-close st {:room-id "r1" :buffer-id "term:t1"}))))
  (is (nil? (run :ui/buffer-close st {:room-id "r1" :buffer-id "file:a"})))
  (is (= [[:terminal/kill {:session-id "s1"}]]
         (:effects (run :ui/buffers-close-all st {:room-id "r1"}))))
  (is (= [[:terminal/kill {:session-id "s1"}]]
         (:effects (run :session/buffer-close st {:session-id "s1"}))))
  (is (= [[:terminal/kill {:term-ids ["t1"]}]]
         (:effects (run :session/buffer-close st {:session-id "s1" :buffer-id "term:t1"}))))
  (is (= [[:terminal/kill {:session-id "s1"}]]
         (:effects (run :session/delete st {:session-id "s1"})))))

(deftest io-events-keep-only-their-payload
  (is (= [[:terminal/input {:client-id "c1" :term-id "t1" :text "ls\r"}]]
         (:effects (run :terminal/input {} {:client-id "c1" :term-id "t1" :text "ls\r"
                                            :user "u" :room-id "r1"}))))
  (is (= [[:terminal/detach-client {:client-id "c1"}]]
         (:effects (run :client/disconnect {} {:client-id "c1"})))))

(deftest term-command-opens-a-terminal
  (let [cmd (first (:commands (terminal/create {})))
        [[fx p]] (:effects ((:handler cmd) st {:room-id "r1" :args "htop -d 5" :client-id "c1"}))]
    (is (= "term" (:name cmd)))
    (is (= :terminal/spawn fx))
    (is (= ["htop" "-d" "5"] (:argv p)))
    (is (= "c1" (:client-id p)))))

(deftest a-program-sends-to-its-chat
  (let [st   {:rooms {"r1" {:session {:id "s1"}} "r2" {:session {:id "s2"}}}}
        term {:session-id "s2" :user "alice"}]
    (testing "by default the text is the user's next prompt in the terminal's chat"
      (is (= {:event {:type :prompt/submit :room-id "r2" :text "explain" :user "alice"
                      :collapsed-label "Explain a.clj:1-3"}}
             (core/send-event st term {:text "explain" :label " Explain a.clj:1-3 "}))))
    (testing "to a sub-agent of that chat"
      (is (= {:event {:type :subagent/spawn :room-id "r2" :task "explain" :prompt "explain"
                      :label "Explain"}}
             (core/send-event st term {:text "explain" :to "subagent" :label "Explain"}))))
    (testing "no text, or the chat is not open"
      (is (:error (core/send-event st term {:text "  "})))
      (is (:error (core/send-event st term {:text 3})))
      (is (:error (core/send-event st {:session-id "gone"} {:text "hi"}))))))

(deftest a-streamed-sub-agent
  (testing "the socket's sub-id names the sub-agent"
    (is (= "sa-term-1"
           (get-in (core/send-event {:rooms {"r" {:session {:id "s"}}}} {:session-id "s"}
                                    {:text "hi" :to "subagent" :sub-id "sa-term-1"})
                   [:event :sub-id]))))
  (testing "its events become stream items"
    (is (= {:type "text" :text "Hel"}
           (core/stream-item {:type :subagent/text-delta :text "Hel"})))
    (is (= {:type "tool" :tool "read" :detail "src/a.clj"}
           (core/stream-item {:type :subagent/tool-start :tool "mcp__xi-tools__read"
                              :arguments {:path "src/a.clj"}})))
    (is (= {:type "tool" :tool "grep" :detail "defn"}
           (core/stream-item {:type :subagent/tool-start :tool "grep"
                              :arguments {"pattern" "defn"}})))
    (is (= {:type "done" :status "stopped"}
           (core/stream-item {:type :subagent/turn-end :aborted? true})))
    (is (= "boom" (:error (core/stream-item {:type :subagent/error :error "boom"})))))
  (testing "the rest is skipped"
    (is (nil? (core/stream-item {:type :subagent/text-delta :text ""})))
    (is (nil? (core/stream-item {:type :subagent/thinking-delta :text "hm"})))))
