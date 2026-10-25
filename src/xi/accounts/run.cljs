(ns xi.accounts.run
  "The account requests behind `/account`, `/login` and `/logout` (xi.fx
   :account/run) and `xi account` / `xi login` / `xi logout` (xi.cli), for
   any surface: `exec!` resolves to a result {:text :title :rows :tone :data
   :error?} the caller shows or prints. A sign-in in progress is kept here per
   provider until it settles, so `code` and `cancel` reach it."
  (:require [clojure.string :as str]
            [xi.accounts :as accounts]))

(defonce ^{:private true :doc "provider id → {:code! :cancel! :needs-code?} of a running sign-in"}
  !pending (atom {}))

(defn- ok [m] (js/Promise.resolve m))

(defn- fail [text] {:text text :tone :error :error? true})

(defn- list! [p]
  (if-let [list-fn (get-in p [:accounts :list!])]
    (list-fn)
    (js/Promise.reject (js/Error. (str (accounts/label p) " keeps no accounts.")))))

(defn- with-target
  "The accounts, and the one `target` names → (f accounts account); a target
   nobody has fails."
  [p target f]
  (-> (list! p)
      (.then (fn [accts]
               (if-let [a (accounts/resolve-target accts target)]
                 (f accts a)
                 (fail (str "No " (accounts/label p) " account \"" target "\". "
                            "See /account " (accounts/provider-name p) ".")))))))

(defn- listing [p]
  (-> (list! p)
      (.then (fn [accts]
               (assoc (accounts/list-result (accounts/label p) (accounts/provider-name p) accts)
                      :data {:accounts (mapv accounts/account->json accts)})))))

(defn- overview [providers]
  (let [ps (filter :accounts (accounts/signable providers))]
    (if (empty? ps)
      (ok {:text "No provider keeps accounts." :data {:providers []}})
      (-> (js/Promise.all (into-array (map #(-> (listing %) (.catch (fn [e] (fail (.-message e))))) ps)))
          (.then (fn [results]
                   (let [rs (vec results)]
                     {:text (str/join "\n\n" (map :text rs))
                      :title "Accounts"
                      :rows (vec (mapcat (fn [p r]
                                           (if (seq (:rows r))
                                             (map #(update % :label (fn [l] (str (accounts/provider-name p) " " l)))
                                                  (:rows r))
                                             ;; none yet, or the list failed: its text says which
                                             [(cond-> {:label (accounts/provider-name p) :text (:text r)}
                                                (:error? r) (assoc :tone :error))]))
                                         ps rs))
                      :data {:providers (mapv (fn [p r] {:provider (accounts/provider-name p)
                                                         :accounts (get-in r [:data :accounts])})
                                              ps rs)}})))))))

(defn- switch! [p target]
  (let [go (fn [a]
             (if (:active? a)
               (ok {:text (str "Already on " (accounts/account-label a) ".")})
               (-> ((get-in p [:accounts :switch!]) (:id a))
                   (.then (fn [a]
                            {:text (str (accounts/label p) " now runs on " (accounts/account-label a)
                                        ". Chats use it from their next message.")
                             :tone :success
                             :data {:account (accounts/account->json a)}})))))]
    (if target
      (with-target p target (fn [_ a] (go a)))
      (-> (list! p)
          (.then (fn [accts]
                   (if-let [a (accounts/next-account accts)]
                     (go a)
                     (fail (str "No other " (accounts/label p) " account to switch to.")))))))))

(defn- login!
  "Sign in to another account; `on-prompt` shows the page (xi.accounts
   prompt-status). Resolves once the sign-in settles."
  [p {:keys [alias email]} on-prompt]
  (let [id (:id p) lbl (accounts/label p)]
    (cond
      (not (:login p)) (ok (fail (str lbl " has no sign-in.")))
      (get @!pending id) (ok (fail (str "A " lbl " sign-in is already running. Finish it, or "
                                        "/account " (accounts/provider-name p) " cancel.")))
      :else
      (do (swap! !pending assoc id {})
          (-> ((get-in p [:login :login!])
               {:alias alias :email email
                :on-prompt (fn [{:keys [code! cancel! needs-code?] :as prompt}]
                             (swap! !pending assoc id {:code! code! :cancel! cancel! :needs-code? needs-code?})
                             (on-prompt prompt))})
              (.then (fn [{:keys [email account]}]
                       {:text (str "Signed in to " lbl (when email (str " as " email)) ".")
                        :tone :success
                        :data (cond-> {} account (assoc :account (accounts/account->json account)))}))
              (.catch (fn [e]
                        (if (re-matches #"(?i)aborted|.*cancell?ed" (str (.-message e)))
                          {:text (str lbl " sign-in cancelled.")}
                          (fail (str lbl " sign-in failed: " (.-message e))))))
              (.finally (fn [] (swap! !pending dissoc id))))))))

(defn- code! [p code]
  (let [pending (get @!pending (:id p))
        {:keys [code! needs-code?]} pending]
    (cond
      (and code! needs-code?)
      (do (code! code)
          (ok {:text (str "Code sent. Waiting for " (accounts/label p) " to finish the sign-in…")}))

      pending
      (ok (fail (str "This " (accounts/label p) " sign-in takes no code here: enter the one it showed "
                     "on its sign-in page.")))

      :else
      (ok (fail (str "No " (accounts/label p) " sign-in is waiting for a code. Start one: /account "
                     (accounts/provider-name p) " login"))))))

(defn- cancel! [p]
  (if-let [cancel (:cancel! (get @!pending (:id p)))]
    (do (cancel) (ok {:text "Cancelling the sign-in…"}))
    (ok (fail (str "No " (accounts/label p) " sign-in is running.")))))

(defn- alias! [p target alias]
  (with-target p target
    (fn [_ a]
      (-> ((get-in p [:accounts :alias!]) (:id a) alias)
          (.then (fn [a]
                   {:text (if alias
                            (str (or (:email a) (str "Account " (:id a))) " is now \"" alias "\".")
                            (str "Cleared the alias of " (accounts/account-label a) "."))
                    :data {:account (accounts/account->json a)}}))))))

(defn- forget! [p target]
  (let [logout! (get-in p [:login :logout!])
        lbl     (accounts/label p)]
    (cond
      (not logout!) (ok (fail (str lbl " has no sign-in to forget.")))
      (nil? target) (-> (logout!) (.then (fn [_] {:text (str "Signed out of " lbl ".")})))
      :else (with-target p target
              (fn [_ a]
                (-> (logout! (:id a))
                    (.then (fn [_] {:text (str "Forgot " (accounts/account-label a) ".")}))))))))

(defn exec!
  "Run `cmd` (xi.accounts parse-args) against `providers`. `on-prompt` shows a
   sign-in's page. Resolves to a result map; never rejects."
  [providers {:keys [op provider target alias email code] :as cmd} {:keys [on-prompt]}]
  (let [p (when provider (accounts/find-provider providers provider))]
    (-> (cond
          (:error cmd)        (ok (fail (:error cmd)))
          (= "overview" op)   (overview providers)
          (nil? p)            (ok (fail (str "No sign-in for \"" provider "\". Available: "
                                             (str/join ", " (map accounts/provider-name (accounts/signable providers))))))
          :else
          (case op
            "list"   (listing p)
            "switch" (switch! p target)
            "login"  (login! p {:alias alias :email email} (or on-prompt (fn [_])))
            "code"   (code! p code)
            "cancel" (cancel! p)
            "alias"  (alias! p target alias)
            "forget" (forget! p target)
            (ok (fail (accounts/usage-text "/account")))))
        (.catch (fn [e] (fail (.-message e)))))))
