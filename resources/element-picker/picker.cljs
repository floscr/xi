(ns picker
  "Browser-side element picker, compiled by squint → esbuild into a
   self-contained IIFE (resources/element-picker/picker.js) that xi injects
   into the MCP-controlled page via chrome-devtools-mcp's evaluate_script.

   Contract (unchanged across the JS→CLJS port):
     input   window.__XI_PICKER_CFG__  {:colors {…} :prefillMessage :maxHTML}
     output  window.__xiPickerResult   {:elements [...] :url}   on submit
             window.__xiPickerCancelled true                    on cancel

   NB: squint has no js->clj / clj->js — read the config object via JS interop
   directly. Everything here is imperative DOM (overlay hit-testing, a highlight
   box tracking the cursor, a modal panel), so it uses raw interop rather than a
   reactive renderer — that also keeps the injected payload tiny.")

;; Re-entrancy guard: a second injection while one is active is a no-op.
(when-not js/window.__xiPickerActive
  (set! js/window.__xiPickerActive true)
  (set! js/window.__xiPickerResult nil)
  (set! js/window.__xiPickerCancelled false)

  (let [cfg   (or js/window.__XI_PICKER_CFG__ #js {})
        C     (.-colors cfg)
        doc   js/document
        root  (.-documentElement doc)
        picked #js []]

    ;; ── Pure helpers ──────────────────────────────────────────────────────

    (letfn [(esc [s]
              (-> (str s)
                  (.replaceAll "&" "&amp;")
                  (.replaceAll "<" "&lt;")
                  (.replaceAll ">" "&gt;")
                  (.replaceAll "\"" "&quot;")))

            (selector [el]
              (if (.-id el)
                (str "#" (js/CSS.escape (.-id el)))
                (let [path #js []]
                  (loop [cur el]
                    (when (and cur
                               (not= cur (.-body doc))
                               (not= cur root))
                      (if (.-id cur)
                        (.unshift path (str "#" (js/CSS.escape (.-id cur))))
                        (let [tag    (.toLowerCase (.-tagName cur))
                              parent (.-parentElement cur)
                              part   (if parent
                                       (let [same (.filter (js/Array.from (.-children parent))
                                                           (fn [c] (= (.-tagName c) (.-tagName cur))))]
                                         (if (> (.-length same) 1)
                                           (str tag ":nth-of-type(" (inc (.indexOf same cur)) ")")
                                           tag))
                                       tag)]
                          (.unshift path part)
                          (recur (.-parentElement cur))))))
                  (or (not-empty (.join path " > "))
                      (.toLowerCase (.-tagName el))))))

            (info [el]
              (let [s (atom (.toLowerCase (.-tagName el)))]
                (cond
                  (.-id el)
                  (reset! s (str @s "#" (.-id el)))

                  (and (.-className el) (string? (.-className el)))
                  (let [cls (-> (.-className el) .trim (.split #"\s+")
                                (.slice 0 3) (.join "."))]
                    (when (not-empty cls) (reset! s (str @s "." cls)))))
                (let [r (.getBoundingClientRect el)]
                  (str @s "  " (js/Math.round (.-width r)) "\u00D7" (js/Math.round (.-height r))))))

            (styles [el]
              (let [cs   (js/getComputedStyle el)
                    keys #js ["display" "position" "width" "height" "margin" "padding" "color"
                              "backgroundColor" "fontSize" "fontFamily" "fontWeight" "border"
                              "borderRadius" "overflow" "flexDirection" "justifyContent"
                              "alignItems" "gridTemplateColumns" "gap"]
                    out  #js {}]
                (doseq [k keys]
                  (let [v (aget cs k)]
                    (when (and v (not= v "none") (not= v "normal") (not= v "0px")
                               (not= v "auto") (not= v "visible") (not= v "static")
                               (not= v "rgba(0, 0, 0, 0)"))
                      (aset out k v))))
                out))

            (mk-div [id css]
              (let [el (.createElement doc "div")]
                (set! (.-id el) id)
                (set! (.. el -style -cssText) css)
                (.appendChild root el)
                el))]

      ;; ── UI elements ─────────────────────────────────────────────────────

      (let [hl      (mk-div "__xi-picker-highlight"
                            (str "position:fixed;pointer-events:none;z-index:2147483645;"
                                 "border:2px solid " (.-primaryBorder C) ";background:" (.-primaryBg C)
                                 ";border-radius:2px;transition:all 50ms ease-out;display:none;"))
            tip     (mk-div "__xi-picker-tooltip"
                            (str "position:fixed;pointer-events:none;z-index:2147483646;"
                                 "background:" (.-bg C) ";color:" (.-text C)
                                 ";padding:4px 8px;border-radius:4px;font:12px/1.4 monospace;"
                                 "box-shadow:0 2px 8px rgba(0,0,0,0.4);display:none;max-width:400px;"
                                 "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"))
            overlay (mk-div "__xi-picker-overlay"
                            "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;cursor:crosshair;")
            badge   (mk-div "__xi-picker-badge"
                            (str "position:fixed;top:12px;right:12px;z-index:2147483646;"
                                 "pointer-events:none;background:" (.-primary C) ";color:white;"
                                 "padding:4px 12px;border-radius:20px;font:13px/1.4 -apple-system,sans-serif;"
                                 "font-weight:600;box-shadow:0 2px 8px rgba(0,0,0,0.3);display:none;"))
            banner  (mk-div "__xi-picker-banner"
                            (str "position:fixed;top:12px;left:50%;transform:translateX(-50%);"
                                 "z-index:2147483646;pointer-events:none;background:" (.-primary C) ";color:white;"
                                 "padding:8px 16px;border-radius:20px;font:13px/1.4 -apple-system,sans-serif;"
                                 "font-weight:600;box-shadow:0 2px 12px rgba(0,0,0,0.4);white-space:nowrap;"
                                 "display:flex;align-items:center;gap:8px;"))
            ;; mutable picking state
            state   #js {:hovered nil :selected nil :panel nil}]

        (set! (.-innerHTML banner)
              (str "\uD83C\uDFAF xi picker \u2014 hover &amp; click an element"
                   "<span style=\"opacity:0.75;font-weight:400;\">\u00B7 Esc to cancel</span>"))

        (letfn [(show-banner [on] (set! (.. banner -style -display) (if on "flex" "none")))

                (update-badge []
                  (if (> (.-length picked) 0)
                    (do (set! (.-textContent badge) (str (.-length picked) " picked"))
                        (set! (.. badge -style -display) "block"))
                    (set! (.. badge -style -display) "none")))

                (update-hl [el]
                  (if-not el
                    (do (set! (.. hl -style -display) "none")
                        (set! (.. tip -style -display) "none"))
                    (let [r (.getBoundingClientRect el)]
                      (set! (.. hl -style -display) "block")
                      (set! (.. hl -style -top) (str (.-top r) "px"))
                      (set! (.. hl -style -left) (str (.-left r) "px"))
                      (set! (.. hl -style -width) (str (.-width r) "px"))
                      (set! (.. hl -style -height) (str (.-height r) "px"))
                      (set! (.. tip -style -display) "block")
                      (set! (.-textContent tip) (info el))
                      (set! (.. tip -style -top)
                            (str (if (> (.-top r) 33) (- (.-top r) 28) (+ (.-bottom r) 5)) "px"))
                      (set! (.. tip -style -left) (str (js/Math.max 5 (.-left r)) "px")))))

                (capture [el msg]
                  (let [r    (.getBoundingClientRect el)
                        html (let [h (.-outerHTML el)]
                               (if (> (.-length h) (.-maxHTML cfg))
                                 (str (.substring h 0 (.-maxHTML cfg)) "\n<!-- truncated -->")
                                 h))]
                    #js {:selector       (selector el)
                         :tagName        (.toLowerCase (.-tagName el))
                         :message        (or msg "")
                         :outerHTML      html
                         :computedStyles (styles el)
                         :boundingRect   #js {:x      (+ (.-x r) js/window.scrollX)
                                              :y      (+ (.-y r) js/window.scrollY)
                                              :width  (.-width r)
                                              :height (.-height r)}}))

                (clear-selection []
                  (set! (.-selected state) nil)
                  (set! (.. hl -style -borderColor) (.-primaryBorder C))
                  (set! (.. hl -style -background) (.-primaryBg C)))

                (cleanup-dom []
                  (doseq [id #js ["__xi-picker-overlay" "__xi-picker-highlight" "__xi-picker-tooltip"
                                  "__xi-picker-panel" "__xi-picker-badge" "__xi-picker-banner"]]
                    (when-let [el (.getElementById doc id)] (.remove el)))
                  (set! js/window.__xiPickerActive false))

                (do-cancel []
                  (cleanup-dom)
                  (set! js/window.__xiPickerCancelled true))

                (do-submit []
                  (let [m  (.getElementById doc "__xi-picker-msg")
                        msg (if m (.trim (.-value m)) "")]
                    (.push picked (capture (.-selected state) msg))
                    (set! js/window.__xiPickerResult
                          #js {:elements picked :url js/window.location.href})
                    (cleanup-dom)))

                (do-pick-more []
                  (let [m   (.getElementById doc "__xi-picker-msg")
                        msg (if m (.trim (.-value m)) "")]
                    (.push picked (capture (.-selected state) msg))
                    (.remove (.-panel state))
                    (set! (.-panel state) nil)
                    (clear-selection)
                    (update-badge)
                    (show-banner true)))

                (show-panel []
                  (let [panel (.createElement doc "div")
                        sel   (selector (.-selected state))
                        inf   (info (.-selected state))
                        count-label (if (> (.-length picked) 0)
                                      (str " <span style=\"color:" (.-primary C)
                                           ";font-size:12px;font-weight:400;\">("
                                           (.-length picked) " already picked)</span>")
                                      "")]
                    (set! (.-id panel) "__xi-picker-panel")
                    (set! (.. panel -style -cssText)
                          (str "position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);"
                               "z-index:2147483647;background:" (.-bg C) ";border:1px solid " (.-border C)
                               ";border-radius:12px;padding:20px;width:420px;"
                               "box-shadow:0 8px 32px rgba(0,0,0,0.5);"
                               "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;color:" (.-text C) ";"))
                    (set! (.-innerHTML panel)
                          (str
                           "<div style=\"margin-bottom:16px;\">"
                           "<div style=\"font-size:14px;font-weight:600;margin-bottom:8px;color:" (.-accent C) "\">"
                           "\uD83C\uDFAF Selected Element" count-label "</div>"
                           "<div style=\"font:12px/1.4 monospace;padding:8px;background:" (.-bgLight C)
                           ";border-radius:6px;color:" (.-textMuted C) ";word-break:break-all;\">"
                           (esc inf) "<br/>"
                           "<span style=\"color:" (.-textDim C) "\">" (esc sel) "</span>"
                           "</div></div>"
                           "<div style=\"margin-bottom:16px;\">"
                           "<label style=\"font-size:13px;color:" (.-textMuted C) ";display:block;margin-bottom:6px;\">"
                           "Message for xi</label>"
                           "<textarea id=\"__xi-picker-msg\" placeholder=\"What should xi do with this element?\""
                           " style=\"width:100%;height:80px;resize:vertical;background:" (.-bgLight C)
                           ";color:" (.-text C) ";border:1px solid " (.-border C)
                           ";border-radius:6px;padding:10px;font-size:14px;font-family:inherit;"
                           "outline:none;box-sizing:border-box;\"></textarea>"
                           "</div>"
                           "<div style=\"display:flex;gap:8px;justify-content:flex-end;align-items:center;\">"
                           "<span style=\"font-size:11px;color:" (.-textDim C) ";margin-right:auto;\">"
                           "Alt+Enter pick more \u00B7 Ctrl+Enter submit \u00B7 Esc back</span>"
                           "<button id=\"__xi-picker-cancel\" style=\"padding:8px 16px;border-radius:6px;"
                           "border:1px solid " (.-border C) ";background:" (.-bgLight C)
                           ";color:" (.-textMuted C) ";cursor:pointer;font-size:13px;\">Cancel</button>"
                           "<button id=\"__xi-picker-submit\" style=\"padding:8px 16px;border-radius:6px;"
                           "border:none;background:" (.-primary C)
                           ";color:white;cursor:pointer;font-size:13px;font-weight:600;\">Send to xi</button>"
                           "</div>"))
                    (.appendChild root panel)
                    (set! (.-panel state) panel)
                    (let [msg-el (.getElementById doc "__xi-picker-msg")]
                      (when (and (= (.-length picked) 0) (not-empty (.-prefillMessage cfg)))
                        (set! (.-value msg-el) (.-prefillMessage cfg)))
                      (js/setTimeout (fn [] (.focus msg-el)) 50)
                      (.addEventListener (.getElementById doc "__xi-picker-cancel") "click" do-cancel)
                      (.addEventListener (.getElementById doc "__xi-picker-submit") "click" do-submit)
                      (.addEventListener msg-el "keydown"
                                         (fn [e]
                                           (cond
                                             (and (= (.-key e) "Enter") (.-altKey e))
                                             (do (.preventDefault e) (do-pick-more))
                                             (and (= (.-key e) "Enter") (or (.-ctrlKey e) (.-metaKey e)))
                                             (do (.preventDefault e) (do-submit))))))))]

          ;; ── Event wiring ──────────────────────────────────────────────────

          (.addEventListener overlay "mousemove"
                             (fn [e]
                               (when-not (.-selected state)
                                 (set! (.. overlay -style -pointerEvents) "none")
                                 (let [el (.elementFromPoint doc (.-clientX e) (.-clientY e))]
                                   (set! (.. overlay -style -pointerEvents) "auto")
                                   (when (and el (or (not (.-id el))
                                                     (not= 0 (.indexOf (.-id el) "__xi-picker"))))
                                     (set! (.-hovered state) el)
                                     (update-hl el))))))

          (.addEventListener overlay "click"
                             (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (when-not (or (.-selected state) (not (.-hovered state)))
                                 (set! (.-selected state) (.-hovered state))
                                 (set! (.. hl -style -borderColor) (.-success C))
                                 (set! (.. hl -style -background) (.-successBg C))
                                 (set! (.. tip -style -display) "none")
                                 (show-banner false)
                                 (show-panel))))

          ;; Escape: panel open → back to picking; picking → cancel entirely.
          (let [esc-handler
                (fn esc-handler [e]
                  (when (= (.-key e) "Escape")
                    (.preventDefault e)
                    (.stopPropagation e)
                    (if (and (.-selected state) (.-panel state))
                      (do (.remove (.-panel state))
                          (set! (.-panel state) nil)
                          (clear-selection)
                          (show-banner true))
                      (do (.removeEventListener doc "keydown" esc-handler true)
                          (do-cancel)))))]
            (.addEventListener doc "keydown" esc-handler true)))))))
