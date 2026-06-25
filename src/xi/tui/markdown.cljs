(ns xi.tui.markdown
  "Markdown component — renders markdown text with ANSI formatting.
   Implements the component protocol (render/invalidate)."
  (:require [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.markdown.ansi :as md-ansi]))

(def ^:private code-bg "\033[48;2;38;44;55m")

(defn- render-md-text
  "Render markdown text to ANSI-formatted lines.
   Returns vectors of {:text s :code? bool} maps."
  [text width]
  (or (md-ansi/render text width)
      []))

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
                                            (ansi/apply-bg-to-line padded width code-bg)
                                            padded)))
                                      entries)
                          result (if (empty? lines) [""] lines)]
                      (swap! state assoc :cached-text text :cached-width width :cached-lines result)
                      result))))})))
