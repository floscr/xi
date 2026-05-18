(ns xi.highlight.grammar.minizinc)

(def minizinc
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "\\%(.*?)\\n" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "\\b(annotation|constraint|predicate|minimize|function|maximize|satisfy|include|record|output|solve|test|list|type|ann|par|any|var|op|of)\\b" :token :keyword}
   {:pattern "\\b(string|tuple|float|array|bool|enum|int|set)\\b" :token :keyword-type}
   {:pattern "\\b(forall|where|endif|then|else|for|if)\\b" :token :keyword}
   {:pattern "\\b(array_intersect|index_set_2of3|index_set_1of3|index_set_3of3|index_set_1of2|index_set_2of2|array_union|show_float|dom_array|int2float|set2array|index_set|dom_size|lb_array|is_fixed|ub_array|bool2int|show_int|array4d|array2d|array1d|array5d|array6d|array3d|product|length|assert|concat|trace|acosh|round|abort|log10|floor|sinh|tanh|atan|sqrt|asin|show|log2|card|ceil|cosh|join|pow|cos|max|log|exp|dom|sin|abs|fix|sum|tan|min|lb|ln|ub)\\b" :token :name-builtin}
   {:pattern "(not|<->|->|<-|\\\\/|xor|/\\\\)" :token :operator}
   {:pattern "(<|>|<=|>=|==|=|!=)" :token :operator}
   {:pattern "(\\+|-|\\*|/|div|mod)" :token :operator}
   {:pattern "\\b(intersect|superset|symdiff|subset|union|diff|in)\\b" :token :operator}
   {:pattern "(\\\\|\\.\\.|\\+\\+)" :token :operator}
   {:pattern "[|()\\[\\]{},:;]" :token :punctuation}
   {:pattern "(true|false)\\b" :token :keyword}
   {:pattern "([+-]?)\\d+(\\.(?!\\.)\\d*)?([eE][-+]?\\d+)?" :token :number}
   {:pattern "::\\s*([^\\W\\d]\\w*)(\\s*\\([^\\)]*\\))?" :token :name-builtin}
   {:pattern "[^\\W\\d]\\w*" :token :name-var}])
