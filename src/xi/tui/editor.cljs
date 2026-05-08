(ns xi.tui.editor
  "Multi-line input editor with readline. For now, wraps node:readline.
   Raw-mode TUI editor is a future enhancement."
  (:require [clojure.string :as str]
            [xi.tui.render :as render]
            ["node:readline" :as readline]))

(defn create-rl
  "Create a readline interface."
  []
  (.createInterface readline
                    #js {:input js/process.stdin
                         :output js/process.stdout}))

(defn prompt
  "Show prompt and read a line. Returns promise of string."
  [rl prompt-text]
  (js/Promise.
   (fn [resolve _reject]
     (.question ^js rl (render/fg :accent prompt-text)
                (fn [answer]
                  (resolve answer))))))
