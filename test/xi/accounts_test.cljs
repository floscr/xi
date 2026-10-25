(ns xi.accounts-test
  (:require [cljs.test :refer [async deftest is testing]]
            [clojure.string :as str]
            [xi.accounts :as accounts]
            [xi.accounts.run :as run]
            [xi.agent :as agent]
            [xi.commands :as commands]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.providers.process :as process]))

(def ^:private accts
  [{:id "1" :email "a@x.io" :alias "pro" :plan "max" :active? true :token-valid? true}
   {:id "2" :email "b@x.io" :alias "apple" :plan "pro" :active? false :token-valid? false}
   {:id "3" :email "c@x.io" :active? false}])

;; ── Arguments ────────────────────────────────────────────────────────────────

(deftest parsing-the-words
  (let [p #(accounts/parse-args (accounts/words %))]
    (is (= {:op "overview"} (p "")))
    (is (= {:op "list" :provider "claude"} (p "claude")))
    (is (= {:op "list" :provider "claude"} (p "claude ls")))
    (testing "a word that is no op names the account to switch to"
      (is (= {:op "switch" :provider "claude" :target "Apple"} (p "claude Apple")))
      (is (= {:op "switch" :provider "claude" :target "2"} (p "claude use 2"))))
    (is (= {:op "switch" :provider "claude"} (p "claude switch")) "no target: rotate")
    (is (= {:op "login" :provider "claude" :alias "work" :email "w@x.io"}
           (p "claude login --alias work --email w@x.io")))
    (is (= {:op "code" :provider "claude" :code "abc#def"} (p "claude code abc#def")))
    (is (= {:op "cancel" :provider "claude"} (p "claude cancel")))
    (is (= {:op "alias" :provider "claude" :target "2" :alias "work"} (p "claude alias 2 work")))
    (is (= {:op "alias" :provider "claude" :target "2"} (p "claude alias 2")) "no name clears")
    (is (= {:op "forget" :provider "claude" :target "apple"} (p "claude rm apple")))
    (is (= {:op "forget" :provider "opencode"} (p "opencode logout")))
    (testing "errors"
      (is (:error (p "claude code")))
      (is (:error (p "claude alias")))
      (is (:error (p "claude login --alias")))
      (is (:error (p "claude switch 2 --alias x")))
      (is (:error (p "claude login --nope"))))))

;; ── Which account ────────────────────────────────────────────────────────────

(deftest naming-an-account
  (is (= "2" (:id (accounts/resolve-target accts "2"))))
  (is (= "2" (:id (accounts/resolve-target accts "B@X.IO"))) "email, any case")
  (is (= "2" (:id (accounts/resolve-target accts "Apple"))) "alias, any case")
  (is (nil? (accounts/resolve-target accts "nobody"))))

(deftest rotating
  (is (= "2" (:id (accounts/next-account accts))))
  (is (= "1" (:id (accounts/next-account (mapv #(assoc % :active? (= "3" (:id %))) accts)))) "wraps")
  (is (= "1" (:id (accounts/next-account (mapv #(assoc % :active? false) accts)))) "none live: the first")
  (is (nil? (accounts/next-account (take 1 accts))) "never the live one"))

;; ── Results ──────────────────────────────────────────────────────────────────

(deftest a-list-reads-in-the-terminal-and-on-the-web
  (let [{:keys [title text rows]} (accounts/list-result "Claude" "claude" accts)]
    (is (= "Claude accounts" title))
    (is (= ["Claude accounts"
            "* 1  a@x.io  pro    max · live"
            "  2  b@x.io  apple  pro · token expired"
            "  3  c@x.io"]
           (str/split-lines text)))
    (is (= [{:label "pro" :text "a@x.io · max · live" :tone :success}
            {:label "apple" :text "b@x.io · pro · token expired"}
            {:label "#3" :text "c@x.io"}]
           rows)))
  (testing "no alias anywhere: no empty column"
    (is (= "* 1  a@x.io  live"
           (second (str/split-lines (:text (accounts/list-result "Go" "go" [{:id "1" :email "a@x.io" :active? true}])))))))
  (is (re-find #"/account claude login" (:text (accounts/list-result "Claude" "claude" [])))))

(deftest the-sign-in-banner
  (let [code (accounts/prompt-status "Claude" "claude" {:url "https://c/oauth" :needs-code? true} "a1")
        dev  (accounts/prompt-status "OpenCode Go" "opencode" {:url "https://o/device" :user-code "AB-12"} "a2")]
    (is (= {:id "a1" :label "Open sign-in page" :url "https://c/oauth"} (:action code)))
    (is (re-find #"/account claude code <CODE>" (:text code)))
    (is (re-find #"Enter the code AB-12" (:text dev)))))

(deftest accounts-from-a-program
  (is (= {:id "2" :email "b@x.io" :alias "apple" :plan "pro" :active? false :token-valid? false}
         (process/account-of {:id "2" :email "b@x.io" :alias "apple" :plan "pro" :active false :tokenValid false})))
  (is (= {:id "7" :active? true} (process/account-of {:id 7 :active true})))
  (is (= {:id "2" :active false :email "b@x.io" :tokenValid false}
         (accounts/account->json {:id "2" :email "b@x.io" :active? false :token-valid? false}))))

;; ── Providers ────────────────────────────────────────────────────────────────

(def ^:private providers
  {:anthropic {:id :anthropic :label "Claude" :login {:name "claude" :label "Claude"} :accounts {}}
   :zen       {:id :zen :label "OpenCode" :login {:name "opencode" :label "OpenCode Go"}}
   :ollama    {:id :ollama :label "Ollama"}})

(deftest finding-a-provider
  (is (= :anthropic (:id (accounts/find-provider providers "claude"))))
  (is (= :anthropic (:id (accounts/find-provider providers "Anthropic"))) "its id")
  (is (= :zen (:id (accounts/find-provider providers "opencode go"))) "its label")
  (is (nil? (accounts/find-provider providers "ollama")) "no sign-in, no accounts")
  (is (= ["claude" "opencode"] (map accounts/provider-name (accounts/signable providers)))))

;; ── Requests (fake provider maps) ────────────────────────────────────────────

(defn- fake-provider
  "A Claude-like provider map whose requests act on an atom of accounts."
  [!accts]
  {:id :anthropic :label "Claude"
   :login {:name "claude" :label "Claude"
           :login! (fn [{:keys [on-prompt]}]
                     (js/Promise.
                      (fn [resolve _]
                        (on-prompt {:url "https://c/oauth" :needs-code? true
                                    :code! (fn [c] (resolve {:email (str c "@x.io")}))
                                    :cancel! (fn [])}))))
           :logout! (fn [& [id]] (swap! !accts (fn [as] (vec (remove #(= id (:id %)) as)))) (js/Promise.resolve {}))}
   :accounts {:list!   (fn [] (js/Promise.resolve @!accts))
              :switch! (fn [id]
                         (swap! !accts (fn [as] (mapv #(assoc % :active? (= id (:id %))) as)))
                         (js/Promise.resolve (some #(when (= id (:id %)) %) @!accts)))
              :alias!  (fn [id alias]
                         (swap! !accts (fn [as] (mapv #(cond-> % (= id (:id %)) (assoc :alias alias)) as)))
                         (js/Promise.resolve (some #(when (= id (:id %)) %) @!accts)))}})

(deftest switching-naming-and-signing-in
  (async done
    (let [!accts (atom accts)
          ps     {:anthropic (fake-provider !accts)}
          exec   #(run/exec! ps (accounts/parse-args (accounts/words %)) {:on-prompt (fn [_])})]
      (-> (exec "claude apple")
          (.then (fn [r]
                   (is (re-find #"now runs on b@x.io \(apple\)" (:text r)))
                   (is (= "2" (:id (first (filter :active? @!accts)))))
                   (exec "claude switch")))
          (.then (fn [r]
                   (is (re-find #"c@x.io" (:text r)) "rotates to the next")
                   (exec "claude c@x.io")))
          (.then (fn [r]
                   (is (re-find #"Already on" (:text r)))
                   (exec "claude alias 3 spare")))
          (.then (fn [r]
                   (is (= "c@x.io is now \"spare\"." (:text r)))
                   (exec "claude nobody")))
          (.then (fn [r]
                   (is (:error? r))
                   (exec "claude code early")))
          (.then (fn [r]
                   (is (re-find #"No Claude sign-in is waiting" (:text r)))
                   (let [login (exec "claude login")]
                     (-> (exec "claude login")
                         (.then (fn [r]
                                  (is (re-find #"already running" (:text r)) "one sign-in at a time")
                                  (exec "claude code me")))
                         (.then (fn [r]
                                  (is (re-find #"Code sent" (:text r)))
                                  login))))))
          (.then (fn [r]
                   (is (= "Signed in to Claude as me@x.io." (:text r)))
                   (exec "claude code again")))
          (.then (fn [r]
                   (is (:error? r) "the settled sign-in is gone")
                   (exec "claude forget pro")))
          (.then (fn [r]
                   (is (= "Forgot a@x.io (pro)." (:text r)))
                   (exec "nope")))
          (.then (fn [r]
                   (is (re-find #"Available: claude" (:text r)))
                   (done)))))))

;; ── Commands ─────────────────────────────────────────────────────────────────

(def ^:private handlers (merge events/core-handlers agent/handlers commands/handlers))

(deftest commands-hand-off-to-the-effect
  (let [st  (events/handle-event handlers (state/initial-state)
                                 {:type :room/create :room-id "r" :room {:cwd "/tmp"}})
        run (fn [name args] (events/handle-event handlers (:state st)
                                                 {:type :command/run :room-id "r" :name name :args args}))]
    (is (= [[:account/run {:room-id "r" :cmd {:op "switch" :provider "claude" :target "apple"}}]]
           (:effects (run "account" "claude apple"))))
    (is (= [[:account/run {:room-id "r" :cmd {:op "login" :provider "claude" :alias "work"}}]]
           (:effects (run "login" "claude --alias work"))))
    (is (= [[:account/run {:room-id "r" :cmd {:op "forget" :provider "opencode"}}]]
           (:effects (run "logout" "opencode"))))
    (testing "a parse error is a status line, no effect"
      (let [r (run "account" "claude code")]
        (is (empty? (:effects r)))
        (is (re-find #"Usage" (:text (last (:history (state/get-room (:state r) "r"))))))))))
