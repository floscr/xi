(ns xi.highlight.grammar.hare)

(def hare
  [   {:pattern "[\\s\\n]+" :token :text}
   {:pattern "@[a-z]+" :token :name-builtin}
   {:pattern "//.*\\n" :token :comment}
   {:pattern "`[^`]*`" :token :string}
   {:pattern "'(\\\\[\\\\0abfnrtv&quot;']||\\\\(x[a-fA-F0-9]{2}|u[a-fA-F0-9]{4}|U[a-fA-F0-9]{8})|[^\\\\'])'" :token :string-char}
   {:pattern "(0|[1-9]\\d*)\\.\\d+([eE][+-]?\\d+)?(f32|f64)?" :token :number}
   {:pattern "(0|[1-9]\\d*)([eE][+-]?\\d+)?(f32|f64)" :token :number}
   {:pattern "0x[0-9a-fA-F]+\\.[0-9a-fA-F]+([pP][+-]?\\d+(f32|f64)?)?" :token :number}
   {:pattern "0x[0-9a-fA-F]+[pP][+-]?\\d+(f32|f64)" :token :number}
   {:pattern "0x[0-9a-fA-F]+(z|[iu](8|16|32|64)?)?" :token :number}
   {:pattern "0o[0-7]+(z|[iu](8|16|32|64)?)?" :token :number}
   {:pattern "0b[01]+(z|[iu](8|16|32|64)?)?" :token :number}
   {:pattern "(0|[1-9]\\d*)([eE][+-]?\\d+)?(z|[iu](8|16|32|64)?)?" :token :number}
   {:pattern "[~!%^&*+=|?:<>/-]|[ai]s\\b|\\.\\.\\." :token :operator}
   {:pattern "[()\\[\\],.{};]" :token :punctuation}
   {:pattern "use\\b" :token :keyword}
   {:pattern "(_|align|break|const|continue|else|enum|export|for|if|return|static|struct|offset|union|fn|free|assert|abort|alloc|let|len|def|type|match|switch|case|append|delete|insert|defer|yield|vastart|vaarg|vaend)\\b" :token :keyword}
   {:pattern "(str|size|rune|bool|int|uint|uintptr|u8|u16|u32|u64|i8|i16|i32|i64|f32|f64|null|void|done|nullable|valist|opaque|never)\\b" :token :keyword-type}
   {:pattern "(true|false)\\b" :token :name-builtin}
   {:pattern "[a-zA-Z_]\\w*" :token :text}])
