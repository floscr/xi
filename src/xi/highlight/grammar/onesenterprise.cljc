(ns xi.highlight.grammar.onesenterprise)

(def onesenterprise
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "//(.*?)\\n" :token :comment}
   {:pattern "(#область|#region|#конецобласти|#endregion|#если|#if|#иначе|#else|#конецесли|#endif).*" :token :comment}
   {:pattern "(&наклиенте|&atclient|&насервере|&atserver|&насерверебезконтекста|&atservernocontext|&наклиентенасерверебезконтекста|&atclientatservernocontext).*" :token :comment}
   {:pattern "(>=|<=|<>|\\+|-|=|>|<|\\*|/|%)" :token :operator}
   {:pattern "(;|,|\\)|\\(|\\.)" :token :punctuation}
   {:pattern "(истина|ложь|или|false|true|не|and|not|и|or)\\b" :token :operator}
   {:pattern "(иначеесли|конецесли|иначе|тогда|если|elsif|endif|else|then|if)\\b" :token :operator}
   {:pattern "(конеццикла|каждого|цикл|пока|для|while|enddo|по|each|из|for|do|in|to)\\b" :token :operator}
   {:pattern "(продолжить|прервать|возврат|перейти|continue|return|break|goto)\\b" :token :operator}
   {:pattern "(конецпроцедуры|конецфункции|процедура|функция|endprocedure|endfunction|procedure|function)\\b" :token :keyword}
   {:pattern "(экспорт|новый|перем|знач|export|new|val|var)\\b" :token :keyword}
   {:pattern "(вызватьисключение|конецпопытки|исключение|попытка|endtry|except|raise|try)\\b" :token :keyword}
   {:pattern "(выполнить|вычислить|execute|eval)\\b" :token :keyword}
   {:pattern "[_а-яА-Я0-9][а-яА-Я0-9]*" :token :text}
   {:pattern "[_\\w][\\w]*" :token :text}])
