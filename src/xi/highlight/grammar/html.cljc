(ns xi.highlight.grammar.html)

(def html
  [   {:pattern "[^<&]+" :token :text}
   {:pattern "&\\S*?;" :token :name-var}
   {:pattern "\\<\\!\\[CDATA\\[.*?\\]\\]\\>" :token :comment}
   {:pattern "<\\?.*?\\?>" :token :comment}
   {:pattern "<![^>]*>" :token :comment}])
