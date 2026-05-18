(ns xi.highlight.grammar.actionscript-3)

(def actionscript-3
  [   {:pattern "\\s+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "/(\\\\\\\\|\\\\/|[^\\n])*/[gisx]*" :token :string}
   {:pattern "(case|default|for|each|in|while|do|break|return|continue|if|else|throw|try|catch|with|new|typeof|arguments|instanceof|this|switch|import|include|as|is)\\b" :token :keyword}
   {:pattern "(class|public|final|internal|native|override|private|protected|static|import|extends|implements|interface|intrinsic|return|super|dynamic|function|const|get|namespace|package|set)\\b" :token :keyword-decl}
   {:pattern "(true|false|null|NaN|Infinity|-Infinity|undefined|void)\\b" :token :keyword}
   {:pattern "(decodeURI|decodeURIComponent|encodeURI|escape|eval|isFinite|isNaN|isXMLName|clearInterval|fscommand|getTimer|getURL|getVersion|isFinite|parseFloat|parseInt|setInterval|trace|updateAfterEvent|unescape)\\b" :token :name-fn}
   {:pattern "[$a-zA-Z_]\\w*" :token :text}
   {:pattern "[0-9][0-9]*\\.[0-9]+([eE][0-9]+)?[fd]?" :token :number}
   {:pattern "0x[0-9a-f]+" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "'(\\\\\\\\|\\\\'|[^'])*'" :token :string}
   {:pattern "[~^*!%&<>|+=:;,/?\\\\{}\\[\\]().-]+" :token :operator}])
