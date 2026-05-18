(ns xi.highlight.grammar.makefile)

(def makefile
  [   {:pattern "\\$[<@$+%?|*]" :token :keyword}
   {:pattern "\\s+" :token :text}
   {:pattern "#.*?\\n" :token :comment}
   {:pattern "export\\s+" :token :keyword}])
