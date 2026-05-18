(ns xi.highlight.grammar.cplusplus)

(def cplusplus
  [   {:pattern "__(multiple_inheritance|virtual_inheritance|single_inheritance|interface|uuidof|super|event)\\b" :token :keyword}
   {:pattern "__(offload|blockingoffload|outer)\\b" :token :keyword}
   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "//(\\n|[\\w\\W]*?[^\\\\]\\n)" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*" :token :comment}])
