(ns xi.ext.session-search-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.session-search :as session-search]
            [xi.session :as session]))

(def ^:private tool-fn
  (get-in session-search/extension [:tool-registry "session_search"]))

(def ^:private view-fn
  (get-in session-search/extension [:tool-registry "session_view"]))

(defn- text [res] (-> res :content first :text))

(defn- agent-ctx [agent-id]
  {:cwd "/room" :room-id "r"
   :get-state (constantly {:rooms {"r" {:ext {:agent {:id agent-id}}}}})})

(deftest search-sessions-blank-query
  (testing "blank/whitespace query short-circuits to no results (no FS access)"
    (is (= [] (session/search-sessions nil "")))
    (is (= [] (session/search-sessions "/tmp" "   ")))))

(deftest tool-blank-query-lists-the-newest
  (testing "a blank query lists the scope's newest sessions"
    (let [res (tool-fn {:query ""} {:cwd "/xi-test/no-such-project"})]
      (is (not (:is-error res)))
      (is (= "No sessions in the current project." (text res))))))

(deftest tool-scope-messaging
  (testing "explicit cwd and all flags shape the empty-result message"
    (is (str/includes?
         (text (tool-fn {:query "" :cwd "/xi-test/x"} {:cwd "/room"}))
         "/xi-test/x"))
    (is (str/includes?
         (text (tool-fn {:query "zz-no-such-session-text-zz" :cwd "/xi-test/x"} {:cwd "/room"}))
         "No sessions found matching"))))

(deftest tool-in-an-agent-room
  (testing "an agent profile's room searches its own sessions, whatever cwd / all say"
    (let [t (text (tool-fn {:query "" :all true :cwd "/x"} (agent-ctx "xi-test-no-such-agent")))]
      (is (str/includes? t "your previous conversations"))
      (is (not (str/includes? t "/x"))))))

(deftest view-unknown-session
  (testing "an id that doesn't resolve (in an agent room: not the agent's) is an error"
    (let [res (view-fn {:id "no-such-id"} (agent-ctx "xi-test-no-such-agent"))]
      (is (:is-error res))
      (is (str/includes? (text res) "No session with id")))))

(deftest view-pages
  (let [msg  (fn [n] {:role "user" :text (apply str (repeat n "x"))})
        msgs [(msg 10) (msg 20000) (msg 20000) (msg 5)]]
    (testing "a page fills up to the cap and names the next offset"
      (is (= [[(msg 10) (msg 20000)] 2] (session-search/page msgs 0)))
      (is (= [[(msg 20000) (msg 5)] nil] (session-search/page msgs 2))))
    (testing "a single oversized message still makes a page"
      (is (= [[(msg 40000)] nil] (session-search/page [(msg 40000)] 0))))
    (testing "past the end"
      (is (= [[] nil] (session-search/page msgs 4))))))
