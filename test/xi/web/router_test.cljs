(ns xi.web.router-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.github.web :as github-web]
            [xi.ext.gtd.web :as gtd-web]
            [xi.web.router :as router]))

;; Router functions take the composed extension route table — exercise them
;; with the real extension routes (the /gtd and /pulls routes live there now).
(def ^:private routes (merge (:routes gtd-web/extension)
                             (:routes github-web/extension)))

;; ── parse-path ───────────────────────────────────────────────────────────────

(deftest parse-path-home
  (testing "root"
    (is (= {:page :home} (router/parse-path routes "/"))))
  (testing "nil defaults to home"
    (is (= {:page :home} (router/parse-path routes nil)))))

(deftest parse-path-chat
  (is (= {:page :chat :session-id "abc-123"}
         (router/parse-path routes "/chat/abc-123"))))

(deftest parse-path-projects
  (testing "bare /projects is home without dir"
    (is (= {:page :home} (router/parse-path routes "/projects"))))
  (testing "all sessions"
    (is (= {:page :home :dir :all} (router/parse-path routes "/projects/all"))))
  (testing "encoded directory"
    (is (= {:page :home :dir "/home/user/code"}
           (router/parse-path routes (str "/projects/" (js/encodeURIComponent "/home/user/code")))))))

(deftest parse-path-gtd
  (testing "bare /gtd"
    (is (= {:page :gtd} (router/parse-path routes "/gtd"))))
  (testing "file drill-down"
    (is (= {:page :gtd :file "inbox.org"}
           (router/parse-path routes "/gtd/inbox.org"))))
  (testing "task detail"
    (is (= {:page :gtd :file "inbox.org" :task-id "abcdef01-1234-5678-9abc-000000000000"}
           (router/parse-path routes "/gtd/inbox.org/abcdef01-1234-5678-9abc-000000000000")))))

(deftest parse-path-pulls
  (testing "pr list for a project"
    (is (= {:page :pr-list :cwd "/home/user/code"}
           (router/parse-path routes (str "/pulls/" (js/encodeURIComponent "/home/user/code"))))))
  (testing "pr detail"
    (is (= {:page :pr-detail :number 42 :cwd "/home/user/code"}
           (router/parse-path routes (str "/pulls/42/" (js/encodeURIComponent "/home/user/code"))))))
  (testing "pr diff"
    (is (= {:page :pr-diff :number 42 :cwd "/home/user/code"}
           (router/parse-path routes (str "/pulls/42/diff/" (js/encodeURIComponent "/home/user/code")))))))

