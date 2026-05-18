(ns xi.highlight.grammar.atl)

(def atl
  [   {:pattern "(and|distinct|endif|else|for|foreach|if|implies|in|let|not|or|self|super|then|thisModule|xor)\\b" :token :keyword}
   {:pattern "(OclUndefined|true|false|#\\w+)\\b" :token :keyword}
   {:pattern "(module|query|library|create|from|to|uses)\\b" :token :keyword}
   {:pattern "(Bag|Boolean|Integer|OrderedSet|Real|Sequence|Set|String|Tuple)" :token :keyword-type}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "0|[1-9][0-9]*" :token :number}
   {:pattern "[*<>+=/-]" :token :operator}
   {:pattern "([{}();:.,!|]|->)" :token :punctuation}
   {:pattern "\\n" :token :text}
   {:pattern "\\w+" :token :name-var}])
