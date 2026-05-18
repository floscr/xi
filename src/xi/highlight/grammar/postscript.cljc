(ns xi.highlight.grammar.postscript)

(def postscript
  [   {:pattern "^%!.+\\n" :token :comment}
   {:pattern "%%.*\\n" :token :comment}
   {:pattern "(^%.*\\n){2,}" :token :comment}
   {:pattern "%.*\\n" :token :comment}
   {:pattern "[{}<>\\[\\]]" :token :punctuation}
   {:pattern "<[0-9A-Fa-f]+>(?=[()<>\\[\\]{}/%\\s])" :token :number}
   {:pattern "[0-9]+\\#(\\-|\\+)?([0-9]+\\.?|[0-9]*\\.[0-9]+|[0-9]+\\.[0-9]*)((e|E)[0-9]+)?(?=[()<>\\[\\]{}/%\\s])" :token :number}
   {:pattern "(\\-|\\+)?([0-9]+\\.?|[0-9]*\\.[0-9]+|[0-9]+\\.[0-9]*)((e|E)[0-9]+)?(?=[()<>\\[\\]{}/%\\s])" :token :number}
   {:pattern "(\\-|\\+)?[0-9]+(?=[()<>\\[\\]{}/%\\s])" :token :number}
   {:pattern "\\/[^()<>\\[\\]{}/%\\s]+(?=[()<>\\[\\]{}/%\\s])" :token :name-var}
   {:pattern "[^()<>\\[\\]{}/%\\s]+(?=[()<>\\[\\]{}/%\\s])" :token :name-fn}
   {:pattern "(false|true)(?=[()<>\\[\\]{}/%\\s])" :token :keyword}
   {:pattern "(eq|ne|g[et]|l[et]|and|or|not|if(?:else)?|for(?:all)?)(?=[()<>\\[\\]{}/%\\s])" :token :keyword}
   {:pattern "(dictstackoverflow|undefinedfilename|currentlinewidth|undefinedresult|currentmatrix|defaultmatrix|invertmatrix|concatmatrix|currentpoint|setlinewidth|syntaxerror|idtransform|identmatrix|setrgbcolor|stringwidth|setlinejoin|getinterval|itransform|strokepath|pathforall|rangecheck|setlinecap|dtransform|transform|translate|setmatrix|typecheck|undefined|scalefont|closepath|findfont|showpage|rcurveto|grestore|truncate|pathbbox|charpath|rlineto|rmoveto|ceiling|newpath|setdash|setfont|restore|curveto|setgray|stroke|pstack|matrix|length|lineto|repeat|rotate|moveto|shfill|concat|gsave|aload|scale|array|round|stack|index|begin|print|floor|exch|quit|clip|copy|bind|loop|idiv|fill|show|roll|exit|load|dict|save|arcn|sqrt|exec|rand|atan|end|div|abs|run|def|cvs|exp|cvi|sin|cos|get|dup|mod|put|sub|pop|add|neg|mul|arc|log|ln|gt)(?=[()<>\\[\\]{}/%\\s])" :token :name-builtin}
   {:pattern "\\s+" :token :text}])
