(ns design
  "Browser-side persistent design mode, compiled by squint → esbuild into a
   self-contained IIFE (resources/design-mode/design.js) that xi injects into
   the MCP-controlled page via chrome-devtools-mcp's evaluate_script.

   Unlike the one-shot element picker, design mode stays resident on the page:

     Ctrl+I / Ctrl+B  toggle element picking (also: click the pill)
     click element    open an anchored popover, type an instruction
     Enter            queue the request (a xi sub-agent handles it), keep browsing
     Esc              close popover → picking → browsing (mode stays on)

   Contract:
     input   window.__XI_DESIGN_CFG__   {:colors {…} :maxHTML}
     output  window.__xiDesignQueue     array of request objects; xi's watcher
                                        loop drains it via evaluate_script
     flag    window.__xiDesignActive    truthy while installed — the watcher
                                        re-injects when it disappears (page
                                        navigation), which is what makes the
                                        mode survive navigations

   NB: squint has no js->clj / clj->js — read the config object via JS interop
   directly. Empty strings are truthy in CLJS when/and — guard with not-empty.")

;; Re-entrancy guard: a second injection while one is active is a no-op.
(when-not js/window.__xiDesignActive
  (set! js/window.__xiDesignActive true)
  (when-not js/window.__xiDesignQueue
    (set! js/window.__xiDesignQueue #js []))

  (let [cfg   (or js/window.__XI_DESIGN_CFG__ #js {})
        C     (or (.-colors cfg) #js {})
        doc   js/document
        root  (.-documentElement doc)
        serif "Copernicus, Charter, Georgia, 'Times New Roman', serif"
        sans  "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
        mono  "ui-monospace, 'SF Mono', Menlo, monospace"
        state #js {:picking false :hovered nil :selected nil :popover nil :toastTimer nil}]

    ;; ── Pure helpers (shared shape with the element picker) ───────────────

    (letfn [(esc [s]
              (-> (str s)
                  (.replaceAll "&" "&amp;")
                  (.replaceAll "<" "&lt;")
                  (.replaceAll ">" "&gt;")
                  (.replaceAll "\"" "&quot;")))

            (selector [el]
              ;; NB: an element without an id has (.-id el) = "", truthy in
              ;; CLJS — guard with not-empty.
              (if (not-empty (.-id el))
                (str "#" (js/CSS.escape (.-id el)))
                (let [path #js []]
                  (loop [cur el]
                    (when (and cur
                               (not= cur (.-body doc))
                               (not= cur root))
                      (if (not-empty (.-id cur))
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
                  (not-empty (.-id el))
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
                     :url            js/window.location.href
                     :ts             (js/Date.now)
                     :boundingRect   #js {:x      (+ (.-x r) js/window.scrollX)
                                          :y      (+ (.-y r) js/window.scrollY)
                                          :width  (.-width r)
                                          :height (.-height r)}}))

            (mk [id css]
              (let [el (.createElement doc "div")]
                (set! (.-id el) id)
                (set! (.. el -style -cssText) css)
                (.appendChild root el)
                el))]

      ;; ── Resident UI ───────────────────────────────────────────────────────

      (let [hl      (mk "__xi-design-hl"
                        (str "position:fixed;pointer-events:none;z-index:2147483645;"
                             "border:1.5px solid " (.-accent C) ";background:" (.-accentBg C)
                             ";border-radius:4px;transition:all 60ms ease-out;display:none;"))
            tip     (mk "__xi-design-tip"
                        (str "position:fixed;pointer-events:none;z-index:2147483646;"
                             "background:" (.-text C) ";color:" (.-surface C)
                             ";padding:4px 9px;border-radius:6px;font:11.5px/1.4 " mono ";"
                             "box-shadow:0 2px 10px rgba(30,30,28,0.25);display:none;max-width:420px;"
                             "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"))
            overlay (mk "__xi-design-overlay"
                        "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;cursor:crosshair;display:none;")
            pill    (mk "__xi-design-pill"
                        (str "display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none;"
                             "background:" (.-surface C) ";color:" (.-text C) ";border:1px solid " (.-border C)
                             ";padding:7px 14px;border-radius:999px;font:13px/1.4 " sans ";"
                             "box-shadow:0 4px 24px rgba(30,30,28,0.14),0 1px 3px rgba(30,30,28,0.08);"))
            ;; The dock holds the agents button (left) + the pill (right),
            ;; bottom-right. The agents button surfaces the design sub-agents:
            ;; a spinner while any is working, a list on click, per-agent commit.
            dock    (mk "__xi-design-dock"
                        "position:fixed;bottom:16px;right:16px;z-index:2147483646;display:flex;align-items:center;gap:10px;")
            agents-btn (mk "__xi-design-agents-btn"
                        (str "display:none;align-items:center;gap:6px;cursor:pointer;user-select:none;"
                             "background:" (.-surface C) ";color:" (.-text C) ";border:1px solid " (.-border C)
                             ";padding:6px 12px;border-radius:999px;font:13px/1.4 " sans ";"
                             "box-shadow:0 4px 24px rgba(30,30,28,0.14),0 1px 3px rgba(30,30,28,0.08);"))]

        (letfn [(pill-idle []
                  (set! (.-innerHTML pill)
                        (str "<span style=\"color:" (.-accent C) ";font-size:14px;\">\u2726</span>"
                             "<span style=\"font-family:" serif ";font-weight:600;letter-spacing:0.01em;\">Design</span>"
                             "<span style=\"color:" (.-textFaint C) ";font-size:11.5px;\">Ctrl+I/B to pick</span>")))

                (pill-picking []
                  (set! (.-innerHTML pill)
                        (str "<span style=\"font-size:14px;\">\u2726</span>"
                             "<span style=\"font-family:" serif ";font-weight:600;\">Pick an element</span>"
                             "<span style=\"opacity:0.75;font-size:11.5px;\">Esc to stop</span>")))

                (style-pill [picking?]
                  (if picking?
                    (do (set! (.. pill -style -background) (.-accent C))
                        (set! (.. pill -style -color) "#fff")
                        (set! (.. pill -style -borderColor) (.-accent C))
                        (pill-picking))
                    (do (set! (.. pill -style -background) (.-surface C))
                        (set! (.. pill -style -color) (.-text C))
                        (set! (.. pill -style -borderColor) (.-border C))
                        (pill-idle))))

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

                (close-popover []
                  (when-let [p (.-popover state)]
                    (.remove p)
                    (set! (.-popover state) nil))
                  (set! (.-selected state) nil))

                (stop-picking []
                  (close-popover)
                  (set! (.-picking state) false)
                  (set! (.-hovered state) nil)
                  (set! (.. overlay -style -display) "none")
                  (update-hl nil)
                  (style-pill false))

                (start-picking []
                  (set! (.-picking state) true)
                  (set! (.. overlay -style -display) "block")
                  (style-pill true))

                (toggle-picking []
                  (if (.-picking state) (stop-picking) (start-picking)))

                (show-toast [text]
                  (when-let [old (.getElementById doc "__xi-design-toast")] (.remove old))
                  (let [toast (mk "__xi-design-toast"
                                  (str "position:fixed;bottom:64px;right:16px;z-index:2147483646;"
                                       "pointer-events:none;display:flex;align-items:center;gap:8px;"
                                       "background:" (.-surface C) ";color:" (.-text C)
                                       ";border:1px solid " (.-border C) ";padding:9px 16px;border-radius:12px;"
                                       "font:13px/1.4 " sans ";box-shadow:0 4px 24px rgba(30,30,28,0.14);"
                                       "opacity:0;transform:translateY(4px);"
                                       "transition:opacity 180ms ease,transform 180ms ease;"))]
                    (set! (.-innerHTML toast)
                          (str "<span style=\"color:" (.-accent C) ";\">\u2726</span>" (esc text)))
                    (js/requestAnimationFrame
                     (fn []
                       (set! (.. toast -style -opacity) "1")
                       (set! (.. toast -style -transform) "translateY(0)")))
                    (js/setTimeout
                     (fn []
                       (set! (.. toast -style -opacity) "0")
                       (js/setTimeout (fn [] (.remove toast)) 250))
                     2600)))

                (submit [msg]
                  (when-let [el (.-selected state)]
                    (.push js/window.__xiDesignQueue (capture el msg))
                    (stop-picking)
                    (show-toast "Sent \u2014 a sub-agent is on it")))

                (open-popover []
                  (let [el    (.-selected state)
                        pop   (.createElement doc "div")
                        r     (.getBoundingClientRect el)
                        vw    (.-innerWidth js/window)
                        vh    (.-innerHeight js/window)
                        w     360]
                    (set! (.-id pop) "__xi-design-pop")
                    (set! (.. pop -style -cssText)
                          (str "position:fixed;z-index:2147483647;width:" w "px;"
                               "background:" (.-surface C) ";color:" (.-text C)
                               ";border:1px solid " (.-border C) ";border-radius:14px;padding:14px;"
                               "box-shadow:0 8px 40px rgba(30,30,28,0.18),0 2px 8px rgba(30,30,28,0.08);"
                               "font-family:" sans ";"))
                    (set! (.-innerHTML pop)
                          (str
                           "<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:10px;\">"
                           "<span style=\"color:" (.-accent C) ";font-size:14px;\">\u2726</span>"
                           "<span style=\"font-family:" serif ";font-size:15px;font-weight:600;\">Describe the change</span>"
                           "</div>"
                           "<div style=\"font:11.5px/1.5 " mono ";padding:7px 10px;margin-bottom:10px;"
                           "background:" (.-surfaceMuted C) ";border-radius:8px;color:" (.-textMuted C)
                           ";word-break:break-all;max-height:56px;overflow:hidden;\">"
                           (esc (info el))
                           "<br/><span style=\"color:" (.-textFaint C) ";\">" (esc (selector el)) "</span></div>"
                           "<textarea id=\"__xi-design-msg\" placeholder=\"e.g. more padding, warmer background\u2026\""
                           " style=\"width:100%;height:64px;resize:none;background:" (.-surface C)
                           ";color:" (.-text C) ";border:1px solid " (.-border C)
                           ";border-radius:10px;padding:9px 11px;font-size:13.5px;font-family:inherit;"
                           "line-height:1.45;outline:none;box-sizing:border-box;\"></textarea>"
                           "<div style=\"display:flex;align-items:center;gap:8px;margin-top:10px;\">"
                           "<span style=\"font-size:11px;color:" (.-textFaint C) ";margin-right:auto;\">"
                           "Enter to send \u00B7 Esc to re-pick</span>"
                           "<button id=\"__xi-design-send\" style=\"padding:7px 16px;border-radius:9px;border:none;"
                           "background:" (.-accent C) ";color:#fff;cursor:pointer;font-size:13px;"
                           "font-weight:600;font-family:inherit;\">Send</button>"
                           "</div>"))
                    (.appendChild root pop)
                    (set! (.-popover state) pop)
                    ;; Anchor near the element, clamped to the viewport.
                    (let [ph   (.-height (.getBoundingClientRect pop))
                          left (js/Math.min (js/Math.max 8 (.-left r)) (- vw w 8))
                          top  (if (< (+ (.-bottom r) 8 ph) vh)
                                 (+ (.-bottom r) 8)
                                 (js/Math.max 8 (- (.-top r) ph 8)))]
                      (set! (.. pop -style -left) (str left "px"))
                      (set! (.. pop -style -top) (str top "px")))
                    (let [msg-el (.getElementById doc "__xi-design-msg")]
                      (js/setTimeout (fn [] (.focus msg-el)) 50)
                      (.addEventListener (.getElementById doc "__xi-design-send") "click"
                                         (fn [] (submit (.trim (.-value msg-el)))))
                      (.addEventListener msg-el "keydown"
                                         (fn [e]
                                           (.stopPropagation e)
                                           (cond
                                             (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                                             (do (.preventDefault e) (submit (.trim (.-value msg-el))))

                                             (= (.-key e) "Escape")
                                             (do (.preventDefault e)
                                                 (close-popover)
                                                 (update-hl nil)
                                                 (show-toast "Re-pick \u2014 click another element"))))))))

                ;; ── Design-agents dock (spinner + list + per-agent commit) ──────

                (spinner-html [size]
                  (str "<span style=\"display:inline-block;width:" size "px;height:" size "px;"
                       "border:2px solid " (.-border C) ";border-top-color:" (.-accent C)
                       ";border-radius:50%;animation:__xiDesignSpin 0.7s linear infinite;\"></span>"))

                (working? [a]
                  (or (= (.-status a) "running") (= (.-commit a) "committing")))

                (list-sig [arr]
                  (.join (.map arr (fn [a] (str (.-id a) ":" (.-status a) ":" (or (.-commit a) "")))) "|"))

                (render-agents-btn []
                  (let [arr (or js/window.__xiDesignAgents #js [])
                        n   (.-length arr)]
                    (if (= n 0)
                      (do (set! (.. agents-btn -style -display) "none")
                          (set! (.-agentsSig state) nil))
                      (let [busy (.some arr (fn [a] (working? a)))
                            sig  (str n ":" busy)]
                        (set! (.. agents-btn -style -display) "flex")
                        (when (not= sig (.-agentsSig state))
                          (set! (.-agentsSig state) sig)
                          (set! (.-innerHTML agents-btn)
                                (str (if busy
                                       (spinner-html 13)
                                       (str "<span style=\"color:" (.-accent C) ";font-size:13px;\">\u2726</span>"))
                                     "<span style=\"font-family:" serif ";font-weight:600;\">"
                                     (if busy "Working" "Agents") "</span>"
                                     "<span style=\"color:" (.-textFaint C) ";font-size:11.5px;\">" n "</span>")))))))

                (agent-row-html [a]
                  (let [status (.-status a)
                        commit (.-commit a)
                        right  (cond
                                 (= status "running")   (spinner-html 12)
                                 (= status "error")     (str "<span style=\"color:#c0392b;font-size:11.5px;\">error</span>")
                                 (= commit "committed") (str "<span style=\"color:" (.-accent C) ";font-size:15px;line-height:1;\">\u2713</span>")
                                 (= commit "committing") (spinner-html 12)
                                 (= commit "error")     (str "<span style=\"color:#c0392b;font-size:11.5px;\">commit failed</span>")
                                 (= status "done")      (str "<button data-commit-id=\"" (esc (.-id a)) "\" "
                                                             "style=\"padding:4px 11px;border-radius:8px;border:1px solid " (.-border C)
                                                             ";background:" (.-accent C) ";color:#fff;cursor:pointer;"
                                                             "font-size:12px;font-weight:600;font-family:inherit;\">Commit</button>")
                                 (= status "stopped")   (str "<span style=\"color:" (.-textFaint C) ";font-size:11.5px;\">stopped</span>")
                                 :else "")
                        dot    (cond (= status "running") (.-accent C)
                                     (= status "error")   "#c0392b"
                                     (= status "done")    "#3a9d5d"
                                     :else (.-textFaint C))]
                    (str "<div style=\"display:flex;align-items:center;gap:9px;padding:8px 2px;border-top:1px solid " (.-border C) ";\">"
                         "<span style=\"width:7px;height:7px;border-radius:50%;flex:none;background:" dot ";\"></span>"
                         "<span style=\"flex:1;min-width:0;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;\">"
                         (esc (or (.-label a) "agent")) "</span>"
                         "<span style=\"flex:none;display:flex;align-items:center;min-height:22px;\">" right "</span>"
                         "</div>")))

                (render-agents-list []
                  (when-let [pop (.-agentsPop state)]
                    (let [arr  (or js/window.__xiDesignAgents #js [])
                          sig  (list-sig arr)
                          body (.getElementById doc "__xi-design-agents-list")]
                      (when (and body (not= sig (.-agentsListSig state)))
                        (set! (.-agentsListSig state) sig)
                        (set! (.-innerHTML body)
                              (if (= 0 (.-length arr))
                                (str "<div style=\"color:" (.-textFaint C) ";font-size:12.5px;padding:8px 2px;\">No design agents yet.</div>")
                                (.join (.map arr (fn [a] (agent-row-html a))) "")))
                        (doseq [btn (js/Array.from (.querySelectorAll body "[data-commit-id]"))]
                          (.addEventListener btn "click"
                                             (fn [e]
                                               (.stopPropagation e)
                                               (request-commit (.getAttribute btn "data-commit-id")))))))))

                (render-agents []
                  (render-agents-btn)
                  (render-agents-list))

                (request-commit [id]
                  (when-not js/window.__xiDesignCommitQueue
                    (set! js/window.__xiDesignCommitQueue #js []))
                  (.push js/window.__xiDesignCommitQueue #js {:id id :ts (js/Date.now)})
                  ;; Optimistic: flip to committing until the watcher confirms.
                  (let [arr (or js/window.__xiDesignAgents #js [])]
                    (.forEach arr (fn [a] (when (= (.-id a) id) (set! (.-commit a) "committing")))))
                  (set! (.-agentsSig state) nil)
                  (set! (.-agentsListSig state) nil)
                  (render-agents))

                (close-agents-pop []
                  (when-let [p (.-agentsPop state)]
                    (.remove p)
                    (set! (.-agentsPop state) nil)
                    (set! (.-agentsListSig state) nil)))

                (open-agents-pop []
                  (let [pop (.createElement doc "div")]
                    (set! (.-id pop) "__xi-design-agents-pop")
                    (set! (.. pop -style -cssText)
                          (str "position:fixed;right:16px;bottom:62px;z-index:2147483647;width:322px;"
                               "max-height:60vh;overflow:auto;background:" (.-surface C) ";color:" (.-text C)
                               ";border:1px solid " (.-border C) ";border-radius:14px;padding:12px 14px;font-family:" sans ";"
                               "box-shadow:0 8px 40px rgba(30,30,28,0.18),0 2px 8px rgba(30,30,28,0.08);"))
                    (set! (.-innerHTML pop)
                          (str "<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:4px;\">"
                               "<span style=\"color:" (.-accent C) ";font-size:14px;\">\u2726</span>"
                               "<span style=\"font-family:" serif ";font-size:15px;font-weight:600;\">Design agents</span></div>"
                               "<div id=\"__xi-design-agents-list\"></div>"))
                    (.appendChild root pop)
                    (set! (.-agentsPop state) pop)
                    (render-agents-list)))

                (toggle-agents-pop []
                  (if (.-agentsPop state) (close-agents-pop) (open-agents-pop)))]

          ;; ── Event wiring ──────────────────────────────────────────────────

          (style-pill false)

          ;; Spinner keyframes (inline styles can't declare @keyframes).
          (let [sheet (.createElement doc "style")]
            (set! (.-id sheet) "__xi-design-style")
            (set! (.-textContent sheet) "@keyframes __xiDesignSpin{to{transform:rotate(360deg)}}")
            (.appendChild root sheet))

          ;; Reparent the pill + agents button into the bottom-right dock
          ;; (agents button on the left, pill on the right).
          (.appendChild dock agents-btn)
          (.appendChild dock pill)

          (when-not js/window.__xiDesignCommitQueue
            (set! js/window.__xiDesignCommitQueue #js []))
          ;; The watcher pushes the agent list into __xiDesignAgents each poll
          ;; and calls this to re-render the dock + open list.
          (set! js/window.__xiDesignRender render-agents)
          (render-agents)

          (.addEventListener agents-btn "click"
                             (fn [e] (.stopPropagation e) (toggle-agents-pop)))
          (.addEventListener pill "click" (fn [] (toggle-picking)))

          (.addEventListener overlay "mousemove"
                             (fn [e]
                               (when-not (.-selected state)
                                 (set! (.. overlay -style -pointerEvents) "none")
                                 (let [el (.elementFromPoint doc (.-clientX e) (.-clientY e))]
                                   (set! (.. overlay -style -pointerEvents) "auto")
                                   (when (and el (or (not (.-id el))
                                                     (not= 0 (.indexOf (.-id el) "__xi-design"))))
                                     (set! (.-hovered state) el)
                                     (update-hl el))))))

          (.addEventListener overlay "click"
                             (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (when-not (or (.-selected state) (not (.-hovered state)))
                                 (set! (.-selected state) (.-hovered state))
                                 (set! (.. hl -style -borderColor) (.-accent C))
                                 (set! (.. tip -style -display) "none")
                                 (open-popover))))

          ;; Global keys: Ctrl+I or Ctrl+B toggles picking; Esc steps back
          ;; (popover → picking → browsing). The handler self-removes once the
          ;; active flag is gone (cleanup ran).
          (let [key-handler
                (fn key-handler [e]
                  (if-not js/window.__xiDesignActive
                    (.removeEventListener doc "keydown" key-handler true)
                    (cond
                      (and (.-ctrlKey e) (not (.-shiftKey e)) (not (.-altKey e))
                           (not (.-metaKey e))
                           (let [k (.toLowerCase (or (.-key e) ""))]
                             (or (= k "i") (= k "b"))))
                      (do (.preventDefault e) (.stopPropagation e) (toggle-picking))

                      (and (= (.-key e) "Escape") (.-picking state))
                      (do (.preventDefault e)
                          (.stopPropagation e)
                          (if (.-popover state)
                            (do (close-popover) (update-hl nil))
                            (stop-picking))))))]
            (.addEventListener doc "keydown" key-handler true)))))))
