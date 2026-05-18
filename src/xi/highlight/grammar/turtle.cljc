(ns xi.highlight.grammar.turtle)

(def turtle
  [   {:pattern "\\s+" :token :text}
   {:pattern "(?<=\\s)a(?=\\s)" :token :keyword-type}
   {:pattern "(<[^<>\"{}|^`\\\\\\x00-\\x20]*>)" :token :name-var}
   {:pattern "#[^\\n]+" :token :comment}
   {:pattern "\\b(true|false)\\b" :token :string}
   {:pattern "[+\\-]?\\d*\\.\\d+" :token :number}
   {:pattern "[+\\-]?\\d*(:?\\.\\d+)?E[+\\-]?\\d+" :token :number}
   {:pattern "[+\\-]?\\d+" :token :number}
   {:pattern "[\\[\\](){}.;,:^]" :token :punctuation}])
