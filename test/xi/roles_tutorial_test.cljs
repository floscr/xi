(ns xi.roles-tutorial-test
  "The guide's users-and-roles tutorial (docs/guide/extension-tutorial-roles.md),
   run as written: its config.edn, rules.edn and board.cljs are read out of the
   page, the extension is loaded through the sandbox, and every outcome the
   page promises is checked."
  (:require [cljs.test :refer [deftest is testing use-fixtures async]]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.ext.user :as user]
            [xi.rules :as rules]
            [xi.rules.store :as rules-store]
            [xi.user-config :as cfg]
            [xi.user-state.store :as store]
            [xi.users :as users]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private doc
  (delay (str (fs/readFileSync "docs/guide/extension-tutorial-roles.md" "utf8"))))

(defn- block
  "The fenced clojure block of the page whose first line is `first-line`."
  [first-line]
  (second (re-find (re-pattern (str "(?s)```clojure\n" first-line "\n(.*?)```")) @doc)))

(def ^:private tmp (atom nil))
(def ^:private saved (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! tmp (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-roles-tutorial-")))
             (reset! saved {:config (cfg/config-file)
                            :data   (aget js/process.env "XDG_DATA_HOME")})
             (aset js/process.env "XDG_DATA_HOME" (node-path/join @tmp "data"))
             (store/set-dir! (node-path/join @tmp "state"))
             (fs/writeFileSync (node-path/join @tmp "config.edn")
                               (block ";; ~/.config/xi/config.edn"))
             (cfg/set-config-file! (node-path/join @tmp "config.edn")))
   :after  (fn []
             (user/stop!)
             (cfg/set-config-file! (:config @saved))
             (store/set-dir! nil)
             (if-let [d (:data @saved)]
               (aset js/process.env "XDG_DATA_HOME" d)
               (js-delete js/process.env "XDG_DATA_HOME"))
             (fs/rmSync @tmp #js {:recursive true :force true}))})

(deftest the-config-and-rules-are-valid
  (is (nil? (:error (cfg/read-config))) "the page's config.edn")
  (is (= {"alice" {:name "Alice" :meta {:roles ["admin"]}}
          "bob"   {:name "Bob" :meta {:roles ["guest"]}}}
         (cfg/users)))
  (is (nil? (:error (rules-store/parse-rules-config
                     (rules-store/read-rule-edn (block ";; ~/.config/xi/rules.edn")))))))

(deftest the-rules-decide-per-role
  (let [ruleset (:rules (rules-store/parse-rules-config
                         (rules-store/read-rule-edn (block ";; ~/.config/xi/rules.edn"))))
        decide  (fn [call who]
                  (let [req (rules-store/enrich-request
                             (rules-store/decision-request call {:cwd "/tmp" :user who})
                             ruleset)]
                    (some-> (rules/first-match ruleset req) :action :type)))
        clear   {:name "board_clear" :arguments {}}
        bash    {:name "bash" :arguments {:command "ls"}}]
    (testing "guests can't run programs or clear the board"
      (is (= :deny (decide bash "bob")))
      (is (= :deny (decide clear "bob"))))
    (testing "everyone else falls through to the defaults"
      (is (nil? (decide bash "alice")))
      (is (nil? (decide clear "alice")))
      (is (nil? (decide bash "carol")) "a user nobody declared has no roles"))
    (testing "admins push without a dialog; the clj tool's (sh …) is a :sh request"
      (let [push (fn [who]
                   (some-> (rules/first-match
                            ruleset
                            (rules-store/enrich-request
                             {:tool :sh :cli "git" :command "git push origin main"
                              :effective-cwd "/tmp" :user who}
                             ruleset))
                           :action :type))]
        (is (= :allow (push "alice")))
        (is (nil? (push "carol")))
        (is (= :deny (push "bob")) "the guest rule comes first")))))

(deftest the-extension-does-what-the-page-says
  (async done
    (let [dir   (node-path/join @tmp "ext")
          _     (fs/mkdirSync dir)
          _     (fs/writeFileSync (node-path/join dir "board.cljs")
                                  (block ";; ~/.config/xi/extensions/board.cljs"))
          _     (user/set-enabled-override! (constantly #{"board.cljs"}))
          [e]   (user/load-dir dir)
          a     (app/create-app {:initial-state (state/initial-state {:mode :server :user "root"})
                                 :handlers events/core-handlers})
          _     (user/start! a)
          _     (doseq [u ["alice" "bob" "carol"]] ((:dispatch! a) (users/loaded-event u)))
          ext   (:extension e)
          seen  (atom [])
          ctx   (fn [who] (cond-> {:get-state (fn [] @(:state a))
                                   :dispatch! #(swap! seen conj %)
                                   :room-id "r"}
                            who (assoc :user who)))
          cmd   (fn [name who args]
                  (let [h   (:handler (first (filter #(= name (:name %)) (:commands ext))))
                        res (h @(:state a) {:user who :args args :room-id "r"})]
                    ;; run the extension's own effects, as the app would
                    (doseq [[fx payload] (:effects res)
                            :when (not= :app/dispatch fx)]
                      ((get-in ext [:fx fx]) (ctx nil) payload))
                    (concat (keep (fn [[fx p]] (when (= :app/dispatch fx) (:text p))) (:effects res))
                            (map :text (let [s @seen] (reset! seen []) s)))))
          tool  (fn [name who args]
                  (js/Promise.resolve ((get-in ext [:tool-registry name]) args (ctx who))))
          board #(str (fs/readFileSync (node-path/join @tmp "data" "xi" "extensions" "board" "board.md")
                                       "utf8"))]
      (is (nil? (:error e)) (str (:error e)))
      (testing "/whoami and /grant"
        (is (= ["alice: admin"] (cmd "whoami" "alice" nil)))
        (is (= ["Only admins can grant roles."] (cmd "grant" "bob" "bob moderator")))
        (is (= ["Usage: /grant <user> <role>"] (cmd "grant" "alice" nil)))
        (is (= ["carol is now moderator"] (cmd "grant" "alice" "carol moderator")))
        (is (= ["carol: moderator"] (cmd "whoami" "carol" nil)))
        (is (re-find #"not a user id" (first (cmd "grant" "alice" "Carol!! moderator")))))
      (-> (tool "board_post" "bob" {:line "hello"})
          (.then (fn [r]
                   (is (= "Posted." (-> r :content first :text)))
                   (is (= "bob: hello\n" (board)))
                   (tool "board_clear" "bob" {})))
          (.then (fn [r]
                   (is (:is-error r) "bob is no moderator")
                   (is (= "Only moderators can clear the board." (-> r :content first :text)))
                   (tool "board_clear" "carol" {})))
          (.then (fn [r]
                   (is (= "Board cleared." (-> r :content first :text)) (pr-str r))
                   (is (= "" (board)))))
          (.catch #(is false (str "unexpected: " (ex-message %))))
          (.finally done)))))
