(ns xi.web.router-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.state :as state]
            [xi.web.router :as router]))

;; Router functions take the composed extension route table — exercise them
;; with a representative extension entry: a `/pulls` segment whose parse fn
;; reads a number, a sub-segment and an encoded cwd, with path fns per page.
(defn- parse-pulls [segs]
  (let [seg1 (first segs)]
    (if (and seg1 (re-matches #"\d+" seg1))
      (if (= "diff" (second segs))
        {:page :pr-diff
         :number (js/parseInt seg1)
         :cwd (js/decodeURIComponent (str/join "/" (drop 2 segs)))}
        {:page :pr-detail
         :number (js/parseInt seg1)
         :cwd (js/decodeURIComponent (str/join "/" (rest segs)))})
      (cond-> {:page :pr-list}
        seg1 (assoc :cwd (js/decodeURIComponent (str/join "/" segs)))))))

(def ^:private routes
  {"pulls" {:parse parse-pulls
            :path {:pr-list   (fn [{:keys [cwd]}]
                                (if cwd
                                  (str "/pulls/" (js/encodeURIComponent cwd))
                                  "/pulls"))
                   :pr-detail (fn [{:keys [number cwd]}]
                                (str "/pulls/" number "/" (js/encodeURIComponent cwd)))
                   :pr-diff   (fn [{:keys [number cwd]}]
                                (str "/pulls/" number "/diff/" (js/encodeURIComponent cwd)))}}})

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
  (is (= #{:home} (router/roomless-pages routes)))
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
    (is (= "/projects/all" (router/route->path routes {:page :home :dir :all})))))

(deftest parse-roundtrips
  (doseq [[label route] [["home"        {:page :home}]
                          ["chat"        {:page :chat :session-id "sid1"}]
                          ["project dir" {:page :home :dir "/home/user/code"}]
                          ["all sessions" {:page :home :dir :all}]
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
    (is (some (fn [[t ev]] (and (= :room/join-with-cache t)
                                (= "s1" (:session-id ev))))
              effects)
        "emits room/join-with-cache with session-id")
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

(deftest navigate-keeps-extension-params
  (testing "a :params map rides on the route untouched"
    (let [{:keys [state effects]} (nav {:page :chat/thread :params {:conv "c1" :msg "m2"}})]
      (is (= {:page :chat/thread :session-id nil :params {:conv "c1" :msg "m2"}}
             (:web/route state)))
      (is (= {:conv "c1" :msg "m2"}
             (get-in (first (filter #(= :history/push (first %)) effects)) [1 :route :params])))))
  (testing "anything but a map is dropped"
    (is (not (contains? (:web/route (:state (nav {:page :home :params "x"}))) :params)))))

(deftest pending-extension-paths-keep-their-url
  (testing "an unknown first segment is a not-yet-loaded extension page"
    (is (true? (router/pending-extension-path? routes "/messages/dm~a~b")))
    (is (false? (router/pending-extension-path? routes "/pulls/42")) "a loaded extension route")
    (is (false? (router/pending-extension-path? routes "/chat/sid")))
    (is (false? (router/pending-extension-path? routes "/"))))
  (testing ":keep-url? rides on the history effect"
    (let [{:keys [effects]} (nav {:page :home :replace? true :keep-url? true})]
      (is (= {:route {:page :home :session-id nil} :replace? true :keep-url? true}
             (second (first (filter #(= :history/push (first %)) effects))))))))

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
      (is (some #(= :room/join-with-cache (first %)) effects)
          "still joins the destination chat")
      (let [idx   (fn [pred] (->> (map-indexed vector effects)
                                  (some (fn [[i e]] (when (pred e) i)))))
            leave (idx (fn [[t ev]] (and (= :app/dispatch t) (= :room/leave (:type ev)))))
            join  (idx (fn [[t]] (= :room/join-with-cache t)))]
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

(deftest navigate-away-parks-typed-new-chat-as-draft
  (let [pending {:id (random-uuid) :cwd "/proj"}
        base    (assoc (state/initial-state) :web/pending-room pending)]
    (testing "a new chat with text is parked in :web/draft-chats"
      (let [{:keys [state]} (nav (assoc-in base [:web/drafts (:id pending)] "half-typed")
                                 {:page :home})]
        (is (nil? (:web/pending-room state)))
        (is (= [pending] (:web/draft-chats state)))
        (is (= "half-typed" (get-in state [:web/drafts (:id pending)]))
            "the text stays under the pending room's id")))
    (testing "an empty or blank new chat is dropped"
      (is (empty? (:web/draft-chats (:state (nav base {:page :home})))))
      (is (empty? (:web/draft-chats
                   (:state (nav (assoc-in base [:web/drafts (:id pending)] "  \n")
                                {:page :home}))))))
    (testing "parking twice doesn't duplicate"
      (let [st (-> base
                   (assoc-in [:web/drafts (:id pending)] "x")
                   router/stash-draft-chat
                   router/stash-draft-chat)]
        (is (= 1 (count (:web/draft-chats st))))))))
