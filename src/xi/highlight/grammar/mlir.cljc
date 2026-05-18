(ns xi.highlight.grammar.mlir)

(def mlir
  [   {:pattern "c?\"[^\"]*?\"" :token :string}
   {:pattern "\\^([-a-zA-Z$._][\\w\\-$.0-9]*)\\s*" :token :name-var}
   {:pattern "([\\w\\d_$.]+)\\s*=" :token :name-var}
   {:pattern "->" :token :punctuation}
   {:pattern "@([\\w_][\\w\\d_$.]*)" :token :name-fn}
   {:pattern "[%#][\\w\\d_$.]+" :token :name-var}
   {:pattern "([1-9?][\\d?]*\\s*x)+" :token :number}
   {:pattern "0[xX][a-fA-F0-9]+" :token :number}
   {:pattern "-?\\d+(?:[.]\\d+)?(?:[eE][-+]?\\d+(?:[.]\\d+)?)?" :token :number}
   {:pattern "[=<>{}\\[\\]()*.,!:]|x\\b" :token :punctuation}
   {:pattern "[\\w\\d]+" :token :text}
   {:pattern "(\\n|\\s)+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "(constant|return)" :token :keyword-type}
   {:pattern "(memref|tensor|vector|func|loc)" :token :keyword-type}
   {:pattern "bf16|f16|f32|f64|index" :token :keyword}
   {:pattern "i[1-9]\\d*" :token :keyword}])
