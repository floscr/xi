(ns xi.highlight.grammar.qml)

(def qml
  [   {:pattern "[})\\].]" :token :punctuation}
   {:pattern "(abstract|boolean|byte|char|class|const|debugger|double|enum|export|extends|final|float|goto|implements|import|int|interface|long|native|package|private|protected|public|short|static|super|synchronized|throws|transient|volatile)\\b" :token :keyword}
   {:pattern "(true|false|null|NaN|Infinity|undefined)\\b" :token :keyword}
   {:pattern "(Array|Boolean|Date|Error|Function|Math|netscape|Number|Object|Packages|RegExp|String|sun|decodeURI|decodeURIComponent|encodeURI|encodeURIComponent|Error|eval|isFinite|isNaN|parseFloat|parseInt|document|this|window)\\b" :token :name-builtin}
   {:pattern "[$a-zA-Z_]\\w*" :token :name-var}
   {:pattern "[0-9][0-9]*\\.[0-9]+([eE][0-9]+)?[fd]?" :token :number}
   {:pattern "0x[0-9a-fA-F]+" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "'(\\\\\\\\|\\\\'|[^'])*'" :token :string}
   {:pattern "\\s+" :token :text}
   {:pattern "<!--" :token :comment}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}])
