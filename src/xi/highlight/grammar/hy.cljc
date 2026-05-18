(ns xi.highlight.grammar.hy)

(def hy
  [   {:pattern ";.*$" :token :comment}
   {:pattern "[,\\s]+" :token :text}
   {:pattern "-?\\d+\\.\\d+" :token :number}
   {:pattern "-?\\d+" :token :number}
   {:pattern "0[0-7]+j?" :token :number}
   {:pattern "0[xX][a-fA-F0-9]+" :token :number}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "'(?!#)[\\w!$%*+<=>?/.#-]+" :token :string-symbol}
   {:pattern "\\\\(.|[a-z]+)" :token :string-char}
   {:pattern "::?(?!#)[\\w!$%*+<=>?/.#-]+" :token :string-symbol}
   {:pattern "~@|[`\\'#^~&@]" :token :operator}
   {:pattern "(eval-when-compile|eval-and-compile|with-decorator|unquote-splice|quasiquote|list_comp|unquote|foreach|kwapply|import|not-in|unless|is-not|quote|progn|slice|assoc|first|while|when|rest|cond|<<=|->>|for|get|>>=|let|cdr|car|is|->|do|in|\\||~|,) " :token :keyword}
   {:pattern "(defmacro|defclass|lambda|defun|defn|setv|def|fn) " :token :keyword-decl}
   {:pattern "(repeatedly|take_while|iterator\\?|iterable\\?|instance\\?|distinct|take_nth|numeric\\?|iterate|filter|repeat|remove|even\\?|none\\?|cycle|zero\\?|odd\\?|pos\\?|neg\\?|take|drop|inc|dec|nth) " :token :name-builtin}
   {:pattern "(?<=\\()(?!#)[\\w!$%*+<=>?/.#-]+" :token :name-fn}
   {:pattern "(?!#)[\\w!$%*+<=>?/.#-]+" :token :name-var}
   {:pattern "(\\[|\\])" :token :punctuation}
   {:pattern "(\\{|\\})" :token :punctuation}
   {:pattern "(\\(|\\))" :token :punctuation}
   {:pattern "(yield from|continue|finally|lambda|assert|global|except|return|print|yield|while|break|raise|elif|pass|exec|else|with|try|for|del|as|if)\\b" :token :keyword}
   {:pattern "(?<!\\.)(staticmethod|classmethod|__import__|isinstance|basestring|issubclass|frozenset|raw_input|bytearray|enumerate|property|callable|reversed|execfile|hasattr|setattr|compile|complex|delattr|unicode|globals|getattr|unichr|reduce|xrange|buffer|intern|filter|locals|divmod|coerce|sorted|reload|object|slice|round|float|super|input|bytes|apply|tuple|range|iter|dict|long|type|hash|vars|next|file|exit|open|repr|eval|bool|list|bin|pow|zip|ord|oct|min|set|any|max|map|all|len|sum|int|dir|hex|chr|abs|cmp|str|id)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)(self|None|Ellipsis|NotImplemented|False|True|cls)\\b" :token :name-builtin}
   {:pattern "(?<!\\.)(PendingDeprecationWarning|UnicodeTranslateError|NotImplementedError|UnicodeEncodeError|UnicodeDecodeError|DeprecationWarning|FloatingPointError|UnboundLocalError|KeyboardInterrupt|ZeroDivisionError|EnvironmentError|IndentationError|ArithmeticError|OverflowWarning|ReferenceError|RuntimeWarning|AttributeError|AssertionError|NotImplemented|UnicodeWarning|FutureWarning|BaseException|StopIteration|SyntaxWarning|OverflowError|StandardError|ImportWarning|GeneratorExit|RuntimeError|WindowsError|UnicodeError|LookupError|SyntaxError|SystemError|ImportError|MemoryError|UserWarning|ValueError|IndexError|SystemExit|Exception|TypeError|NameError|EOFError|VMSError|KeyError|TabError|IOError|OSError|Warning)\\b" :token :name-class}])
