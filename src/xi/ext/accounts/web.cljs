(ns xi.ext.accounts.web
  "The /accounts page: every provider's stored accounts (xi.accounts) with
   Switch, and Rename and Forget in a row's menu, and a sign-in to another
   account as steps: the page to open, the code to enter there or the box for
   the code the page shows.

   State lives at :web/accounts {:providers :loading? :busy? :result
   :menu [provider id] :editing {:provider :id :value}
   :drafts {provider {:adding? :alias :opened? :code}}}. Entering the page sends the roomless
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
  (let [[pname op] words]
    {:state   (update st :web/accounts
                      #(cond-> (dissoc % :result :menu :editing)
                         (quick-ops op)  (assoc :busy? true)
                         (= "login" op) (update-in [:drafts pname] dissoc :adding? :alias :opened?)))
     :effects [[:ws/send {:type :accounts/run :words (vec words)}]]}))

(defn- result [st {:keys [result]}]
  {:state (update st :web/accounts assoc :result result :busy? false)})

(defn- draft [st {:keys [provider key value]}]
  {:state (assoc-in st [:web/accounts :drafts provider key] value)})

(defn- toggle-menu
  "Opens the ⋯ menu of account `id`, or closes it (no :id, or the open one)."
  [st {:keys [provider id]}]
  {:state (update st :web/accounts
                  #(if (and id (not= [provider id] (:menu %)))
                     (assoc % :menu [provider id])
                     (dissoc % :menu)))})

(defn- edit-alias
  "The alias being edited {:provider :id :value}; no :provider stops editing."
  [st {:keys [provider id value]}]
  {:state (update st :web/accounts
                  #(-> (dissoc % :menu)
                       (assoc :editing (when provider {:provider provider :id id :value value}))))})

(defn- on-navigate
  "Chained after the router: entering the page fetches what it shows."
  [st {:keys [page]}]
  (when (= page :accounts)
    (fetch st nil)))

;; ── Pieces ───────────────────────────────────────────────────────────────────

(defn- send-words! [dispatch! & words]
  (dispatch! {:type :accounts/run :words (vec words)}))

(defn- draft! [dispatch! pname key value]
  (dispatch! {:type :accounts/draft :provider pname :key key :value value}))

