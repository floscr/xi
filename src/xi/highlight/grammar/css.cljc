(ns xi.highlight.grammar.css)

(def css
  [   {:pattern "\\s+" :token :text}
   {:pattern "/\\*(?:.|\\n)*?\\*/" :token :comment}
   {:pattern "[\\w-]+" :token :keyword}
   {:pattern "[~^*!%&$\\[\\]()<>|+=@:;,./?-]" :token :operator}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "'(\\\\\\\\|\\\\'|[^'])*'" :token :string}])
