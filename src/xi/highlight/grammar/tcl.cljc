(ns xi.highlight.grammar.tcl)

(def tcl
  [   {:pattern "\\}" :token :keyword}
   {:pattern "(eq|ne|in|ni)\\b" :token :operator}
   {:pattern "!=|==|<<|>>|<=|>=|&&|\\|\\||\\*\\*|[-+~!*/%<>&^|?:]" :token :operator}
   {:pattern "\\s+" :token :text}
   {:pattern "0x[a-fA-F0-9]+" :token :number}
   {:pattern "0[0-7]+" :token :number}
   {:pattern "\\d+\\.\\d+" :token :number}
   {:pattern "\\d+" :token :number}
   {:pattern "\\$([\\w.:-]+)" :token :name-var}
   {:pattern "([\\w.:-]+)" :token :text}])
