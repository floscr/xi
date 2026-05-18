(ns xi.highlight.grammar.cap-sharp39-n-proto)

(def cap-sharp39-n-proto
  [   {:pattern "#.*?$" :token :comment}
   {:pattern "@[0-9a-zA-Z]*" :token :name-builtin}
   {:pattern "(struct|enum|interface|union|import|using|const|annotation|extends|in|of|on|as|with|from|fixed)\\b" :token :keyword}
   {:pattern "[\\w.]+" :token :text}
   {:pattern "[^#@=:$\\w]+" :token :text}])
