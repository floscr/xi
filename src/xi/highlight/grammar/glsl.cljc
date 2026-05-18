(ns xi.highlight.grammar.glsl)

(def glsl
  [   {:pattern "^#.*" :token :comment}
   {:pattern "//.*" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "\\+|-|~|!=?|\\*|/|%|<<|>>|<=?|>=?|==?|&&?|\\^|\\|\\|?" :token :operator}
   {:pattern "[?:]" :token :operator}
   {:pattern "\\bdefined\\b" :token :operator}
   {:pattern "[;{}(),\\[\\]]" :token :punctuation}
   {:pattern "[+-]?\\d*\\.\\d+([eE][-+]?\\d+)?" :token :number}
   {:pattern "[+-]?\\d+\\.\\d*([eE][-+]?\\d+)?" :token :number}
   {:pattern "0[xX][0-9a-fA-F]*" :token :number}
   {:pattern "0[0-7]*" :token :number}
   {:pattern "[1-9][0-9]*" :token :number}
   {:pattern "\\b(sampler3DsamplerCube|sampler2DShadow|sampler1DShadow|invariant|sampler1D|sampler2D|attribute|mat3mat4|centroid|continue|varying|uniform|discard|mat4x4|mat3x3|mat2x3|mat4x2|mat3x2|mat2x2|mat2x4|mat3x4|struct|return|mat4x3|bvec4|false|ivec4|ivec3|const|float|inout|ivec2|break|while|bvec3|bvec2|vec3|else|true|void|bool|vec2|vec4|mat2|for|out|int|in|do|if)\\b" :token :keyword}
   {:pattern "\\b(sampler2DRectShadow|sampler2DRect|sampler3DRect|namespace|precision|interface|volatile|template|unsigned|external|noinline|mediump|typedef|default|switch|static|extern|inline|sizeof|output|packed|double|public|fvec3|class|union|short|highp|fixed|input|fvec4|hvec2|hvec3|hvec4|dvec2|dvec3|dvec4|fvec2|using|long|this|enum|lowp|cast|goto|half|asm)\\b" :token :keyword}
   {:pattern "[a-zA-Z_]\\w*" :token :text}
   {:pattern "\\." :token :punctuation}
   {:pattern "\\s+" :token :text}])
