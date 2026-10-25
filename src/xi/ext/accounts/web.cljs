(ns xi.ext.accounts.web
  "The /accounts page: every provider's stored accounts (xi.accounts) with
   Switch and Forget, and a sign-in to another account with the link to
   open, the code to enter there or the box for the code the page shows.

   State lives at :web/accounts {:providers :loading? :busy? :result
   :drafts {provider {:alias :code}}}. Entering the page sends the roomless
   :accounts/fetch; the server (xi.server.ws) answers :accounts/state, again
   once a sign-in shows its page. A button sends :accounts/run with the
   words of the /account command it stands for; its :accounts/result is the
   line at the top."
  (:require [clojure.string :as str]
            [ui.alert :as alert]
            [ui.badge :as badge]
            [ui.button :as button]
            [ui.card :as card]
            [ui.empty-state :as empty-state]
            [ui.form :as form]
            [ui.icon :as icon]
            [xi.accounts :as accounts]
            [xi.web.palette-items :as palette-items]
            [xi.web.views :as views]))

;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- fetch [st _]
  {:state   (assoc-in st [:web/accounts :loading?] true)
   :effects [[:ws/send {:type :accounts/fetch}]]})

(defn- received [st {:keys [providers]}]
  {:state (update st :web/accounts assoc :providers (vec providers) :loading? false)})

(def ^:private quick-ops
  "Ops that answer at once; a sign-in answers when it settles."
  #{"switch" "alias" "forget"})

(defn- run [st {:keys [words]}]
  {:state   (update st :web/accounts
                    #(cond-> (dissoc % :result)
                       (quick-ops (second words)) (assoc :busy? true)))
   :effects [[:ws/send {:type :accounts/run :words (vec words)}]]})

(defn- result [st {:keys [result]}]
  {:state (update st :web/accounts assoc :result result :busy? false)})

(defn- draft [st {:keys [provider key value]}]
  {:state (assoc-in st [:web/accounts :drafts provider key] value)})

(defn- on-navigate
  "Chained after the router: entering the page fetches what it shows."
  [st {:keys [page]}]
  (when (= page :accounts)
    (fetch st nil)))

;; ── Pieces ───────────────────────────────────────────────────────────────────

(defn- send-words! [dispatch! & words]
  (dispatch! {:type :accounts/run :words (vec words)}))

(defn- account-row [dispatch! busy? pname {:keys [id email alias plan active? token-valid?]}]
  [:div {:class ["accounts-row"] :replicant/key id}
   [:div {:class ["accounts-row-main"]}
    [:span {:class ["accounts-row-email"]} (or email (str "Account " id))]
    [:span {:class ["accounts-row-notes"]}
     (str/join " · " (remove nil? [alias plan (when (false? token-valid?) "token expired")]))]]
   (if active?
     (badge/badge {:variant :success :size :sm} "Live")
     [:div {:class ["accounts-row-actions"]}
      (button/button {:variant :outline :size :sm :disabled busy?
                      :on-click (fn [_] (send-words! dispatch! pname "switch" id))}
                     "Switch")
      (button/button {:variant :ghost :size :sm :disabled busy?
                      :attrs {:title (str "Forget " (or email id))}
                      :on-click (fn [_] (send-words! dispatch! pname "forget" id))}
                     "Forget")])])

(defn- sign-in-panel
  "A sign-in in progress: the page to open, then the code to enter there or
   the box for the code it shows."
  [dispatch! pname label {:keys [url user-code needs-code?]} code]
  [:div {:class ["accounts-sign-in"]}
   [:div {:class ["accounts-sign-in-title"]} (str "Sign in to " label)]
   (if-not url
     [:div {:class ["accounts-note"]} (views/spinner) " Starting the sign-in…"]
     [:div {:class ["accounts-sign-in-steps"]}
      (button/button {:variant :primary :size :sm :href url
                      :attrs {:target "_blank" :rel "noopener noreferrer"}}
                     "Open sign-in page")
      (if needs-code?
        [:span {:class ["accounts-note"]} "Then paste the code it shows:"]
        [:span {:class ["accounts-note"]} "Enter " [:code user-code] " there. Waiting for the approval…"])])
   (when needs-code?
     [:div {:class ["accounts-sign-in-code"]}
      (form/form-input
       {:value (or code "") :placeholder "Code"
        :attrs {:aria-label "Code from the sign-in page" :autocomplete "off"
                :autocapitalize "off" :spellcheck "false"
                :on {:input (fn [^js e] (dispatch! {:type :accounts/draft :provider pname :key :code
                                                    :value (.. e -target -value)}))}}})
      (button/button {:variant :primary :size :sm :disabled (str/blank? code)
                      :on-click (fn [_]
                                  (send-words! dispatch! pname "code" (str/trim code))
                                  (dispatch! {:type :accounts/draft :provider pname :key :code :value nil}))}
                     "Submit")])
   [:div
    (button/button {:variant :ghost :size :sm
                    :on-click (fn [_] (send-words! dispatch! pname "cancel"))}
                   "Cancel")]])

