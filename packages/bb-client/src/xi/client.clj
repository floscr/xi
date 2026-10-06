(ns xi.client
  "Babashka/JVM client for one-shot xi agent runs (`xi prompt`).

   Lets any bb service hold a stateful conversation with a named personal
   agent without hand-rolling process spawning or re-sending chat history:

     (require '[xi.client :as xi])

     (xi/prompt! {:agent \"coach\" :message \"I ran 5k today\"})
     ;; => {:ok true :session-id \"0198…\" :text \"Nice pace! …\"}

     (xi/prompt! {:agent \"coach\" :session-id \"0198…\"
                  :message \"how does that compare to last week?\"})
     ;; => {:ok true :session-id \"0198…\" :text \"…\"}

   The agent's profile (tools, system prompt, model) is the [:agents <agent>]
   entry of ~/.config/xi/config.edn and its sessions live in
   ~/.config/xi/personal-agent/<agent>/ — see docs/guide/agents.md
   in the xi repo. \"root\" is the default profile.

   Uses ProcessBuilder directly (babashka.process thread pools die under
   systemd) and runs the agent in an empty temp dir, so an agent run receives
   only what's in the prompt."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn find-bundle
  "Locate the compiled xi bundle: $XI_BUNDLE, then the deployed checkout
   (/var/lib/xi), then the dev checkout. nil when none exists."
  []
  (or (System/getenv "XI_BUNDLE")
      (->> ["/var/lib/xi/target/main.js"
            (str (System/getProperty "user.home") "/Code/Projects/xi/target/main.js")]
           (filter #(.exists (io/file %)))
           first)))

(defn build-args
  "The `bun … prompt` argv for an opts map (pure; the prompt text itself goes
   on stdin)."
  [{:keys [bundle agent session-id model no-store?]}]
  (cond-> ["bun" bundle "prompt" "--json"]
    agent      (into ["--agent" agent])
    session-id (into ["--session" session-id])
    model      (into ["--model" model])
    no-store?  (conj "--no-store")))

(defn prompt!
  "Run one turn against a xi agent and block for the reply. opts:

     :message         - the prompt text (required)
     :agent           - agent profile id (~/.config/xi/config.edn [:agents id]:
                        its :tools allowlist + system prompt); sessions are
                        stored per agent in ~/.config/xi/personal-agent/<id>/.
                        Without it the run is a full coding harness in the
                        temp dir — pass \"root\" for the default restricted
                        profile.
     :session-id      - continue a saved conversation (pass back the
                        :session-id from a previous reply); the provider
                        transcript is resumed, so history does NOT need to be
                        re-sent in the prompt
     :model           - override the agent's/default model
     :no-store?       - ephemeral run, leaves no session behind (the reply's
                        :session-id is then not resumable)
     :bundle          - path to xi's target/main.js (default: find-bundle)

   Returns {:ok true :session-id s :text s}
        or {:ok false :error s}."
  [{:keys [message bundle] :as opts}]
  (let [bundle (or bundle (find-bundle))]
    (cond
      (str/blank? (str message)) {:ok false :error "Empty message"}
      (nil? bundle) {:ok false :error "xi bundle not found (set XI_BUNDLE)"}
      :else
      (let [tmp-dir (.toFile (Files/createTempDirectory "xi-client" (make-array FileAttribute 0)))
            stderr-file (io/file tmp-dir "stderr.log")
            pb (doto (ProcessBuilder. ^java.util.List (build-args (assoc opts :bundle bundle)))
                 (.directory tmp-dir)
                 (.redirectError stderr-file))
            process (.start pb)]
        (with-open [w (io/writer (.getOutputStream process))]
          (.write w ^String message))
        (let [output (slurp (.getInputStream process))
              exit (.waitFor process)
              err (when-not (zero? exit) (slurp stderr-file))]
          (io/delete-file stderr-file true)
          (io/delete-file tmp-dir true)
          (if (zero? exit)
            (try
              (let [{:keys [session-id text]} (json/parse-string output true)]
                {:ok true :session-id session-id :text text})
              (catch Exception _
                {:ok false :error (str "unparseable xi output: " (str/trim output))}))
            {:ok false :error (str "xi prompt exited " exit ": "
                                   (str/trim (or err "")))}))))))
