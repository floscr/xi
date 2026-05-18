(ns xi.highlight.grammar.nix)

(def nix
  [   {:pattern "#.*$" :token :comment}
   {:pattern "import(?![a-zA-Z0-9_'-])" :token :keyword}
   {:pattern "(inherit|assert|with|then|else|rec|if)(?![a-zA-Z0-9_'-])" :token :keyword}
   {:pattern "throw(?![a-zA-Z0-9_'-])" :token :name-class}
   {:pattern "(dependencyClosure|fetchTarball|filterSource|currentTime|removeAttrs|baseNameOf|derivation|toString|builtins|getAttr|hasAttr|getEnv|isNull|abort|dirOf|toXML|map)(?![a-zA-Z0-9_'-])" :token :name-builtin}
   {:pattern "(false|true|null)(?![a-zA-Z0-9_'-])" :token :name-var}
   {:pattern "[a-zA-Z][a-zA-Z0-9+.-]*:[a-zA-Z0-9%/?:@&=+$,_.!~*'-]+" :token :string}
   {:pattern "[a-zA-Z0-9._+-]*(/[a-zA-Z0-9._+-]+)+" :token :string}
   {:pattern "~(/[a-zA-Z0-9._+-]+)+/?" :token :string}
   {:pattern "<[a-zA-Z0-9._+-]+(/[a-zA-Z0-9._+-]+)*>" :token :string}
   {:pattern "-?[0-9]+(?![a-zA-Z0-9_'-])" :token :number}
   {:pattern "-?(([1-9][0-9]*\\.[0-9]*)|(0?\\.[0-9]+))([Ee][+-]?[0-9]+)?(?![a-zA-Z0-9_'-])" :token :number}
   {:pattern " [/-] " :token :operator}
   {:pattern "(&&|>=|<=|\\+\\+|->|!=|=|\\|\\||//|==|@|!|\\+|\\?|<|\\.|>|\\*)" :token :operator}
   {:pattern "[;:]" :token :punctuation}
   {:pattern "[a-zA-Z_][a-zA-Z0-9_'-]*" :token :text}
   {:pattern "[ \\t\\r\\n]+" :token :text}])
