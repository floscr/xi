(ns xi.accounts
  "Provider accounts (docs/provider-protocol.md, the `accounts` capability):
   several sign-ins a provider stores, one of them live. Pure: which provider
   a user means, what `/account` and `xi account` ask for, which stored
   account a word names, and how results read. The requests run in
   xi.accounts.run; a provider map's :accounts and :login hold them
   (xi.providers.process).

   An account is {:id :email :alias :plan :active? :token-valid?}."
  (:require [clojure.string :as str]))

;; ── Providers ────────────────────────────────────────────────────────────────

(defn provider-name
  "What a user calls a provider: its sign-in's name (\"claude\"), else its id."
  [p]
  (or (get-in p [:login :name]) (name (:id p))))

(defn signable
  "The providers a user can sign in to or keep accounts with, by name."
  [providers]
  (sort-by provider-name (filter #(or (:login %) (:accounts %)) (vals providers))))

(defn find-provider
  "The signable provider `q` names: its sign-in name, id, or either label,
   any case."
  [providers q]
  (let [q (str/lower-case (str/trim (str q)))]
    (some #(when (contains? #{(str/lower-case (provider-name %))
                              (name (:id %))
                              (str/lower-case (str (:label %)))
                              (str/lower-case (str (get-in % [:login :label])))}
                            q)
             %)
          (signable providers))))

(defn label
  "A provider's name in messages: its sign-in's label, else its own."
  [p]
  (or (get-in p [:login :label]) (:label p) (provider-name p)))

;; ── Arguments ────────────────────────────────────────────────────────────────

