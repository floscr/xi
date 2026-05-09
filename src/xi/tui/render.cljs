(ns xi.tui.render
  "Terminal renderer — ANSI color helpers.
   Most rendering now happens via the component system in tui/core.
   This namespace re-exports ansi helpers for backward compatibility."
  (:require [xi.tui.ansi :as ansi]
            [xi.tui.terminal :as term]))

;; Re-export fg/bg for existing callers
(def fg ansi/fg)
(def bg ansi/bg)
(def visible-width ansi/visible-width)

(defn set-title [title]
  (term/write! (str "\033]0;" title "\007")))
