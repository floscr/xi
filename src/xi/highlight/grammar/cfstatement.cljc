(ns xi.highlight.grammar.cfstatement)

(def cfstatement
  [   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/\\*(?:.|\\n)*?\\*/" :token :comment}
   {:pattern "\\+\\+|--" :token :operator}
   {:pattern "[-+*/^&=!]" :token :operator}
   {:pattern "<=|>=|<|>|==" :token :operator}
   {:pattern "mod\\b" :token :operator}
   {:pattern "(eq|lt|gt|lte|gte|not|is|and|or)\\b" :token :operator}
   {:pattern "\\|\\||&&" :token :operator}
   {:pattern "\\?" :token :operator}
   {:pattern "'.*?'" :token :string}
   {:pattern "\\d+" :token :number}
   {:pattern "(if|else|len|var|xml|default|break|switch|component|property|function|do|try|catch|in|continue|for|return|while|required|any|array|binary|boolean|component|date|guid|numeric|query|string|struct|uuid|case)\\b" :token :keyword}
   {:pattern "(true|false|null)\\b" :token :keyword}
   {:pattern "(application|session|client|cookie|super|this|variables|arguments)\\b" :token :name-var}
   {:pattern "[a-z_$][\\w.]*" :token :name-var}
   {:pattern "[()\\[\\]{};:,.\\\\]" :token :punctuation}
   {:pattern "\\s+" :token :text}])
