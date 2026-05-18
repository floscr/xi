(ns xi.highlight.grammar.iscdhcpd)

(def iscdhcpd
  [   {:pattern "#.*?\\n" :token :comment}
   {:pattern "(hardware|packet|leased-address|host-decl-name|lease-time|max-lease-time|client-state|config-option|option|filename|next-server|allow|deny|match|ignore)\\b" :token :keyword}
   {:pattern "(include|group|host|subnet|subnet6|netmask|class|subclass|pool|failover|include|shared-network|range|range6|prefix6)\\b" :token :keyword-type}
   {:pattern "(on|off|true|false|none)\\b" :token :keyword}
   {:pattern "(if|elsif|else)\\b" :token :keyword}
   {:pattern "(exists|known|static)\\b" :token :keyword}
   {:pattern "(and|or|not)\\b" :token :operator}
   {:pattern "(==|!=|~=|~~|=)" :token :operator}
   {:pattern "[{},;\\)]" :token :punctuation}
   {:pattern "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\/\\d{1,2}" :token :number}
   {:pattern "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" :token :number}
   {:pattern "[a-fA-F0-9]{1,2}:[a-fA-F0-9]{1,2}:[a-fA-F0-9]{1,2}:[a-fA-F0-9]{1,2}:[a-fA-F0-9]{1,2}:[a-fA-F0-9]{1,2}" :token :number}
   {:pattern "[\\w\\-.]+" :token :name-var}
   {:pattern "\\s+" :token :text}])
