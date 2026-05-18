(ns xi.highlight.grammar.stas)

(def stas
  [   {:pattern "(\\n|\\s)+" :token :text}
   {:pattern "(?<!\\S)(fn|argc|argv|swap|dup|over|over2|rot|rot4|drop|w8|w16|w32|w64|r8|r16|r32|r64|syscall0|syscall1|syscall2|syscall3|syscall4|syscall5|syscall6|_breakpoint|assert|const|auto|reserve|pop|include|addr|if|else|elif|while|break|continue|ret)(?!\\S)" :token :keyword}
   {:pattern "(?<!\\S)(\\+|\\-|\\*|\\/|\\%|\\%\\%|\\+\\+|\\-\\-|>>|<<)(?!\\S)" :token :operator}
   {:pattern "(?<!\\S)(\\=|\\!\\=|>|<|>\\=|<\\=|>s|<s|>\\=s|<\\=s)(?!\\S)" :token :operator}
   {:pattern "(?<!\\S)(\\&|\\||\\^|\\~|\\!|-\\>)(?!\\S)" :token :operator}
   {:pattern "(?<!\\S)\\-?(\\d+)(?!\\S)" :token :number}
   {:pattern "(?<!\\S);.*(\\S|\\n)" :token :comment}
   {:pattern "(?<!\\S)[{}](?!\\S)" :token :punctuation}
   {:pattern "(?<!\\S)[^\\s]+(?!\\S)" :token :text}])
