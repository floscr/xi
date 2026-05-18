(ns xi.highlight.grammar.cassandra-cql)

(def cassandra-cql
  [   {:pattern "\\s+" :token :text}
   {:pattern "(--|\\/\\/).*\\n?" :token :comment}
   {:pattern "(ascii|bigint|blob|boolean|counter|date|decimal|double|float|frozen|inet|int|list|map|set|smallint|text|time|timestamp|timeuuid|tinyint|tuple|uuid|varchar|varint)\\b" :token :name-builtin}
   {:pattern "(DURABLE_WRITES|LOCAL_QUORUM|MATERIALIZED|COLUMNFAMILY|REPLICATION|NORECURSIVE|NOSUPERUSER|PERMISSIONS|EACH_QUORUM|CONSISTENCY|PERMISSION|CLUSTERING|WRITETIME|SUPERUSER|KEYSPACES|AUTHORIZE|LOCAL_ONE|AGGREGATE|FINALFUNC|PARTITION|FILTERING|UNLOGGED|CONTAINS|DISTINCT|FUNCTION|LANGUAGE|INFINITY|INITCOND|TRUNCATE|KEYSPACE|PASSWORD|REPLACE|OPTIONS|TRIGGER|STORAGE|ENTRIES|RETURNS|COMPACT|PRIMARY|EXISTS|STATIC|PAGING|UPDATE|CUSTOM|VALUES|INSERT|DELETE|MODIFY|CREATE|SELECT|SCHEMA|LOGGED|REVOKE|RENAME|QUORUM|CALLED|STYPE|ORDER|ALTER|BATCH|BEGIN|COUNT|ROLES|APPLY|WHERE|SFUNC|LEVEL|INPUT|LOGIN|INDEX|TABLE|THREE|ALLOW|TOKEN|LIMIT|USING|USERS|GRANT|FROM|KEYS|JSON|USER|INTO|ROLE|TYPE|VIEW|DESC|WITH|DROP|FULL|ASC|TTL|OFF|PER|KEY|USE|ADD|NAN|ONE|ALL|ANY|TWO|AND|NOT|AS|IN|IF|OF|IS|ON|TO|BY|OR)\\b" :token :keyword}
   {:pattern "[+*/<>=~!@#%^&|`?-]+" :token :operator}
   {:pattern "(true|false|null)\\b" :token :keyword}
   {:pattern "0x[0-9a-f]+" :token :number}
   {:pattern "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" :token :number}
   {:pattern "\\.[0-9]+(e[+-]?[0-9]+)?" :token :text}
   {:pattern "-?[0-9]+(\\.[0-9])?(e[+-]?[0-9]+)?" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "[a-z_]\\w*" :token :text}
   {:pattern ":(['\"]?)[a-z]\\w*\\b\\1" :token :name-var}
   {:pattern "[;:()\\[\\]\\{\\},.]" :token :punctuation}])