(defn- focus-on-mount [{:replicant/keys [node]}]
  (.focus node #js {:preventScroll true}))

(defn- on-keys
  "Input :on handlers: Enter runs `submit`, Escape `cancel`."
  [submit cancel]
  {:keydown (fn [^js e]
              (case (.-key e)
                "Enter"  (do (.preventDefault e) (submit))
                "Escape" (cancel)
                nil))})

(defn- avatar [{:keys [id email alias active?]}]
  [:span {:class ["accounts-avatar"]}
   (str/upper-case (str (first (or alias email id "?"))))
   (when active? [:span {:class ["accounts-avatar-dot"] :title "In use"}])])

(defn- notes [{:keys [alias plan active? token-valid?]}]
  (str/join " · " (remove nil? [alias plan
                                 (when (false? token-valid?)
                                   ;; tokens renew once their account is live (provider protocol)
                                   (if active? "token expired" "token renews on switch"))])))

(defn- rename-field [dispatch! pname {:keys [id value]}]
  (let [save!   #(apply send-words! dispatch! pname "alias" id
                        (when-not (str/blank? value) [(str/trim value)]))
        cancel! #(dispatch! {:type :accounts/edit})]
    [:div {:class ["accounts-rename"]}
     (form/form-input
      {:value (or value "") :placeholder "Alias, empty clears it"
       :attrs {:aria-label "Alias" :autocomplete "off" :spellcheck "false"
               :replicant/on-mount focus-on-mount
               :on (merge {:input (fn [^js e] (dispatch! {:type :accounts/edit :provider pname :id id
                                                          :value (.. e -target -value)}))}
                          (on-keys save! cancel!))}})
     (button/button {:variant :primary :size :sm :on-click (fn [_] (save!))} "Save")
     (button/button {:variant :ghost :size :sm :on-click (fn [_] (cancel!))} "Cancel")]))

(defn- menu-item [label icon-name on-click & [danger?]]
  [:button {:class (cond-> ["overflow-menu-item"] danger? (conj "accounts-menu-danger"))
            :replicant/key label
            :on {:click (fn [^js e] (.stopPropagation e) (on-click))}}
   (icon/icon {:icon-name icon-name :size :sm})
   [:span label]])

(defn- row-menu [dispatch! busy? pname {:keys [id alias active?]} open?]
  [:div {:class ["overflow-menu-wrap"]}
   [:button {:class ["icon-btn" "icon-btn--sm"] :title "More"
             :on {:click (fn [^js e]
                           (.stopPropagation e)
                           (dispatch! {:type :accounts/menu :provider pname :id id}))}}
    (views/more-vertical-icon)]
   (when open?
     (list
      [:div {:class ["overflow-menu-backdrop"] :replicant/key "backdrop"
             :on {:click (fn [_] (dispatch! {:type :accounts/menu}))}}]
      [:div {:class ["overflow-menu"] :replicant/key "menu"}
       (menu-item (if alias "Rename" "Add alias") :edit
                  #(dispatch! {:type :accounts/edit :provider pname :id id :value (or alias "")}))
       (when-not (or active? busy?)
         (menu-item "Forget" :trash #(send-words! dispatch! pname "forget" id) true))]))])

(defn- account-row [dispatch! busy? pname menu editing {:keys [id email active?] :as a}]
  (let [editing? (= [pname id] [(:provider editing) (:id editing)])]
    [:div {:class ["accounts-row"] :replicant/key id}
     (avatar a)
     (if editing?
       (rename-field dispatch! pname editing)
       [:div {:class ["accounts-row-main"]}
        [:span {:class ["accounts-row-email"]} (or email (str "Account " id))]
        (let [n (notes a)]
          (when-not (str/blank? n) [:span {:class ["accounts-row-notes"]} n]))])
     (when-not editing?
       [:div {:class ["accounts-row-actions"]}
        (if active?
          (badge/badge {:variant :success :size :sm} "In use")
          (button/button {:variant :outline :size :sm :disabled busy?
                          :on-click (fn [_] (send-words! dispatch! pname "switch" id))}
                         "Switch"))
        (row-menu dispatch! busy? pname a (= [pname id] menu))])]))

(defn- step [n done? & body]
  [:li {:class ["accounts-step"]}
   [:span {:class (cond-> ["accounts-step-n"] done? (conj "accounts-step-n--done"))}
    (if done? (icon/icon {:icon-name :check :size :xs}) n)]
   (into [:div {:class ["accounts-step-body"]}] body)])

(defn- sign-in-panel
  "A sign-in in progress as steps: open the page, then the code to enter
   there or the box for the code it shows, then the wait for approval."
  [dispatch! pname label {:keys [url user-code needs-code?]} {:keys [code opened?]}]
  [:div {:class ["accounts-sign-in"]}
   [:div {:class ["accounts-sign-in-title"]} (str "New " label " account")]
   (if-not url
     [:div {:class ["accounts-note"]} (views/spinner) " Starting the sign-in…"]
     (let [submit! #(when-not (str/blank? code)
                      (send-words! dispatch! pname "code" (str/trim code))
                      (draft! dispatch! pname :code nil))]
       [:ol {:class ["accounts-steps"]}
        (step 1 opened?
              [:span "Open the sign-in page"]
              (button/button {:variant (if opened? :outline :primary) :size :sm :href url
                              :icon-right :external-link
                              :attrs {:target "_blank" :rel "noopener noreferrer"}
                              :on-click (fn [_] (draft! dispatch! pname :opened? true))}
                             (if opened? "Open again" "Open sign-in page")))
        (if needs-code?
          (step 2 false
                [:span "Paste the code it shows"]
                [:div {:class ["accounts-sign-in-code"]}
                 (form/form-input
                  {:value (or code "") :placeholder "Code"
                   :attrs {:aria-label "Code from the sign-in page" :autocomplete "off"
                           :autocapitalize "off" :spellcheck "false"
                           :on (merge {:input (fn [^js e] (draft! dispatch! pname :code (.. e -target -value)))}
                                      (on-keys submit! (fn [])))}})
                 (button/button {:variant :primary :size :sm :disabled (str/blank? code)
                                 :on-click (fn [_] (submit!))}
                                "Submit")])
          (step 2 false
                [:span "Enter this code there"]
                [:div {:class ["accounts-user-code"]}
                 [:code user-code]
                 (button/button {:variant :ghost :size :sm :icon-left :copy
                                 :on-click (fn [_] (views/copy! dispatch! user-code))}
                                "Copy")]))
        (when-not needs-code?
          (step 3 false [:span {:class ["accounts-note"]} (views/spinner) " Waiting for approval…"]))]))
   [:div {:class ["accounts-sign-in-foot"]}
    (button/button {:variant :ghost :size :sm
                    :on-click (fn [_] (send-words! dispatch! pname "cancel"))}
                   "Cancel")]])

(defn- add-account [dispatch! pname any? {:keys [adding? alias]}]
  (if adding?
    (let [start!  #(apply send-words! dispatch! pname "login"
                          (when-not (str/blank? alias) ["--alias" (str/trim alias)]))
          cancel! #(draft! dispatch! pname :adding? false)]
      [:div {:class ["accounts-add-form"]}
       (form/form-input
        {:value (or alias "") :placeholder "Alias (optional), e.g. work"
         :attrs {:aria-label "Alias of the new account" :autocomplete "off" :spellcheck "false"
                 :replicant/on-mount focus-on-mount
                 :on (merge {:input (fn [^js e] (draft! dispatch! pname :alias (.. e -target -value)))}
                            (on-keys start! cancel!))}})
       (button/button {:variant :primary :size :sm :on-click (fn [_] (start!))} "Continue")
       (button/button {:variant :ghost :size :sm :on-click (fn [_] (cancel!))} "Cancel")])
    [:button {:class ["accounts-add"]
              :on {:click (fn [_] (draft! dispatch! pname :adding? true))}}
     [:span {:class ["accounts-add-plus"]} (icon/icon {:icon-name :plus :size :sm})]
     (if any? "Add another account" "Add an account")]))

(defn- provider-card
  [dispatch! busy? {:keys [drafts menu editing]} {:keys [name label accounts? login? accounts error sign-in]}]
  (let [d (get drafts name)]
    (card/card {:class "accounts-card" :attrs {:replicant/key name}}
      (card/card-header {:class "accounts-card-head"}
        [:div {:class ["accounts-card-title"]}
         [:span {:class ["accounts-logo" (str "accounts-logo--" name)]}
          (str/upper-case (str (first label)))]
         [:div {:class ["usage-card-title"]} label]]
        [:code {:class ["accounts-card-name"]} (str "/account " name)])
      (card/card-body {:class "accounts-card-body"}
        (when error (alert/alert {:variant :danger} error))
        (when accounts?
          (cond
            (seq accounts)
            [:div {:class ["accounts-rows"]}
             (for [a accounts] (account-row dispatch! busy? name menu editing a))]

            ;; with a sign-in, the "Add an account" row says it
            (not login?)
            [:div {:class ["accounts-note"]} "No accounts stored yet."]))
        (when login?
          (if sign-in
            (sign-in-panel dispatch! name label sign-in d)
            (add-account dispatch! name (seq accounts) d)))))))

;; ── Page ─────────────────────────────────────────────────────────────────────

(defn- accounts-page [state dispatch!]
  (let [{:keys [providers loading? busy? result] :as page} (:web/accounts state)]
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
         (for [p providers] (provider-card dispatch! (boolean busy?) page p))])]]))

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
              :accounts/menu   toggle-menu
              :accounts/edit   edit-alias
              :route/navigate  on-navigate}
   :routes   {"accounts" {:parse (fn [_] {:page :accounts})
                          :path  {:accounts (fn [_] "/accounts")}}}
   :pages    {:accounts accounts-page}
   :nav-items [{:menu :palette :label "Accounts" :icon :user
                :event {:type :route/navigate :page :accounts}}
               {:menu :overflow :label "Accounts" :icon :user
                :event {:type :route/navigate :page :accounts}}]
   :taps     [load-on-palette-open]})
