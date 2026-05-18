(ns xi.highlight.grammar.ada)

(def ada
  [   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "--.*?\\n" :token :comment}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "task|protected" :token :keyword-decl}
   {:pattern "(true|false|null)\\b" :token :keyword}
   {:pattern "(Short_Short_Integer|Short_Short_Float|Long_Long_Integer|Long_Long_Float|Wide_Character|Reference_Type|Short_Integer|Long_Integer|Wide_String|Short_Float|Controlled|Long_Float|Character|Generator|File_Type|File_Mode|Positive|Duration|Boolean|Natural|Integer|Address|Cursor|String|Count|Float|Byte)\\b" :token :keyword-type}
   {:pattern "(and(\\s+then)?|in|mod|not|or(\\s+else)|rem)\\b" :token :operator}
   {:pattern "generic|private" :token :keyword-decl}
   {:pattern "<<\\w+>>" :token :name-var}
   {:pattern "\\b(synchronized|overriding|terminate|interface|exception|protected|separate|constant|abstract|renames|reverse|subtype|aliased|declare|requeue|limited|return|tagged|access|record|select|accept|digits|others|pragma|entry|elsif|delta|delay|array|until|range|raise|while|begin|abort|else|loop|when|type|null|then|body|task|goto|case|exit|end|for|abs|xor|all|new|out|is|of|if|or|do|at)\\b" :token :keyword}
   {:pattern "\"[^\"]*\"" :token :string}
   {:pattern "'[^']'" :token :string-char}
   {:pattern "(<>|=>|:=|[()|:;,.'])" :token :punctuation}
   {:pattern "[*<>+=/&-]" :token :operator}
   {:pattern "\\n+" :token :text}
   {:pattern "[0-9_]+#[0-9a-f]+#" :token :number}
   {:pattern "[0-9_]+\\.[0-9_]*" :token :number}
   {:pattern "[0-9_]+" :token :number}])
