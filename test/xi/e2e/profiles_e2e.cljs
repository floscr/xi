(ns xi.e2e.profiles-e2e
  "Different users' ~/.config/xi setups end to end: strict rules, roles,
   extensions, agent profiles and a broken config."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h]
            [xi.e2e.profiles :as profiles]))

(defn- calls
  "The tool calls of the env's main turns, in order."
  [env]
  (vec (mapcat :tool-calls (h/main-turns env))))

(deftest locked-down-blocks-programs-and-asks-before-edits
  (async done
    (h/with-env! profiles/locked-down
      {:default [{:tool "clj" :args {:code "(sh \"ls\")"}}
                 {:tool "edit" :args {:path "README.md"
                                      :edits [{:oldText "# e2e project" :newText "# changed"}]}}
                 {:text "done"}]}
      done
      (fn [env]
        (-> (h/prompt! env "try everything")
            (.then
             (fn [{:keys [code stderr]}]
               (is (= 0 code) stderr)
               (let [[clj edit] (calls env)]
                 (is (str/includes? (:content clj) "No programs here.") (:content clj))
                 (testing "an ask nobody can answer (prompt mode) is a no"
                   (is (:is-error edit) (:content edit))
                   (is (= "# e2e project\n" (h/slurp (h/path env "README.md")))))))))))))

(deftest roles-decide-per-user
  (async done
    (h/with-env! (profiles/team)
      {:default [{:tool "clj" :args {:code "(sh \"ls\")"}}
                 {:tool "board_post" :args {:line "hi"}}
                 {:tool "board_clear" :args {}}
                 {:text "done"}]}
      done
      (fn [env]
        (let [board #(h/slurp (h/home-path env ".local/share/xi/extensions/board/board.md"))]
          (-> (h/prompt! env "go" "--user" "bob")
              (.then
               (fn [{:keys [code stderr]}]
                 (is (= 0 code) stderr)
                 (let [[clj post clear] (calls env)]
                   (testing "bob is a guest"
                     (is (= "Guests can't run programs." (:content clj)))
                     (is (not (:is-error post)) (:content post))
                     (is (= "Guests can't clear the board." (:content clear)))
                     (is (= "bob: hi\n" (board)))))
                 (h/prompt! env "go" "--user" "alice")))
              (.then
               (fn [{:keys [code stderr]}]
                 (is (= 0 code) stderr)
                 (let [[clj _post clear] (drop 3 (calls env))]
                   (testing "alice is an admin, but no moderator"
                     (is (not (:is-error clj)) (:content clj))
                     (is (str/includes? (:content clj) "README.md"))
                     (is (= "Only moderators can clear the board." (:content clear)))
                     (is (= "bob: hi\nalice: hi\n" (board)))))
                 (is (= ["bob" "alice"] (map :user (h/main-turns env))))))))))))

(deftest extensions-load-and-a-broken-one-is-reported
  (async done
    (h/with-env! profiles/extended
      {:rules [{:when {:tool "notes_add"}
                :reply [{:tool "notes_add" :args {:text "remember the milk"}}
                        {:text "{{tool-result}}"}]}]}
      done
      (fn [env]
        (-> (h/prompt! env "note it")
            (.then
             (fn [{:keys [code stdout stderr]}]
               (is (= 0 code) stderr)
               (is (= "added\n" stdout) "the notes rule matched: notes_add was advertised")
               (let [tools (set (:tools (first (h/llm-log env))))]
                 (is (contains? tools "board_post"))
                 (is (not-any? #(str/includes? % "broken") tools)))
               (is (= "remember the milk\n"
                      (h/slurp (h/home-path env ".local/share/xi/extensions/notes/notes.md"))))
               (is (re-find #"\[user-ext\] loaded: .*board" stderr) stderr)
               (is (str/includes? stderr "[user-ext] rejected broken.cljs") stderr))))))))

(deftest an-agent-profile-gets-only-its-tools-and-prompt
  (async done
    (h/with-env! profiles/agent
      {:default [{:tool "ls" :args {:path "."}}
                 {:tool "write" :args {:path "pwned.txt" :content "x"}}
                 {:text "listed"}]}
      done
      (fn [env]
        (-> (h/prompt! env "look around" "--agent" "helper")
            (.then
             (fn [{:keys [code stderr]}]
               (is (= 0 code) stderr)
               (let [turn (first (h/llm-log env))
                     [ls write] (:tool-calls turn)]
                 (is (= #{"read" "ls"} (set (:tools turn))))
                 (is (str/includes? (:system turn) "You are the e2e helper."))
                 (is (not (str/includes? (:system turn) "E2E-PROJECT-INSTRUCTIONS"))
                     "no AGENTS.md for a profile")
                 (is (str/includes? (:content ls) "README.md"))
                 (is (= "Unknown tool: write" (:content write)))
                 (is (nil? (h/slurp (h/path env "pwned.txt")))))
               (is (= 1 (count (h/files-under (h/home-path env ".config/xi/personal-agent/helper"))))
                   "the session is the agent's")
               (is (= [] (h/files-under (h/home-path env ".config/xi/sessions")))))))))))

(deftest a-broken-config-still-answers-but-denies-tools
  (async done
    (h/with-env! profiles/broken
      {:default [{:tool "read" :args {:path "README.md"}} {:text "answered"}]}
      done
      (fn [env]
        (-> (h/prompt! env "hi")
            (.then
             (fn [{:keys [code stdout stderr]}]
               (is (= 0 code) stderr)
               (is (= "answered\n" stdout))
               (is (str/includes? stderr "config.edn is invalid") stderr)
               (let [[read] (calls env)]
                 (testing "an unreadable rules file denies everything, naming the file"
                   (is (:is-error read))
                   (is (str/includes? (:content read) "rules.edn") (:content read)))))))))))
