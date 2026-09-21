(ns style-editor
  "Browser-side live style editor, compiled by squint → esbuild into a
   self-contained IIFE (resources/style-editor/style-editor.js) that xi injects
   into the MCP-controlled page via chrome-devtools-mcp's evaluate_script.

   Unlike the element picker (user-triggered), this is driven by the agent: the
   model calls the `style_editor` tool with a list of controls, the user
   visually tunes the values on a single floating panel, and the committed
   values are returned to the model so it can edit the source.

   Each control targets its OWN element+property, so one panel can tune several
   elements at once. A control's element is `control.selector` (or the
   top-level default `selector` when the control omits one).

   Contract:
     input   window.__XI_STYLE_EDITOR_CFG__
               {:selector str?           default selector for controls
                :title    str
                :controls [{:property :label :type :min :max :step :unit
                            :selector str?} …]
                :colors   {…}}
     output  window.__xiStyleEditorResult
               {:changes  [{:selector :property :css} …]
                :missing  [{:selector :property} …]   controls with no target
                :notFound bool                        no control resolved at all
                :refine str :applyToClass bool :classCandidates [.cls …]}
     cancel  window.__xiStyleEditorCancelled  true    (restores inline styles)

   NB (squint): no js->clj / clj->js — read the config object via JS interop.
   Controls live-apply to their target's inline style on input; Cancel restores
   the original inline values; camelCase property names are expected."
  )

