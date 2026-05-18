(ns xi.highlight.grammar.cfengine3)

(def cfengine3
  [   {:pattern "#.*?\\n" :token :comment}
   {:pattern "^@.*?\\n" :token :comment}
   {:pattern "@[{(][^)}]+[})]" :token :name-var}
   {:pattern "\\$[(][^)]+[)]" :token :name-var}
   {:pattern "[(){},;]" :token :punctuation}
   {:pattern "=>" :token :operator}
   {:pattern "->" :token :operator}
   {:pattern "\\d+\\.\\d+" :token :number}
   {:pattern "\\d+" :token :number}
   {:pattern "\\w+" :token :name-fn}
   {:pattern "\\s+" :token :text}])
