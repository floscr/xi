(ns xi.highlight.grammar.smalltalk)

(def smalltalk
  [   {:pattern "\\^|:=|_" :token :operator}
   {:pattern "[\\]({}.;!]" :token :text}
   {:pattern "^\"(\"\"|[^\"])*\"!" :token :keyword}
   {:pattern "^'(''|[^'])*'!" :token :keyword}
   {:pattern "! !$" :token :keyword}
   {:pattern "\\s+" :token :text}
   {:pattern "\"(\"\"|[^\"])*\"" :token :comment}])
