(ns xi.ext.tmp-cleanup-intercept
  "Intercepts `rm` commands that clean up files under /tmp.

   On this machine /tmp is a temporary filesystem (cleared on restart),
   so cleaning up temp files is wasted effort. When a bash command tries
   to `rm` something under /tmp, the command is not run and a steering
   note nudges the agent to leave temp files in place."
  (:require [clojure.string :as str]))

(def ^:private tmp-rm-re
  "Matches an `rm` invocation that targets a /tmp path within the same
   command segment (no &&, ||, ;, |, or newline between `rm` and /tmp).
   Handles flags and quotes around the path."
  #"\brm\b[^\n&|;]*?/tmp(?:/|\b)")

(defn removes-tmp?
  "True when the bash command tries to `rm` a path under /tmp."
  [cmd]
  (boolean (re-find tmp-rm-re (str cmd))))

(def ^:private STEER_NOTE
  (str "Skipped: `/tmp` is a temporary filesystem on this machine (cleared on "
       "restart), so there's no need to clean up files under `/tmp`. The `rm` "
       "was not run. Leave temp files in place — they're removed automatically."))

(defn- tool-gate
  "Intercept `rm` commands that clean up /tmp files."
  [tool-call _ctx]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (if (and (= "bash" lname) (removes-tmp? (:command arguments)))
      {:intercepted true
       :result {:content [{:type "text" :text STEER_NOTE}]
                :is-error false}}
      tool-call)))

(def extension
  {:id        :tmp-cleanup-intercept
   :tool-gate tool-gate})
