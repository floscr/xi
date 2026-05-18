(ns xi.highlight.grammar.modelica)

(def modelica
  [   {:pattern "//[^\\n\\r]*" :token :comment}
   {:pattern "/[*].*?[*]/" :token :comment}
   {:pattern "\\s+" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "\\b(model|equation|end|extends|function|type|record|connector|parameter|constant|if|then|else|for|in|when|assert|outer|algorithm|flow|discrete|input|output|loop|elseif|return|public|protected|external|real|integer|boolean|string|array|complex|union|tuple|class|der|time|tstart|tstop)\\b" :token :keyword}
   {:pattern "\\b(boolean|integer|real|string|array|record|complex|union|tuple)\\b" :token :keyword-type}
   {:pattern "#[ \\t]*(if|else|endif|define|include|error)\\b" :token :comment}
   {:pattern "\\\\&quot;[^&quot;\\n]*\\\\&quot;" :token :string}
   {:pattern "'[^'\\n]'" :token :string-char}
   {:pattern "\\d+(\\.\\d+)?([eE][+-]?\\d+)?" :token :number}
   {:pattern "@[A-Za-z_]\\w*" :token :text}
   {:pattern "[A-Za-z_]\\w*" :token :text}
   {:pattern "[{}(),;=.+\\-*/&|!<>^%]" :token :punctuation}
   {:pattern "(\\+|\\-|\\*|\\/|\\^|\\&|\\||\\<|\\>|\\%|\\=|\\!=|\\<\\=|\\>\\=)" :token :operator}])
