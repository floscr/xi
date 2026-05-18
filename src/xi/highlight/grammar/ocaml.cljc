(ns xi.highlight.grammar.ocaml)

(def ocaml
  [   {:pattern "\\s+" :token :text}
   {:pattern "false|true|\\(\\)|\\[\\]" :token :name-builtin}
   {:pattern "\\b([A-Z][\\w\\']*)" :token :name-class}
   {:pattern "\\b(as|assert|begin|class|constraint|do|done|downto|else|end|exception|external|false|for|fun|function|functor|if|in|include|inherit|initializer|lazy|let|match|method|module|mutable|new|object|of|open|private|raise|rec|sig|struct|then|to|true|try|type|value|val|virtual|when|while|with)\\b" :token :keyword}
   {:pattern "(~|\\}|\\|]|\\||\\{<|\\{|`|_|]|\\[\\||\\[>|\\[<|\\[|\\?\\?|\\?|>\\}|>]|>|=|<-|<|;;|;|:>|:=|::|:|\\.\\.|\\.|->|-\\.|-|,|\\+|\\*|\\)|\\(|&&|&|#|!=)" :token :operator}
   {:pattern "([=<>@^|&+\\*/$%-]|[!?~])?[!$%&*+\\./:<=>?@^|~-]" :token :operator}
   {:pattern "\\b(and|asr|land|lor|lsl|lxor|mod|or)\\b" :token :operator}
   {:pattern "\\b(unit|int|float|bool|string|char|list|array)\\b" :token :keyword-type}
   {:pattern "[^\\W\\d][\\w']*" :token :text}
   {:pattern "-?\\d[\\d_]*(.[\\d_]*)?([eE][+\\-]?\\d[\\d_]*)" :token :number}
   {:pattern "0[xX][\\da-fA-F][\\da-fA-F_]*" :token :number}
   {:pattern "0[oO][0-7][0-7_]*" :token :number}
   {:pattern "0[bB][01][01_]*" :token :number}
   {:pattern "\\d[\\d_]*" :token :number}
   {:pattern "'(?:(\\\\[\\\\\\\"'ntbr ])|(\\\\[0-9]{3})|(\\\\x[0-9a-fA-F]{2}))'" :token :string-char}
   {:pattern "'.'" :token :string-char}
   {:pattern "'" :token :keyword}
   {:pattern "[~?][a-z][\\w\\']*:" :token :name-var}])
