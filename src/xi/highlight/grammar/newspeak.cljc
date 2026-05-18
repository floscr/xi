(ns xi.highlight.grammar.newspeak)

(def newspeak
  [   {:pattern "\\b(Newsqueak2)\\b" :token :keyword-decl}
   {:pattern "'[^']*'" :token :string}
   {:pattern "\\b(mixin|self|super|private|public|protected|nil|true|false)\\b" :token :keyword}
   {:pattern "<\\w+>" :token :comment}
   {:pattern "(\\d+\\.\\d*|\\.\\d+|\\d+[fF])[fF]?" :token :number}
   {:pattern "\\d+" :token :number}
   {:pattern ":\\w+" :token :name-var}
   {:pattern "\\w+:" :token :name-fn}
   {:pattern "\\w+" :token :name-var}
   {:pattern "\\(|\\)" :token :punctuation}
   {:pattern "\\[|\\]" :token :punctuation}
   {:pattern "\\{|\\}" :token :punctuation}
   {:pattern "(\\^|\\+|\\/|~|\\*|<|>|=|@|%|\\||&|\\?|!|,|-|:)" :token :operator}
   {:pattern "\\.|;" :token :punctuation}
   {:pattern "\\s+" :token :text}
   {:pattern "\"[^\"]*\"" :token :comment}
   {:pattern "\\$." :token :string}
   {:pattern "'[^']*'" :token :string}
   {:pattern "#'[^']*'" :token :string-symbol}
   {:pattern "#\\w+:?" :token :string-symbol}
   {:pattern "#(\\+|\\/|~|\\*|<|>|=|@|%|\\||&|\\?|!|,|-)+" :token :string-symbol}])
