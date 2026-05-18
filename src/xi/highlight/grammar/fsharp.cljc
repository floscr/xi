(ns xi.highlight.grammar.fsharp)

(def fsharp
  [   {:pattern "\\s+" :token :text}
   {:pattern "\\(\\)|\\[\\]" :token :name-builtin}
   {:pattern "\\b([A-Z][\\w\\']*)" :token :text}
   {:pattern "///.*?\\n" :token :string}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "\\b(abstract|as|assert|base|begin|class|default|delegate|do!|do|done|downcast|downto|elif|else|end|exception|extern|false|finally|for|function|fun|global|if|inherit|inline|interface|internal|in|lazy|let!|let|match|member|module|mutable|namespace|new|null|of|open|override|private|public|rec|return!|return|select|static|struct|then|to|true|try|type|upcast|use!|use|val|void|when|while|with|yield!|yield|atomic|break|checked|component|const|constraint|constructor|continue|eager|event|external|fixed|functor|include|method|mixin|object|parallel|process|protected|pure|sealed|tailcall|trait|virtual|volatile)\\b" :token :keyword}
   {:pattern "``([^`\\n\\r\\t]|`[^`\\n\\r\\t])+``" :token :text}
   {:pattern "#[ \\t]*(if|endif|else|line|nowarn|light|r|\\d+)\\b" :token :comment}
   {:pattern "(!=|#|&&|&|\\(|\\)|\\*|\\+|,|-\\.|->|-|\\.\\.|\\.|::|:=|:>|:|;;|;|<-|<\\]|<|>\\]|>|\\?\\?|\\?|\\[<|\\[\\||\\[|\\]|_|`|\\{|\\|\\]|\\||\\}|~|<@@|<@|=|@>|@@>)" :token :operator}
   {:pattern "([=<>@^|&+\\*/$%-]|[!?~])?[!$%&*+\\./:<=>?@^|~-]" :token :operator}
   {:pattern "\\b(and|or|not)\\b" :token :operator}
   {:pattern "\\b(sbyte|byte|char|nativeint|unativeint|float32|single|float|double|int8|uint8|int16|uint16|int32|uint32|int64|uint64|decimal|unit|bool|string|list|exn|obj|enum)\\b" :token :keyword-type}
   {:pattern "[^\\W\\d][\\w']*" :token :text}
   {:pattern "\\d[\\d_]*[uU]?[yslLnQRZINGmM]?" :token :number}
   {:pattern "0[xX][\\da-fA-F][\\da-fA-F_]*[uU]?[yslLn]?[fF]?" :token :number}
   {:pattern "0[oO][0-7][0-7_]*[uU]?[yslLn]?" :token :number}
   {:pattern "0[bB][01][01_]*[uU]?[yslLn]?" :token :number}
   {:pattern "-?\\d[\\d_]*(.[\\d_]*)?([eE][+\\-]?\\d[\\d_]*)[fFmM]?" :token :number}
   {:pattern "'(?:(\\\\[\\\\\\\"'ntbr ])|(\\\\[0-9]{3})|(\\\\x[0-9a-fA-F]{2}))'B?" :token :string-char}
   {:pattern "'.'" :token :string-char}
   {:pattern "'" :token :keyword}
   {:pattern "[~?][a-z][\\w\\']*:" :token :name-var}])
