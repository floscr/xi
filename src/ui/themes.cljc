(ns ui.themes
  "Built-in themes for ui.theme-model: well-known editor palettes as light +
   dark pairs, so the light/dark switch keeps working under each. An entry
   is theme parameters (`ui.theme-model/defaults` keys, the :light / :dark
   palettes) plus :id and :name; `params` fills the scales from its colors
   (`ui.theme-model/params-from-palette`)."
  (:require [ui.theme-model :as model]))

(def catalog
  [{:id :tokyo-night :name "Tokyo Night"
    :bg-light "#e1e2e7" :bg-dark "#1a1b26"
    :light {:sidebar "#d0d5e3" :fg "#3760bf" :fg-muted "#6172b0" :fg-subtle "#848cb5"
            :border "#c4c8da" :accent "#2e7de9"
            :success "#587539" :warning "#8c6c3e" :danger "#f52a65"
            :keyword "#9854f1" :string "#587539" :fn "#2e7de9" :number "#b15c00"
            :symbol "#007197" :comment "#848cb5"}
    :dark  {:sidebar "#16161e" :fg "#c0caf5" :fg-muted "#a9b1d6" :fg-subtle "#565f89"
            :border "#292e42" :accent "#7aa2f7"
            :success "#9ece6a" :warning "#e0af68" :danger "#f7768e"
            :keyword "#bb9af7" :string "#9ece6a" :fn "#7aa2f7" :number "#ff9e64"
            :symbol "#7dcfff" :comment "#565f89"}}

   ;; Latte / Mocha
   {:id :catppuccin :name "Catppuccin"
    :bg-light "#eff1f5" :bg-dark "#1e1e2e"
    :light {:sidebar "#e6e9ef" :fg "#4c4f69" :fg-muted "#6c6f85" :fg-subtle "#8c8fa1"
            :border "#ccd0da" :accent "#8839ef"
            :success "#40a02b" :warning "#df8e1d" :danger "#d20f39"
            :keyword "#8839ef" :string "#40a02b" :fn "#1e66f5" :number "#fe640b"
            :symbol "#179299" :comment "#7c7f93"}
    :dark  {:sidebar "#181825" :fg "#cdd6f4" :fg-muted "#a6adc8" :fg-subtle "#7f849c"
            :border "#45475a" :accent "#cba6f7"
            :success "#a6e3a1" :warning "#f9e2af" :danger "#f38ba8"
            :keyword "#cba6f7" :string "#a6e3a1" :fn "#89b4fa" :number "#fab387"
            :symbol "#94e2d5" :comment "#9399b2"}}

   {:id :gruvbox :name "Gruvbox"
    :bg-light "#fbf1c7" :bg-dark "#282828"
    :light {:sidebar "#f2e5bc" :fg "#3c3836" :fg-muted "#665c54" :fg-subtle "#928374"
            :border "#d5c4a1" :accent "#b57614"
            :success "#79740e" :warning "#b57614" :danger "#9d0006"
            :keyword "#9d0006" :string "#79740e" :fn "#427b58" :number "#8f3f71"
            :symbol "#076678" :comment "#928374"}
    :dark  {:sidebar "#1d2021" :fg "#ebdbb2" :fg-muted "#bdae93" :fg-subtle "#928374"
            :border "#504945" :accent "#fabd2f"
            :success "#b8bb26" :warning "#fabd2f" :danger "#fb4934"
            :keyword "#fb4934" :string "#b8bb26" :fn "#8ec07c" :number "#d3869b"
            :symbol "#83a598" :comment "#928374"}}

   ;; Snow Storm / Polar Night; Nord's frost and aurora darkened for the
   ;; light page, where the originals lack contrast
   {:id :nord :name "Nord"
    :bg-light "#eceff4" :bg-dark "#2e3440"
    :light {:sidebar "#e5e9f0" :fg "#2e3440" :fg-muted "#4c566a" :fg-subtle "#7b88a1"
            :border "#d8dee9" :accent "#5e81ac"
            :success "#5f7d43" :warning "#a07a2c" :danger "#bf616a"
            :keyword "#5e81ac" :string "#5f7d43" :fn "#3b7d8c" :number "#8c5d86"
            :symbol "#4c8a89" :comment "#7b88a1"}
    :dark  {:sidebar "#272c36" :fg "#eceff4" :fg-muted "#d8dee9" :fg-subtle "#7b88a1"
            :border "#434c5e" :accent "#88c0d0"
            :success "#a3be8c" :warning "#ebcb8b" :danger "#bf616a"
            :keyword "#81a1c1" :string "#a3be8c" :fn "#88c0d0" :number "#b48ead"
            :symbol "#8fbcbb" :comment "#616e88"}}

   ;; Dawn / Main
   {:id :rose-pine :name "Rosé Pine"
    :bg-light "#faf4ed" :bg-dark "#191724"
    :light {:sidebar "#f2e9e1" :fg "#575279" :fg-muted "#797593" :fg-subtle "#9893a5"
            :border "#dfdad9" :accent "#907aa9"
            :success "#286983" :warning "#ea9d34" :danger "#b4637a"
            :keyword "#286983" :string "#ea9d34" :fn "#d7827e" :number "#907aa9"
            :symbol "#56949f" :comment "#9893a5"}
    :dark  {:sidebar "#1f1d2e" :fg "#e0def4" :fg-muted "#908caa" :fg-subtle "#6e6a86"
            :border "#403d52" :accent "#c4a7e7"
            :success "#9ccfd8" :warning "#f6c177" :danger "#eb6f92"
            :keyword "#31748f" :string "#f6c177" :fn "#ebbcba" :number "#c4a7e7"
            :symbol "#9ccfd8" :comment "#6e6a86"}}

   ;; 鼠 (nezumi), Edo-period mouse grays: 柳鼠 yanagi nezumi (willow grey)
   ;; is the dark ink and, dimmed, the light page; 錆鼠 sabi nezu (rust grey) the
   ;; light ink and the dark lines, on a deep page in its hue. A lime
   ;; accent (deepened on the light page to stay readable); 錆朱 rust, 黄土
   ;; ochre, 藍 indigo and other muted traditional colors for code. Soft
   ;; contrast, set in a monospace.
   {:id :nezumi :name "Nezumi"
    :bg-light "#b2bba9" :bg-dark "#1f292b"
    :font-body "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    :font-mono "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    :light {:sidebar "#a7b09e" :fg "#223034" :fg-muted "#3a4b4f" :fg-subtle "#5c6c65"
            :border "#8d9885" :accent "#426400" :bubble "#2e5b46"
            :success "#4a642f" :warning "#7b5101" :danger "#963634"
            :keyword "#884627" :string "#4a642f" :fn "#335578" :number "#6f571e"
            :symbol "#824458" :comment "#607069"}
    :dark  {:sidebar "#192224" :fg "#c8d5bb" :fg-muted "#a3b2a0" :fg-subtle "#7a8b86"
            :border "#47585c" :accent "#c3f187" :bubble "#2e5b46"
            :success "#b3c58f" :warning "#e0b48a" :danger "#e29c9c"
            :keyword "#e3a88d" :string "#b3c58f" :fn "#a3beda" :number "#dcc188"
            :symbol "#d6a6a4" :comment "#8a9b94"}}])

(def by-id (into {} (map (juxt :id identity)) catalog))

(defn params
  "The theme parameters of built-in `id`, scales filled from its colors; nil
   for an unknown id."
  [id]
  (some-> (by-id id) (dissoc :id :name) model/params-from-palette))

(defn theme-name [id] (:name (by-id id)))
