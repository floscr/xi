(ns xi.highlight.grammar.nasm)

(def nasm
  [   {:pattern "[a-z$._?][\\w$.?#@~]*:" :token :name-var}
   {:pattern "[\\r\\n]+" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "[ \\t]+" :token :text}
   {:pattern ";.*" :token :comment}])
