(ns xi.highlight.grammar.r)

(def r
  [   {:pattern "((?:`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`)|(?:(?:[a-zA-z]|[_.][^0-9])[\\w_.]*))\\s*(?=\\()" :token :name-fn}
   {:pattern "\\{|\\}" :token :punctuation}
   {:pattern "." :token :text}
   {:pattern "(if|else|for|while|repeat|in|next|break|return|switch|function)(?![\\w.])" :token :keyword}
   {:pattern "\\s+" :token :text}
   {:pattern "#.*$" :token :comment}
   {:pattern "(NULL|NA(_(integer|real|complex|character)_)?|letters|LETTERS|Inf|TRUE|FALSE|NaN|pi|\\.\\.(\\.|[0-9]+))(?![\\w.])" :token :keyword}
   {:pattern "(T|F)\\b" :token :name-builtin}
   {:pattern "(?:`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`)|(?:(?:[a-zA-z]|[_.][^0-9])[\\w_.]*)" :token :text}
   {:pattern "0[xX][a-fA-F0-9]+([pP][0-9]+)?[Li]?" :token :number}
   {:pattern "[+-]?([0-9]+(\\.[0-9]+)?|\\.[0-9]+|\\.)([eE][+-]?[0-9]+)?[Li]?" :token :number}
   {:pattern "\\[{1,2}|\\]{1,2}|\\(|\\)|;|," :token :punctuation}
   {:pattern "<<?-|->>?|-|==|<=|>=|<|>|&&?|!=|\\|\\|?|\\?" :token :operator}
   {:pattern "\\*|\\+|\\^|/|!|%[^%]*%|=|~|\\$|@|:{1,3}" :token :operator}])
