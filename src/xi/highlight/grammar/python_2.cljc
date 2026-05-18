(ns xi.highlight.grammar.python-2)

(def python-2
  [   {:pattern "\\n" :token :text}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "\\A#!.+$" :token :comment}
   {:pattern "#.*$" :token :comment}
   {:pattern "[]{}:(),;[]" :token :punctuation}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "\\\\" :token :text}
   {:pattern "(in|is|and|or|not)\\b" :token :operator}
   {:pattern "!=|==|<<|>>|[-~+/*%=<>&^|.]" :token :operator}
   {:pattern "(yield from|continue|finally|lambda|assert|global|except|return|print|yield|while|break|raise|elif|pass|exec|else|with|try|for|del|as|if)\\b" :token :keyword}
   {:pattern "(?<!\\.)(staticmethod|classmethod|__import__|isinstance|basestring|issubclass|frozenset|raw_input|bytearray|enumerate|property|callable|reversed|execfile|hasattr|setattr|compile|complex|delattr|unicode|globals|getattr|unichr|reduce|xrange|buffer|intern|filter|locals|divmod|coerce|sorted|reload|object|slice|round|float|super|input|bytes|apply|tuple|range|iter|dict|long|type|hash|vars|next|file|exit|open|repr|eval|bool|list|bin|pow|zip|ord|oct|min|set|any|max|map|all|len|sum|int|dir|hex|chr|abs|cmp|str|id)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)(self|None|Ellipsis|NotImplemented|False|True|cls)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)(PendingDeprecationWarning|UnicodeTranslateError|NotImplementedError|UnicodeDecodeError|DeprecationWarning|UnicodeEncodeError|FloatingPointError|ZeroDivisionError|UnboundLocalError|KeyboardInterrupt|EnvironmentError|IndentationError|OverflowWarning|ArithmeticError|ReferenceError|AttributeError|AssertionError|RuntimeWarning|UnicodeWarning|GeneratorExit|SyntaxWarning|StandardError|BaseException|OverflowError|FutureWarning|ImportWarning|StopIteration|UnicodeError|WindowsError|RuntimeError|ImportError|UserWarning|LookupError|SyntaxError|SystemError|MemoryError|SystemExit|ValueError|IndexError|NameError|Exception|TypeError|EOFError|KeyError|VMSError|TabError|IOError|Warning|OSError)\\b" :token :name-class}
   {:pattern "(__instancecheck__|__subclasscheck__|__getattribute__|__rfloordiv__|__ifloordiv__|__setslice__|__getslice__|__contains__|__reversed__|__floordiv__|__rtruediv__|__itruediv__|__delslice__|__rlshift__|__rrshift__|__delitem__|__rdivmod__|__nonzero__|__missing__|__delattr__|__setattr__|__irshift__|__complex__|__setitem__|__getitem__|__truediv__|__unicode__|__ilshift__|__getattr__|__delete__|__coerce__|__invert__|__lshift__|__divmod__|__rshift__|__enter__|__index__|__float__|__iadd__|__rsub__|__init__|__imul__|__rpow__|__repr__|__rmul__|__isub__|__iter__|__rmod__|__ixor__|__call__|__imod__|__long__|__hash__|__rxor__|__idiv__|__iand__|__rdiv__|__ipow__|__rcmp__|__rand__|__exit__|__radd__|__str__|__cmp__|__pos__|__pow__|__oct__|__new__|__neg__|__mul__|__mod__|__set__|__xor__|__sub__|__len__|__and__|__get__|__rop__|__add__|__ior__|__div__|__iop__|__int__|__abs__|__hex__|__ror__|__del__|__eq__|__or__|__ne__|__lt__|__le__|__ge__|__gt__|__op__)\\b" :token :name-fn}
   {:pattern "(__metaclass__|__defaults__|__globals__|__closure__|__weakref__|__module__|__slots__|__class__|__bases__|__file__|__func__|__dict__|__name__|__self__|__code__|__mro__|__doc__)\\b" :token :name-var}
   {:pattern "`.*?`" :token :string}
   {:pattern "@[\\w.]+" :token :name-builtin}
   {:pattern "[a-zA-Z_]\\w*" :token :text}
   {:pattern "(\\d+\\.\\d*|\\d*\\.\\d+)([eE][+-]?[0-9]+)?j?" :token :number}
   {:pattern "\\d+[eE][+-]?[0-9]+j?" :token :number}
   {:pattern "0[0-7]+j?" :token :number}
   {:pattern "0[bB][01]+" :token :number}
   {:pattern "0[xX][a-fA-F0-9]+" :token :number}
   {:pattern "\\d+L" :token :number}
   {:pattern "\\d+j?" :token :number}])
