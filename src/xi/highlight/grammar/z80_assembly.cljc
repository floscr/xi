(ns xi.highlight.grammar.z80-assembly)

(def z80-assembly
  [   {:pattern ";.*?$" :token :comment}
   {:pattern "^[.\\w]+:" :token :name-var}
   {:pattern "((0x)|\\$)[0-9a-fA-F]+" :token :number}
   {:pattern "[0-9][0-9a-fA-F]+h" :token :number}
   {:pattern "((0b)|%)[01]+" :token :number}
   {:pattern "-?[0-9]+" :token :number}
   {:pattern "'\\\\?.'" :token :string-char}
   {:pattern "[,=()\\\\]" :token :punctuation}
   {:pattern "^\\s*#\\w+" :token :comment}
   {:pattern "\\.(db|dw|end|org|byte|word|fill|block|addinstr|echo|error|list|nolist|equ|show|option|seek)" :token :name-builtin}
   {:pattern "(ex|exx|ld|ldd|lddr|ldi|ldir|pop|push|adc|add|cp|cpd|cpdr|cpi|cpir|cpl|daa|dec|inc|neg|sbc|sub|and|bit|ccf|or|res|scf|set|xor|rl|rla|rlc|rlca|rld|rr|rra|rrc|rrca|rrd|sla|sra|srl|call|djnz|jp|jr|ret|rst|nop|reti|retn|di|ei|halt|im|in|ind|indr|ini|inir|out|outd|otdr|outi|otir)" :token :keyword}
   {:pattern "(z|nz|c|nc|po|pe|p|m)" :token :keyword}
   {:pattern "[+-/*~\\^&|]" :token :operator}
   {:pattern "\\w+" :token :text}
   {:pattern "\\s+" :token :text}])
