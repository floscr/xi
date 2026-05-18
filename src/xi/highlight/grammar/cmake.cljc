(ns xi.highlight.grammar.cmake)

(def cmake
  [   {:pattern "\\b(WIN32|UNIX|APPLE|CYGWIN|BORLAND|MINGW|MSVC|MSVC_IDE|MSVC60|MSVC70|MSVC71|MSVC80|MSVC90)\\b" :token :keyword}
   {:pattern "[ \\t]+" :token :text}
   {:pattern "#.*\\n" :token :comment}])
