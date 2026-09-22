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
        sans  "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
        mono  "ui-monospace, 'SF Mono', Menlo, monospace"
        ;; HUD chrome: flat dark panels on the same surface as the dialkit
        ;; popover (--bg-0), no drop shadows — hairline borders carry the edge.
        panel-bg (str "background:" (.-surface C)
                      ";border:1px solid " (.-border C) ";")
        btn-primary (str "background:linear-gradient(180deg," (.-accentBright C) "," (.-accent C)
                         ");color:#fff;border:none;box-shadow:0 2px 12px " (.-accentGlow C)
                         ",inset 0 1px 0 oklch(1 0 0 / 0.25);")
        ;; Inline lucide monoline icons (currentColor via stroke) — the web
        ;; client is icon-driven, so the overlay uses the same visual language
        ;; instead of a filled display glyph.
        svg-icon (fn [paths size color]
                   (str "<svg width=\"" size "\" height=\"" size "\" viewBox=\"0 0 24 24\" "
                        "fill=\"none\" stroke=\"" color "\" stroke-width=\"2\" "
                        "stroke-linecap=\"round\" stroke-linejoin=\"round\" "
                        "style=\"display:inline-block;vertical-align:middle;flex:none;\">" paths "</svg>"))
        sparkles-path (str "<path d=\"M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962"
                           "L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5"
                           "A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 "
                           "0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z\"/>"
                           "<path d=\"M20 3v4\"/><path d=\"M22 5h-4\"/>"
                           "<path d=\"M4 17v2\"/><path d=\"M5 18H3\"/>")
        check-path "<path d=\"M20 6 9 17l-5-5\"/>"
        spark    (svg-icon sparkles-path 15 (.-accentBright C))
        check-ok (svg-icon check-path 16 (.-success C))
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

            (capture [el msg mode]
              (let [r    (.getBoundingClientRect el)
                    html (let [h (.-outerHTML el)]
                           (if (> (.-length h) (.-maxHTML cfg))
                             (str (.substring h 0 (.-maxHTML cfg)) "\n<!-- truncated -->")
                             h))]
                #js {:selector       (selector el)
                     :tagName        (.toLowerCase (.-tagName el))
                     :mode           (or mode "edit")
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
                             panel-bg "color:" (.-text C)
                             ";padding:4px 9px;border-radius:6px;font:11.5px/1.4 " mono ";"
                             "display:none;max-width:420px;"
                             "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"))
            overlay (mk "__xi-design-overlay"
                        "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;cursor:crosshair;display:none;")
            pill    (mk "__xi-design-pill"
                        (str "display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none;"
                             "color:" (.-text C)
                             ";padding:9px 15px;font:12px/1.4 " sans ";"))
            ;; The dock is ONE capsule group holding the agents button (left)
            ;; + the pill (right) as borderless segments split by a hairline
            ;; divider. The agents button surfaces the design sub-agents:
            ;; a spinner while any is working, a list on click, per-agent commit.
            dock    (mk "__xi-design-dock"
                        (str "position:fixed;bottom:16px;right:16px;z-index:2147483646;"
                             "display:flex;align-items:center;"
                             panel-bg "border-radius:999px;"
                             "transition:border-color 150ms ease,box-shadow 150ms ease;"))
            agents-btn (mk "__xi-design-agents-btn"
                        (str "display:none;align-items:center;gap:7px;cursor:pointer;user-select:none;"
                             "color:" (.-text C)
                             ";padding:9px 15px;font:12px/1.4 " sans ";"
                             "border-right:1px solid " (.-border C) ";"))]

        (letfn [(pill-idle []
                  (set! (.-innerHTML pill)
                        (str spark
                             "<span style=\"font-weight:600;letter-spacing:0.01em;\">Design</span>"
                             "<span style=\"color:" (.-textFaint C) ";font-size:11.5px;\">Ctrl+I/B to pick</span>")))

                (pill-picking []
                  (set! (.-innerHTML pill)
                        (str spark
                             "<span style=\"font-weight:600;\">Pick an element</span>"
                             "<span style=\"color:" (.-textFaint C) ";font-size:11px;\">Esc to stop</span>")))

                (style-pill [picking?]
                  ;; Active = violet border + glow on the whole dock capsule
                  ;; (the pill is just a segment of it, not its own panel).
                  (if picking?
                    (do (set! (.. dock -style -borderColor) (.-accentBorder C))
                        (set! (.. dock -style -boxShadow) (str "0 0 16px " (.-accentGlow C)))
                        (pill-picking))
                    (do (set! (.. dock -style -borderColor) (.-border C))
                        (set! (.. dock -style -boxShadow) "none")
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
                                  (str "position:fixed;bottom:68px;right:16px;z-index:2147483646;"
                                       "pointer-events:none;display:flex;align-items:center;gap:8px;"
                                       panel-bg "color:" (.-text C)
                                       ";padding:10px 16px;border-radius:12px;"
                                       "font:12.5px/1.4 " sans ";"
                                       "opacity:0;transform:translateY(4px);"
                                       "transition:opacity 180ms ease,transform 180ms ease;"))]
                    (set! (.-innerHTML toast)
                          (str spark (esc text)))
                    (js/requestAnimationFrame
                     (fn []
                       (set! (.. toast -style -opacity) "1")
                       (set! (.. toast -style -transform) "translateY(0)")))
                    (js/setTimeout
                     (fn []
                       (set! (.. toast -style -opacity) "0")
                       (js/setTimeout (fn [] (.remove toast)) 250))
                     2600)))

                (submit [msg mode]
                  (when-let [el (.-selected state)]
                    (.push js/window.__xiDesignQueue (capture el msg (or mode "edit")))
                    (stop-picking)
                    (show-toast (if (= mode "choices")
                                  "Generating options \u2014 a sub-agent is on it"
                                  "Sent \u2014 a sub-agent is on it"))))

                (open-popover []
                  (let [el    (.-selected state)
                        pop   (.createElement doc "div")
                        r     (.getBoundingClientRect el)
                        vw    (.-innerWidth js/window)
                        vh    (.-innerHeight js/window)
                        w     360]
                    (set! (.-id pop) "__xi-design-pop")
                    ;; Reuse dialkit's panel chrome + tokens (.dial-panel /
                    ;; .dial-panel-head / .dial-panel-body / .dial-action, and
                    ;; the --bg-*/--fg-*/--accent/--radius-* vars). The root is
                    ;; .dialkit-root so the tokens resolve; we only override
                    ;; its fixed position + width for element anchoring.
                    (set! (.-className pop) "dialkit-root")
                    (set! (.. pop -style -cssText)
                          (str "position:fixed;z-index:2147483647;width:" w "px;"
                               "font-family:" sans ";"))
                    (set! (.-innerHTML pop)
                          (str
                           "<div class=\"dial-panel\">"
                           "<div class=\"dial-panel-head\" style=\"cursor:default;\">"
                           "<span class=\"dial-panel-title\" style=\"display:flex;align-items:center;gap:7px;font-size:13px;\">"
                           (svg-icon sparkles-path 15 "var(--accent)")
                           "<span>Describe the change</span></span></div>"
                           "<div class=\"dial-panel-body\" style=\"gap:10px;\">"
                           "<div style=\"font:11.5px/1.5 var(--font-mono);padding:7px 10px;"
                           "background:var(--bg-1);border-radius:var(--radius-sm);color:var(--fg-1);"
                           "word-break:break-all;max-height:56px;overflow:hidden;\">"
                           (esc (info el))
                           "<br/><span style=\"color:var(--fg-2);\">" (esc (selector el)) "</span></div>"
                           "<textarea id=\"__xi-design-msg\" placeholder=\"e.g. more padding, warmer background\u2026\""
                           " style=\"width:100%;height:64px;resize:none;background:var(--bg-1);"
                           "color:var(--fg-0);border:var(--border-1);border-radius:var(--radius-sm);"
                           "padding:9px 11px;font-size:13.5px;font-family:inherit;"
                           "line-height:1.45;outline:none;box-sizing:border-box;\"></textarea>"
                           "<div style=\"font-size:11px;color:var(--fg-2);\">"
                           "Enter to send \u00B7 Esc to re-pick</div>"
                           "<div style=\"display:flex;align-items:center;gap:8px;\">"
                           "<button id=\"__xi-design-choices\" class=\"dial-action\" "
                           "style=\"width:auto;padding:7px 14px;font-size:13px;font-weight:600;\">Choices</button>"
                           "<button id=\"__xi-design-send\" style=\"margin-left:auto;padding:7px 16px;"
                           "border-radius:var(--radius-sm);" btn-primary
                           "cursor:pointer;font-size:13px;font-weight:600;font-family:inherit;\">Send</button>"
                           "</div></div></div>"))
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
                                         (fn [] (submit (.trim (.-value msg-el)) "edit")))
                      (.addEventListener (.getElementById doc "__xi-design-choices") "click"
                                         (fn [] (submit (.trim (.-value msg-el)) "choices")))
                      (.addEventListener msg-el "keydown"
                                         (fn [e]
                                           (.stopPropagation e)
                                           (cond
                                             (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                                             (do (.preventDefault e) (submit (.trim (.-value msg-el)) "edit"))

                                             (= (.-key e) "Escape")
                                             (do (.preventDefault e)
                                                 (close-popover)
                                                 (update-hl nil)
                                                 (show-toast "Re-pick \u2014 click another element"))))))))

                ;; ── Design-agents dock (spinner + list + per-agent commit) ──────

                (spinner-html [size]
                  (str "<span style=\"display:inline-block;width:" size "px;height:" size "px;"
                       "border:2px solid " (.-accentBg C) ";border-top-color:" (.-accentBright C)
                       ";border-radius:50%;animation:__xiDesignSpin 1s linear infinite;\"></span>"))

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
                                (str (if busy (spinner-html 13) spark)
                                     "<span style=\"font-weight:600;\">"
                                     (if busy "Working" "Agents") "</span>"
                                     "<span style=\"background:" (.-accentBg C) ";color:" (.-accentBright C)
                                     ";border-radius:999px;padding:1px 7px;font-size:10.5px;font-weight:600;\">" n "</span>")))))))

                (agent-row-html [a]
                  (let [status (.-status a)
                        kind   (.-kind a)
                        commit (.-commit a)
                        chs    (.-choices a)
                        n-ch   (if chs (.-length chs) 0)
                        right  (cond
                                 (= status "running")   (spinner-html 12)
                                 (= status "error")     (str "<span style=\"color:" (.-danger C) ";font-size:11.5px;\">error</span>")
                                 (= status "stopped")   (str "<span style=\"color:" (.-textFaint C) ";font-size:11.5px;\">stopped</span>")
                                 (= kind "choices")     (if (> n-ch 0)
                                                          (str "<button data-choices-id=\"" (esc (.-id a)) "\" "
                                                               "style=\"padding:4px 11px;border-radius:8px;border:1px solid oklch(1 0 0 / 0.12)"
                                                               ";background:transparent;color:" (.-textMuted C) ";cursor:pointer;"
                                                               "font-size:12px;font-weight:600;font-family:inherit;\">View " n-ch "</button>")
                                                          (str "<span style=\"color:" (.-textFaint C) ";font-size:11.5px;\">no options</span>"))
                                 (= commit "committed") check-ok
                                 (= commit "committing") (spinner-html 12)
                                 (= commit "error")     (str "<span style=\"color:" (.-danger C) ";font-size:11.5px;\">commit failed</span>")
                                 (= status "done")      (str "<button data-commit-id=\"" (esc (.-id a)) "\" "
                                                             "style=\"padding:5px 13px;border-radius:8px;" btn-primary
                                                             "cursor:pointer;"
                                                             "font-size:12px;font-weight:600;font-family:inherit;\">Commit</button>")
                                 :else "")
                        dot    (cond (= status "running") (.-success C)
                                     (= status "error")   (.-danger C)
                                     (= status "done")    (.-success C)
                                     :else (.-textFaint C))
                        dot-glow (if (= status "running")
                                   (str "box-shadow:0 0 8px " dot ";")
                                   "")]
                    (str "<div style=\"display:flex;align-items:center;gap:9px;padding:8px 2px;border-top:1px solid oklch(1 0 0 / 0.05);\">"
                         "<span style=\"width:7px;height:7px;border-radius:50%;flex:none;background:" dot ";" dot-glow "\"></span>"
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
                                               (request-commit (.getAttribute btn "data-commit-id")))))
                        (doseq [btn (js/Array.from (.querySelectorAll body "[data-choices-id]"))]
                          (.addEventListener btn "click"
                                             (fn [e]
                                               (.stopPropagation e)
                                               (open-choices-modal (.getAttribute btn "data-choices-id")))))))))

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

                ;; ── Choices dialog (preview cards → pick a direction) ───────────

                (request-pick [id index]
                  (when-not js/window.__xiDesignPickQueue
                    (set! js/window.__xiDesignPickQueue #js []))
                  (.push js/window.__xiDesignPickQueue #js {:id id :index index :ts (js/Date.now)})
                  (close-choices-modal)
                  (show-toast "Applying \u2014 a sub-agent is on it"))

                (close-choices-modal []
                  (when-let [m (.-choicesModal state)]
                    (.remove m)
                    (set! (.-choicesModal state) nil)
                    (set! (.-choicesId state) nil)))

                (choices-card-html [ch i]
                  (str "<div style=\"border:1px solid " (.-border C) ";border-radius:12px;overflow:hidden;"
                       "display:flex;flex-direction:column;background:" (.-surfaceMuted C) ";\">"
                       "<div style=\"height:200px;overflow:hidden;background:#fff;border-bottom:1px solid " (.-border C) ";\">"
                       "<iframe sandbox=\"\" style=\"width:100%;height:100%;border:none;pointer-events:none;\" "
                       "srcdoc=\"" (esc (or (.-html ch) "")) "\"></iframe></div>"
                       "<div style=\"padding:11px 13px;display:flex;flex-direction:column;gap:6px;\">"
                       "<div style=\"font-size:13.5px;font-weight:600;\">"
                       (esc (or (.-label ch) (str "Option " (inc i)))) "</div>"
                       (let [note (.-note ch)]
                         (if (and note (not= note ""))
                           (str "<div style=\"font-size:12px;color:" (.-textMuted C) ";line-height:1.4;\">"
                                (esc note) "</div>")
                           ""))
                       "<button data-pick-index=\"" i "\" style=\"margin-top:4px;padding:7px 14px;"
                       "border-radius:8px;" btn-primary "cursor:pointer;"
                       "font-size:13px;font-weight:600;font-family:inherit;\">Pick this</button>"
                       "</div></div>"))

                (open-choices-modal [id]
                  (close-choices-modal)
                  (let [arr (or js/window.__xiDesignAgents #js [])
                        a   (.find arr (fn [x] (= (.-id x) id)))
                        chs (and a (.-choices a))]
                    (when (and chs (> (.-length chs) 0))
                      (let [backdrop (.createElement doc "div")
                            panel    (.createElement doc "div")]
                        (set! (.-id backdrop) "__xi-design-choices-modal")
                        (set! (.. backdrop -style -cssText)
                              (str "position:fixed;inset:0;z-index:2147483647;background:oklch(0 0 0 / 0.6);"
                                   "-webkit-backdrop-filter:blur(4px);backdrop-filter:blur(4px);"
                                   "display:flex;align-items:center;justify-content:center;padding:24px;"
                                   "font-family:" sans ";"))
                        (set! (.. panel -style -cssText)
                              (str panel-bg "color:" (.-text C)
                                   ";border-radius:14px;padding:18px 20px;width:min(880px,100%);max-height:86vh;"
                                   "overflow:auto;"))
                        (set! (.-innerHTML panel)
                              (str "<div style=\"display:flex;align-items:center;gap:8px;margin-bottom:14px;\">"
                                   spark
                                   "<span style=\"font-size:16px;font-weight:600;\">Pick a direction</span>"
                                   "<button id=\"__xi-design-choices-close\" style=\"margin-left:auto;background:none;"
                                   "border:none;cursor:pointer;color:" (.-textMuted C) ";font-size:20px;line-height:1;"
                                   "font-family:inherit;\">\u00D7</button></div>"
                                   "<div style=\"display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px;\">"
                                   (.join (.map chs (fn [ch i] (choices-card-html ch i))) "")
                                   "</div>"))
                        (.appendChild backdrop panel)
                        (.appendChild root backdrop)
                        (set! (.-choicesModal state) backdrop)
                        (set! (.-choicesId state) id)
                        (.addEventListener backdrop "click"
                                           (fn [e] (when (= (.-target e) backdrop) (close-choices-modal))))
                        (.addEventListener (.getElementById doc "__xi-design-choices-close") "click"
                                           (fn [e] (.stopPropagation e) (close-choices-modal)))
                        (doseq [btn (js/Array.from (.querySelectorAll panel "[data-pick-index]"))]
                          (.addEventListener btn "click"
                                             (fn [e]
                                               (.stopPropagation e)
                                               (request-pick id (js/parseInt (.getAttribute btn "data-pick-index") 10)))))))))

                (close-agents-pop []
                  (when-let [p (.-agentsPop state)]
                    (.remove p)
                    (set! (.-agentsPop state) nil)
                    (set! (.-agentsListSig state) nil)))

                (open-agents-pop []
                  (let [pop (.createElement doc "div")]
                    (set! (.-id pop) "__xi-design-agents-pop")
                    (set! (.. pop -style -cssText)
                          (str "position:fixed;right:16px;bottom:68px;z-index:2147483647;width:340px;"
                               "max-height:60vh;overflow:auto;" panel-bg "color:" (.-text C)
                               ";border-radius:14px;padding:12px 14px;font-family:" sans ";"))
                    (set! (.-innerHTML pop)
                          (str "<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:4px;\">"
                               spark
                               "<span style=\"font-size:15px;font-weight:600;\">Design agents</span></div>"
                               "<div id=\"__xi-design-agents-list\"></div>"))
                    (.appendChild root pop)
                    (set! (.-agentsPop state) pop)
                    (render-agents-list)))

                (toggle-agents-pop []
                  (if (.-agentsPop state) (close-agents-pop) (open-agents-pop)))]

          ;; ── Event wiring ──────────────────────────────────────────────────

          (style-pill false)

          ;; Spinner keyframes (inline styles can't declare @keyframes) plus
          ;; the dialkit component CSS (.dial-* classes + resolved dark-theme
          ;; tokens) the popover reuses — supplied by the node side.
          (let [sheet (.createElement doc "style")]
            (set! (.-id sheet) "__xi-design-style")
            (set! (.-textContent sheet)
                  (str "@keyframes __xiDesignSpin{to{transform:rotate(360deg)}}\n"
                       (or (.-dialkitCss cfg) "")))
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
