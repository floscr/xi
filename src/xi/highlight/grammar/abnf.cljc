(ns xi.highlight.grammar.abnf)

(def abnf
  [   {:pattern ";.*$" :token :comment}
   {:pattern "(%[si])?\"[^\"]*\"" :token :string}
   {:pattern "%b[01]+\\-[01]+\\b" :token :string}
   {:pattern "%b[01]+(\\.[01]+)*\\b" :token :string}
   {:pattern "%d[0-9]+\\-[0-9]+\\b" :token :string}
   {:pattern "%d[0-9]+(\\.[0-9]+)*\\b" :token :string}
   {:pattern "%x[0-9a-fA-F]+\\-[0-9a-fA-F]+\\b" :token :string}
   {:pattern "%x[0-9a-fA-F]+(\\.[0-9a-fA-F]+)*\\b" :token :string}
   {:pattern "\\b[0-9]+\\*[0-9]+" :token :operator}
   {:pattern "\\b[0-9]+\\*" :token :operator}
   {:pattern "\\b[0-9]+" :token :operator}
   {:pattern "\\*" :token :operator}
   {:pattern "(HEXDIG|DQUOTE|DIGIT|VCHAR|OCTET|ALPHA|CHAR|CRLF|HTAB|LWSP|BIT|CTL|WSP|LF|SP|CR)\\b" :token :keyword}
   {:pattern "[a-zA-Z][a-zA-Z0-9-]+\\b" :token :name-class}
   {:pattern "(=/|=|/)" :token :operator}
   {:pattern "[\\[\\]()]" :token :punctuation}
   {:pattern "\\s+" :token :text}
   {:pattern "." :token :text}])
