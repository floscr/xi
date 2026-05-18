(ns xi.highlight.grammar.dart)

(def dart
  [   {:pattern "#!(.*?)$" :token :comment}
   {:pattern "\\b(library|source|part of|part)\\b" :token :keyword}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "//[^\\n]*\\n?" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "\\b(assert|break|case|catch|continue|default|do|else|finally|for|if|in|is|new|return|super|switch|this|throw|try|while)\\b" :token :keyword}
   {:pattern "\\b(abstract|async|await|const|extends|factory|final|get|implements|native|operator|required|set|static|sync|typedef|var|with|yield)\\b" :token :keyword-decl}
   {:pattern "\\b(bool|double|dynamic|int|num|Object|String|void)\\b" :token :keyword-type}
   {:pattern "\\b(false|null|true)\\b" :token :keyword}
   {:pattern "[~!%^&*+=|?:<>/-]|as\\b" :token :operator}
   {:pattern "[a-zA-Z_$]\\w*:" :token :name-var}
   {:pattern "[a-zA-Z_$]\\w*" :token :text}
   {:pattern "[(){}\\[\\],.;]" :token :punctuation}
   {:pattern "0[xX][0-9a-fA-F]+" :token :number}
   {:pattern "\\d+(\\.\\d*)?([eE][+-]?\\d+)?" :token :number}
   {:pattern "\\.\\d+([eE][+-]?\\d+)?" :token :number}
   {:pattern "\\n" :token :text}
   {:pattern "r\"\"\"([\\w\\W]*?)\"\"\"" :token :string}
   {:pattern "r'''([\\w\\W]*?)'''" :token :string}
   {:pattern "r\"(.*?)\"" :token :string}
   {:pattern "r'(.*?)'" :token :string}])
