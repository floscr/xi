(ns xi.highlight.grammar.yang)

(def yang
  [   {:pattern "\\s+" :token :text}
   {:pattern "[\\{\\}\\;]+" :token :punctuation}
   {:pattern "(?<![\\-\\w])(and|or|not|\\+|\\.)(?![\\-\\w])" :token :operator}
   {:pattern "\"(?:\\\\\"|[^\"])*?\"" :token :string}
   {:pattern "'(?:\\\\'|[^'])*?'" :token :string}
   {:pattern "//.*?$" :token :comment}
   {:pattern "([0-9]{4}\\-[0-9]{2}\\-[0-9]{2})(?=[\\s\\{\\}\\;])" :token :string}
   {:pattern "([0-9]+\\.[0-9]+)(?=[\\s\\{\\}\\;])" :token :number}
   {:pattern "([0-9]+)(?=[\\s\\{\\}\\;])" :token :number}
   {:pattern "(submodule|module)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(yang-version|belongs-to|namespace|prefix)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(organization|description|reference|revision|contact)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(revision-date|include|import)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(notification|if-feature|deviation|extension|identity|argument|grouping|typedef|feature|augment|output|action|input|rpc)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(leaf-list|container|presence|anydata|deviate|choice|config|anyxml|refine|leaf|must|list|case|uses|when)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(require-instance|fraction-digits|error-app-tag|error-message|min-elements|max-elements|yin-element|ordered-by|position|modifier|default|pattern|length|status|units|value|range|type|path|enum|base|bit)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(mandatory|unique|key)(?=[^\\w\\-\\:])" :token :keyword}
   {:pattern "(not-supported|invert-match|deprecated|unbounded|obsolete|current|replace|delete|false|true|user|min|max|add)(?=[^\\w\\-\\:])" :token :name-class}
   {:pattern "(instance-identifier|identityref|enumeration|decimal64|boolean|leafref|uint64|uint32|string|binary|uint16|int32|int64|int16|empty|uint8|union|int8|bits)(?=[^\\w\\-\\:])" :token :name-class}
   {:pattern "[^;{}\\s\\'\\\"]+" :token :text}])
