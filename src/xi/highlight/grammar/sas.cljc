(ns xi.highlight.grammar.sas)

(def sas
  [   {:pattern "." :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "^\\s*\\*.*?;" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "^\\s*\\*(.|\\n)*?;" :token :comment}
   {:pattern "/[*](.|\\n)*?[*]/" :token :comment}
   {:pattern "(^|;)\\s*(proc \\w+|data|run|quit)[\\s;]" :token :keyword}
   {:pattern "\\b(datalines4|datalines|delimiter|startsas|redirect|lostcard|continue|informat|filename|footnote|catname|options|libname|systask|display|waitsas|missing|replace|delete|window|endsas|update|format|attrib|length|infile|select|return|retain|rename|remove|output|cards4|modify|leave|title|merge|delim|input|cards|abort|where|label|array|error|call|page|stop|keep|file|drop|link|skip|list|goto|put|out|set|by|dm|in|x)\\b" :token :keyword}
   {:pattern "\\b(references|distinct|describe|validate|restrict|cascade|msgtype|message|primary|foreign|delete|update|create|unique|having|modify|insert|select|group|check|table|alter|order|reset|index|where|into|from|view|null|like|drop|add|not|key|and|set|on|in|or|as)\\b" :token :keyword}
   {:pattern "\\b(while|until|then|else|end|if|do)\\b" :token :keyword}
   {:pattern "%(sysevalf|nrbquote|qsysfunc|qlowcase|compstor|nrquote|display|qupcase|datatyp|qcmpres|unquote|syscall|sysfunc|sysrput|sysprod|syslput|sysexec|lowcase|qsubstr|sysget|length|keydef|global|superq|substr|verify|bquote|cmpres|upcase|window|label|qleft|while|qtrim|quote|nrstr|until|sysrc|input|macro|local|qscan|index|else|scan|mend|eval|trim|then|goto|left|put|let|end|str|do|to|if)\\b" :token :name-builtin}
   {:pattern "\\b(vinformatnx|vinformatwx|vinformatdx|vinformatw|vinformatd|vinformatx|vinformatn|vinformat|translate|vinarrayx|vformatwx|vformatnx|vformatdx|getoption|fileexist|fetchobs|vlengthx|filename|fipstate|kurtosis|vinarray|vformatx|pathname|foptname|compound|compress|vformatw|hosthelp|vformatn|zipnamel|vformatd|probbeta|daccdbsl|zipstate|trigamma|probbnml|probhypr|probnegb|probnorm|datepart|datetime|varlabel|varinfmt|dropnote|skewness|doptname|timepart|fipnamel|dequote|tranwrd|sysprod|digamma|stnamel|soundex|depdbsl|reverse|daccsyd|doptnum|resolve|uniform|datejul|varname|varrayx|probgam|probchi|fappend|dacctab|vformat|poisson|collate|brshift|ordinal|fdelete|blshift|betainv|fileref|lowcase|libname|fipname|vlabelx|vlength|weekday|juldate|jbessel|ibessel|zipfips|foptnum|zipname|getvarn|getvarc|frewind|vartype|depsyd|stderr|stfips|fwrite|gaminv|second|substr|vtypex|symget|hbound|vnamex|fpoint|saving|fnonct|rewind|indexc|indexw|repeat|inputc|inputn|ranuni|stname|rantbl|ranpoi|rannor|sysget|rangam|ranexp|vlabel|lbound|cexist|length|lgamma|rancau|libref|cnonct|ranbin|compbl|logpdf|logpmf|logsdf|sysmsg|curobs|daccdb|verify|daccsl|minute|system|tnonct|dsname|varray|varnum|probit|spedis|normal|varlen|dclose|varfmt|fexist|deptab|upcase|rantri|fclose|nmiss|point|trimn|depsl|trunc|peekc|depdb|probf|exist|fetch|netpv|today|mopen|probt|month|dairy|sysrc|finfo|quote|log10|close|floor|dinfo|range|fnote|attrn|intrr|intnx|intck|attrc|input|dread|dopen|index|right|round|vname|vtype|fread|gamma|arsin|arcos|fopen|frlen|fget|sinh|sqrt|addr|airy|sign|fsep|year|fuzz|dnum|scan|rank|fput|fpos|putn|putc|hour|tanh|atan|dhms|tinv|band|bnot|erfc|fcol|poke|trim|byte|ceil|peek|left|cinv|finv|open|log2|mean|note|date|cosh|mort|time|bxor|bor|mdy|std|max|css|sin|cos|npv|log|var|uss|pdf|pmf|cdf|abs|hms|day|erf|put|lag|irr|int|sum|tan|min|dif|qtr|sdf|dim|yyq|exp|mod|cv|n)\\(" :token :name-builtin}
   {:pattern "&[a-z_]\\w{0,31}\\.?" :token :name-var}
   {:pattern "%[a-z_]\\w{0,31}" :token :name-fn}
   {:pattern "(null|missing|_all_|_automatic_|_character_|_n_|_infile_|_name_|_null_|_numeric_|_user_|_webout_)" :token :keyword}
   {:pattern "\\b[+-]?([0-9]+(\\.[0-9]+)?|\\.[0-9]+|\\.)(E[+-]?[0-9]+)?i?\\b" :token :number}])
