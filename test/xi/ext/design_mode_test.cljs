(ns xi.ext.design-mode-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.design-mode :as dm]))

(def ^:private injection-fn #'dm/injection-fn)
(def ^:private poll-fn #'dm/poll-fn)
(def ^:private cleanup-fn @#'dm/cleanup-fn)
(def ^:private parse-eval-return #'dm/parse-eval-return)
(def ^:private page-options #'dm/page-options)

(def ^:private req
  {:selector "#app > main > button.cta"
   :tagName "button"
   :message "make this coral and larger"
   :outerHTML "<button class=\"cta\">Go</button>"
   :computedStyles {:color "rgb(0, 0, 0)" :padding "4px 8px"}
   :boundingRect {:x 10 :y 20 :width 120 :height 32}
   :url "http://localhost:7476/"})

(deftest injection-fn-embeds-config-and-script
  (let [js (injection-fn)]
    (testing "sets the design config with the Claude palette"
      (is (str/includes? js "__XI_DESIGN_CFG__"))
      (is (str/includes? js "#D97757")))
    (testing "is an arrow function that returns"
      (is (str/starts-with? (str/trim js) "() =>"))
      (is (str/includes? js "return true")))
    (testing "cleans up any stale UI before installing"
      (is (str/includes? js "__xi-design-pill")))
    (testing "includes the resident design script"
      (is (str/includes? js "__xiDesignQueue")))))

(deftest poll-fn-pushes-agents-and-drains-both-queues
  (let [js (poll-fn "[{\"id\":\"design-1\"}]")]
    (testing "reads the active flag and splice-drains both queues atomically"
      (is (str/includes? js "__xiDesignActive"))
      (is (str/includes? js "__xiDesignQueue"))
      (is (str/includes? js "__xiDesignCommitQueue"))
      (is (str/includes? js "splice(0)")))
    (testing "pushes the agent list into the page and re-renders the dock"
      (is (str/includes? js "window.__xiDesignAgents = incoming"))
      (is (str/includes? js "design-1"))
      (is (str/includes? js "__xiDesignRender")))
    (testing "preserves a client-side optimistic commit flag"
      (is (str/includes? js "a.commit = p.commit")))))

(deftest cleanup-fn-removes-globals-and-dock
  (is (str/includes? cleanup-fn "delete window.__xiDesignActive"))
  (is (str/includes? cleanup-fn "delete window.__xiDesignQueue"))
  (is (str/includes? cleanup-fn "delete window.__xiDesignCommitQueue"))
  (is (str/includes? cleanup-fn "delete window.__xiDesignAgents"))
  (is (str/includes? cleanup-fn "__xi-design-agents-btn")))

(deftest commit-prompt-carries-the-result-summary
  (let [p (dm/commit-prompt {:label "Design: bigger CTA"
                             :result "Enlarged .cta padding in src/app/button.css"})]
    (testing "includes the result summary and commit guidance"
      (is (str/includes? p "Enlarged .cta padding in src/app/button.css"))
      (is (str/includes? p "Conventional-Commit"))
      (is (str/includes? p "do NOT push"))))
  (testing "falls back to git inspection when there is no summary"
    (let [p (dm/commit-prompt {:label "Design: x" :result ""})]
      (is (str/includes? p "git status"))
      (is (str/includes? p "Design: x")))))

(deftest page-agents-maps-tracked-agents-and-commit-status
  (let [st {:rooms {"r1" {:ext {:subagents
                                {:agents [{:id "design-1" :label "Design: bigger" :status :done}
                                          {:id "design-2" :label "Design: colour" :status :running}
                                          {:id "commit-1" :label "Commit: bigger" :status :done}]}}}}}
        out (dm/page-agents st "r1"
                            [{:sub-id "design-1" :commit-sub-id "commit-1"}
                             {:sub-id "design-2" :commit-sub-id nil}])]
    (testing "a committed agent reports commit=committed"
      (is (= {:id "design-1" :label "Design: bigger" :status "done" :commit "committed"}
             (first out))))
    (testing "a running agent with no commit reports commit=nil"
      (is (= {:id "design-2" :label "Design: colour" :status "running" :commit nil}
             (second out))))))

(deftest parse-eval-return-extracts-poll-payload
  (testing "parses active flag + queued requests from the fenced JSON wrapper"
    (let [res {:content [{:type "text"
                          :text (str "Script ran and returned:\n```json\n"
                                     "{\"active\":true,\"q\":[{\"selector\":\"button.cta\","
                                     "\"message\":\"bigger\"}]}\n```")}]
               :is-error false}
          v (parse-eval-return res)]
      (is (true? (:active v)))
      (is (= "bigger" (get-in v [:q 0 :message])))))
  (testing "an empty queue parses to an empty vector"
    (let [res {:content [{:type "text" :text "```json\n{\"active\":false,\"q\":[]}\n```"}]}]
      (is (= {:active false :q []} (parse-eval-return res)))))
  (testing "returns nil on unparseable text"
    (is (nil? (parse-eval-return {:content [{:type "text" :text "boom"}]})))))

(deftest request-label-prefers-message-over-selector
  (testing "uses the instruction's first line"
    (is (= "Design: make this coral and larger" (dm/request-label req))))
  (testing "truncates long messages"
    (let [label (dm/request-label {:message (apply str (repeat 60 "x"))})]
      (is (str/ends-with? label "…"))
      (is (<= (count label) 60))))
  (testing "falls back to the selector, then a generic label"
    (is (= "Design: button.cta" (dm/request-label {:selector "button.cta" :message "  "})))
    (is (= "Design: element" (dm/request-label {})))))

(deftest build-prompt-carries-element-context
  (let [p (dm/build-prompt req "/home/u/.config/xi/uploads/abc.png")]
    (testing "includes the user request, url, selector, and html"
      (is (str/includes? p "make this coral and larger"))
      (is (str/includes? p "http://localhost:7476/"))
      (is (str/includes? p "#app > main > button.cta"))
      (is (str/includes? p "<button class=\"cta\">Go</button>")))
    (testing "includes computed styles and the screenshot path"
      (is (str/includes? p "padding"))
      (is (str/includes? p "uploads/abc.png")))
    (testing "guides the sub-agent to edit source, not the browser"
      (is (str/includes? p "Do NOT drive"))
      (is (str/includes? p "source files"))))
  (testing "an empty message asks the sub-agent to infer"
    (let [p (dm/build-prompt (assoc req :message "") nil)]
      (is (str/includes? p "infer"))
      (is (not (str/includes? p "Screenshot"))))))

(deftest page-options-label-tabs
  (let [opts (page-options [{:id 0 :title "Home" :url "http://a/" :selected? false}
                            {:id 1 :title "" :url "http://b/" :selected? true}])]
    (testing "labels combine title and url; bare-url tabs use the url"
      (is (= "Home — http://a/" (:label (first opts))))
      (is (= "http://b/  [current]" (:label (second opts)))))
    (testing "values are the page ids"
      (is (= [0 1] (mapv :value opts))))))

(deftest install-exposes-command-keybinding-fx-and-shutdown
  (let [m (dm/install (fn [_ _] (js/Promise.resolve {:content []})))]
    (testing "contributes the /design command"
      (is (= "design" (-> m :commands first :name))))
    (testing "binds ctrl+shift+d to the command"
      (is (= {:key "ctrl+shift+d" :event {:type :command/run :name "design"}}
             (first (:keybindings m)))))
    (testing "registers the toggle effect and a shutdown hook"
      (is (fn? (get-in m [:fx :ext.design-mode/toggle])))
      (is (fn? (:shutdown! m))))))
