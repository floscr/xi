(ns xi.crash-log
  "Where stray errors go. A crash is recorded to ~/.config/xi/crash.log so it
   stays diagnosable after the fact: a terminal client's scrollback and a
   restarted server's tmux pane are both gone by the time anyone looks.

   `guarded` wraps a callback that runs on a node event (stdin data, a resize,
   a timer) so one bad key or frame is recorded and dropped instead of
   escaping into the runtime, which would kill the process with the terminal
   still in raw mode."
  (:require ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defonce ^:private file-override
  ;; Test seam: write somewhere other than ~/.config/xi/crash.log.
  (atom nil))

(defn set-file!
  "Override the crash log path (nil restores the default)."
  [file]
  (reset! file-override file))

(defn file []
  (or @file-override
      (.join node-path (aget js/process.env "HOME") ".config" "xi" "crash.log")))

(defn stack-of
  "The stack text of a thrown value (anything can be thrown)."
  [err]
  (or (some-> err .-stack) (str err)))

(defn record!
  "Append `err` under `label` to the crash log. Never throws. → the stack text."
  [label err]
  (let [stack (stack-of err)]
    (try
      (let [f (file)]
        (.mkdirSync fs (.dirname node-path f) #js {:recursive true})
        (.appendFileSync fs f (str "\n[" (.toISOString (js/Date.)) "] " label "\n" stack "\n")))
      (catch :default _ nil))
    stack))

(defn guarded
  "`f` as a function that records a throw under `label` and returns nil
   instead of letting it escape."
  [label f]
  (fn [& args]
    (try
      (apply f args)
      (catch :default e
        (record! label e)
        nil))))
