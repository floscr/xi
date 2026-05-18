(ns xi.highlight.grammar.moonbit)

(def moonbit
  [   {:pattern "#.*$" :token :comment}
   {:pattern "//.*$" :token :comment}
   {:pattern "b?\\&#x27;.*\\&#x27;" :token :string}
   {:pattern "#\\|.*$" :token :string}
   {:pattern "0(b|B)[01]+" :token :number}
   {:pattern "0(o|O)[0-7]+" :token :number}
   {:pattern "0(x|X)[0-9a-fA-F][0-9a-fA-F_]*\\.[0-9a-fA-F][0-9a-fA-F_]*(P|p)(\\+|\\-)?[0-9][0-9]*" :token :number}
   {:pattern "0(x|X)[0-9a-fA-F][0-9a-fA-F_]*\\.?(P|p)(\\+|\\-)?[0-9][0-9]*" :token :number}
   {:pattern "0(x|X)[0-9a-fA-F][0-9a-fA-F_]*\\.[0-9a-fA-F][0-9a-fA-F_]*" :token :number}
   {:pattern "0(x|X)[0-9a-fA-F][0-9a-fA-F_]*\\." :token :number}
   {:pattern "0(x|X)[0-9a-fA-F][0-9a-fA-F_]*" :token :number}
   {:pattern "\\d(_|\\d)*U?L" :token :number}
   {:pattern "\\d(_|\\d)*U?" :token :number}
   {:pattern "\\d+(.\\d+)?" :token :number}
   {:pattern "(type|type!|enum|struct|trait|typealias|traitalias)\\b" :token :keyword-decl}
   {:pattern "(async|fn|const|let|mut|impl|with|derive|fnalias)\\b" :token :keyword-decl}
   {:pattern "(self|Self)\\b" :token :keyword}
   {:pattern "(guard|if|while|match|else|loop|for|in|is)\\b" :token :keyword}
   {:pattern "(return|break|continue)\\b" :token :keyword}
   {:pattern "(try|catch|raise|noraise)\\b" :token :keyword}
   {:pattern "\\bas\\b" :token :keyword}
   {:pattern "(extern|pub|priv|pub\\(all\\)|pub\\(readonly\\)|pub\\(open\\)|test)\\b" :token :keyword}
   {:pattern "(true|false)\\b" :token :keyword}
   {:pattern "(Eq|Compare|Hash|Show|Default|ToJson|FromJson)\\b" :token :name-builtin}
   {:pattern "(Array|FixedArray|Int|Int64|UInt|UInt64|Option|Result|Byte|Bool|Unit|String|Float|Double)\\b" :token :name-builtin}
   {:pattern "(\\+|\\-|\\*|/|%|\\|>|>>|<<|\\&\\&|\\|\\||\\&|\\||<|>|==)" :token :operator}
   {:pattern "(not|lsl|lsr|asr|op_add|op_sub|op_div|op_mul|op_mod|\\.\\.\\.)" :token :operator}
   {:pattern "@[A-Za-z][A-Za-z0-9_/]*\\." :token :name-var}
   {:pattern "([a-z][A-Za-z0-9_]*)(?=!?\\()" :token :name-fn}
   {:pattern "Error" :token :name-class}
   {:pattern "(=>)|(->)|[\\(\\)\\{\\}\\[\\]:,\\.=!?~;]" :token :punctuation}
   {:pattern "[a-z][a-zA-Z0-9_]*" :token :name-var}
   {:pattern "[A-Z_][a-zA-Z0-9_]*" :token :name-class}
   {:pattern "[\\s]" :token :text}])
