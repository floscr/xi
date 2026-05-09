(ns xi.tui.markdown
  "Markdown component — renders markdown text with ANSI formatting.
   Implements the component protocol (render/invalidate)."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]))

(defn- render-inline
  "Apply inline markdown formatting (bold, code)."
  [line]
  (-> line
      (str/replace #"\*\*([^*]+)\*\*" (fn [[_ text]] (ansi/fg :bold text)))
      (str/replace #"`([^`]+)`" (fn [[_ text]] (ansi/fg :accent text)))))

(defn- render-md-line
  "Render a single markdown line with ANSI formatting."
  [line]
  (cond
    (str/starts-with? line "### ") (ansi/fg :bold (subs line 4))
    (str/starts-with? line "## ")  (ansi/fg :bold (subs line 3))
    (str/starts-with? line "# ")   (ansi/fg :bold (subs line 2))
    (str/starts-with? line "- [x] ") (str (ansi/fg :success "✓") " " (render-inline (subs line 6)))
    (str/starts-with? line "- [ ] ") (str (ansi/fg :dim "○") " " (render-inline (subs line 6)))
    :else (render-inline line)))

(def ^:private code-bg "\033[48;2;67;76;94m")
(def ^:private code-fg "\033[38;2;255;255;255m")

(defn- render-md-text
  "Render markdown text to ANSI-formatted lines.
   Returns vectors of {:text s :code? bool} maps."
  [text width]
  (let [raw-lines (str/split-lines text)
        in-code (atom false)
        result (atom [])]
    (doseq [line raw-lines]
      (cond
        (str/starts-with? line "```")
        (if @in-code
          (do (swap! result conj {:text "" :code? true})
              (reset! in-code false))
          (do (swap! result conj {:text "" :code? true})
              (reset! in-code true)))

        @in-code
        (swap! result conj {:text (str "  " line) :code? true})

        :else
        ;; Wrap long lines
        (let [formatted (render-md-line line)
              wrapped (ansi/wrap-text formatted width)]
          (doseq [w wrapped]
            (swap! result conj {:text w :code? false})))))
    @result))

(defn make-markdown
  "Create a markdown component."
  ([text] (make-markdown text {}))
  ([text opts]
   (let [state (atom {:text text
                      :cached-text nil
                      :cached-width nil
                      :cached-lines nil})
         padding-x (or (:padding-x opts) 0)]
     {:type :markdown
      :set-text (fn [t]
                  (swap! state assoc :text t :cached-text nil :cached-width nil :cached-lines nil)
                  (tui/request-render!))
      :get-text (fn [] (:text @state))
      :invalidate (fn [] (swap! state assoc :cached-text nil :cached-width nil :cached-lines nil))
      :render (fn [width]
                (let [{:keys [text cached-text cached-width cached-lines]} @state]
                  (if (and cached-lines (= cached-text text) (= cached-width width))
                    cached-lines
                    (let [content-w (max 1 (- width (* 2 padding-x)))
                          left-pad (apply str (repeat padding-x " "))
                          entries (if (or (nil? text) (empty? text))
                                    []
                                    (render-md-text text content-w))
                          lines (mapv (fn [{:keys [text code?]}]
                                        (let [padded (str left-pad text)]
                                          (if code?
                                            (ansi/apply-bg-to-line
                                             (str code-fg padded) width code-bg)
                                            padded)))
                                      entries)
                          result (if (empty? lines) [""] lines)]
                      (swap! state assoc :cached-text text :cached-width width :cached-lines result)
                      result))))})))
