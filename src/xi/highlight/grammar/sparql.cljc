(ns xi.highlight.grammar.sparql)

(def sparql
  [   {:pattern "\\s+" :token :text}
   {:pattern "(a)\\b" :token :keyword}
   {:pattern "(<(?:[^<>\"{}|^`\\\\\\x00-\\x20])*>)" :token :name-var}
   {:pattern "(_:[_\\p{L}\\p{N}](?:[-_.\\p{L}\\p{N}]*[-_\\p{L}\\p{N}])?)" :token :name-var}
   {:pattern "[?$][_\\p{L}\\p{N}]+" :token :name-var}
   {:pattern "(true|false)" :token :keyword}
   {:pattern "[+\\-]?(\\d+\\.\\d*[eE][+-]?\\d+|\\.?\\d+[eE][+-]?\\d+)" :token :number}
   {:pattern "[+\\-]?(\\d+\\.\\d*|\\.\\d+)" :token :number}
   {:pattern "[+\\-]?\\d+" :token :number}
   {:pattern "(\\|\\||&&|=|\\*|\\-|\\+|/|!=|<=|>=|!|<|>)" :token :operator}
   {:pattern "[(){}.;,:^\\[\\]]" :token :punctuation}
   {:pattern "#[^\\n]*" :token :comment}])
