(ns xi.highlight.grammar.diff)

(def diff
  [   {:pattern " .*\\n" :token :text}
   {:pattern "\\d+(,\\d+)?(a|c|d)\\d+(,\\d+)?\\n" :token :keyword}
   {:pattern "---\\n" :token :keyword}
   {:pattern "< .*\\n" :token :operator}
   {:pattern "> .*\\n" :token :string}
   {:pattern "\\+.*\\n" :token :string}
   {:pattern "-.*\\n" :token :operator}
   {:pattern "!.*\\n" :token :keyword}
   {:pattern "@.*\\n" :token :keyword}
   {:pattern "([Ii]ndex|diff).*\\n" :token :keyword}
   {:pattern "=.*\\n" :token :keyword}
   {:pattern ".*\\n" :token :text}])
