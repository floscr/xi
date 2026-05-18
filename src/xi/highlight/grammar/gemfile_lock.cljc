(ns xi.highlight.grammar.gemfile-lock)

(def gemfile-lock
  [   {:pattern "^(GIT|PATH|GEM|PLUGIN SOURCE|PLATFORMS|DEPENDENCIES|BUNDLED WITH|RUBY VERSION|CHECKSUMS)$" :token :keyword}
   {:pattern "!" :token :operator}
   {:pattern "https?://\\S+" :token :string-symbol}
   {:pattern "git@\\S+" :token :string-symbol}
   {:pattern "sha\\d+=[A-Fa-f0-9]+" :token :number}
   {:pattern "\\b[a-f0-9]{7,40}\\b" :token :number}
   {:pattern "\\b\\d[\\w.]*" :token :number}
   {:pattern "[A-Za-z_][A-Za-z0-9_.-]*" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "[ \\t]+" :token :text}
   {:pattern "." :token :text}])
