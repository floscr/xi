(ns xi.highlight.grammar.vb-net)

(def vb-net
  [   {:pattern "^\\s*<.*?>" :token :name-var}
   {:pattern "\\s+" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "rem\\b.*?\\n" :token :comment}
   {:pattern "'.*?\\n" :token :comment}
   {:pattern "#If\\s.*?\\sThen|#ElseIf\\s.*?\\sThen|#Else|#End\\s+If|#Const|#ExternalSource.*?\\n|#End\\s+ExternalSource|#Region.*?\\n|#End\\s+Region|#ExternalChecksum" :token :comment}
   {:pattern "[(){}!#,.:]" :token :punctuation}
   {:pattern "Option\\s+(Strict|Explicit|Compare)\\s+(On|Off|Binary|Text)" :token :keyword-decl}
   {:pattern "(?<!\\.)(NotOverridable|NotInheritable|RemoveHandler|MustOverride|Overridable|MustInherit|Implements|RaiseEvent|AddHandler|ParamArray|WithEvents|DirectCast|Overrides|Overloads|Protected|WriteOnly|Interface|Narrowing|Inherits|Widening|SyncLock|ReadOnly|Operator|Continue|Delegate|Optional|MyClass|Declare|CUShort|Handles|Default|Shadows|TryCast|Finally|Private|Nothing|Partial|CSByte|Select|Option|Return|Friend|Resume|ElseIf|MyBase|Shared|Single|Public|CShort|Static|Global|Catch|CType|Error|CUInt|Using|While|GoSub|False|CDate|Throw|Event|CChar|CULng|CBool|Erase|ByVal|ByRef|Alias|EndIf|CByte|ReDim|Stop|Call|Wend|Next|CLng|Loop|True|CDec|With|Then|GoTo|CObj|CSng|Exit|CStr|Else|Each|Case|CInt|Step|When|CDbl|Set|For|Let|Lib|Try|New|Not|Get|On|To|Do|If|Of|Me)\\b" :token :keyword}
   {:pattern "(?<!\\.)(Boolean|Byte|Char|Date|Decimal|Double|Integer|Long|Object|SByte|Short|Single|String|Variant|UInteger|ULong|UShort)\\b" :token :keyword-type}
   {:pattern "(?<!\\.)(AddressOf|And|AndAlso|As|GetType|In|Is|IsNot|Like|Mod|Or|OrElse|TypeOf|Xor)\\b" :token :operator}
   {:pattern "&=|[*]=|/=|\\\\=|\\^=|\\+=|-=|<<=|>>=|<<|>>|:=|<=|>=|<>|[-&*/\\\\^+=<>\\[\\]]" :token :operator}
   {:pattern "_\\n" :token :text}
   {:pattern "[_\\w][\\w]*" :token :text}
   {:pattern "#.*?#" :token :string}
   {:pattern "(\\d+\\.\\d*|\\d*\\.\\d+)(F[+-]?[0-9]+)?" :token :number}
   {:pattern "\\d+([SILDFR]|US|UI|UL)?" :token :number}
   {:pattern "&H[0-9a-f]+([SILDFR]|US|UI|UL)?" :token :number}
   {:pattern "&O[0-7]+([SILDFR]|US|UI|UL)?" :token :number}])
