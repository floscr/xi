(ns xi.test-util
  "Helpers shared by the test suites (not a test namespace itself).")

(defn silenced
  "Call `f` with js/console.error and js/console.warn muted, restoring them
   afterwards; returns f's value. For tests that deliberately trigger a
   logged-and-recovered error, so the expected stack trace does not paint
   over the test output. If `f` returns a promise, the console stays muted
   until it settles (the log often fires in a .catch)."
  [f]
  (let [error    (.-error js/console)
        warn     (.-warn js/console)
        noop     (fn [& _] nil)
        restore! (fn []
                   (set! (.-error js/console) error)
                   (set! (.-warn js/console) warn))]
    (set! (.-error js/console) noop)
    (set! (.-warn js/console) noop)
    (let [v (try (f)
                 (catch :default e
                   (restore!)
                   (throw e)))]
      (if (and (some? v) (fn? (.-then v)))
        (.finally v restore!)
        (do (restore!) v)))))
