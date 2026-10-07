(ns xi.web.key-hints
  "Alt-held key hints: while Alt is held, every element tagged with
   `data-key-action` (an xi.keys action id, e.g. \"permission/allow\") shows
   the key that runs that action right now as a badge, so the buttons tell
   their shortcuts. The key comes from the effective keymap
   (xi.web.keymap/shortcut), so config.edn `:keys` overrides show up, and an
   action with no key in the active layers gets no badge.

   Pure DOM, like the command palette's quick-nav hints (ui.js.command): the
   runtime sets `data-key-hint` on the tagged elements and `data-key-hints` on
   <html>; style.css draws the badges. Nothing goes through app state, so
   holding Alt never re-renders.

   The badges appear only after Alt has been held for `show-delay-ms`, so a
   quick Alt+j doesn't flash them, and they relabel when a key is released
   with Alt still held (the action may have changed which buttons exist)."
  (:require [xi.web.keymap :as keymap]))

(def ^:private show-delay-ms 250)

(def ^:private neutral-keys
  "Modifiers pressed on top of Alt (Alt+Shift+A): neither show nor cancel."
  #{"Shift" "Control" "Meta" "AltGraph"})

(defonce ^:private timer (atom nil))

(defn- root [] (.-documentElement js/document))

(defn- shown? [] (.hasAttribute (root) "data-key-hints"))

(defn- cancel-timer! []
  (when-let [t @timer]
    (js/clearTimeout t)
    (reset! timer nil)))

(defn- label!
  "Set each tagged element's `data-key-hint` to its action's current key."
  [state]
  (.forEach (.querySelectorAll js/document "[data-key-action]")
            (fn [^js el]
              (if-let [k (keymap/shortcut state (keyword (.. el -dataset -keyAction)))]
                (.setAttribute el "data-key-hint" k)
                (.removeAttribute el "data-key-hint")))))

(defn- show! [state]
  (label! state)
  (.setAttribute (root) "data-key-hints" ""))

(defn hide! []
  (cancel-timer!)
  (when (shown?)
    (.removeAttribute (root) "data-key-hints")
    (.forEach (.querySelectorAll js/document "[data-key-hint]")
              (fn [^js el] (.removeAttribute el "data-key-hint")))))

(defn- on-keydown [get-state ^js e]
  (let [k (.-key e)]
    (cond
      (= "Alt" k)            (when-not (or (shown?) @timer)
                               (reset! timer (js/setTimeout
                                              (fn []
                                                (reset! timer nil)
                                                (show! (get-state)))
                                              show-delay-ms)))
      (contains? neutral-keys k) nil
      ;; A chord before the delay ran out: the user knows the key.
      (.-altKey e)           (cancel-timer!)
      :else                  (hide!))))

(defn- on-keyup [get-state ^js e]
  (cond
    (= "Alt" (.-key e))         (hide!)
    (and (.-altKey e) (shown?)) (js/requestAnimationFrame
                                 #(when (shown?) (label! (get-state))))))

(defn install!
  "Listen for Alt on the document. `get-state` returns the current app state."
  [get-state]
  (.addEventListener js/document "keydown" #(on-keydown get-state %))
  (.addEventListener js/document "keyup" #(on-keyup get-state %))
  ;; The keyup never arrives when Alt is released in another window.
  (.addEventListener js/window "blur" hide!)
  (.addEventListener js/document "visibilitychange"
                     #(when (= "hidden" (.-visibilityState js/document)) (hide!))))
