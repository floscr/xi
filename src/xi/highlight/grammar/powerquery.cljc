(ns xi.highlight.grammar.powerquery)

(def powerquery
  [   {:pattern "\\s+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "(and|as|each|else|error|false|if|in|is|let|meta|not|null|or|otherwise|section|shared|then|true|try|type)\\b" :token :keyword}
   {:pattern "(#binary|#date|#datetime|#datetimezone|#duration|#infinity|#nan|#sections|#shared|#table|#time)\\b" :token :keyword-type}
   {:pattern "(([a-zA-Z]|_)[\\w|._]*|#\"[^\"]+\")" :token :text}
   {:pattern "0[xX][0-9a-fA-F][0-9a-fA-F_]*[lL]?" :token :number}
   {:pattern "([0-9]+\\.[0-9]+|\\.[0-9]+)([eE][0-9]+)?" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "[\\(\\)\\[\\]\\{\\}]" :token :punctuation}
   {:pattern "\\.\\.|\\.\\.\\.|=>|<=|>=|<>|[@!?,;=<>\\+\\-\\*\\/&]" :token :operator}])
