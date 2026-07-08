(ns xi.sandbox.firejail
  "firejail sandbox backend.

   Weaker confinement than bwrap: the home directory is made read-only
   (with writable roots re-enabled), hidden credential paths are
   blacklisted, /tmp is private and the network is dropped unless the
   policy allows it. Paths outside $HOME keep their normal permissions.
   Pure argv builder — no I/O.")

(defn wrap-argv
  "Wrap argv in a firejail invocation enforcing the policy."
  [argv {:keys [home writable hidden-dirs hidden-files network]}]
  (-> ["firejail" "--quiet" "--noprofile" "--private-tmp"]
      (conj (str "--read-only=" home))
      (into (map #(str "--read-write=" %) writable))
      (into (map #(str "--blacklist=" %) (concat hidden-dirs hidden-files)))
      (into (when (= network :none) ["--net=none"]))
      (into argv)))

(def backend
  {:id        :firejail
   :binary    "firejail"
   :wrap-argv wrap-argv})
