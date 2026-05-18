(ns xi.highlight.grammar.openedge-abl)

(def openedge-abl
  [   {:pattern "//.*?$" :token :comment}
   {:pattern "\\s*&.*" :token :comment}
   {:pattern "0[xX][0-9a-fA-F]+[LlUu]*" :token :number}
   {:pattern "\"(~~|~[^~]|[^\"~])*\"" :token :string}
   {:pattern "'(~~|~[^~]|[^\"~])*'" :token :string}
   {:pattern "[0-9][0-9]*\\.[0-9]+([eE][0-9]+)?[fd]?" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\\s+" :token :text}
   {:pattern "[+*/=-]" :token :operator}
   {:pattern "[.:()]" :token :punctuation}
   {:pattern "." :token :name-var}])
