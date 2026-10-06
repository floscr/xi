(ns xi.api-user-test
  "xi.api.user — users and the state extensions keep about them — driven the
   way it runs: extensions loaded through the sandbox, a real app, a temp
   state directory and a temp config file."
  (:require [cljs.reader :refer [read-string]]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.ext.user :as user]
            [xi.user-config :as cfg]
            [xi.user-state.store :as store]
            [xi.users :as users]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private tmp (atom nil))
(def ^:private prev-config (atom nil))

(defn- write! [dir name src]
  (fs/writeFileSync (node-path/join dir name) src))

(use-fixtures :each
  {:before (fn []
             (reset! tmp (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-api-user-")))
             (reset! prev-config (cfg/config-file))
             (store/set-dir! (node-path/join @tmp "state"))
             (write! @tmp "config.edn"
                     (str "{:type :xi/config :version 1"
                          " :users {\"alice\" {:name \"Alice\" :meta {:team \"ops\" :admin? true}}"
                          "         \"bob\" {:name \"Bob\"}}}"))
             (cfg/set-config-file! (node-path/join @tmp "config.edn")))
   :after  (fn []
             (user/stop!)
             (cfg/set-config-file! @prev-config)
             (store/set-dir! nil)
             (fs/rmSync @tmp #js {:recursive true :force true}))})

(def ^:private notes-src
  "(ns notes (:require [xi.api.user :as u]))
   (def extension
     {:id :notes
      :tool-registry
      {\"visit\"  (fn [_ ctx]
                  (let [n (inc (or (:visits (u/state ctx)) 0))]
                    (u/set-state! ctx {:visits n})
                    {:content [{:type \"text\" :text (str n)}]}))
       \"whoami\" (fn [_ ctx]
                  {:content [{:type \"text\"
                              :text (pr-str {:current (u/current ctx)
                                             :info    (u/info ctx)
                                             :state   (u/state ctx)})}]})
       \"for\"    (fn [{:keys [id]} ctx]
                  (u/set-state! ctx id {:seen true})
                  {:content [{:type \"text\" :text \"ok\"}]})
       \"all\"    (fn [_ ctx]
                  {:content [{:type \"text\" :text (pr-str (u/users ctx))}]})
       \"bad\"    (fn [{:keys [what]} ctx]
                  (u/set-state! ctx (case what
                                      \"fn\"  inc
                                      \"big\" (apply str (repeat 70000 \"x\"))
                                      \"sym\" 'a-symbol))
                  {:content [{:type \"text\" :text \"written\"}]})
       \"forget\" (fn [_ ctx] (u/set-state! ctx nil) {:content [{:type \"text\" :text \"ok\"}]})
       \"badid\"  (fn [_ ctx] (u/info ctx \"Not A Slug\"))}
      ;; a handler that tries to write the users slice directly
      :handlers {:ext.notes/grab (fn [st _]
                                   {:state (assoc-in st [:users \"alice\" :ext :notes] {:visits 999})})
                 :ext.notes/mine (fn [st _]
                                   {:state (assoc-in st [:ext :notes :ok] true)})}})")

(def ^:private other-src
  "(ns other (:require [xi.api.user :as u]))
   (def extension
     {:id :other
      :tool-registry
      {\"peek\" (fn [_ ctx]
                {:content [{:type \"text\"
                            :text (pr-str {:state (u/state ctx)
                                           :info-keys (sort (keys (u/info ctx)))})}]})
       \"keep\" (fn [_ ctx] (u/set-state! ctx {:secret 1}) {:content [{:type \"text\" :text \"ok\"}]})}})")

(defn- boot!
  "A running app (core handlers) with `srcs` loaded as extensions; → a fn
   (call ext tool args {:user …}) → the tool's text, plus the app."
  [& srcs]
  (let [dir (fs/mkdtempSync (node-path/join @tmp "ext-"))
        _   (doseq [[i src] (map-indexed vector srcs)]
              (write! dir (str "e" i ".cljs") src))
        _   (user/set-enabled-override! (fn [] (set (map #(str "e" % ".cljs") (range (count srcs))))))
        entries (->> (user/load-dir dir) (filter :id))
        a   (app/create-app {:initial-state (state/initial-state {:mode :server :user "carol"})
                             :handlers events/core-handlers})]
    (user/start! a)
    {:app a
     :entries entries
     :call (fn [ext tool args ctx-extra]
             (let [entry (first (filter #(= ext (:id %)) entries))
                   f     (get-in entry [:extension :tool-registry tool])
                   res   (f args (merge {:dispatch! (:dispatch! a) :get-state (fn [] @(:state a))
                                         :room-id "r"} ctx-extra))]
               (-> res :content first :text)))}))

(defn- st [{:keys [app]}] @(:state app))

(deftest an-extension-keeps-state-per-user-and-it-persists
  (let [{:keys [call] :as sys} (boot! notes-src)]
    (is (= "1" (call :notes "visit" {} {:user "alice"})))
    (is (= "2" (call :notes "visit" {} {:user "alice"})))
    (is (= "1" (call :notes "visit" {} {:user "bob"})) "bob has his own count")
    (testing "it lands in app state, where handlers and other code can read it"
      (is (= {:visits 2} (state/user-ext (st sys) "alice" :notes)))
      (is (= {:visits 1} (state/user-ext (st sys) "bob" :notes))))
    (testing "and on disk, as the store reads it"
      (is (= {:visits 2} (store/ext-state "alice" :notes)))
      (is (= {:ext {:notes {:visits 1}}} (store/load-state "bob"))))
    (testing "a fresh process sees it: the record is loaded from disk"
      (is (= {:visits 2} (:notes (:ext (users/record "alice"))))))))

(deftest the-current-user-defaults-sensibly
  (let [{:keys [call] :as sys} (boot! notes-src)
        who (fn [ctx] (read-string (call :notes "whoami" {} ctx)))]
    (is (= "alice" (:current (who {:user "alice"}))) "the user whose prompt started the turn")
    (is (= "carol" (:current (who {}))) "no user on the ctx: the process' own user")
    (is (= "carol" (:current (who {:user nil}))))
    (is (some? sys))))

(deftest info-is-read-only-and-has-no-extension-state
  (let [{:keys [call]} (boot! notes-src)
        info (:info (read-string (call :notes "whoami" {} {:user "alice"})))]
    (is (= {:id "alice" :name "Alice" :meta {:team "ops" :admin? true} :ui {}} info))
    (testing "an undeclared user has an id and nothing else"
      (is (= {:id "zed" :name nil :meta {} :ui {}}
             (:info (read-string (call :notes "whoami" {} {:user "zed"}))))))
    (testing "state of the extension itself is a separate call"
      (call :notes "visit" {} {:user "alice"})
      (let [r (read-string (call :notes "whoami" {} {:user "alice"}))]
        (is (= {:visits 1} (:state r)))
        (is (not (contains? (:info r) :ext)))))))

(deftest ui-state-shows-up-in-info
  (let [{:keys [call app]} (boot! notes-src)]
    ((:dispatch! app) (users/loaded-event "alice"))
    ((:dispatch! app) {:type :user/ui-set :user "alice" :key :theme :value "dark"})
    (is (= {:theme "dark"} (:ui (:info (read-string (call :notes "whoami" {} {:user "alice"}))))))))

(deftest an-extension-only-ever-reaches-its-own-entry
  (let [{:keys [call] :as sys} (boot! notes-src other-src)]
    (call :notes "visit" {} {:user "alice"})
    (testing "another extension sees nothing of it"
      (is (= {:state nil :info-keys [:id :meta :name :ui]}
             (read-string (call :other "peek" {} {:user "alice"})))))
    (testing "and its own writes sit beside, not over, it"
      (call :other "keep" {} {:user "alice"})
      (is (= {:notes {:visits 1} :other {:secret 1}}
             (:ext (state/user-record (st sys) "alice"))))
      (is (= {:visits 1} (store/ext-state "alice" :notes))))))

(deftest one-extension-can-keep-state-for-another-user-it-names
  (let [{:keys [call] :as sys} (boot! notes-src)]
    (call :notes "for" {:id "bob"} {:user "alice"})
    (is (= {:seen true} (state/user-ext (st sys) "bob" :notes)))
    (is (nil? (state/user-ext (st sys) "alice" :notes)))
    (testing "the id must be a plain user id"
      (let [{:keys [call]} (boot! notes-src)
            r (call :notes "for" {:id "Not A Slug"} {:user "alice"})]
        (is (re-find #"extension error: xi.api.user: not a user id" r))))))

(deftest refused-values-write-nothing
  (let [{:keys [call] :as sys} (boot! notes-src)]
    (doseq [[what why] [["fn" #"plain data"] ["sym" #"plain data"] ["big" #"too large"]]]
      (let [r (call :notes "bad" {:what what} {:user "alice"})]
        (is (re-find #"extension error" r) (str what " is refused"))
        (is (re-find why r))))
    (is (nil? (state/user-ext (st sys) "alice" :notes)))
    (is (not (.existsSync fs (store/file "alice"))) "nothing reached the disk")))

(deftest nil-forgets-the-entry
  (let [{:keys [call] :as sys} (boot! notes-src)]
    (call :notes "visit" {} {:user "alice"})
    (is (some? (state/user-ext (st sys) "alice" :notes)))
    (call :notes "forget" {} {:user "alice"})
    (is (nil? (state/user-ext (st sys) "alice" :notes)))
    (is (nil? (store/ext-state "alice" :notes)))))

(deftest users-lists-declared-loaded-and-stored-users
  (let [{:keys [call app]} (boot! notes-src)]
    (store/set-key! "stored" :theme "dark")
    ((:dispatch! app) (users/loaded-event "visitor"))
    (is (= [{:id "alice" :name "Alice"} {:id "bob" :name "Bob"} {:id "root" :name nil}
            {:id "stored" :name nil} {:id "visitor" :name nil}]
           (read-string (call :notes "all" {} {:user "alice"}))))))

(deftest a-handler-cannot-write-the-users-slice
  (let [entry (first (filter :id (do (user/set-enabled-override! (fn [] #{"e0.cljs"}))
                                     (let [dir (fs/mkdtempSync (node-path/join @tmp "ext-"))]
                                       (write! dir "e0.cljs" notes-src)
                                       (user/load-dir dir)))))
        handlers (get-in entry [:extension :handlers])
        before   {:users {"alice" {:id "alice" :ext {:notes {:visits 1}}}} :ext {:notes {}}}]
    (testing "an attempt to set what an extension keeps for a user is dropped"
      (let [after (:state ((:ext.notes/grab handlers) before {:type :ext.notes/grab}))]
        (is (= {:visits 1} (get-in after [:users "alice" :ext :notes])))))
    (testing "its own slice still works"
      (is (true? (get-in (:state ((:ext.notes/mine handlers) before {:type :ext.notes/mine}))
                         [:ext :notes :ok]))))))

(deftest the-api-needs-a-running-app
  (let [dir (fs/mkdtempSync (node-path/join @tmp "ext-"))]
    (user/set-enabled-override! (fn [] #{"e0.cljs"}))
    (write! dir "e0.cljs" notes-src)
    (let [entry (first (filter :id (user/load-dir dir)))
          f     (get-in entry [:extension :tool-registry "visit"])]
      (testing "before the app runs there is no host: refused, not a crash"
        (is (re-find #"isn't available in this process"
                     (-> (f {} {:user "alice"}) :content first :text)))))))
