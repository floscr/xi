(ns style-editor
  "Browser-side live style editor, compiled by squint → esbuild into a
   self-contained IIFE (resources/style-editor/style-editor.js) that xi injects
   into the MCP-controlled page via chrome-devtools-mcp's evaluate_script.

   The panel itself is clj-ui-framework's dialkit (`window.__uiDial`, required
   here as the `dial` namespace and bundled in by esbuild) — a leva-style
   floating value-tuning panel. Each style_editor control becomes one dial
   control (slider / color); onChange live-applies the tuned values to the
   target elements, and Apply / Cancel are dial `action` controls.

   Unlike the element picker (user-triggered), this is agent-driven: the model
   calls the `style_editor` tool with a list of controls, the user tunes the
   values, and the committed values are returned so the model can edit source.

   Each control targets its OWN element+property, so one panel can tune several
   elements at once. A control's element is `control.selector` (or the
   top-level default `selector` when the control omits one).

   Contract:
     input   window.__XI_STYLE_EDITOR_CFG__
               {:selector str?           default selector for controls
                :title    str
                :controls [{:property :label :type :min :max :step :unit
                            :selector str?} …]}
     output  window.__xiStyleEditorResult
               {:changes  [{:selector :property :css} …]
                :missing  [{:selector :property} …]   controls with no target
                :notFound bool                        no control resolved at all
                :refine str :applyToClass bool :classCandidates [.cls …]}
     cancel  window.__xiStyleEditorCancelled  true    (restores inline styles)

   NB (squint): no js->clj / clj->js — read the config object via JS interop."
  (:require [dial]))

