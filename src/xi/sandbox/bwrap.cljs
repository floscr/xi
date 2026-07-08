(ns xi.sandbox.bwrap
  "bubblewrap sandbox backend — unprivileged Linux namespaces.

   Whole filesystem read-only, writable roots bind-mounted rw, hidden
   credential dirs masked with tmpfs (files with /dev/null binds),
   private /tmp, all namespaces unshared (network re-shared only when
   the policy allows it). Pure argv builder — no I/O.")

(defn wrap-argv
  "Wrap argv in a bwrap invocation enforcing the policy."
  [argv {:keys [writable hidden-dirs hidden-files network]}]
  (-> ["bwrap"
       "--ro-bind" "/" "/"
       "--dev" "/dev"
       "--proc" "/proc"
       "--tmpfs" "/tmp"]
      (into (mapcat (fn [p] ["--bind" p p]) writable))
      (into (mapcat (fn [p] ["--tmpfs" p]) hidden-dirs))
      (into (mapcat (fn [p] ["--ro-bind" "/dev/null" p]) hidden-files))
      (into (if (= network :all)
              ["--unshare-all" "--share-net"]
              ["--unshare-all"]))
      (conj "--die-with-parent")
      (into argv)))

(def backend
  {:id        :bwrap
   :binary    "bwrap"
   :wrap-argv wrap-argv})
