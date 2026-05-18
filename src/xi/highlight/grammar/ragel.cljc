(ns xi.highlight.grammar.ragel)

(def ragel
  [   {:pattern "=" :token :operator}
   {:pattern ";" :token :punctuation}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "'(\\\\\\\\|\\\\'|[^'])*'" :token :string}
   {:pattern "\\[(\\\\\\\\|\\\\\\]|[^\\]])*\\]" :token :string}
   {:pattern "/(?!\\*)(\\\\\\\\|\\\\/|[^/])*/" :token :string}
   {:pattern "\\s+" :token :text}
   {:pattern "\\#.*$" :token :comment}
   {:pattern "(access|action|alphtype)\\b" :token :keyword}
   {:pattern "(getkey|write|machine|include)\\b" :token :keyword}
   {:pattern "(any|ascii|extend|alpha|digit|alnum|lower|upper)\\b" :token :keyword}
   {:pattern "(xdigit|cntrl|graph|print|punct|space|zlen|empty)\\b" :token :keyword}
   {:pattern "0x[0-9A-Fa-f]+" :token :number}
   {:pattern "[+-]?[0-9]+" :token :number}
   {:pattern "[a-zA-Z_]\\w*" :token :name-var}
   {:pattern "," :token :operator}
   {:pattern "\\||&|--?" :token :operator}
   {:pattern "\\.|<:|:>>?" :token :operator}
   {:pattern ":" :token :operator}
   {:pattern "->" :token :operator}
   {:pattern "(>|\\$|%|<|@|<>)(/|eof\\b)" :token :operator}
   {:pattern "(>|\\$|%|<|@|<>)(!|err\\b)" :token :operator}
   {:pattern "(>|\\$|%|<|@|<>)(\\^|lerr\\b)" :token :operator}
   {:pattern "(>|\\$|%|<|@|<>)(~|to\\b)" :token :operator}
   {:pattern "(>|\\$|%|<|@|<>)(\\*|from\\b)" :token :operator}
   {:pattern ">|@|\\$|%" :token :operator}
   {:pattern "\\*|\\?|\\+|\\{[0-9]*,[0-9]*\\}" :token :operator}
   {:pattern "!|\\^" :token :operator}
   {:pattern "\\(|\\)" :token :operator}])
