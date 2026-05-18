(ns xi.highlight.grammar.vhs)

(def vhs
  [   {:pattern "\\b(Set|Type|Left|Right|Up|Down|Backspace|Enter|Tab|Space|Ctrl|Sleep|Hide|Show|Escape)\\b" :token :keyword}
   {:pattern "\\b(FontFamily|FontSize|Framerate|Height|Width|Theme|Padding|TypingSpeed|PlaybackSpeed|LineHeight|Framerate|LetterSpacing)\\b" :token :name-builtin}
   {:pattern "#.*(\\S|$)" :token :comment}
   {:pattern "(@|\\+)" :token :punctuation}
   {:pattern "\\d+" :token :number}
   {:pattern "\\s+" :token :text}
   {:pattern "(ms|s)" :token :text}])
