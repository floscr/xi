(ns xi.highlight.grammar.microcad)

(def microcad
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "///.*" :token :string}
   {:pattern "//.*" :token :comment}
   {:pattern "\\b(pub|sketch|part|op|mod|use|fn|const|prop|init|return|if|else|mat|__builtin|or|and|not|as)\\b" :token :keyword}
   {:pattern "\\b(Integer|Scalar|String|Color|Length|Area|Volume|Angle|Weight|Density|Bool|Matrix[0-9])\\b" :token :keyword-type}
   {:pattern "\\b([a-z_][a-zA-Z0-9_]*)\\s*(?=\\()" :token :name-fn}
   {:pattern "[+\\-*/%&|<>^!@=]" :token :operator}
   {:pattern "\\b(([0-9]+(\\.[0-9]+)?)(%|m²|cm²|mm²|µm²|in²|ft²|yd²|m³|cm³|mm³|µm³|in³|ft³|yd³|ml|cl|l|µl|cm|mm|m|µm|in|ft|yd|deg|°|grad|turn|rad|g|kg|lb|oz)?)|true|false\\b" :token :number}
   {:pattern "\\b([A-Z_][A-Z0-9_]*)\\b" :token :name-var}
   {:pattern "\\b([A-Z_][a-zA-Z0-9_]*)\\b" :token :name-class}
   {:pattern "\\b([a-z_][a-zA-Z0-9_]*)\\b" :token :text}
   {:pattern "&quot;[^&quot;]*&quot;" :token :string}
   {:pattern "[{}()\\[\\],.;:]" :token :punctuation}
   {:pattern "[\\}\\)]" :token :punctuation}])
