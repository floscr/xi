(ns xi.highlight.grammar.hexdump)

(def hexdump
  [   {:pattern "\\n" :token :text}
   {:pattern "[0-9A-Ha-h]{2}" :token :number}
   {:pattern "\\s" :token :text}
   {:pattern "^\\*" :token :punctuation}
   {:pattern "^[0-9A-Ha-h]+" :token :name-var}])
