(ns xi.highlight.grammar.tal)

(def tal
  [   {:pattern "\\s+" :token :text}
   {:pattern "(?<!\\S)(BRK|LIT|INC|POP|DUP|NIP|SWP|OVR|ROT|EQU|NEQ|GTH|LTH|JMP|JCN|JSR|STH|LDZ|STZ|LDR|STR|LDA|STA|DEI|DEO|ADD|SUB|MUL|DIV|AND|ORA|EOR|SFT)2?k?r?(?!\\S)" :token :keyword}
   {:pattern "[][{}](?!\\S)" :token :punctuation}
   {:pattern "#([0-9a-f]{2}){1,2}(?!\\S)" :token :number}
   {:pattern "&quot;\\S+" :token :string}
   {:pattern "([0-9a-f]{2}){1,2}(?!\\S)" :token :string}
   {:pattern "[|$][0-9a-f]{1,4}(?!\\S)" :token :keyword-decl}
   {:pattern "%\\S+" :token :name-builtin}
   {:pattern "@\\S+" :token :name-fn}
   {:pattern "&\\S+" :token :name-var}
   {:pattern "/\\S+" :token :keyword}
   {:pattern "\\.\\S+" :token :name-var}
   {:pattern ",\\S+" :token :name-var}
   {:pattern ";\\S+" :token :name-var}
   {:pattern "-\\S+" :token :string}
   {:pattern "_\\S+" :token :string}
   {:pattern "=\\S+" :token :string}
   {:pattern "!\\S+" :token :name-fn}
   {:pattern "\\?\\S+" :token :name-fn}
   {:pattern "~\\S+" :token :keyword}
   {:pattern "\\S+" :token :name-fn}])
