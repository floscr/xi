(ns xi.highlight.grammar.nginx-configuration-file)

(def nginx-configuration-file
  [   {:pattern "#.*\\n" :token :comment}
   {:pattern "on|off" :token :name-var}
   {:pattern "\\$[^\\s;#()]+" :token :name-var}
   {:pattern "(\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b)" :token :text}
   {:pattern "[a-z-]+/[a-z-+]+" :token :string}
   {:pattern "[0-9]+[km]?\\b" :token :number}
   {:pattern "[:=~]" :token :punctuation}
   {:pattern "[^\\s;#{}$]+" :token :string}
   {:pattern "/[^\\s;#]*" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "[$;]" :token :text}])
