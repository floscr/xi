(ns xi.highlight.grammar.cython)

(def cython
  [   {:pattern "\\n" :token :text}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "#.*$" :token :comment}
   {:pattern "[]{}:(),;[]" :token :punctuation}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "\\\\" :token :text}
   {:pattern "(in|is|and|or|not)\\b" :token :operator}
   {:pattern "!=|==|<<|>>|[-~+/*%=<>&^|.?]" :token :operator}
   {:pattern "[uU]?\"\"\"" :token :string}
   {:pattern "[uU]?'''" :token :string}
   {:pattern "[uU]?\"" :token :string}
   {:pattern "[uU]?'" :token :string}
   {:pattern "(continue|ctypedef|except\\?|include|finally|global|return|lambda|assert|except|print|nogil|while|fused|yield|break|raise|exec|else|elif|pass|with|gil|for|try|del|by|as|if)\\b" :token :keyword}
   {:pattern "(DEF|IF|ELIF|ELSE)\\b" :token :comment}
   {:pattern "(?<!\\.)(staticmethod|classmethod|__import__|issubclass|isinstance|basestring|bytearray|raw_input|frozenset|enumerate|property|unsigned|reversed|callable|execfile|hasattr|compile|complex|delattr|setattr|unicode|globals|getattr|reload|divmod|xrange|unichr|filter|reduce|buffer|intern|coerce|sorted|locals|object|round|input|range|super|tuple|bytes|float|slice|apply|bool|long|exit|vars|file|next|type|iter|open|dict|repr|hash|list|eval|oct|map|zip|int|hex|set|sum|chr|cmp|any|str|pow|ord|dir|len|min|all|abs|max|bin|id)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)(self|None|Ellipsis|NotImplemented|False|True|NULL)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)(PendingDeprecationWarning|UnicodeTranslateError|NotImplementedError|FloatingPointError|DeprecationWarning|UnicodeDecodeError|UnicodeEncodeError|UnboundLocalError|KeyboardInterrupt|ZeroDivisionError|IndentationError|EnvironmentError|OverflowWarning|ArithmeticError|RuntimeWarning|UnicodeWarning|AttributeError|AssertionError|NotImplemented|ReferenceError|StopIteration|SyntaxWarning|OverflowError|GeneratorExit|FutureWarning|BaseException|ImportWarning|StandardError|RuntimeError|UnicodeError|LookupError|ImportError|SyntaxError|MemoryError|SystemError|UserWarning|SystemExit|ValueError|IndexError|NameError|TypeError|Exception|KeyError|EOFError|TabError|OSError|Warning|IOError)\\b" :token :name-class}
   {:pattern "`.*?`" :token :string}
   {:pattern "@\\w+" :token :name-builtin}
   {:pattern "[a-zA-Z_]\\w*" :token :text}
   {:pattern "(\\d+\\.?\\d*|\\d*\\.\\d+)([eE][+-]?[0-9]+)?" :token :number}
   {:pattern "0\\d+" :token :number}
   {:pattern "0[xX][a-fA-F0-9]+" :token :number}
   {:pattern "\\d+L" :token :number}
   {:pattern "\\d+" :token :number}])
