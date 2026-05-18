(ns xi.highlight.grammar.kotlin)

(def kotlin
  [   {:pattern "^\\s*\\[.*?\\]" :token :name-var}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "//[^\\n]*\\n?" :token :comment}
   {:pattern "/[*].*?[*]/" :token :comment}
   {:pattern "\\n" :token :text}
   {:pattern "!==|!in|!is|===" :token :operator}
   {:pattern "%=|&&|\\*=|\\+\\+|\\+=|--|-=|->|\\.\\.|\\/=|::|<=|==|>=|!!|!=|\\|\\||\\?[:.]" :token :operator}
   {:pattern "[{}]" :token :punctuation}
   {:pattern "'\\\\.'|'[^\\\\]'" :token :string-char}
   {:pattern "0[xX][0-9a-fA-F]+(_+[0-9a-fA-F]+)*[uU]?L?" :token :number}
   {:pattern "0[bB][01]+(_+[01]+)*[uU]?L?" :token :number}
   {:pattern "[0-9]+(_+[0-9]+)*\\.[0-9]+(_+[0-9]+)*([eE][+-]?[0-9]+(_+[0-9]+)*)?[fF]?|\\.[0-9]+(_+[0-9]+)*([eE][+-]?[0-9]+(_+[0-9]+)*)?[fF]?|[0-9]+(_+[0-9]+)*[eE][+-]?[0-9]+(_+[0-9]+)*[fF]?|[0-9]+(_+[0-9]+)*[fF]" :token :number}
   {:pattern "[0-9]+(_+[0-9]+)*[uU]?L?" :token :number}
   {:pattern "[~!%^&*()+=|\\[\\]:;,.<>\\/?-]" :token :punctuation}
   {:pattern "(abstract|actual|annotation|as|as\\?|break|by|catch|class|companion|const|constructor|continue|crossinline|data|delegate|do|dynamic|else|enum|expect|external|false|field|file|final|finally|for|fun|get|if|import|in|infix|init|inline|inner|interface|internal|is|it|lateinit|noinline|null|object|open|operator|out|override|package|param|private|property|protected|public|receiver|reified|return|sealed|set|setparam|super|suspend|tailrec|this|throw|true|try|typealias|typeof|val|value|var|vararg|when|where|while)\\b" :token :keyword}
   {:pattern "@(?:[_\\p{L}][\\p{L}\\p{N}]*|`@?[_\\p{L}][\\p{L}\\p{N}]+`)" :token :name-builtin}
   {:pattern "(?:\\p{Lu}[_\\p{L}]*)(?=\\.)" :token :name-class}
   {:pattern "(?:[_\\p{L}][\\p{L}\\p{N}]*|`@?[_\\p{L}][\\p{L}\\p{N}]+`)" :token :text}])
