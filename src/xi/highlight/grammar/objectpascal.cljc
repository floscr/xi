(ns xi.highlight.grammar.objectpascal)

(def objectpascal
  [   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "[^\\u0000-\\u007F]+" :token :text}
   {:pattern "\\{[$].*?\\}|\\{[-](NOD|EXT|OBJ).*?\\}|\\([*][$].*?[*]\\)" :token :comment}
   {:pattern "//.*" :token :comment}
   {:pattern "\\([*](.|\\n)*?[*]\\)" :token :comment}
   {:pattern "[{](.|\\n)*?[}]" :token :comment}
   {:pattern "(?i:(\\.\\.))" :token :operator}
   {:pattern "[\\#][0-9a-fA-F]*|[0-9]+[xX][0-9a-fA-F]*" :token :string}
   {:pattern "[\\$][0-9a-fA-F]*[xX][0-9a-fA-F]*|[\\$][0-9a-fA-F]*|([0-9]+[0-9a-fA-F]+(?=[hH]))" :token :number}
   {:pattern "[0-9]+(\\'[0-9]+)*\\.[0-9]+(\\'[0-9]+)*[eE][+-]?[0-9]+(\\'[0-9]+)*|[0-9]+(\\'[0-9]+)*\\.[0-9]+(\\'[0-9]+)*|\\d+[eE][+-]?[0-9]+" :token :number}
   {:pattern "0|[1-9][0-9_]*?" :token :number}
   {:pattern "('''\\s*\\n)(.|\\n)*?(''')(?=\\s*&#59;)" :token :string}
   {:pattern "(?i:(\\')).*?(?i:(\\'))" :token :string}
   {:pattern "(?i:(\")).*?(?i:(\"))" :token :string}
   {:pattern "\\b(?!=\\.)(?i:(NativeInt|NativeUInt|LongInt|LongWord|Integer|Int64|Cardinal|UInt64|ShortInt|SmallInt|FixedInt|Byte|Word|FixedUInt|Int8|Int16|Int32|UInt8|UInt16|UInt32|Real48|Single|Double|Real|Extended|Comp|Currency|Char|AnsiChar|WideChar|UCS2Char|UCS4Char|string|ShortString|AnsiString|UnicodeString|WideString|RawByteString|UTF8String|File|TextFile|Text|Boolean|ByteBool|WordBool|LongBool|Pointer|Variant|OleVariant))\\b(?![&#60;\\/(])" :token :keyword-type}
   {:pattern "\\b(?!=\\.)(?i:(TSingleRec|TDoubleRec|TExtended80Rec|TByteArray|TTextBuf|TVarRec|TWordArray))\\b(?![&#60;\\/(])" :token :keyword-type}
   {:pattern "\\b(?!=\\.)(?i:(PChar|PAnsiChar|PWideChar|PRawByteString|PUnicodeString|PString|PAnsiString|PShortString|PTextBuf|PWideString|PByte|PShortInt|PWord|PSmallInt|PCardinal|PLongWord|PFixedUInt|PLongint|PFixedInt|PUInt64|PInt64|PNativeUInt|PNativeInt|PByteArray|PCurrency|PDouble|PExtended|PSingle|PInteger|POleVariant|PVarRec|PVariant|PWordArray|PBoolean|PWordBool|PLongBool|PPointer))\\b(?![&#60;\\/(])" :token :keyword-type}
   {:pattern "\\b(?!=\\.)(?i:(IntPtr|UIntPtr|Float32|Float64|_ShortStr|_ShortString|_AnsiStr|_AnsiString|_AnsiChr|_AnsiChar|_WideStr|_WideString|_PAnsiChr|_PAnsiChar|UTF8Char|_AnsiChar|PUTF8Char|_PAnsiChar|MarshaledString|MarshaledAString))\\b(?![&#60;\\/(])" :token :keyword-type}
   {:pattern "\\b(?!=\\.)(?i:(Result))\\b(?![&#60;\\/(])" :token :text}
   {:pattern "\\b(?!=\\.)(?i:(True|False))\\b(?![&#60;\\/(])" :token :name-var}
   {:pattern "[(\\:\\=)]" :token :operator}
   {:pattern "[\\+\\-\\*\\/\\^&#60;&#62;\\=\\@]" :token :operator}
   {:pattern "\\b(?i:([div][mod][not][and][or][xor][shl][shr][in]))\\b" :token :operator}
   {:pattern "[&#38;\\#\\$\\%]" :token :operator}
   {:pattern "[\\(\\)\\,\\.\\:\\;\\[\\]]" :token :punctuation}
   {:pattern "\\b(?!=\\.)(?i:(and|end|interface|record|var|array|except|is|repeat|while|as|exports|label|resourcestring|with|asm|file|library|set|xor|begin|finalization|mod|shl|case|finally|nil|shr|class|for|not|string|const|function|object|then|constructor|goto|of|threadvar|destructor|if|or|to|dispinterface|implementation|packed|try|div|in|procedure|type|do|inherited|program|unit|downto|initialization|property|until|else|inline|raise|uses))\\b(?![&#60;\\/(])" :token :keyword}
   {:pattern "\\b(?!=\\.)(?i:(absolute|export|name|public|stdcall|abstract|external|published|strict|assembler|nodefault|read|stored|automated|final|operator|readonly|unsafe|cdecl|forward|out|reference|varargs|contains|helper|overload|register|virtual|default|implements|override|reintroduce|winapi|delayed|index|package|requires|write|deprecated|inline|pascal|writeonly|dispid|library|platform|safecall|dynamic|local|private|sealed|experimental|message|protected|static))\\b(?![&#60;\\/(])" :token :keyword}
   {:pattern "\\b(?!=\\.)(?i:(near|far|resident))\\b(?![&#60;\\/(])" :token :keyword}
   {:pattern "\\b(?!=\\.)(?i:(Abs|High|Low|Pred|Succ|Chr|Length|Odd|Round|Swap|Hi|Lo|Ord|SizeOf|Trunc))\\b(?![&#60;\\/(])" :token :keyword}
   {:pattern "([^\\W\\d]|\\$)[\\w$]*" :token :text}])
