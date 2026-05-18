(ns xi.highlight.grammar.coffeescript)

(def coffeescript
  [   {:pattern "[})\\].]" :token :punctuation}
   {:pattern "(?<![.$])(true|false|yes|no|on|off|null|NaN|Infinity|undefined)\\b" :token :keyword}
   {:pattern "(Array|Boolean|Date|Error|Function|Math|netscape|Number|Object|Packages|RegExp|String|sun|decodeURI|decodeURIComponent|encodeURI|encodeURIComponent|eval|isFinite|isNaN|parseFloat|parseInt|document|window)\\b" :token :name-builtin}
   {:pattern "@?[$a-zA-Z_][\\w$]*" :token :name-var}
   {:pattern "[0-9][0-9]*\\.[0-9]+([eE][0-9]+)?[fd]?" :token :number}
   {:pattern "0x[0-9a-fA-F]+" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\\s+" :token :text}
   {:pattern "###[^#].*?###" :token :comment}
   {:pattern "#(?!##[^#]).*?\\n" :token :comment}])