(deftest roomless-pages-from-routes
  (is (= #{:home :gtd} (router/roomless-pages routes)))
  (is (= #{:home} (router/roomless-pages {}))))

;; ── route->path ──────────────────────────────────────────────────────────────

(deftest route->path-basics
  (testing "home"
    (is (= "/" (router/route->path routes {:page :home}))))
  (testing "chat"
    (is (= "/chat/sid1" (router/route->path routes {:page :chat :session-id "sid1"}))))
  (testing "project dir"
    (is (= (str "/projects/" (js/encodeURIComponent "/home/user"))
           (router/route->path routes {:page :home :dir "/home/user"}))))
  (testing "all sessions"
    (is (= "/projects/all" (router/route->path routes {:page :home :dir :all}))))
  (testing "gtd file"
    (is (= (str "/gtd/" (js/encodeURIComponent "inbox.org"))
           (router/route->path routes {:page :gtd :file "inbox.org"}))))
  (testing "gtd task"
    (is (= (str "/gtd/" (js/encodeURIComponent "inbox.org") "/task-uuid")
           (router/route->path routes {:page :gtd :file "inbox.org" :task-id "task-uuid"})))))

(deftest parse-roundtrips
  (doseq [[label route] [["home"        {:page :home}]
                          ["chat"        {:page :chat :session-id "sid1"}]
                          ["project dir" {:page :home :dir "/home/user/code"}]
                          ["all sessions" {:page :home :dir :all}]
                          ["gtd"         {:page :gtd :file "inbox.org"}]
                          ["pr detail"   {:page :pr-detail :number 7 :cwd "/home/user"}]]]
    (testing (str label " → path → parse")
      (is (= route (router/parse-path routes (router/route->path routes route)))))))

;; ── navigate handler (pure) ──────────────────────────────────────────────────

(def ^:private roomless (router/roomless-pages routes))

(defn- nav
  "Call navigate on a base state."
  ([ev] (nav (state/initial-state) ev))
  ([st ev] (router/navigate roomless st (merge {:page :home} ev))))

(defn- has-dispatch?
  "True when effects contain an :app/dispatch with the given :type."
  [effects dispatch-type]
  (some (fn [[eff-type ev]]
          (and (= :app/dispatch eff-type)
               (= dispatch-type (:type ev))))
        effects))

(deftest navigate-to-home
  (let [{:keys [state effects]} (nav {:page :home})]
    (is (= :home (get-in state [:web/route :page])))
    (is (some #(= :history/push (first %)) effects)
        "emits history/push")
    (is (has-dispatch? effects :room/leave)
        "emits room/leave")))

(deftest navigate-to-chat
  (let [{:keys [state effects]} (nav {:page :chat :session-id "s1"})]
    (is (= :chat (get-in state [:web/route :page])))
    (is (= "s1" (get-in state [:web/route :session-id])))
    (is (some (fn [[t ev]] (and (= :app/dispatch t)
                                (= :room/join (:type ev))
                                (= "s1" (:session-id ev))))
              effects)
        "emits room/join with session-id")
    (is (has-dispatch? effects :session/mark-read)
        "emits mark-read")))

(deftest navigate-to-project-dir
  (let [{:keys [state effects]} (nav {:page :home :dir "/home/user/code"})]
    (is (= "/home/user/code" (:web/selected-project-dir state)))
    (is (some (fn [[t ev]] (and (= :app/dispatch t)
                                (= :projects/web-sessions (:type ev))
                                (= "/home/user/code" (:cwd ev))))
              effects)
        "fetches sessions for the dir")))

(deftest navigate-to-all-sessions
  (let [{:keys [state effects]} (nav {:page :home :dir :all})]
    (is (= :all (:web/selected-project-dir state)))
    (is (not (has-dispatch? effects :projects/web-sessions))
        "does NOT fetch sessions (flat list comes from lobby)")))

(deftest navigate-home-clears-project-state
  (let [st (assoc (state/initial-state)
                  :web/selected-project-dir "/old"
                  :web/project-sessions [{:session-id "x"}]
                  :web/project-sessions-cwd "/old")
        {:keys [state]} (router/navigate roomless st {:page :home})]
    (is (nil? (:web/selected-project-dir state)))
    (is (not (contains? state :web/project-sessions)))
    (is (not (contains? state :web/project-sessions-cwd)))))

(deftest navigate-replace-flag
  (testing "replace? forwarded in history/push effect"
    (let [{:keys [effects]} (nav {:page :home :replace? true})
          [_ payload] (first (filter #(= :history/push (first %)) effects))]
      (is (true? (:replace? payload)))))
  (testing "non-replace has falsy replace?"
    (let [{:keys [effects]} (nav {:page :home})
          [_ payload] (first (filter #(= :history/push (first %)) effects))]
      (is (not (:replace? payload))))))

(deftest navigate-resets-timeline-window
  (let [st (assoc (state/initial-state) :web/timeline-window {:offset 10 :size 50})
        {:keys [state]} (router/navigate roomless st {:page :home})]
    (is (nil? (:web/timeline-window state)))))

(deftest navigate-gtd-syncs-file-and-task
  (testing "the GTD ext handler (chained after base navigate) syncs drill-down state"
    (let [on-nav (get-in gtd-web/extension [:handlers :route/navigate])
          {:keys [state effects]} (on-nav (state/initial-state)
                                          {:type :route/navigate :page :gtd
                                           :file "work.org" :task-id "tid-1"})]
      (is (= "work.org" (:web/gtd-file state)))
      (is (= "tid-1" (:web/gtd-task-id state)))
      (is (has-dispatch? effects :gtd/web-list)
          "fetches the task list when none is cached")))
  (testing "non-gtd navigation is a no-op for the ext handler"
    (let [on-nav (get-in gtd-web/extension [:handlers :route/navigate])]
      (is (nil? (on-nav (state/initial-state) {:type :route/navigate :page :home}))))))

(deftest navigate-leaving-chat-remembers-session
  (testing "pending-read set when leaving a chat"
    (let [st (-> (state/initial-state)
                 (assoc-in [:rooms "r1" :session :id] "old-sid")
                 (assoc :active-room "r1"))
          {:keys [state]} (router/navigate roomless st {:page :home})]
      (is (= "old-sid" (:web/pending-read state))))))

(defn- effect-order
  "Indices (into the effect vector) of the first :app/dispatch matching each
   given event type. Returns a map {type idx}."
  [effects types]
  (into {}
        (keep (fn [t]
                (when-let [idx (->> (map-indexed vector effects)
                                    (some (fn [[i [eff-type ev]]]
                                            (when (and (= :app/dispatch eff-type)
                                                       (= t (:type ev)))
                                              i))))]
                  [t idx])))
        types))

(deftest navigate-empty-new-room-closed-on-chat-switch
  (testing "switching from an untouched new session to another chat leaves it"
    (let [st (-> (state/initial-state)
                 (assoc-in [:rooms "r1" :session :id] "new-sid")
                 (assoc :active-room "r1"))
          {:keys [effects]} (router/navigate roomless st {:page :chat :session-id "other-sid"})]
      (is (has-dispatch? effects :room/leave)
          "emits room/leave for the orphaned empty room")
      (is (has-dispatch? effects :room/join)
          "still joins the destination chat")
      (let [{:keys [:room/leave :room/join]} (effect-order effects [:room/leave :room/join])]
        (is (< leave join)
            "leaves the old room BEFORE joining the new one"))))
  (testing "a room with history is NOT closed on chat switch"
    (let [st (-> (state/initial-state)
                 (assoc-in [:rooms "r1" :session :id] "real-sid")
                 (assoc-in [:rooms "r1" :history] [{:kind :user :text "hi"}])
                 (assoc :active-room "r1"))
          {:keys [effects]} (router/navigate roomless st {:page :chat :session-id "other-sid"})]
      (is (not (has-dispatch? effects :room/leave))
          "does not leave a session the user actually used")))
  (testing "a non-empty draft keeps the new room open"
    (let [st (-> (state/initial-state)
                 (assoc-in [:rooms "r1" :session :id] "new-sid")
                 (assoc-in [:web/drafts "new-sid"] "half-typed")
                 (assoc :active-room "r1"))
          {:keys [effects]} (router/navigate roomless st {:page :chat :session-id "other-sid"})]
      (is (not (has-dispatch? effects :room/leave))
          "a drafted prompt means the room isn't truly empty"))))
