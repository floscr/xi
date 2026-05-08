(ns xi.tui.markdown
  "Minimal markdown rendering for terminal output — code blocks, bold, headers."
  (:require [clojure.string :as str]
            [xi.tui.render :as render]))

(defn- render-line
  "Render a single markdown line with ANSI formatting."
  [line]
  (cond
    ;; Headers
    (str/starts-with? line "### ")
    (render/fg :bold (subs line 4))

    (str/starts-with? line "## ")
    (render/fg :bold (subs line 3))

    (str/starts-with? line "# ")
    (render/fg :bold (subs line 2))

    ;; List items with checkboxes
    (str/starts-with? line "- [x] ")
    (str (render/fg :success "✓") " " (subs line 6))

    (str/starts-with? line "- [ ] ")
    (str (render/fg :dim "○") " " (subs line 6))

    :else
    ;; Inline formatting: **bold**, `code`
    (-> line
        (str/replace #"\*\*([^*]+)\*\*" (fn [[_ text]] (render/fg :bold text)))
        (str/replace #"`([^`]+)`" (fn [[_ text]] (render/fg :accent text))))))

(defn render-md
  "Render markdown text with ANSI formatting for terminal display."
  [text]
  (let [lines (str/split text #"\n")
        in-code (atom false)
        result (atom [])]
    (doseq [line lines]
      (cond
        ;; Code block start/end
        (str/starts-with? line "```")
        (if @in-code
          (do (swap! result conj (render/fg :dim "───"))
              (reset! in-code false))
          (do (swap! result conj (render/fg :dim (str "─── " (subs line 3))))
              (reset! in-code true)))

        ;; Inside code block
        @in-code
        (swap! result conj (render/fg :dim (str "  " line)))

        ;; Normal line
        :else
        (swap! result conj (render-line line))))
    (str/join "\n" @result)))