(def ^:private ops #{"list" "switch" "login" "code" "cancel" "alias" "forget"})

(def ^:private op-aliases
  {"ls" "list" "use" "switch" "remove" "forget" "rm" "forget" "logout" "forget"})

(defn usage-text [cmd]
  (str "Usage: " cmd " [<provider> [list | <account> | switch [<account>] | "
       "login [--alias NAME] [--email ADDR] | code <CODE> | cancel | "
       "alias <account> [NAME] | forget [<account>]]]"))

(defn- split-flags
  "Words → [positional {:alias :email}]; a flag without a value is an error."
  [words]
  (loop [[w & more] words pos [] flags {}]
    (cond
      (nil? w) [pos flags]
      (#{"--alias" "--email"} w)
      (if (and (seq more) (not (str/starts-with? (first more) "--")))
        (recur (rest more) pos (assoc flags (keyword (subs w 2)) (first more)))
        [nil {:error (str w " needs a value")}])
      (str/starts-with? w "--") [nil {:error (str "Unknown flag " w)}]
      :else (recur more (conj pos w) flags))))

(defn parse-args
  "`/account` words → {:op …} for xi.accounts.run, or {:error msg}.
     []                               {:op \"overview\"}
     [p] / [p \"list\"]               {:op \"list\" :provider p}
     [p \"switch\" a?] / [p a]        {:op \"switch\" :provider p :target a}
     [p \"login\" --alias N --email E] {:op \"login\" … :alias N :email E}
     [p \"code\" C]                   {:op \"code\" … :code C}
     [p \"cancel\"]                   {:op \"cancel\" …}
     [p \"alias\" a N?]               {:op \"alias\" … :target a :alias N}  ; no N clears
     [p \"forget\" a?]                {:op \"forget\" … :target a}          ; none: the live one"
  [words]
  (let [[provider word & more] (remove str/blank? words)
        op-word (some-> word str/lower-case)
        op      (or (get op-aliases op-word) (ops op-word))
        ;; a word that names no op names the account to switch to
        [op more] (cond (nil? word) ["list" nil]
                        op          [op more]
                        :else       ["switch" (cons word more)])
        [pos {:keys [error] :as flags}] (split-flags more)
        cmd {:op op :provider provider}]
    (cond
      (nil? provider) {:op "overview"}
      error           {:error error}
      (and (seq flags) (not= "login" op)) {:error (str "--alias and --email go with login")}
      :else
      (case op
        "list"   cmd
        "switch" (cond-> cmd (first pos) (assoc :target (first pos)))
        "login"  (merge cmd (select-keys flags [:alias :email]))
        "code"   (if (seq pos)
                   (assoc cmd :code (str/join " " pos))
                   {:error (str "Usage: /account " provider " code <the code the sign-in page shows>")})
        "cancel" cmd
        "alias"  (if (seq pos)
                   (cond-> (assoc cmd :target (first pos)) (second pos) (assoc :alias (second pos)))
                   {:error (str "Usage: /account " provider " alias <account> [name]")})
        "forget" (cond-> cmd (first pos) (assoc :target (first pos)))))))

(defn words [s]
  (vec (remove str/blank? (str/split (str/trim (str s)) #"\s+"))))

;; ── Which account ────────────────────────────────────────────────────────────

(defn- ci= [a b]
  (and a b (= (str/lower-case a) (str/lower-case b))))

(defn resolve-target
  "The account `target` names: its id, else its email, else its alias (any case)."
  [accounts target]
  (let [t (str/trim (str target))]
    (or (some #(when (= t (:id %)) %) accounts)
        (some #(when (ci= t (:email %)) %) accounts)
        (some #(when (ci= t (:alias %)) %) accounts))))

(defn next-account
  "The account after the live one in list order, wrapping; never the live one.
   Without a live one, the first. nil when there is no other."
  [accounts]
  (let [i (first (keep-indexed (fn [i a] (when (:active? a) i)) accounts))]
    (if i
      (first (remove :active? (concat (drop (inc i) accounts) (take i accounts))))
      (first accounts))))

(defn account-label [{:keys [id email alias]}]
  (str (or email (str "account " id)) (when alias (str " (" alias ")"))))

;; ── Results ──────────────────────────────────────────────────────────────────

(defn- notes [{:keys [plan active? token-valid?]}]
  (str/join " · " (cond-> []
                    plan                   (conj plan)
                    active?                (conj "live")
                    (false? token-valid?)  (conj "token expired"))))

(defn list-result
  "The status of a provider's accounts: space-aligned text for the terminal
   (`*` marks the live one), :rows for the web."
  [provider-label pname accounts]
  (if (empty? accounts)
    {:text (str "No " provider-label " accounts yet. Sign in: /account " pname " login")}
    (let [width (fn [k] (apply max 0 (map (comp count str k) accounts)))
          pad   (fn [s n] (str s (apply str (repeat (- n (count (str s))) " "))))
          cols  (fn [a] (->> [:id :email :alias]
                             (filter #(pos? (width %)))
                             (map #(pad (get a %) (width %)))))]
      {:title (str provider-label " accounts")
       :text  (str provider-label " accounts\n"
                   (str/join "\n"
                             (for [{:keys [active?] :as a} accounts]
                               (str/trimr (str (if active? "* " "  ")
                                               (str/join "  " (concat (cols a) [(notes a)])))))))
       :rows  (vec (for [{:keys [id email alias active?] :as a} accounts]
                     (cond-> {:label (or alias (str "#" id))
                              :text  (str/join " · " (remove str/blank? [email (notes a)]))}
                       active? (assoc :tone :success))))})))

(defn prompt-status
  "The banner of a sign-in in progress: the page to open, and what to bring
   back from it (`needs-code?`) or enter there (`user-code`)."
  [provider-label pname {:keys [url user-code needs-code?]} action-id]
  (let [then (if needs-code?
               (str "Then send the code it shows: /account " pname " code <CODE>")
               (str "Enter the code " user-code " there. Waiting for the approval…"))]
    {:title  (str "Sign in to " provider-label)
     :text   (str "Sign in to " provider-label ": open " url "\n" then)
     :detail then
     :tone   :info
     :action {:id action-id :label "Open sign-in page" :url url}}))

(defn account->json
  "An account as `xi account --json` prints it (the protocol's field names)."
  [{:keys [id email alias plan active? token-valid?]}]
  (cond-> {:id id :active (boolean active?)}
    email                  (assoc :email email)
    alias                  (assoc :alias alias)
    plan                   (assoc :plan plan)
    (boolean? token-valid?) (assoc :tokenValid token-valid?)))
