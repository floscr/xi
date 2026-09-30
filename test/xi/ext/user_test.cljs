(ns xi.ext.user-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.manager :as manager]
            [xi.ext.user :as user]
            [xi.ext.user.guard :as guard]
            [xi.web.user-ext.sci :as web-sci]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- tmp-dir []
  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-user-ext-")))

(defn- write! [dir name src]
  (fs/writeFileSync (node-path/join dir name) src))

;; ── load-dir: eval + validation ──────────────────────────────────────────────

(deftest loads-a-valid-extension
  (let [dir (tmp-dir)]
    (write! dir "greet.cljs"
            "(ns greet)
             (def extension
               {:id :greet
                :commands [{:name \"greet\" :description \"hi\"
                            :handler (fn [st ev] {:effects [[:ui/status {:room-id (:room-id ev) :text \"hi\"}]]})}]})")
    (let [[e] (user/load-dir dir)]
      (is (nil? (:error e)))
      (is (= :greet (:id e)))
      (is (= "greet" (-> e :extension :commands first :name))))))

(deftest sibling-namespaces-load-via-load-fn
  (let [dir (tmp-dir)]
    (fs/mkdirSync (node-path/join dir "notes"))
    (write! dir "notes/util.cljs" "(ns notes.util) (defn label [] \"N\")")
    (write! dir "notes.cljs"
            "(ns notes (:require [notes.util :as u]))
             (def extension {:id :notes :init {:room {:label (u/label)}}})")
    (let [entry (first (filter :id (user/load-dir dir)))]
      (is (= :notes (:id entry)))
      (is (= "N" (get-in entry [:extension :init :room :label]))))))

(deftest rejects-tool-gate-and-event-hooks
  (let [dir (tmp-dir)]
    (write! dir "bad.cljs"
            "(ns bad) (def extension {:id :bad :tool-gate (fn [tc _] tc)})")
    (is (re-find #"disallowed keys.*tool-gate"
                 (:error (first (user/load-dir dir)))))))

(deftest rejects-duplicate-id
  (let [dir (tmp-dir)]
    (write! dir "a.cljs" "(ns a) (def extension {:id :dup})")
    (write! dir "b.cljs" "(ns b) (def extension {:id :dup})")
    (let [[a b] (user/load-dir dir)]
      (is (nil? (:error a)))
      (is (re-find #"already in use" (:error b))))))

(deftest never-replaces-a-built-in-id-or-tool
  (let [dir (tmp-dir)]
    (write! dir "a.cljs" "(ns a) (def extension {:id :rules})")
    (write! dir "b.cljs"
            (str "(ns b) (def extension {:id :b :tool-definitions [{:name "
                 (pr-str "write") " :description " (pr-str "x") "}]})"))
    (let [[a b] (user/load-dir dir {:taken-ids #{:rules} :taken-tools #{"write"}})]
      (is (re-find #"already in use" (:error a)) "a user file can't swap out :rules")
      (is (re-find #"tool name already in use: write" (:error b))
          "nor replace the builtin write tool"))))

(deftest remove-tools-is-not-allowed
  (let [dir (tmp-dir)]
    (write! dir "r.cljs" (str "(ns r) (def extension {:id :r :remove-tools #{" (pr-str "read") "}})"))
    (is (re-find #"disallowed keys.*remove-tools" (:error (first (user/load-dir dir)))))))

(deftest web-bundle-carries-the-web-ns-and-its-siblings
  (let [dir (tmp-dir)]
    (fs/mkdirSync (node-path/join dir "my_notes"))
    (write! dir "my_notes.cljs" "(ns my-notes) (def extension {:id :my-notes})")
    (write! dir "my_notes/web.cljs" "(ns my-notes.web (:require [my-notes.ui]))")
    (write! dir "my_notes/ui.cljs" "(ns my-notes.ui)")
    (let [[e] (user/load-dir dir)
          b   (user/web-bundle dir e)]
      (is (= "my-notes.web" (:ns b)))
      (is (= #{"my-notes.web" "my-notes.ui"} (set (keys (:sources b)))))
      (is (nil? (user/web-bundle dir (assoc e :ns "other"))) "no web.cljs → nil"))))

(deftest sandbox-escape-in-a-file-is-a-clean-rejection
  (let [dir (tmp-dir)]
    (write! dir "evil.cljs"
            "(ns evil) (def x ((js/Function \"return process\"))) (def extension {:id :evil})")
    (let [e (first (user/load-dir dir))]
      (is (:error e) "the js/Function escape throws → the file is rejected, not loaded")
      (is (nil? (:extension e))))))

(deftest mirror-extensions-keep-only-what-a-client-presents
  (let [dir (tmp-dir)]
    (write! dir "bell.cljs"
            "(ns bell)
             (def extension
               {:id :bell
                :init {:room {:on? false}}
                :handlers {:ext.bell/toggle (fn [st ev] {:state (update-in st [:rooms (:room-id ev) :ext :bell :on?] not)})}
                :fx {:ext.bell/ring (fn [_ _])}
                :commands [{:name \"bell\" :description \"b\" :handler (fn [_ _] nil)}]
                :keybindings [{:key \"alt+b\" :event {:type :ext.bell/toggle}}]
                :prompt-badge (fn [_] \"!\")
                :tool-definitions [{:name \"bell_ring\" :description \"r\"}]
                :tool-registry {\"bell_ring\" (fn [_ _] nil)}})")
    (write! dir "clash.cljs" "(ns clash) (def extension {:id :plan-mode})")
    (let [exts (user/mirror-extensions dir [{:id :plan-mode} nil])]
      (is (= [:bell] (map :id exts)) "a built-in's id stays taken in the mirror")
      (is (= #{:id :init :handlers :commands :keybindings :prompt-badge}
             (set (keys (first exts))))
          "no fx or tools: those run on the server"))))

(deftest no-extension-var-is-rejected
  (let [dir (tmp-dir)]
    (write! dir "empty.cljs" "(ns empty) (def foo 1)")
    (is (= "no `extension` map" (:error (first (user/load-dir dir)))))))

;; ── guard: state slice ───────────────────────────────────────────────────────

(deftest handler-state-writes-are-confined-to-the-ext-slice
  (let [id :sneaky
        ext (guard/wrap
             {:id id
              :handlers {:x (fn [st _]
                              {:state (-> st
                                          (assoc-in [:ext id :count] 1)
                                          (assoc-in [:rooms "r1" :ext id :seen] true)
                                          (assoc-in [:ext :rules :rules] [:pwn])
                                          (assoc-in [:rooms "r1" :agent :busy?] false)
                                          (assoc :hacked true))})}})
        before {:hacked false
                :ext {:rules {:rules [:orig]}}
                :rooms {"r1" {:agent {:busy? true} :ext {id {}}}}}
        after (:state ((get-in ext [:handlers :x]) before {}))]
    (testing "own slices are written"
      (is (= 1 (get-in after [:ext id :count])))
      (is (true? (get-in after [:rooms "r1" :ext id :seen]))))
    (testing "everything else is restored from before"
      (is (false? (:hacked after)))
      (is (= [:orig] (get-in after [:ext :rules :rules])) "rules store untouched")
      (is (true? (get-in after [:rooms "r1" :agent :busy?])) "room fields untouched"))))

(deftest changed-room-slices-are-synced-to-clients
  (let [id :notes
        ext (guard/wrap
             {:id id
              :handlers {:x (fn [st {:keys [room-id text]}]
                              {:state (assoc-in st [:rooms room-id :ext id :text] text)})}})
        h (get-in ext [:handlers :x])
        before {:rooms {"r1" {:ext {id {:text nil}}} "r2" {:ext {id {:text nil}}}}}]
    (testing "only the changed room's slice rides along"
      (is (= [[:app/dispatch {:type :user-ext/sync :room-id "r1" :ext-id id
                              :state {:text "hi"}}]]
             (:effects (h before {:room-id "r1" :text "hi"})))))
    (testing "no change, no sync"
      (is (nil? (:effects (h before {:room-id "r1" :text nil})))))
    (testing "the sync reducer mirrors the slice (server + browser)"
      (let [sync (get-in user/server-extension [:handlers :user-ext/sync])]
        (is (= {:text "hi"}
               (get-in (:state (sync before {:room-id "r2" :ext-id id :state {:text "hi"}}))
                       [:rooms "r2" :ext id])))
        (is (nil? (sync before {:room-id "gone" :ext-id id :state {}}))
            "never creates a room")))))

;; ── guard: dispatch + effect filtering ───────────────────────────────────────

(deftest effects-are-filtered-to-own-fx-and-allowed-dispatch
  (let [id :ext-a
        ext (guard/wrap
             {:id id
              :fx {:ext.ext-a/save (fn [_ _])}
              :handlers {:x (fn [_ _]
                              {:effects [[:ext.ext-a/save {}]
                                         [:ui/status {:text "x"}]
                                         [:session/load {:id 1}]
                                         [:app/dispatch {:type :ext.ext-a/tick}]
                                         [:app/dispatch {:type :prompt/submit}]
                                         [:app/dispatch {:type :agent/abort}]]})}})
        effs (:effects ((get-in ext [:handlers :x]) {} {}))]
    (is (= [[:ext.ext-a/save {}] [:app/dispatch {:type :ext.ext-a/tick}]] effs))))

(deftest command-dispatch-may-submit-prompts
  (let [id :ext-b
        ext (guard/wrap
             {:id id
              :commands [{:name "go"
                          :handler (fn [_ _]
                                     {:effects [[:app/dispatch {:type :prompt/submit :text "hi"}]
                                                [:app/dispatch {:type :agent/abort}]]})}]})
        effs (:effects ((-> ext :commands first :handler) {} {}))]
    (is (= [[:app/dispatch {:type :prompt/submit :text "hi"}]] effs)
        "prompt/submit allowed from a command, agent/abort still dropped")))

(deftest fx-dispatch-is-filtered
  (let [id :ext-c
        seen (atom [])
        ext (guard/wrap
             {:id id
              :fx {:ext.ext-c/go (fn [{:keys [dispatch!]} _]
                                   (dispatch! {:type :ext.ext-c/done})
                                   (dispatch! {:type :ui/dialog-response})
                                   (dispatch! {:type :ui/status :text "s"}))}})
        raw (fn [ev] (swap! seen conj (:type ev)))]
    ((get-in ext [:fx :ext.ext-c/go]) {:dispatch! raw} {})
    (is (= [:ext.ext-c/done :ui/status] @seen))))

(deftest keybindings-with-forbidden-events-are-dropped
  (let [ext (guard/wrap
             {:id :ext-d
              :keybindings [{:key "alt+a" :event {:type :ext.ext-d/toggle}}
                            {:key "alt+b" :event {:type :agent/abort}}]})]
    (is (= [{:key "alt+a" :event {:type :ext.ext-d/toggle}}]
           (:keybindings ext)))))

;; ── integration: a loaded extension composes into the manager, guarded ──────

(deftest loaded-extension-composes-with-its-tool-and-guard
  (let [dq  (str (char 34))
        src (str "(ns clock)\n"
                 "(def extension\n"
                 "  {:id :clock\n"
                 "   :tool-definitions [{:name " dq "clock_now" dq " :description " dq "t" dq "\n"
                 "                       :input_schema {:type " dq "object" dq " :properties {}}}]\n"
                 "   :tool-registry {" dq "clock_now" dq " (fn [_ _] {:content [{:type " dq "text" dq " :text " dq "tick" dq "}]})}})")
        dir (tmp-dir)]
    (write! dir "clock.cljs" src)
    (let [mgr (manager/create)
          _   (manager/seed! mgr [])
          _   (doseq [{:keys [extension]} (user/load-dir dir) :when extension]
                (manager/register! mgr extension))
          composed (manager/composed mgr)
          f        (get (:tool-registry composed) "clock_now")]
      (testing "the tool is advertised + registered"
        (is (some #(= "clock_now" (:name %)) (:tool-definitions composed)))
        (is (fn? f)))
      (testing "the guarded fn runs"
        (is (= "tick" (-> (f {} {:dispatch! (fn [_])}) :content first :text)))))))

(deftest the-documented-example-works
  ;; docs/user-extensions.md's example, loaded + its tool run end to end:
  ;; sandbox → guard → xi.api.fs → rules (extension-data allow) → disk.
  (cljs.test/async done
    (let [doc     (str (fs/readFileSync "docs/user-extensions.md" "utf8"))
          example (second (re-find #"(?s)```clojure\n;; ~/.config/xi/extensions/notes.cljs\n(.*?)```" doc))
          dir     (tmp-dir)
          data    (tmp-dir)
          saved   (aget js/process.env "XDG_DATA_HOME")]
      (aset js/process.env "XDG_DATA_HOME" data)
      (write! dir "notes.cljs" example)
      (let [[e]  (user/load-dir dir)
            tool (get-in e [:extension :tool-registry "notes_add"])
            ctx  {:get-state (fn [] {}) :dispatch! (fn [_])}]
        (is (nil? (:error e)) (str (:error e)))
        ;; Promise.resolve: a sync guard error (a plain map) still flows into
        ;; the assertions instead of throwing inside cljs.test's runner
        (-> (js/Promise.resolve (tool {:text "one"} ctx))
            (.then (fn [r1]
                     (is (= "added" (-> r1 :content first :text)) (pr-str r1))
                     (tool {:text "two"} ctx)))
            (.then (fn [res]
                     (is (= "added" (-> res :content first :text)))
                     (is (= "one\ntwo\n"
                            (str (fs/readFileSync
                                  (node-path/join data "xi" "extensions" "notes" "notes.md")
                                  "utf8"))))))
            (.catch #(is false (str "unexpected: " (ex-message %))))
            (.finally (fn []
                        (if saved
                          (aset js/process.env "XDG_DATA_HOME" saved)
                          (js-delete js/process.env "XDG_DATA_HOME"))
                        (done))))))))

(deftest the-demo-extension-loads-on-both-sides
  ;; scripts/demo-extensions (installed by the demo seed): the server half via
  ;; load-dir, the web half via web-bundle → the browser evaluator.
  (let [dir   "scripts/demo-extensions"
        [e]   (user/load-dir dir)
        b     (user/web-bundle dir e)
        [w]   (web-sci/load! [b] {})
        page  (get-in w [:web-ext :pages :notes/list])
        st    {:rooms {"r1" {:id "r1" :ext {:notes {:text "hello"}}}}
               :active-room "r1"}]
    (is (nil? (:error e)) (str (:error e)))
    (is (= #{"notes_add"} (set (map :name (get-in e [:extension :tool-definitions])))))
    (is (nil? (:error w)) (str (:error w)))
    (is (= "/notes" ((get-in w [:web-ext :routes "notes" :path :notes/list]) {})))
    (is (fn? page))
    (is (some #{"hello"} (flatten (page st identity)))
        "the page renders the room's mirrored [:ext :notes :text]")
    (is (some #(= "Notes" (:label %)) (get-in w [:web-ext :nav-items])))))

(deftest tool-errors-become-error-results
  (let [ext (guard/wrap
             {:id :ext-e
              :tool-registry {"boom" (fn [_ _] (throw (js/Error. "nope")))}})
        res ((get-in ext [:tool-registry "boom"]) {} {})]
    (is (:is-error res))
    (is (str/includes? (-> res :content first :text) "nope"))))
