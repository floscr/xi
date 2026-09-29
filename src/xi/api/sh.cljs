(ns xi.api.sh
  "Rules-gated shell-outs for user extensions: argv-style, one command, no
   shell — the same `{:tool :sh :cli :command :argv}` request clj's (sh …)
   produces, so the same rules decide it (read-only CLIs run, sudo / shells /
   remote copy are denied, everything else asks — `[a]lways` pins the exact
   command to this extension).

   `bb serve:restart` / `serve:stop` run detached (xi.server-control), since
   inline they would kill the server running the extension.

   The child gets a scrubbed env (xi.paths/scrub-env — no API keys or tokens)
   plus the desktop session vars notification CLIs need."
  (:require [clojure.string :as str]
            [xi.api.core :as core]
            [xi.paths :as paths]
            [xi.server-control :as server-control]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]))

(def ^:private TIMEOUT_MS 120000)
(def ^:private MAX_OUTPUT 20000)

(def ^:private DESKTOP_ENV
  "Session (not secret) vars for notify-send / dunstify / xdotool & co."
  ["DISPLAY" "WAYLAND_DISPLAY" "XDG_RUNTIME_DIR" "DBUS_SESSION_BUS_ADDRESS"])

(defn- child-env []
  (let [env (paths/scrub-env)]
    (doseq [k DESKTOP_ENV]
      (when-some [v (aget js/process.env k)]
        (unchecked-set env k v)))
    env))

(defn- clip [s]
  (if (> (count s) MAX_OUTPUT)
    (str (subs s 0 MAX_OUTPUT) "\n… [truncated]")
    s))

(defn- spawn!
  "Run argv in dir → Promise<{:exit :out :err}>, killed after TIMEOUT_MS.
   node:child_process (not Bun.spawn) so it runs under node tests too."
  [argv dir]
  (js/Promise.
   (fn [resolve _]
     (let [out   #js []
           err   #js []
           proc  (cp/spawn (first argv) (clj->js (rest argv))
                           #js {:cwd dir :env (child-env)
                                :stdio #js ["ignore" "pipe" "pipe"]})
           timer (js/setTimeout #(.kill proc) TIMEOUT_MS)
           done  (fn [code msg]
                   (js/clearTimeout timer)
                   (resolve {:exit (or code -1)
                             :out  (clip (.join out ""))
                             :err  (clip (str (.join err "") msg))}))]
       (.on (.-stdout proc) "data" #(.push out (str %)))
       (.on (.-stderr proc) "data" #(.push err (str %)))
       (.on proc "error" #(done -1 (.-message %)))
       (.on proc "close" #(done % nil))))))

(defn sh
  "(sh ctx \"cmd\" \"arg\" …) → Promise<stdout> (trimmed; stderr when stdout
   is empty). Rejects with {:exit :out :err} on a non-zero exit, or with the
   rule's message when refused. An optional opts map before the argv takes
   :dir — the directory to run in, relative to the room cwd (or data dir)."
  [ctx & args]
  (let [[opts argv] (if (map? (first args)) [(first args) (rest args)] [nil args])
        argv        (mapv str argv)
        bin         (first argv)]
    (cond
      (str/blank? bin)
      (js/Promise.reject (ex-info "xi.api.sh: (sh ctx \"cmd\" \"arg\" …) needs a command" {}))

      (str/includes? bin " ")
      (js/Promise.reject (ex-info "xi.api.sh: argv-style — (sh ctx \"cmd\" \"arg\"), not a shell string" {}))

      :else
      (let [base (core/base-cwd ctx)
            dir  (if-let [d (:dir opts)] (paths/real-resolve base d) base)
            cmd  (str/join " " argv)]
        (-> (core/gate! ctx {:tool :sh :cli bin :command cmd :argv argv :effective-cwd dir})
            (.then (fn [_]
                     ;; outside a room the cwd is the data dir, which only
                     ;; exists after a first write
                     (when (= dir (paths/extension-data-dir (:extension ctx)))
                       (fs/mkdirSync dir #js {:recursive true}))
                     (if (server-control/kind cmd)
                       (server-control/run-detached! cmd dir)
                       (-> (spawn! argv dir)
                           (.then (fn [{:keys [exit out err] :as r}]
                                    (if (zero? exit)
                                      (let [out' (str/trimr out)]
                                        (if (str/blank? out') (str/trimr err) out'))
                                      (throw (ex-info (str "xi.api.sh: " cmd " failed (exit " exit "): "
                                                           (str/trim (str err "\n" out)))
                                                      r))))))))))))))