;; Re-entrancy guard: a second injection while one is active is a no-op.
(when-not js/window.__xiStyleEditorActive
  (set! js/window.__xiStyleEditorActive true)
  (set! js/window.__xiStyleEditorResult nil)
  (set! js/window.__xiStyleEditorCancelled false)

  (let [cfg  (or js/window.__XI_STYLE_EDITOR_CFG__ #js {})
        C    (or (.-colors cfg) #js {})
        doc  js/document
        root (.-documentElement doc)
        sel  (.-selector cfg)]

    (letfn [(pad2 [s] (if (= 1 (.-length s)) (str "0" s) s))

            (to-hex [n]
              (pad2 (.toString (js/Math.round (js/Math.max 0 (js/Math.min 255 n))) 16)))

            (parse-rgb [s]
              (let [m (and s (.match s #"rgba?\(([^)]+)\)"))]
                (if m
                  (let [p (.map (.split (aget m 1) ",")
                                (fn [x] (js/parseFloat (.trim x))))]
                    #js {:r (or (aget p 0) 0)
                         :g (or (aget p 1) 0)
                         :b (or (aget p 2) 0)
                         :a (if (> (.-length p) 3) (aget p 3) 1)})
                  #js {:r 0 :g 0 :b 0 :a 1})))

            (rgb->hex [c] (str "#" (to-hex (.-r c)) (to-hex (.-g c)) (to-hex (.-b c))))

            (hex->rgb [hex]
              (let [n (js/parseInt (.slice hex 1) 16)]
                #js {:r (js/Math.floor (/ n 65536))
                     :g (mod (js/Math.floor (/ n 256)) 256)
                     :b (mod n 256)}))

            (infer-type [prop declared]
              (cond
                (not-empty declared)                    declared
                (= prop "opacity")                      "opacity"
                (.includes (.toLowerCase prop) "color") "color"
                :else                                   "range"))

            (mk [tag css]
              (let [el (.createElement doc tag)]
                (when css (set! (.. el -style -cssText) css))
                el))

            ;; Merge class selectors from `el` up the tree into `out`
            ;; (closest first, deduped), so the agent can pick a shared class.
            (class-candidates-into [out el]
              (loop [e el depth 0]
                (when (and e (< depth 8))
                  (let [cn (.trim (str (or (.-className e) "")))]
                    (when (not-empty cn)
                      (doseq [tok (.split cn #"\s+")]
                        (when (and (not-empty tok) (< (.indexOf out (str "." tok)) 0))
                          (.push out (str "." tok))))))
                  (recur (.-parentElement e) (inc depth)))))]

      (let [controls (or (.-controls cfg) #js [])
            ctrls    #js []
            missing  #js []
            extras   #js {}
            panel    (mk "div"
                         (str "position:fixed;top:20px;right:20px;z-index:2147483647;"
                              "width:340px;background:" (.-bg C) ";color:" (.-text C)
                              ";border:1px solid " (.-border C) ";border-radius:12px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,0.5);"
                              "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;"
                              "font-size:13px;overflow:hidden;"))
            header   (mk "div"
                         (str "display:flex;align-items:center;gap:8px;padding:12px 16px;"
                              "cursor:move;background:" (.-bgLight C)
                              ";border-bottom:1px solid " (.-border C) ";user-select:none;"))
            bodyd    (mk "div"
                         "padding:14px 16px;display:flex;flex-direction:column;gap:16px;max-height:60vh;overflow-y:auto;")
            footer   (mk "div"
                         (str "display:flex;gap:8px;justify-content:flex-end;align-items:center;"
                              "padding:12px 16px;border-top:1px solid " (.-border C) ";"))
            sels     (.map controls (fn [c] (or (not-empty (.-selector c)) sel)))
            multi?   (> (.-size (js/Set. sels)) 1)]

        (set! (.-id panel) "__xi-style-editor")
        (set! (.-innerHTML header)
              (str "<span style=\"font-weight:600;color:" (.-accent C) "\">"
                   "\uD83C\uDFA8 " (or (.-title cfg) "Style editor") "</span>"
                   "<span style=\"margin-left:auto;font:11px/1.4 monospace;color:" (.-textDim C)
                   ";white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:150px;\">"
                   (if multi? "multiple elements" (or sel "")) "</span>"))

        (letfn [(scope-on? []
                  (let [s (.-scope extras)] (if s (.-checked s) false)))

                ;; The target's base (first) class, as a selector — the class
                ;; whose rule the agent will edit when scope is on.
                (first-class [el]
                  (let [cn (.trim (str (or (.-className el) "")))]
                    (when (not-empty cn)
                      (let [tok (aget (.split cn #"\s+") 0)]
                        (when (not-empty tok) (str "." tok))))))

                ;; Live-apply `css` to the control's target — and, when the
                ;; scope toggle is on and the target carries a class, to every
                ;; element sharing that class, so the preview reflects the
                ;; class-wide edit the agent will make. Originals of the extra
                ;; elements are stashed (extraOrig) so toggling scope off (or
                ;; Cancel) can restore them.
                (apply-css! [c css]
                  (let [prop (.-property c)
                        tgt  (.-target c)
                        csel (.-classSel c)
                        m    (.-extraOrig c)
                        set  (if (and (scope-on?) csel)
                               (js/Array.from (.querySelectorAll doc csel))
                               #js [tgt])
                        keep (js/Set. set)]
                    (doseq [el set]
                      (when (and (not (identical? el tgt)) (not (.has m el)))
                        (.set m el (aget (.-style el) prop)))
                      (aset (.-style el) prop css))
                    ;; Restore elements that fell out of the active set
                    ;; (scope was just turned off).
                    (doseq [el (js/Array.from (.keys m))]
                      (when-not (.has keep el)
                        (aset (.-style el) prop (.get m el))
                        (.delete m el)))
                    (aset (.-style tgt) prop css)))

                (apply! [c]
                  (let [t (.-type c) out (.-out c)]
                    (cond
                      (= t "color")
                      (let [rgb (hex->rgb (.-value (.-input c)))
                            a   (/ (js/parseFloat (.-value (.-alpha c))) 100)
                            css (str "rgba(" (.-r rgb) ", " (.-g rgb) ", " (.-b rgb) ", " a ")")]
                        (set! (.-css c) css)
                        (apply-css! c css)
                        (set! (.-textContent out) css))

                      (= t "opacity")
                      (let [pct (.-value (.-input c))
                            css (str (/ (js/parseFloat pct) 100))]
                        (set! (.-css c) css)
                        (apply-css! c css)
                        (set! (.-textContent out) (str pct "%")))

                      :else
                      (let [css (str (.-value (.-input c)) (.-unit c))]
                        (set! (.-css c) css)
                        (apply-css! c css)
                        (set! (.-textContent out) css)))))

                (add-control [c]
                  (let [csel (or (not-empty (.-selector c)) sel)
                        tgt  (and csel (.querySelector doc csel))]
                    (if-not tgt
                      (.push missing #js {:selector (or csel "") :property (.-property c)})
                      (let [cs   (js/getComputedStyle tgt)
                            prop (.-property c)
                            type (infer-type prop (.-type c))
                            row  (mk "div" "display:flex;flex-direction:column;gap:6px;")
                            top  (mk "div" "display:flex;align-items:center;justify-content:space-between;gap:8px;")
                            lbl  (mk "span" (str "color:" (.-textMuted C) ";"))
                            out  (mk "span" (str "font:11px/1.4 monospace;color:" (.-textDim C) ";"))
                            st   #js {:property prop :type type :out out
                                      :unit (or (.-unit c) "px") :css ""
                                      :target tgt :selector csel
                                      :classSel (first-class tgt)
                                      :extraOrig (js/Map.)
                                      :orig (aget (.-style tgt) prop)}]
                        (set! (.-textContent lbl) (or (.-label c) prop))
                        (.appendChild top lbl)
                        (.appendChild top out)
                        (.appendChild row top)
                        ;; When the panel targets several elements, tag each row
                        ;; with its selector so it's clear what it controls.
                        (when multi?
                          (let [tag (mk "span" (str "font:10px/1.3 monospace;color:" (.-textDim C)
                                                    ";word-break:break-all;opacity:0.85;"))]
                            (set! (.-textContent tag) csel)
                            (.appendChild row tag)))
                        (cond
                          (= type "color")
                          (let [wrap  (mk "div" "display:flex;align-items:center;gap:8px;")
                                c0    (parse-rgb (aget cs prop))
                                color (.createElement doc "input")
                                alpha (.createElement doc "input")]
                            (set! (.-type color) "color")
                            (set! (.-value color) (rgb->hex c0))
                            (set! (.. color -style -cssText)
                                  "width:36px;height:28px;padding:0;border:none;background:none;cursor:pointer;flex:none;")
                            (set! (.-type alpha) "range")
                            (set! (.-min alpha) "0")
                            (set! (.-max alpha) "100")
                            (set! (.-value alpha) (str (js/Math.round (* (.-a c0) 100))))
                            (set! (.. alpha -style -cssText) "flex:1;")
                            (.appendChild wrap color)
                            (.appendChild wrap alpha)
                            (.appendChild row wrap)
                            (set! (.-input st) color)
                            (set! (.-alpha st) alpha)
                            (.addEventListener color "input" (fn [] (apply! st)))
                            (.addEventListener alpha "input" (fn [] (apply! st))))

                          (= type "opacity")
                          (let [init (js/parseFloat (aget cs "opacity"))
                                inp  (.createElement doc "input")]
                            (set! (.-type inp) "range")
                            (set! (.-min inp) "0")
                            (set! (.-max inp) "100")
                            (set! (.-step inp) "1")
                            (set! (.-value inp) (str (js/Math.round (* (if (js/isNaN init) 1 init) 100))))
                            (set! (.. inp -style -cssText) "width:100%;")
                            (.appendChild row inp)
                            (set! (.-input st) inp)
                            (.addEventListener inp "input" (fn [] (apply! st))))

                          :else
                          (let [initv (js/parseFloat (aget cs prop))
                                v     (if (js/isNaN initv) 0 initv)
                                inp   (.createElement doc "input")]
                            (set! (.-type inp) "range")
                            (set! (.-min inp) (str (if (some? (.-min c)) (.-min c) 0)))
                            (set! (.-max inp) (str (if (some? (.-max c)) (.-max c) (js/Math.max 100 (js/Math.ceil v)))))
                            (set! (.-step inp) (str (if (some? (.-step c)) (.-step c) 1)))
                            (set! (.-value inp) (str v))
                            (set! (.. inp -style -cssText) "width:100%;")
                            (.appendChild row inp)
                            (set! (.-input st) inp)
                            (.addEventListener inp "input" (fn [] (apply! st)))))
                        (.push ctrls st)
                        (.appendChild bodyd row)
                        (apply! st)))))

                (cleanup-dom []
                  (when-let [el (.getElementById doc "__xi-style-editor")] (.remove el))
                  (set! js/window.__xiStyleEditorActive false))

                (do-apply []
                  (let [changes #js []
                        cands   #js []
                        refel   (.-refine extras)
                        scpel   (.-scope extras)]
                    (doseq [c ctrls]
                      (.push changes #js {:selector (.-selector c)
                                          :property (.-property c)
                                          :css (.-css c)})
                      (class-candidates-into cands (.-target c)))
                    (set! js/window.__xiStyleEditorResult
                          #js {:changes changes :missing missing :notFound false
                               :refine (if refel (.trim (.-value refel)) "")
                               :applyToClass (if scpel (.-checked scpel) false)
                               :classCandidates cands})
                    (cleanup-dom)))

                (do-cancel []
                  (doseq [c ctrls]
                    (aset (.-style (.-target c)) (.-property c) (.-orig c))
                    (let [m (.-extraOrig c) prop (.-property c)]
                      (doseq [el (js/Array.from (.keys m))]
                        (aset (.-style el) prop (.get m el)))))
                  (cleanup-dom)
                  (set! js/window.__xiStyleEditorCancelled true))]

          ;; ── Build controls ──────────────────────────────────────────────
          (loop [i 0]
            (when (< i (.-length controls))
              (add-control (aget controls i))
              (recur (inc i))))

          (if (= 0 (.-length ctrls))
            ;; Nothing resolved — report and bail without a panel.
            (do (set! js/window.__xiStyleEditorResult
                      #js {:changes #js [] :missing missing :notFound true
                           :refine "" :applyToClass false :classCandidates #js []})
                (set! js/window.__xiStyleEditorActive false))

            (do
              ;; ── Scope toggle + refine prompt ─────────────────────────────
              (let [divider  (mk "div" (str "border-top:1px dashed " (.-border C) ";"))
                    scoperow (mk "label" "display:flex;align-items:center;gap:8px;cursor:pointer;")
                    scope    (.createElement doc "input")
                    scopelbl (mk "span" (str "color:" (.-textMuted C) ";font-size:12px;line-height:1.3;"))
                    refwrap  (mk "div" "display:flex;flex-direction:column;gap:6px;")
                    reflbl   (mk "span" (str "color:" (.-textMuted C) ";font-size:12px;"))
                    refine   (.createElement doc "textarea")]
                (set! (.-type scope) "checkbox")
                (set! (.-checked scope) true)
                (set! (.. scope -style -cssText) "width:15px;height:15px;cursor:pointer;flex:none;margin:0;")
                (set! (.-textContent scopelbl)
                      "Apply to a shared class rule (find a common class, not just this one node)")
                (.appendChild scoperow scope)
                (.appendChild scoperow scopelbl)
                (set! (.-textContent reflbl) "Refine (optional)")
                (set! (.-placeholder refine) "Extra instructions for xi…")
                (set! (.. refine -style -cssText)
                      (str "width:100%;height:52px;resize:vertical;box-sizing:border-box;background:"
                           (.-bgLight C) ";color:" (.-text C) ";border:1px solid " (.-border C)
                           ";border-radius:6px;padding:8px;font-size:13px;font-family:inherit;outline:none;"))
                (.appendChild refwrap reflbl)
                (.appendChild refwrap refine)
                (set! (.-scope extras) scope)
                (set! (.-refine extras) refine)
                ;; Scope defaults on → switch every control's preview to
                ;; class-wide now (the initial per-control apply ran before this
                ;; toggle existed, so it only touched the single node). Toggling
                ;; re-applies, extending/restoring the class-wide preview.
                (.addEventListener scope "change" (fn [] (doseq [c ctrls] (apply! c))))
                (doseq [c ctrls] (apply! c))
                (.appendChild bodyd divider)
                (.appendChild bodyd scoperow)
                (.appendChild bodyd refwrap))

              ;; ── Footer buttons ───────────────────────────────────────────
              (let [hint    (mk "span" (str "font-size:11px;color:" (.-textDim C) ";margin-right:auto;"))
                    cancel  (mk "button"
                                (str "padding:7px 14px;border-radius:6px;border:1px solid " (.-border C)
                                     ";background:" (.-bgLight C) ";color:" (.-textMuted C)
                                     ";cursor:pointer;font-size:13px;"))
                    applyb  (mk "button"
                                (str "padding:7px 14px;border-radius:6px;border:none;background:" (.-primary C)
                                     ";color:white;cursor:pointer;font-size:13px;font-weight:600;"))]
                (set! (.-textContent hint) "⌘/Ctrl+Enter apply · Cancel to discard")
                (set! (.-textContent cancel) "Cancel")
                (set! (.-textContent applyb) "Apply")
                (.addEventListener cancel "click" do-cancel)
                (.addEventListener applyb "click" do-apply)
                (.appendChild footer hint)
                (.appendChild footer cancel)
                (.appendChild footer applyb))

              (.appendChild panel header)
              (.appendChild panel bodyd)
              (.appendChild panel footer)
              (.appendChild root panel)

              ;; ── Drag by header ───────────────────────────────────────────
              (let [drag #js {:on false :dx 0 :dy 0}]
                (.addEventListener header "mousedown"
                                   (fn [e]
                                     (set! (.-on drag) true)
                                     (let [r (.getBoundingClientRect panel)]
                                       (set! (.-dx drag) (- (.-clientX e) (.-left r)))
                                       (set! (.-dy drag) (- (.-clientY e) (.-top r))))))
                (.addEventListener doc "mousemove"
                                   (fn [e]
                                     (when (.-on drag)
                                       (set! (.. panel -style -left) (str (- (.-clientX e) (.-dx drag)) "px"))
                                       (set! (.. panel -style -top) (str (- (.-clientY e) (.-dy drag)) "px"))
                                       (set! (.. panel -style -right) "auto"))))
                (.addEventListener doc "mouseup" (fn [] (set! (.-on drag) false))))

              ;; ── Keyboard: Ctrl/Cmd+Enter apply (Esc intentionally ignored) ──
              (let [kd (fn kd [e]
                         (when (and (= (.-key e) "Enter") (or (.-ctrlKey e) (.-metaKey e)))
  (.preventDefault e)
  (.removeEventListener doc "keydown" kd true)
  (do-apply)))]
                (.addEventListener doc "keydown" kd true)))))))))
