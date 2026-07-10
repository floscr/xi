(ns xi.config-macros
  "Compile-time helpers for declaring config options.

   Lives in its own .clj (not xi.config) because xi.config is a .cljc whose
   only :require entries sit behind the :node/:browser reader features — loaded
   as a JVM macro namespace it would present an empty (:require), which fails
   the ns spec. Keeping the macros here avoids ever loading xi.config on the
   macro-expansion JVM; the expansion just emits a fully-qualified reference to
   xi.config/tui-opt, resolved at cljs runtime.")

(defmacro deftui-opt
  "Define a private TUI config option `sym`. The option's default is the
   literal `default` (which lives in the consuming namespace); the effective
   value is read from xi.config/tui under keyword `(keyword sym)`, falling back
   to `default` when the key is absent.

     (deftui-opt truncate-output-block-after-n-lines 100 \"doc…\")
     ;; => (def ^:private truncate-output-block-after-n-lines
     ;;      (xi.config/tui-opt :truncate-output-block-after-n-lines 100))"
  [sym default docstring]
  `(def ~(vary-meta sym assoc :private true :doc docstring)
     (xi.config/tui-opt ~(keyword (name sym)) ~default)))
