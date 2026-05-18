(ns xi.highlight.grammar.ampl)

(def ampl
  [   {:pattern "\\s+" :token :text}
   {:pattern "#[^\\n]*" :token :comment}
   {:pattern "&quot;(\\\\.|[^\\\\&quot;])*&quot;" :token :string}
   {:pattern "'(\\\\.|[^\\\\'])*'" :token :string}
   {:pattern "\\b\\d+\\.\\d*([eE][+-]?\\d+)?\\b" :token :number}
   {:pattern "\\b\\d+[eE][+-]?\\d+\\b" :token :number}
   {:pattern "\\b\\d+\\b" :token :number}
   {:pattern "\\b(call|cd|close|commands|data|delete|display|drop|end|environ|exit|expand|include|load|model|objective|option|problem|purge|quit|redeclare|reload|remove|reset|restore|shell|show|solexpand|solution|solve|update|unload|xref|coeff|coef|cover|obj|interval|default|from|to|to_come|net_in|net_out|dimen|dimension|check|complements|write|function|pipe|format|if|then|else|in|while|repeat|for)\\b" :token :keyword}
   {:pattern "\\b(integer|binary|symbolic|ordered|circular|reversed|INOUT|IN|OUT|LOCAL)\\b" :token :keyword-type}
   {:pattern "\\b(set|param|var|arc|minimize|maximize|subject to|s\\.t\\.|subj to|node|table|suffix|read table|write table)\\b" :token :keyword-decl}
   {:pattern "\\b(abs|acos|acosh|alias|asin|asinh|atan|atan2|atanh|ceil|ctime|cos|exp|floor|log|log10|max|min|precision|round|sin|sinh|sqrt|tan|tanh|time|trunc|Beta|Cauchy|Exponential|Gamma|Irand224|Normal|Normal01|Poisson|Uniform|Uniform01|num|num0|ichar|char|length|substr|sprintf|match|sub|gsub|print|printf|next|nextw|prev|prevw|first|last|ord|ord0|card|arity|indexarity)\\b" :token :name-builtin}
   {:pattern "\\b(or|exists|forall|and|in|not|within|union|diff|difference|symdiff|inter|intersect|intersection|cross|setof|by|less|sum|prod|product|div|mod)\\b" :token :operator}
   {:pattern "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*" :token :text}
   {:pattern "\\+|\\-|\\*|/|\\*\\*|=|<=|>=|==|\\||\\^|<|>|\\!|\\.\\.|:=|\\&|\\!=|<<|>>" :token :operator}
   {:pattern "[(),;:\\[\\]{}.]" :token :punctuation}])
