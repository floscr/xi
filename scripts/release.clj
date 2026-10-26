(ns release
  "`bb release`: publish the version at the top of CHANGELOG.md. Commit and
   tag, npm, both remotes, docs site, each skipped when already done, so a
   failed run is resumed by running it again. The npm publish asks for the
   2FA code, so only a person runs this (.xi/rules.edn denies it to agents)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def remotes ["github" "origin"])
(def site-url "https://xi-agent.com")
(def site-deploy
  [(str (fs/home) "/Code/Projects/uberspace/scripts/subdomain")
   "deploy" "xi-agent.com" (str (fs/absolutize "site/dist")) "-y"])

(defn- out [& args]
  (str/trim (:out (apply shell {:out :string} args))))

(defn- ok? [& args]
  (zero? (:exit (apply shell {:out :string :err :string :continue true} args))))

(defn- fail! [& msg]
  (binding [*out* *err*] (println (str "✗ " (str/join msg))))
  (System/exit 1))

(defn- step [title] (println (str "\n── " title)))

(def ^:private heading-re #"(?m)^## (\d+\.\d+\.\d+) - (\d{4}-\d{2}-\d{2})$")

(defn- changelog-release
  "The top release heading of CHANGELOG.md: {:version :date :heading}."
  []
  (let [[heading version date] (re-find heading-re (slurp "CHANGELOG.md"))]
    (when-not version (fail! "CHANGELOG.md has no `## X.Y.Z - YYYY-MM-DD` heading"))
    {:version version :date date :heading heading}))

(defn- semver [v] (mapv parse-long (str/split v #"\.")))

(defn- tagged? [tag] (ok? "git" "rev-parse" "-q" "--verify" (str "refs/tags/" tag)))

(defn- published? [version]
  ;; exact match: npm's 404 message for a missing version names it too
  (= version (str/trim (:out (shell {:out :string :err :string :continue true}
                                    "npm" "view" (str "xi-agent@" version) "version")))))

(defn- dirty-files
  "Working-tree changes other than CHANGELOG.md: `bb package` builds from the
   tree, so they would ship."
  []
  ;; untrimmed: each line's first column is part of its status
  (->> (str/split-lines (:out (shell {:out :string} "git" "status" "--porcelain")))
       (remove str/blank?)
       (remove #(= "CHANGELOG.md" (subs % 3)))))

(defn- preflight! [{:keys [version]} resuming?]
  (step "Preflight")
  (let [branch (out "git" "branch" "--show-current")]
    (when-not (= "master" branch) (fail! "on " branch ", not master")))
  (when-let [dirty (seq (dirty-files))]
    (fail! "uncommitted changes besides CHANGELOG.md:\n  " (str/join "\n  " dirty)))
  (doseq [r remotes]
    (shell "git" "fetch" "--quiet" r)
    (let [behind (parse-long (out "git" "rev-list" "--count" (str "master.." r "/master")))]
      (when (pos? behind) (fail! "master is " behind " commits behind " r "/master"))))
  (when-not (ok? "npm" "whoami") (fail! "not logged in to npm: run `npm login`"))
  (when-not resuming?
    (let [current (:version (json/parse-string (slurp "package.json") true))]
      (when-not (pos? (compare (semver version) (semver current)))
        (fail! "CHANGELOG.md's " version " is not above package.json's " current)))
    (shell "bb" "test")
    (shell "bb" "docs:gen" "--check"))
  (println "✓ ready to release" version))

(defn- date-today!
  "Date the release heading today, the day it goes out."
  [{:keys [version date heading]}]
  (let [today (str (java.time.LocalDate/now))]
    (when-not (= date today)
      (spit "CHANGELOG.md" (str/replace-first (slurp "CHANGELOG.md") heading
                                              (str "## " version " - " today)))
      (println "  dated the heading" today "(was" (str date ")")))))

(defn- commit-and-tag! [{:keys [version] :as release} tag]
  (step (str "Commit and tag " tag))
  (date-today! release)
  (shell "npm" "version" version "--no-git-tag-version" "--allow-same-version")
  (shell "git" "add" "CHANGELOG.md" "package.json" "package-lock.json")
  (shell "git" "commit" "-m" (str "chore(release): " tag))
  (shell "git" "tag" "-a" tag "-m" (str "Xi " version)))

(defn- await-npm!
  "A fresh version takes minutes to show up in `npm view`; the push and the
   site wait for it, so the changelog never names a version you can't install."
  [version]
  (let [deadline (+ (System/currentTimeMillis) (* 20 60 1000))]
    (print "  waiting for npm to list it") (flush)
    (loop []
      (cond
        (published? version) (println " ✓")
        (> (System/currentTimeMillis) deadline)
        (fail! "\nnpm still doesn't list " version " after 20 min; run `bb release` again once it does")
        :else (do (Thread/sleep 15000) (print ".") (flush) (recur))))))

(defn- publish! [version]
  (step (str "Publish xi-agent@" version " to npm"))
  (if (published? version)
    (println "  already on npm")
    (let [{:keys [exit]} (shell {:continue true} "bb" "package:publish")]
      (when-not (zero? exit)
        (fail! "npm publish failed. If it says " version " already exists, it is still"
               " propagating: run `bb release` again in a few minutes"))
      (await-npm! version))))

(defn- push! []
  (step "Push")
  (doseq [r remotes]
    (shell "git" "push" "--follow-tags" r "master")))

(defn- deploy-site! []
  (step "Docs site")
  (shell "bb" "site:build")
  (if (fs/exists? (first site-deploy))
    (apply shell site-deploy)
    (fail! "no deploy script at " (first site-deploy))))

(defn- verify! [version]
  (step "Verify")
  (let [date  (:date (changelog-release))
        body  (:body (http/get (str site-url "/changelog/") {:throw false}))
        live? (str/includes? (str body) (str version " - " date))]
    (println (if live? "✓" "✗") (str site-url "/changelog/") "shows" version)
    (when-not live? (System/exit 1))))

(defn run [_args]
  (let [{:keys [version] :as release} (changelog-release)
        tag       (str "v" version)
        resuming? (tagged? tag)]
    (when resuming? (println "Resuming" tag "(already committed and tagged)"))
    (preflight! release resuming?)
    (when-not resuming? (commit-and-tag! release tag))
    (publish! version)
    (push!)
    (deploy-site!)
    (verify! version)
    (println "\n✓ Xi" version "released")))
