(ns xi.highlight.grammar.core)

(def core
  [   {:pattern "\\s+" :token :text}
   {:pattern "//(.*?)\\n" :token :comment}
   {:pattern "(class|value|union|trait|impl|annotation)\\b" :token :keyword-decl}
   {:pattern "(fun|let|var)\\b" :token :keyword-decl}
   {:pattern "(mod|import)\\b" :token :keyword}
   {:pattern "(if|else|is|for|in|while|return)\\b" :token :keyword}
   {:pattern "(true|false|self)\\b" :token :keyword}
   {:pattern "0[b][01](_?[01])*(i32|i64|u8|f32|f64)?" :token :number}
   {:pattern "0[x][\\da-fA-F](_?[\\dA-Fa-f])*(i32|i64|u8|f32|f64)?" :token :number}
   {:pattern "\\d(_?\\d)*\\.\\d(_?\\d)*([eE][-+]?\\d(_?\\d)*)?(f32|f64)?" :token :number}
   {:pattern "\\d(_?\\d)*(i32|i64|u8|f32|f64)?" :token :number}
   {:pattern "@([a-z_]\\w*[!?]?)" :token :name-var}
   {:pattern "===|!==|==|!=|>=|<=|[><*/+-=&|^]" :token :operator}
   {:pattern "[A-Z][A-Za-z0-9_]*" :token :name-class}
   {:pattern "([a-z_]\\w*[!?]?)" :token :text}
   {:pattern "[(){}\\[\\],.;]" :token :punctuation}])
