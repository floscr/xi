(ns xi.highlight.grammar.rgbds-assembly)

(def rgbds-assembly
  [   {:pattern ";.*?$" :token :comment}
   {:pattern "/[*](.|\\n)*?[*]/" :token :comment}
   {:pattern "(0x|\\$)[0-9a-fA-F][0-9a-fA-F_]*" :token :number}
   {:pattern "[0-9a-fA-F][0-9a-fA-F_]*h\\b" :token :number}
   {:pattern "(0o|&)[0-7][0-7_]*" :token :number}
   {:pattern "(0b|%)[01][01_]*" :token :number}
   {:pattern "-?[0-9][0-9_]*\\.[0-9_]+(q[0-9]+)?" :token :number}
   {:pattern "-?[0-9][0-9_]*" :token :number}
   {:pattern "`[^\\s]+" :token :number}
   {:pattern "\\\\[1-9@#]" :token :name-var}
   {:pattern "[\\[\\],()\\\\:]" :token :punctuation}
   {:pattern "\\b(_rs|_narg|__date__|__time__|__iso_8601_local__|__iso_8601_utc__|__utc_year__|__utc_month__|__utc_day__|__utc_hour__|__utc_minute__|__utc_second__|__rgbds_major__|__rgbds_minor__|__rgbds_patch__|__rgbds_rc__|__rgbds_version__)\\b" :token :name-var}
   {:pattern "\\b(align|assert|bank|break|charmap|db|dl|ds|dw|elif|else|endc|endl|endm|endr|endsection|endu|export|fail|fatal|for|fragment|hram|if|incbin|include|load|macro|newcharmap|nextu|oam|popc|popo|pops|println|print|purge|pushc|pusho|pushs|redef|rept|rom0|romx|rsreset|rsset|section|setcharmap|shift|sram|static_assert|union|vram|warn|wram0|wramx)" :token :name-builtin}
   {:pattern "\\b(high|low|bitwidth|tzcount)\\b" :token :name-fn}
   {:pattern "\\b(div|mul|fmod|pow|log|round|ceil|floor|sin|cos|tan|asin|acos|atan|atan2)\\b" :token :name-fn}
   {:pattern "\\b(strcat|strupr|strlwr|strslice|strrpl|strfmt|strchar|revchar|strlen|strcmp|strfind|strrfind|incharmap|charlen|charcmp|charsize|strsub|strin|strrin|charsub)\\b" :token :name-fn}
   {:pattern "\\b(def|isconst|sizeof|startof)" :token :name-fn}
   {:pattern "\\b(adc|add|and|bit|call|ccf|cp|cpl|daa|dec|di|ei|halt|inc|jp|jr|ld|ldd|ldh|ldi|nop|or|pop|push|res|ret|reti|rlca|rla|rlc|rl|rr|rra|rrc|rrca|rst|sbc|scf|set|sla|sra|srl|stop|sub|swap|xor)\\b" :token :keyword}
   {:pattern "\\b(a|f|b|c|d|e|h|l|af|bc|de|hl|sp|pc|z|nz|nc)\\b" :token :keyword}
   {:pattern "[-%!+/*~\\^&|=<>]" :token :operator}
   {:pattern "(@|\\.|\\.\\.)" :token :name-var}
   {:pattern "\\s+" :token :text}])
