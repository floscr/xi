(ns xi.highlight.grammar.pig)

(def pig
  [   {:pattern "\\s+" :token :text}
   {:pattern "--.*" :token :comment}
   {:pattern "/\\*[\\w\\W]*?\\*/" :token :comment}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "\\\\" :token :text}
   {:pattern "\\'(?:\\\\[ntbrf\\\\\\']|\\\\u[0-9a-f]{4}|[^\\'\\\\\\n\\r])*\\'" :token :string}
   {:pattern "[0-9]*\\.[0-9]+(e[0-9]+)?[fd]?" :token :number}
   {:pattern "0x[0-9a-f]+" :token :number}
   {:pattern "[0-9]+L?" :token :number}
   {:pattern "\\n" :token :text}
   {:pattern "[()#:]" :token :text}
   {:pattern "[^(:#\\'\")\\s]+" :token :text}
   {:pattern "\\S+\\s+" :token :text}
   {:pattern "(assert|and|any|all|arrange|as|asc|bag|by|cache|CASE|cat|cd|cp|%declare|%default|define|dense|desc|describe|distinct|du|dump|eval|exex|explain|filter|flatten|foreach|full|generate|group|help|if|illustrate|import|inner|input|into|is|join|kill|left|limit|load|ls|map|matches|mkdir|mv|not|null|onschema|or|order|outer|output|parallel|pig|pwd|quit|register|returns|right|rm|rmf|rollup|run|sample|set|ship|split|stderr|stdin|stdout|store|stream|through|union|using|void)\\b" :token :keyword}
   {:pattern "(bytearray|BIGINTEGER|BIGDECIMAL|chararray|datetime|double|float|int|long|tuple)\\b" :token :keyword-type}
   {:pattern "(AVG|BinStorage|cogroup|CONCAT|copyFromLocal|copyToLocal|COUNT|cross|DIFF|MAX|MIN|PigDump|PigStorage|SIZE|SUM|TextLoader|TOKENIZE)\\b" :token :name-builtin}
   {:pattern "[;(){}\\[\\]]" :token :punctuation}
   {:pattern "[#=,./%+\\-?]" :token :operator}
   {:pattern "(eq|gt|lt|gte|lte|neq|matches)\\b" :token :operator}
   {:pattern "(==|<=|<|>=|>|!=)" :token :operator}])