;; Re-entrancy guard: a second injection while one is active is a no-op.
(when-not js/window.__xiStyleEditorActive
  (set! js/window.__xiStyleEditorActive true)
  (set! js/window.__xiStyleEditorResult nil)
  (set! js/window.__xiStyleEditorCancelled false)

  (let [cfg      (or js/window.__XI_STYLE_EDITOR_CFG__ #js {})
        doc      js/document
        sel      (.-selector cfg)
        controls (or (.-controls cfg) #js [])]

    (letfn [(pad2 [s] (if (= 1 (.-length s)) (str "0" s) s))
            (to-hex [n]
              (pad2 (.toString (js/Math.round (js/Math.max 0 (js/Math.min 255 n))) 16)))
            (parse-rgb [s]
              (let [m (and s (.match s #"rgba?\(([^)]+)\)"))]
                (if m
                  (let [p (.map (.split (aget m 1) ",")
                                (fn [x] (js/parseFloat (.trim x))))]
                    #js {:r (or (aget p 0) 0) :g (or (aget p 1) 0)
                         :b (or (aget p 2) 0)
                         :a (if (> (.-length p) 3) (aget p 3) 1)})
                  #js {:r 0 :g 0 :b 0 :a 1})))
            (rgba-str [c]
              (str "rgba(" (js/Math.round (.-r c)) ", " (js/Math.round (.-g c)) ", "
                   (js/Math.round (.-b c)) ", " (.-a c) ")"))

            (infer-type [prop declared]
              (cond
                (not-empty declared)                    declared
                (= prop "opacity")                      "opacity"
                (.includes (.toLowerCase prop) "color") "color"
                :else                                   "range"))

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
                  (recur (.-parentElement e) (inc depth)))))

            ;; The target's base (first) class, as a selector.
            (first-class [el]
              (let [cn (.trim (str (or (.-className el) "")))]
                (when (not-empty cn)
                  (let [tok (aget (.split cn #"\s+") 0)]
                    (when (not-empty tok) (str "." tok))))))]

      (let [meta      #js []          ; per-resolved-control metadata
            missing   #js []          ; controls whose selector didn't resolve
            config    #js {}          ; dial config object
            used-keys #js {}          ; key uniqueness
            sels      (.map controls (fn [c] (or (not-empty (.-selector c)) sel)))
            multi?    (> (.-size (js/Set. sels)) 1)]

        (letfn [(uniq-key [label]
                  (let [base (or (not-empty (.trim (.replace (str label) #"\." " "))) "value")]
                    (loop [k base i 2]
                      (if (aget used-keys k)
                        (recur (str base " " i) (inc i))
                        (do (aset used-keys k true) k)))))

                (add-control [c]
                  (let [csel (or (not-empty (.-selector c)) sel)
                        tgt  (and csel (.querySelector doc csel))]
                    (if-not tgt
                      (.push missing #js {:selector (or csel "") :property (.-property c)})
                      (let [cs   (js/getComputedStyle tgt)
                            prop (.-property c)
                            type (infer-type prop (.-type c))
                            ;; Tag the label with its selector when multiple
                            ;; elements are tuned, so each row is unambiguous.
                            label (str (or (.-label c) prop)
                                       (when multi? (str "  ·  " csel)))
                            k    (uniq-key label)
                            st   #js {:key k :property prop :type type
                                      :unit (or (.-unit c) "px") :css ""
                                      :target tgt :selector csel
                                      :classSel (first-class tgt)
                                      :extraOrig (js/Map.)
                                      :orig (aget (.-style tgt) prop)}]
                        (cond
                          (= type "color")
                          (let [c0 (parse-rgb (aget cs prop))]
                            (aset config k #js {:type "color" :label label
                                                :default (rgba-str c0)}))

                          (= type "opacity")
                          (let [init (js/parseFloat (aget cs "opacity"))
                                v    (js/Math.round (* (if (js/isNaN init) 1 init) 100))]
                            (aset config k #js [v 0 100 1]))

                          :else
                          (let [unit  (or (.-unit c) "px")
                                raw   (js/parseFloat (aget cs prop))
                                ;; getComputedStyle resolves line-height to px;
                                ;; convert back to the unitless ratio so an
                                ;; unitless control starts in range instead of
                                ;; later applying a px number as a unitless
                                ;; multiplier (which explodes the line box).
                                ratio (if (and (= prop "lineHeight") (= unit "")
                                               (not (js/isNaN raw)))
                                        (let [fs (js/parseFloat (aget cs "fontSize"))]
                                          (if (and (not (js/isNaN fs)) (> fs 0))
                                            (/ raw fs) raw))
                                        raw)
                                v0    (if (js/isNaN ratio) 0 ratio)
                                mn    (if (some? (.-min c)) (.-min c) 0)
                                mx    (if (some? (.-max c)) (.-max c) (js/Math.max 100 (js/Math.ceil v0)))
                                ;; Clamp into range so a stray computed value
                                ;; can never seed an out-of-range slider.
                                v     (js/Math.max mn (js/Math.min mx v0))
                                stp   (if (some? (.-step c)) (.-step c) 1)]
                            (aset config k #js [v mn mx stp])))
                        (.push meta st)))))]

          ;; ── Build one dial control per style_editor control ──────────────
          (loop [i 0]
            (when (< i (.-length controls))
              (add-control (aget controls i))
              (recur (inc i))))

          (if (= 0 (.-length meta))
            ;; Nothing resolved — report and bail without a panel.
            (do (set! js/window.__xiStyleEditorResult
                      #js {:changes #js [] :missing missing :notFound true
                           :refine "" :applyToClass false :classCandidates #js []})
                (set! js/window.__xiStyleEditorActive false))

            (let [scope-key  "Apply to shared class"
                  refine-key "Refine"
                  apply-key  "Apply"
                  cancel-key "Cancel"]
              ;; Trailing controls: scope toggle, refine text, action buttons.
              (aset config scope-key true)
              (aset config refine-key
                    #js {:type "text" :label "Refine (optional)"
                         :placeholder "Extra instructions for xi…"})
              (aset config apply-key  #js {:type "action" :label "✓ Apply"})
              (aset config cancel-key #js {:type "action" :label "✕ Cancel"})

              (letfn [(scope-on? [vals] (boolean (aget vals scope-key)))

                      ;; css value for control `st` given its dial value.
                      (css-of [st v]
                        (cond
                          (= (.-type st) "color")   (str v)
                          (= (.-type st) "opacity") (str (/ (js/parseFloat v) 100))
                          :else                     (str v (.-unit st))))

                      ;; Live-apply `css` to `st`'s target, and (when scope is on
                      ;; and the target carries a class) to every element sharing
                      ;; that class. Originals of extra elements are stashed so
                      ;; toggling scope off / Cancel can restore them.
                      (apply-css! [st css scope?]
                        (let [prop (.-property st)
                              tgt  (.-target st)
                              csel (.-classSel st)
                              m    (.-extraOrig st)
                              set  (if (and scope? csel)
                                     (js/Array.from (.querySelectorAll doc csel))
                                     #js [tgt])
                              keep (js/Set. set)]
                          (doseq [el set]
                            (when (and (not (identical? el tgt)) (not (.has m el)))
                              (.set m el (aget (.-style el) prop)))
                            (aset (.-style el) prop css))
                          (doseq [el (js/Array.from (.keys m))]
                            (when-not (.has keep el)
                              (aset (.-style el) prop (.get m el))
                              (.delete m el)))
                          (aset (.-style tgt) prop css)))

                      ;; Recompute + apply every control from a dial values map.
                      (apply-all! [vals]
                        (let [scope? (scope-on? vals)]
                          (doseq [st meta]
                            (let [css (css-of st (aget vals (.-key st)))]
                              (set! (.-css st) css)
                              (apply-css! st css scope?)))))

                      (cleanup! []
                        (when-let [d js/window.__xiStyleEditorDial] (.destroy d))
                        (set! js/window.__xiStyleEditorDial nil)
                        (set! js/window.__xiStyleEditorActive false))

                      (do-apply [vals]
                        (let [changes #js []
                              cands   #js []]
                          (doseq [st meta]
                            (.push changes #js {:selector (.-selector st)
                                                :property (.-property st)
                                                :css (.-css st)})
                            (class-candidates-into cands (.-target st)))
                          (set! js/window.__xiStyleEditorResult
                                #js {:changes changes :missing missing :notFound false
                                     :refine (.trim (str (or (aget vals refine-key) "")))
                                     :applyToClass (scope-on? vals)
                                     :classCandidates cands})
                          (cleanup!)))

                      (do-cancel []
                        (doseq [st meta]
                          (aset (.-style (.-target st)) (.-property st) (.-orig st))
                          (let [m (.-extraOrig st) prop (.-property st)]
                            (doseq [el (js/Array.from (.keys m))]
                              (aset (.-style el) prop (.get m el)))))
                        (cleanup!)
                        (set! js/window.__xiStyleEditorCancelled true))]

                (let [ctrl (js/window.__uiDial
                            (or (.-title cfg) "Style editor")
                            config
                            #js {:id "xi-style-editor"
                                 :persist false
                                 :position "top-right"
                                 :onChange (fn [vals] (apply-all! vals))
                                 :onAction (fn [name]
                                             (cond
                                               (= name apply-key)  (do-apply (.-values (or js/window.__xiStyleEditorDial ctrl)))
                                               (= name cancel-key) (do-cancel)))})]
                  (set! js/window.__xiStyleEditorDial ctrl)
                  ;; Seed the preview from the initial (computed) values.
                  (apply-all! (.-values ctrl)))))))))))
