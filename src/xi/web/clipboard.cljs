(ns xi.web.clipboard
  "Writing the system clipboard from the browser: the copy buttons
   (xi.web.views) and a terminal program's OSC 52 (xi.web.terminal).")

(defn- fallback-copy!
  "Synchronous textarea-based clipboard copy for contexts without
   navigator.clipboard (iOS over plain HTTP). The textarea setup follows
   clipboard.js's iOS recipe: readonly, in-viewport, non-zooming font,
   explicit setSelectionRange. Focus goes back where it was (a terminal)."
  [text]
  (let [el       (.createElement js/document "textarea")
        focused  (.-activeElement js/document)
        scroll-y (or (.-pageYOffset js/window)
                     (.. js/document -documentElement -scrollTop)
                     0)]
    (set! (.-value el) text)
    (.setAttribute el "readonly" "")
    (set! (.. el -style -position) "absolute")
    (set! (.. el -style -left) "-9999px")
    (set! (.. el -style -top) (str scroll-y "px"))
    (set! (.. el -style -fontSize) "12pt")    ;; prevent iOS auto-zoom
    (.appendChild js/document.body el)
    (.focus el)
    (.select el)
    (.setSelectionRange el 0 (.. el -value -length))
    (.execCommand js/document "copy")
    (.removeChild js/document.body el)
    (some-> focused (.focus #js {:preventScroll true}))))

(defn copy!
  "Promise of `text` being on the clipboard."
  [text]
  (if (and (some? js/navigator.clipboard) js/window.isSecureContext)
    (-> (.writeText js/navigator.clipboard text)
        (.catch (fn [_] (fallback-copy! text))))
    (do (fallback-copy! text)
        (js/Promise.resolve))))