(defn- sign-in-start [dispatch! pname alias]
  [:div {:class ["accounts-sign-in-start"]}
   (form/form-input
    {:value (or alias "") :placeholder "Alias (optional)"
     :attrs {:aria-label "Alias of the new account" :autocomplete "off" :spellcheck "false"
             :on {:input (fn [^js e] (dispatch! {:type :accounts/draft :provider pname :key :alias
                                                 :value (.. e -target -value)}))}}})
   (button/button {:variant :outline :size :sm :icon-left :log-in
                   :on-click (fn [_]
                               (apply send-words! dispatch! pname "login"
                                      (when-not (str/blank? alias) ["--alias" (str/trim alias)])))}
                  "Sign in to another account")])

(defn- provider-card
  [dispatch! busy? drafts {:keys [name label accounts? login? accounts error sign-in]}]
  (let [{:keys [alias code]} (get drafts name)]
    (card/card {:class "accounts-card" :attrs {:replicant/key name}}
      (card/card-header {:class "accounts-card-head"}
        [:div {:class ["usage-card-title"]} label]
        [:code {:class ["accounts-card-name"]} (str "/account " name)])
      (card/card-body {:class "accounts-card-body"}
        (when error (alert/alert {:variant :danger} error))
        (when accounts?
          (if (seq accounts)
            (for [a accounts] (account-row dispatch! busy? name a))
            [:div {:class ["accounts-note"]} "No accounts stored yet."]))
        (when login?
          (if sign-in
            (sign-in-panel dispatch! name label sign-in code)
            (sign-in-start dispatch! name alias)))))))

;; ── Page ─────────────────────────────────────────────────────────────────────

(defn- accounts-page [state dispatch!]
  (let [{:keys [providers loading? busy? result drafts]} (:web/accounts state)]
    [:div {:class ["container"] :replicant/key "accounts"}
     [:div {:class ["topbar"]}
      (views/menu-button dispatch!)
      [:div {:class ["topbar-title"]} "Accounts"]
      [:button {:class ["icon-btn"] :title "Refresh"
                :disabled (when loading? true)
                :on {:click (fn [_] (dispatch! {:type :accounts/fetch}))}}
       (icon/icon {:icon-name :refresh :size :md})]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (when result
        (alert/alert {:variant (:tone result) :class "accounts-result"} (:text result)))
      (cond
        (and loading? (empty? providers))
        (empty-state/empty-state {} (views/spinner) [:p "Loading accounts…"])

        (empty? providers)
        (empty-state/empty-state {} [:p "No provider here signs in or keeps accounts."])

        :else
        [:div {:class ["accounts-grid"]}
         (for [p providers] (provider-card dispatch! (boolean busy?) drafts p))])]]))

;; ── Palette ──────────────────────────────────────────────────────────────────

(defn- switch-items
  "A Cmd+K entry per stored account that isn't live, once the page loaded them."
  [state]
  (for [{:keys [name label accounts]} (get-in state [:web/accounts :providers])
        {:keys [id email alias active?] :as a} accounts
        :when (not active?)]
    {:label (str "Switch " label " to " (accounts/account-label a))
     :icon  :user
     :event {:type :accounts/run :words [name "switch" id]}}))

;; Built-in web halves carry no :palette-items key the app composes (that is
;; user extensions' xi.web.user-ext): register when the page's code loads,
;; as those are, through the var so a hot reload takes the new fn.
(palette-items/register! :accounts #(switch-items %))

(defn- load-on-palette-open
  "The palette lists accounts to switch to: opening it fetches them once."
  [dispatch!]
  (fn [event state]
    (when (and (= :palette/opened (:type event))
               (nil? (get-in state [:web/accounts :providers])))
      (dispatch! {:type :accounts/fetch}))))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id       :accounts
   :handlers {:accounts/fetch  fetch
              :accounts/state  received
              :accounts/run    run
              :accounts/result result
              :accounts/draft  draft
              :route/navigate  on-navigate}
   :routes   {"accounts" {:parse (fn [_] {:page :accounts})
                          :path  {:accounts (fn [_] "/accounts")}}}
   :pages    {:accounts accounts-page}
   :nav-items [{:menu :palette :label "Accounts" :icon :user
                :event {:type :route/navigate :page :accounts}}
               {:menu :overflow :label "Accounts" :icon :user
                :event {:type :route/navigate :page :accounts}}]
   :taps     [load-on-palette-open]})
