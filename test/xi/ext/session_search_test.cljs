(ns xi.ext.session-search-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.session-search :as session-search]
            [xi.session :as session]))

(def ^:private tool-fn
  (get-in session-search/extension [:tool-registry "session_search"]))

(deftest search-sessions-blank-query
  (testing "blank/whitespace query short-circuits to no results (no FS access)"
    (is (= [] (session/search-sessions nil "")))
    (is (= [] (session/search-sessions "/tmp" "   ")))))

(deftest tool-blank-query-message
  (testing "tool reports no matches for a blank query, scoped to current cwd"
    (let [{:keys [content is-error]} (tool-fn {:query ""} {:cwd "/some/project"})
          text (-> content first :text)]
      (is (not is-error))
      (is (str/includes? text "No sessions found"))
      (is (str/includes? text "current project")))))

(deftest tool-scope-messaging
  (testing "explicit cwd and all flags shape the empty-result message"
    (is (str/includes?
         (-> (tool-fn {:query "" :cwd "/x"} {:cwd "/room"}) :content first :text)
         "/x"))
    (is (str/includes?
         (-> (tool-fn {:query "" :all true} {:cwd "/room"}) :content first :text)
         "all projects"))))
