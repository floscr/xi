(ns xi.highlight.grammar.psl)

(def psl
  [   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "//.*$" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "\\+|-|\\*|\\/|\\b%\\b|<|>|=|'|\\band\\b|\\bor\\b|_|:|!" :token :operator}
   {:pattern "\\.?\\d+" :token :number}
   {:pattern "\\b(do|set|if|else|for|while|quit|catch|return|ret|while)\\b" :token :keyword}
   {:pattern "\\b(true|false)\\b" :token :keyword}
   {:pattern "\\b(public|req|private|void)\\b" :token :keyword-decl}
   {:pattern "\\b(Boolean|String|Number|Date)\\b" :token :keyword-type}
   {:pattern "\\.?(%|\\${0,2})[_a-zA-Z]\\w*" :token :text}])
