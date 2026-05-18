(ns xi.highlight.grammar.rexx)

(def rexx
  [   {:pattern "\\s" :token :text}
   {:pattern "[0-9]+(\\.[0-9]+)?(e[+-]?[0-9])?" :token :number}
   {:pattern "[a-z_]\\w*" :token :text}
   {:pattern "(address|arg|by|call|do|drop|else|end|exit|for|forever|if|interpret|iterate|leave|nop|numeric|off|on|options|parse|pull|push|queue|return|say|select|signal|to|then|trace|until|while)\\b" :token :keyword}
   {:pattern "(-|//|/|\\(|\\)|\\*\\*|\\*|\\\\<<|\\\\<|\\\\==|\\\\=|\\\\>>|\\\\>|\\\\|\\|\\||\\||&&|&|%|\\+|<<=|<<|<=|<>|<|==|=|><|>=|>>=|>>|>|¬<<|¬<|¬==|¬=|¬>>|¬>|¬|\\.|,)" :token :operator}])
