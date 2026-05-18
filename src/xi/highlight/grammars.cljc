(ns xi.highlight.grammars
  "Registry of all language grammars for syntax highlighting.
   Individual grammars live in xi.highlight.grammar.* namespaces.
   Auto-generated — do not edit by hand."
  (:require
   [xi.highlight.grammar.abap :refer [abap]]
   [xi.highlight.grammar.abnf :refer [abnf]]
   [xi.highlight.grammar.actionscript :refer [actionscript]]
   [xi.highlight.grammar.actionscript-3 :refer [actionscript-3]]
   [xi.highlight.grammar.ada :refer [ada]]
   [xi.highlight.grammar.agda :refer [agda]]
   [xi.highlight.grammar.al :refer [al]]
   [xi.highlight.grammar.alloy :refer [alloy]]
   [xi.highlight.grammar.ampl :refer [ampl]]
   [xi.highlight.grammar.angular2 :refer [angular2]]
   [xi.highlight.grammar.antlr :refer [antlr]]
   [xi.highlight.grammar.apacheconf :refer [apacheconf]]
   [xi.highlight.grammar.apl :refer [apl]]
   [xi.highlight.grammar.applescript :refer [applescript]]
   [xi.highlight.grammar.arangodb-aql :refer [arangodb-aql]]
   [xi.highlight.grammar.arduino :refer [arduino]]
   [xi.highlight.grammar.armasm :refer [armasm]]
   [xi.highlight.grammar.arturo :refer [arturo]]
   [xi.highlight.grammar.atl :refer [atl]]
   [xi.highlight.grammar.autohotkey :refer [autohotkey]]
   [xi.highlight.grammar.autoit :refer [autoit]]
   [xi.highlight.grammar.awk :refer [awk]]
   [xi.highlight.grammar.ballerina :refer [ballerina]]
   [xi.highlight.grammar.bash :refer [bash]]
   [xi.highlight.grammar.bash-session :refer [bash-session]]
   [xi.highlight.grammar.batchfile :refer [batchfile]]
   [xi.highlight.grammar.beef :refer [beef]]
   [xi.highlight.grammar.bibtex :refer [bibtex]]
   [xi.highlight.grammar.bicep :refer [bicep]]
   [xi.highlight.grammar.blitzbasic :refer [blitzbasic]]
   [xi.highlight.grammar.bnf :refer [bnf]]
   [xi.highlight.grammar.bqn :refer [bqn]]
   [xi.highlight.grammar.brainfuck :refer [brainfuck]]
   [xi.highlight.grammar.c :refer [c]]
   [xi.highlight.grammar.c3 :refer [c3]]
   [xi.highlight.grammar.cap-sharp39-n-proto :refer [cap-sharp39-n-proto]]
   [xi.highlight.grammar.cassandra-cql :refer [cassandra-cql]]
   [xi.highlight.grammar.ceylon :refer [ceylon]]
   [xi.highlight.grammar.cfengine3 :refer [cfengine3]]
   [xi.highlight.grammar.cfstatement :refer [cfstatement]]
   [xi.highlight.grammar.chaiscript :refer [chaiscript]]
   [xi.highlight.grammar.chapel :refer [chapel]]
   [xi.highlight.grammar.cheetah :refer [cheetah]]
   [xi.highlight.grammar.clojure :refer [clojure]]
   [xi.highlight.grammar.cmake :refer [cmake]]
   [xi.highlight.grammar.cobol :refer [cobol]]
   [xi.highlight.grammar.coffeescript :refer [coffeescript]]
   [xi.highlight.grammar.coq :refer [coq]]
   [xi.highlight.grammar.core :refer [core]]
   [xi.highlight.grammar.cplusplus :refer [cplusplus]]
   [xi.highlight.grammar.crystal :refer [crystal]]
   [xi.highlight.grammar.css :refer [css]]
   [xi.highlight.grammar.csv :refer [csv]]
   [xi.highlight.grammar.cue :refer [cue]]
   [xi.highlight.grammar.cython :refer [cython]]
   [xi.highlight.grammar.d :refer [d]]
   [xi.highlight.grammar.dart :refer [dart]]
   [xi.highlight.grammar.dax :refer [dax]]
   [xi.highlight.grammar.desktop-file :refer [desktop-file]]
   [xi.highlight.grammar.devicetree :refer [devicetree]]
   [xi.highlight.grammar.diff :refer [diff]]
   [xi.highlight.grammar.django-jinja :refer [django-jinja]]
   [xi.highlight.grammar.dns :refer [dns]]
   [xi.highlight.grammar.docker :refer [docker]]
   [xi.highlight.grammar.dtd :refer [dtd]]
   [xi.highlight.grammar.dylan :refer [dylan]]
   [xi.highlight.grammar.ebnf :refer [ebnf]]
   [xi.highlight.grammar.elixir :refer [elixir]]
   [xi.highlight.grammar.elm :refer [elm]]
   [xi.highlight.grammar.erb :refer [erb]]
   [xi.highlight.grammar.erlang :refer [erlang]]
   [xi.highlight.grammar.factor :refer [factor]]
   [xi.highlight.grammar.fennel :refer [fennel]]
   [xi.highlight.grammar.fish :refer [fish]]
   [xi.highlight.grammar.forth :refer [forth]]
   [xi.highlight.grammar.fortran :refer [fortran]]
   [xi.highlight.grammar.fortranfixed :refer [fortranfixed]]
   [xi.highlight.grammar.fsharp :refer [fsharp]]
   [xi.highlight.grammar.gas :refer [gas]]
   [xi.highlight.grammar.gdscript :refer [gdscript]]
   [xi.highlight.grammar.gdscript3 :refer [gdscript3]]
   [xi.highlight.grammar.gemfile-lock :refer [gemfile-lock]]
   [xi.highlight.grammar.gettext :refer [gettext]]
   [xi.highlight.grammar.gherkin :refer [gherkin]]
   [xi.highlight.grammar.gleam :refer [gleam]]
   [xi.highlight.grammar.glsl :refer [glsl]]
   [xi.highlight.grammar.gnuplot :refer [gnuplot]]
   [xi.highlight.grammar.go-template :refer [go-template]]
   [xi.highlight.grammar.graphql :refer [graphql]]
   [xi.highlight.grammar.handlebars :refer [handlebars]]
   [xi.highlight.grammar.hare :refer [hare]]
   [xi.highlight.grammar.haskell :refer [haskell]]
   [xi.highlight.grammar.hcl :refer [hcl]]
   [xi.highlight.grammar.hexdump :refer [hexdump]]
   [xi.highlight.grammar.hlb :refer [hlb]]
   [xi.highlight.grammar.hlsl :refer [hlsl]]
   [xi.highlight.grammar.holyc :refer [holyc]]
   [xi.highlight.grammar.html :refer [html]]
   [xi.highlight.grammar.hy :refer [hy]]
   [xi.highlight.grammar.idris :refer [idris]]
   [xi.highlight.grammar.igor :refer [igor]]
   [xi.highlight.grammar.ini :refer [ini]]
   [xi.highlight.grammar.io :refer [io]]
   [xi.highlight.grammar.iscdhcpd :refer [iscdhcpd]]
   [xi.highlight.grammar.j :refer [j]]
   [xi.highlight.grammar.janet :refer [janet]]
   [xi.highlight.grammar.java :refer [java]]
   [xi.highlight.grammar.javascript :refer [javascript]]
   [xi.highlight.grammar.json :refer [json]]
   [xi.highlight.grammar.jsonata :refer [jsonata]]
   [xi.highlight.grammar.jsonnet :refer [jsonnet]]
   [xi.highlight.grammar.julia :refer [julia]]
   [xi.highlight.grammar.jungle :refer [jungle]]
   [xi.highlight.grammar.kakoune :refer [kakoune]]
   [xi.highlight.grammar.kdl :refer [kdl]]
   [xi.highlight.grammar.kotlin :refer [kotlin]]
   [xi.highlight.grammar.lateralus :refer [lateralus]]
   [xi.highlight.grammar.lean4 :refer [lean4]]
   [xi.highlight.grammar.lighttpd-configuration-file :refer [lighttpd-configuration-file]]
   [xi.highlight.grammar.lilypond :refer [lilypond]]
   [xi.highlight.grammar.llvm :refer [llvm]]
   [xi.highlight.grammar.lox :refer [lox]]
   [xi.highlight.grammar.lua :refer [lua]]
   [xi.highlight.grammar.makefile :refer [makefile]]
   [xi.highlight.grammar.mako :refer [mako]]
   [xi.highlight.grammar.mason :refer [mason]]
   [xi.highlight.grammar.materialize-sql-dialect :refer [materialize-sql-dialect]]
   [xi.highlight.grammar.mathematica :refer [mathematica]]
   [xi.highlight.grammar.matlab :refer [matlab]]
   [xi.highlight.grammar.mcfunction :refer [mcfunction]]
   [xi.highlight.grammar.meson :refer [meson]]
   [xi.highlight.grammar.metal :refer [metal]]
   [xi.highlight.grammar.microcad :refer [microcad]]
   [xi.highlight.grammar.minizinc :refer [minizinc]]
   [xi.highlight.grammar.mlir :refer [mlir]]
   [xi.highlight.grammar.modelica :refer [modelica]]
   [xi.highlight.grammar.modula-2 :refer [modula-2]]
   [xi.highlight.grammar.mojo :refer [mojo]]
   [xi.highlight.grammar.monkeyc :refer [monkeyc]]
   [xi.highlight.grammar.moonbit :refer [moonbit]]
   [xi.highlight.grammar.moonscript :refer [moonscript]]
   [xi.highlight.grammar.morrowindscript :refer [morrowindscript]]
   [xi.highlight.grammar.myghty :refer [myghty]]
   [xi.highlight.grammar.mysql :refer [mysql]]
   [xi.highlight.grammar.nasm :refer [nasm]]
   [xi.highlight.grammar.natural :refer [natural]]
   [xi.highlight.grammar.newspeak :refer [newspeak]]
   [xi.highlight.grammar.nginx-configuration-file :refer [nginx-configuration-file]]
   [xi.highlight.grammar.nim :refer [nim]]
   [xi.highlight.grammar.nix :refer [nix]]
   [xi.highlight.grammar.nsis :refer [nsis]]
   [xi.highlight.grammar.nu :refer [nu]]
   [xi.highlight.grammar.objective-c :refer [objective-c]]
   [xi.highlight.grammar.objectpascal :refer [objectpascal]]
   [xi.highlight.grammar.ocaml :refer [ocaml]]
   [xi.highlight.grammar.octave :refer [octave]]
   [xi.highlight.grammar.odin :refer [odin]]
   [xi.highlight.grammar.onesenterprise :refer [onesenterprise]]
   [xi.highlight.grammar.openedge-abl :refer [openedge-abl]]
   [xi.highlight.grammar.openscad :refer [openscad]]
   [xi.highlight.grammar.org-mode :refer [org-mode]]
   [xi.highlight.grammar.pacmanconf :refer [pacmanconf]]
   [xi.highlight.grammar.perl :refer [perl]]
   [xi.highlight.grammar.php :refer [php]]
   [xi.highlight.grammar.pig :refer [pig]]
   [xi.highlight.grammar.pkgconfig :refer [pkgconfig]]
   [xi.highlight.grammar.pl-pgsql :refer [pl-pgsql]]
   [xi.highlight.grammar.plaintext :refer [plaintext]]
   [xi.highlight.grammar.plutus-core :refer [plutus-core]]
   [xi.highlight.grammar.pony :refer [pony]]
   [xi.highlight.grammar.postgresql-sql-dialect :refer [postgresql-sql-dialect]]
   [xi.highlight.grammar.postscript :refer [postscript]]
   [xi.highlight.grammar.povray :refer [povray]]
   [xi.highlight.grammar.powerquery :refer [powerquery]]
   [xi.highlight.grammar.powershell :refer [powershell]]
   [xi.highlight.grammar.prolog :refer [prolog]]
   [xi.highlight.grammar.promela :refer [promela]]
   [xi.highlight.grammar.promql :refer [promql]]
   [xi.highlight.grammar.properties :refer [properties]]
   [xi.highlight.grammar.protocol-buffer :refer [protocol-buffer]]
   [xi.highlight.grammar.protocol-buffer-text-format :refer [protocol-buffer-text-format]]
   [xi.highlight.grammar.prql :refer [prql]]
   [xi.highlight.grammar.psl :refer [psl]]
   [xi.highlight.grammar.puppet :refer [puppet]]
   [xi.highlight.grammar.python :refer [python]]
   [xi.highlight.grammar.python-2 :refer [python-2]]
   [xi.highlight.grammar.qbasic :refer [qbasic]]
   [xi.highlight.grammar.qml :refer [qml]]
   [xi.highlight.grammar.r :refer [r]]
   [xi.highlight.grammar.racket :refer [racket]]
   [xi.highlight.grammar.ragel :refer [ragel]]
   [xi.highlight.grammar.react :refer [react]]
   [xi.highlight.grammar.reasonml :refer [reasonml]]
   [xi.highlight.grammar.reg :refer [reg]]
   [xi.highlight.grammar.rego :refer [rego]]
   [xi.highlight.grammar.rexx :refer [rexx]]
   [xi.highlight.grammar.rgbds-assembly :refer [rgbds-assembly]]
   [xi.highlight.grammar.ring :refer [ring]]
   [xi.highlight.grammar.rpgle :refer [rpgle]]
   [xi.highlight.grammar.rpmspec :refer [rpmspec]]
   [xi.highlight.grammar.ruby :refer [ruby]]
   [xi.highlight.grammar.rust :refer [rust]]
   [xi.highlight.grammar.sas :refer [sas]]
   [xi.highlight.grammar.sass :refer [sass]]
   [xi.highlight.grammar.scala :refer [scala]]
   [xi.highlight.grammar.scdoc :refer [scdoc]]
   [xi.highlight.grammar.scheme :refer [scheme]]
   [xi.highlight.grammar.scilab :refer [scilab]]
   [xi.highlight.grammar.scss :refer [scss]]
   [xi.highlight.grammar.sed :refer [sed]]
   [xi.highlight.grammar.sieve :refer [sieve]]
   [xi.highlight.grammar.smali :refer [smali]]
   [xi.highlight.grammar.smalltalk :refer [smalltalk]]
   [xi.highlight.grammar.smarty :refer [smarty]]
   [xi.highlight.grammar.snbt :refer [snbt]]
   [xi.highlight.grammar.snobol :refer [snobol]]
   [xi.highlight.grammar.solidity :refer [solidity]]
   [xi.highlight.grammar.sourcepawn :refer [sourcepawn]]
   [xi.highlight.grammar.spade :refer [spade]]
   [xi.highlight.grammar.sparql :refer [sparql]]
   [xi.highlight.grammar.sql :refer [sql]]
   [xi.highlight.grammar.squidconf :refer [squidconf]]
   [xi.highlight.grammar.stas :refer [stas]]
   [xi.highlight.grammar.stylus :refer [stylus]]
   [xi.highlight.grammar.swift :refer [swift]]
   [xi.highlight.grammar.systemd :refer [systemd]]
   [xi.highlight.grammar.systemverilog :refer [systemverilog]]
   [xi.highlight.grammar.tablegen :refer [tablegen]]
   [xi.highlight.grammar.tal :refer [tal]]
   [xi.highlight.grammar.tasm :refer [tasm]]
   [xi.highlight.grammar.tcl :refer [tcl]]
   [xi.highlight.grammar.tcsh :refer [tcsh]]
   [xi.highlight.grammar.termcap :refer [termcap]]
   [xi.highlight.grammar.terminfo :refer [terminfo]]
   [xi.highlight.grammar.terraform :refer [terraform]]
   [xi.highlight.grammar.tex :refer [tex]]
   [xi.highlight.grammar.thrift :refer [thrift]]
   [xi.highlight.grammar.toml :refer [toml]]
   [xi.highlight.grammar.tradingview :refer [tradingview]]
   [xi.highlight.grammar.transact-sql :refer [transact-sql]]
   [xi.highlight.grammar.turing :refer [turing]]
   [xi.highlight.grammar.turtle :refer [turtle]]
   [xi.highlight.grammar.twig :refer [twig]]
   [xi.highlight.grammar.typescript :refer [typescript]]
   [xi.highlight.grammar.typoscript :refer [typoscript]]
   [xi.highlight.grammar.typoscriptcssdata :refer [typoscriptcssdata]]
   [xi.highlight.grammar.typoscripthtmldata :refer [typoscripthtmldata]]
   [xi.highlight.grammar.typst :refer [typst]]
   [xi.highlight.grammar.ucode :refer [ucode]]
   [xi.highlight.grammar.v :refer [v]]
   [xi.highlight.grammar.v-shell :refer [v-shell]]
   [xi.highlight.grammar.vala :refer [vala]]
   [xi.highlight.grammar.vb-net :refer [vb-net]]
   [xi.highlight.grammar.verilog :refer [verilog]]
   [xi.highlight.grammar.vhdl :refer [vhdl]]
   [xi.highlight.grammar.vhs :refer [vhs]]
   [xi.highlight.grammar.viml :refer [viml]]
   [xi.highlight.grammar.vue :refer [vue]]
   [xi.highlight.grammar.wdte :refer [wdte]]
   [xi.highlight.grammar.webassembly-text-format :refer [webassembly-text-format]]
   [xi.highlight.grammar.webgpu-shading-language :refer [webgpu-shading-language]]
   [xi.highlight.grammar.whiley :refer [whiley]]
   [xi.highlight.grammar.xml :refer [xml]]
   [xi.highlight.grammar.xorg :refer [xorg]]
   [xi.highlight.grammar.yaml :refer [yaml]]
   [xi.highlight.grammar.yang :refer [yang]]
   [xi.highlight.grammar.z80-assembly :refer [z80-assembly]]
   [xi.highlight.grammar.zed :refer [zed]]
   [xi.highlight.grammar.zig :refer [zig]]))

(def registry
  {   "abap" abap
   "abnf" abnf
   "actionscript" actionscript
   "as" actionscript
   "actionscript-3" actionscript-3
   "as3" actionscript-3
   "actionscript3" actionscript-3
   "ada" ada
   "ada95" ada
   "ada2005" ada
   "adb" ada
   "ads" ada
   "agda" agda
   "al" al
   "dal" al
   "alloy" alloy
   "als" alloy
   "ampl" ampl
   "mod" ampl
   "run" ampl
   "angular2" angular2
   "ng2" angular2
   "antlr" antlr
   "apacheconf" apacheconf
   "aconf" apacheconf
   "apache" apacheconf
   "apl" apl
   "applescript" applescript
   "arangodb-aql" arangodb-aql
   "aql" arangodb-aql
   "arduino" arduino
   "ino" arduino
   "armasm" armasm
   "s" armasm
   "arturo" arturo
   "art" arturo
   "atl" atl
   "autohotkey" autohotkey
   "ahk" autohotkey
   "ahkl" autohotkey
   "autoit" autoit
   "au3" autoit
   "awk" awk
   "gawk" awk
   "mawk" awk
   "nawk" awk
   "ballerina" ballerina
   "bal" ballerina
   "bash" bash
   "sh" bash
   "ksh" bash
   "zsh" bash
   "shell" bash
   "ebuild" bash
   "eclass" bash
   "env" bash
   "exlib" bash
   "zshrc" bash
   "bashrc" bash
   "apkbuild" bash
   "pkgbuild" bash
   "bash-session" bash-session
   "console" bash-session
   "shell-session" bash-session
   "batchfile" batchfile
   "bat" batchfile
   "batch" batchfile
   "dosbatch" batchfile
   "winbatch" batchfile
   "cmd" batchfile
   "beef" beef
   "bf" beef
   "bibtex" bibtex
   "bib" bibtex
   "bicep" bicep
   "blitzbasic" blitzbasic
   "b3d" blitzbasic
   "bplus" blitzbasic
   "bb" blitzbasic
   "decls" blitzbasic
   "bnf" bnf
   "bqn" bqn
   "brainfuck" brainfuck
   "b" brainfuck
   "c" c
   "h" c
   "idc" c
   "c3" c3
   "c3i" c3
   "c3t" c3
   "cap-sharp39-n-proto" cap-sharp39-n-proto
   "capnp" cap-sharp39-n-proto
   "cassandra-cql" cassandra-cql
   "cassandra" cassandra-cql
   "cql" cassandra-cql
   "ceylon" ceylon
   "cfengine3" cfengine3
   "cf3" cfengine3
   "cf" cfengine3
   "cfstatement" cfstatement
   "cfs" cfstatement
   "chaiscript" chaiscript
   "chai" chaiscript
   "chapel" chapel
   "chpl" chapel
   "cheetah" cheetah
   "spitfire" cheetah
   "tmpl" cheetah
   "spt" cheetah
   "clojure" clojure
   "clj" clojure
   "cljs" clojure
   "cljc" clojure
   "edn" clojure
   "cmake" cmake
   "cobol" cobol
   "cob" cobol
   "cpy" cobol
   "coffeescript" coffeescript
   "coffee-script" coffeescript
   "coffee" coffeescript
   "coq" coq
   "v" coq
   "core" core
   "cplusplus" cplusplus
   "cpp" cplusplus
   "c++" cplusplus
   "hpp" cplusplus
   "cc" cplusplus
   "hh" cplusplus
   "cxx" cplusplus
   "hxx" cplusplus
   "cp" cplusplus
   "tpp" cplusplus
   "crystal" crystal
   "cr" crystal
   "css" css
   "csv" csv
   "cue" cue
   "cython" cython
   "pyx" cython
   "pyrex" cython
   "pxd" cython
   "pxi" cython
   "d" d
   "di" d
   "dart" dart
   "dax" dax
   "desktop-file" desktop-file
   "desktop" desktop-file
   "desktop_entry" desktop-file
   "devicetree" devicetree
   "dts" devicetree
   "dtsi" devicetree
   "diff" diff
   "udiff" diff
   "patch" diff
   "django-jinja" django-jinja
   "django" django-jinja
   "jinja" django-jinja
   "dns" dns
   "zone" dns
   "bind" dns
   "docker" docker
   "dockerfile" docker
   "containerfile" docker
   "dtd" dtd
   "dylan" dylan
   "dyl" dylan
   "intr" dylan
   "ebnf" ebnf
   "elixir" elixir
   "ex" elixir
   "exs" elixir
   "eex" elixir
   "elm" elm
   "erb" erb
   "erlang" erlang
   "erl" erlang
   "hrl" erlang
   "es" erlang
   "escript" erlang
   "factor" factor
   "fennel" fennel
   "fnl" fennel
   "fish" fish
   "fishshell" fish
   "load" fish
   "forth" forth
   "frt" forth
   "fth" forth
   "fs" forth
   "fortran" fortran
   "f90" fortran
   "f03" fortran
   "f95" fortran
   "fortranfixed" fortranfixed
   "f" fortranfixed
   "fsharp" fsharp
   "fsi" fsharp
   "gas" gas
   "asm" gas
   "gdscript" gdscript
   "gd" gdscript
   "gdscript3" gdscript3
   "gd3" gdscript3
   "gemfile-lock" gemfile-lock
   "gemfilelock" gemfile-lock
   "gettext" gettext
   "pot" gettext
   "po" gettext
   "gherkin" gherkin
   "cucumber" gherkin
   "feature" gherkin
   "gleam" gleam
   "glsl" glsl
   "vert" glsl
   "frag" glsl
   "geo" glsl
   "gnuplot" gnuplot
   "plot" gnuplot
   "plt" gnuplot
   "go-template" go-template
   "gotmpl" go-template
   "graphql" graphql
   "graphqls" graphql
   "gql" graphql
   "handlebars" handlebars
   "hbs" handlebars
   "hare" hare
   "ha" hare
   "haskell" haskell
   "hs" haskell
   "hcl" hcl
   "hexdump" hexdump
   "hlb" hlb
   "hlsl" hlsl
   "hlsli" hlsl
   "cginc" hlsl
   "fx" hlsl
   "fxh" hlsl
   "holyc" holyc
   "hc" holyc
   "html" html
   "htm" html
   "xhtml" html
   "xslt" html
   "hy" hy
   "hylang" hy
   "idris" idris
   "idr" idris
   "igor" igor
   "igorpro" igor
   "ipf" igor
   "ini" ini
   "cfg" ini
   "dosini" ini
   "inf" ini
   "service" ini
   "socket" ini
   "container" ini
   "network" ini
   "build" ini
   "pod" ini
   "kube" ini
   "volume" ini
   "image" ini
   "pylintrc" ini
   "io" io
   "iscdhcpd" iscdhcpd
   "j" j
   "ijs" j
   "janet" janet
   "jdn" janet
   "java" java
   "javascript" javascript
   "js" javascript
   "jsm" javascript
   "mjs" javascript
   "cjs" javascript
   "json" json
   "jsonl" json
   "jsonc" json
   "json5" json
   "avsc" json
   "jsonata" jsonata
   "jsonnet" jsonnet
   "libsonnet" jsonnet
   "julia" julia
   "jl" julia
   "jungle" jungle
   "kakoune" kakoune
   "kak" kakoune
   "kakrc" kakoune
   "kakscript" kakoune
   "kdl" kdl
   "kotlin" kotlin
   "kt" kotlin
   "kts" kotlin
   "lateralus" lateralus
   "ltl" lateralus
   "lean4" lean4
   "lean" lean4
   "lighttpd-configuration-file" lighttpd-configuration-file
   "lighty" lighttpd-configuration-file
   "lighttpd" lighttpd-configuration-file
   "lilypond" lilypond
   "ly" lilypond
   "llvm" llvm
   "ll" llvm
   "lox" lox
   "lua" lua
   "wlua" lua
   "makefile" makefile
   "make" makefile
   "mf" makefile
   "bsdmake" makefile
   "mak" makefile
   "mk" makefile
   "gnumakefile" makefile
   "bsdmakefile" makefile
   "justfile" makefile
   "mako" mako
   "mao" mako
   "mason" mason
   "m" mason
   "mhtml" mason
   "mc" mason
   "mi" mason
   "autohandler" mason
   "dhandler" mason
   "materialize-sql-dialect" materialize-sql-dialect
   "materialize" materialize-sql-dialect
   "mzsql" materialize-sql-dialect
   "mathematica" mathematica
   "mma" mathematica
   "nb" mathematica
   "cdf" mathematica
   "ma" mathematica
   "mt" mathematica
   "mx" mathematica
   "nbp" mathematica
   "wl" mathematica
   "matlab" matlab
   "mcfunction" mcfunction
   "mcf" mcfunction
   "meson" meson
   "meson.build" meson
   "metal" metal
   "microcad" microcad
   "µcad" microcad
   "ucad" microcad
   "mcad" microcad
   "minizinc" minizinc
   "mzn" minizinc
   "dzn" minizinc
   "fzn" minizinc
   "mlir" mlir
   "modelica" modelica
   "mo" modelica
   "modula-2" modula-2
   "modula2" modula-2
   "m2" modula-2
   "def" modula-2
   "mojo" mojo
   "🔥" mojo
   "monkeyc" monkeyc
   "moonbit" moonbit
   "mbt" moonbit
   "moonscript" moonscript
   "moon" moonscript
   "morrowindscript" morrowindscript
   "morrowind" morrowindscript
   "mwscript" morrowindscript
   "myghty" myghty
   "myt" myghty
   "autodelegate" myghty
   "mysql" mysql
   "mariadb" mysql
   "sql" mysql
   "nasm" nasm
   "natural" natural
   "nsn" natural
   "nsp" natural
   "nss" natural
   "nsh" natural
   "nsg" natural
   "nsl" natural
   "nsa" natural
   "nsm" natural
   "nsc" natural
   "ns7" natural
   "newspeak" newspeak
   "ns2" newspeak
   "nginx-configuration-file" nginx-configuration-file
   "nginx" nginx-configuration-file
   "nim" nim
   "nimrod" nim
   "nix" nix
   "nixos" nix
   "nsis" nsis
   "nsi" nsis
   "nu" nu
   "objective-c" objective-c
   "objectivec" objective-c
   "obj-c" objective-c
   "objc" objective-c
   "objectpascal" objectpascal
   "pas" objectpascal
   "pp" objectpascal
   "inc" objectpascal
   "dpr" objectpascal
   "dpk" objectpascal
   "lpr" objectpascal
   "lpk" objectpascal
   "ocaml" ocaml
   "ml" ocaml
   "mli" ocaml
   "mll" ocaml
   "mly" ocaml
   "octave" octave
   "odin" odin
   "onesenterprise" onesenterprise
   "ones" onesenterprise
   "1s" onesenterprise
   "1s:enterprise" onesenterprise
   "epf" onesenterprise
   "erf" onesenterprise
   "openedge-abl" openedge-abl
   "openedge" openedge-abl
   "abl" openedge-abl
   "progress" openedge-abl
   "openedgeabl" openedge-abl
   "p" openedge-abl
   "cls" openedge-abl
   "w" openedge-abl
   "i" openedge-abl
   "openscad" openscad
   "scad" openscad
   "org-mode" org-mode
   "org" org-mode
   "orgmode" org-mode
   "pacmanconf" pacmanconf
   "perl" perl
   "pl" perl
   "pm" perl
   "t" perl
   "php" php
   "php3" php
   "php4" php
   "php5" php
   "pig" pig
   "pkgconfig" pkgconfig
   "pc" pkgconfig
   "pl-pgsql" pl-pgsql
   "plpgsql" pl-pgsql
   "plaintext" plaintext
   "text" plaintext
   "plain" plaintext
   "no-highlight" plaintext
   "txt" plaintext
   "plutus-core" plutus-core
   "plc" plutus-core
   "pony" pony
   "postgresql-sql-dialect" postgresql-sql-dialect
   "postgresql" postgresql-sql-dialect
   "postgres" postgresql-sql-dialect
   "postscript" postscript
   "postscr" postscript
   "ps" postscript
   "eps" postscript
   "povray" povray
   "pov" povray
   "powerquery" powerquery
   "pq" powerquery
   "powershell" powershell
   "posh" powershell
   "ps1" powershell
   "psm1" powershell
   "psd1" powershell
   "pwsh" powershell
   "prolog" prolog
   "ecl" prolog
   "pro" prolog
   "promela" promela
   "pml" promela
   "prom" promela
   "prm" promela
   "pr" promela
   "promql" promql
   "properties" properties
   "java-properties" properties
   "protocol-buffer" protocol-buffer
   "protobuf" protocol-buffer
   "proto" protocol-buffer
   "protocol-buffer-text-format" protocol-buffer-text-format
   "txtpb" protocol-buffer-text-format
   "textproto" protocol-buffer-text-format
   "textpb" protocol-buffer-text-format
   "pbtxt" protocol-buffer-text-format
   "prql" prql
   "psl" psl
   "trig" psl
   "proc" psl
   "puppet" puppet
   "python" python
   "py" python
   "sage" python
   "python3" python
   "py3" python
   "starlark" python
   "pyi" python
   "pyw" python
   "jy" python
   "sc" python
   "sconstruct" python
   "sconscript" python
   "bzl" python
   "buck" python
   "workspace" python
   "star" python
   "tac" python
   "python-2" python-2
   "python2" python-2
   "py2" python-2
   "qbasic" qbasic
   "basic" qbasic
   "bas" qbasic
   "qml" qml
   "qbs" qml
   "r" r
   "splus" r
   "racket" racket
   "rkt" racket
   "rktd" racket
   "rktl" racket
   "ragel" ragel
   "react" react
   "jsx" react
   "reasonml" reasonml
   "reason" reasonml
   "re" reasonml
   "rei" reasonml
   "reg" reg
   "registry" reg
   "rego" rego
   "rexx" rexx
   "arexx" rexx
   "rex" rexx
   "rx" rexx
   "rgbds-assembly" rgbds-assembly
   "rgbasm" rgbds-assembly
   "ring" ring
   "rh" ring
   "rform" ring
   "rpgle" rpgle
   "sqlrpgle" rpgle
   "rpg iv" rpgle
   "rpmspec" rpmspec
   "spec" rpmspec
   "ruby" ruby
   "rb" ruby
   "duby" ruby
   "rbw" ruby
   "rakefile" ruby
   "rake" ruby
   "gemspec" ruby
   "rbx" ruby
   "gemfile" ruby
   "vagrantfile" ruby
   "appraisals" ruby
   "rust" rust
   "rs" rust
   "sas" sas
   "sass" sass
   "scala" scala
   "scdoc" scdoc
   "scd" scdoc
   "scheme" scheme
   "scm" scheme
   "ss" scheme
   "scilab" scilab
   "sci" scilab
   "sce" scilab
   "tst" scilab
   "scss" scss
   "sed" sed
   "gsed" sed
   "ssed" sed
   "sieve" sieve
   "siv" sieve
   "smali" smali
   "smalltalk" smalltalk
   "squeak" smalltalk
   "st" smalltalk
   "smarty" smarty
   "tpl" smarty
   "snbt" snbt
   "snobol" snobol
   "solidity" solidity
   "sol" solidity
   "sourcepawn" sourcepawn
   "sp" sourcepawn
   "spade" spade
   "sparql" sparql
   "rq" sparql
   "squidconf" squidconf
   "squid.conf" squidconf
   "squid" squidconf
   "stas" stas
   "stylus" stylus
   "styl" stylus
   "swift" swift
   "systemd" systemd
   "automount" systemd
   "device" systemd
   "dnssd" systemd
   "link" systemd
   "mount" systemd
   "netdev" systemd
   "path" systemd
   "scope" systemd
   "slice" systemd
   "swap" systemd
   "target" systemd
   "timer" systemd
   "systemverilog" systemverilog
   "sv" systemverilog
   "svh" systemverilog
   "tablegen" tablegen
   "td" tablegen
   "tal" tal
   "uxntal" tal
   "tasm" tasm
   "tcl" tcl
   "rvt" tcl
   "tcsh" tcsh
   "csh" tcsh
   "termcap" termcap
   "terminfo" terminfo
   "terraform" terraform
   "tf" terraform
   "tex" tex
   "latex" tex
   "aux" tex
   "toc" tex
   "thrift" thrift
   "toml" toml
   "pipfile" toml
   "tradingview" tradingview
   "tv" tradingview
   "transact-sql" transact-sql
   "tsql" transact-sql
   "t-sql" transact-sql
   "turing" turing
   "tu" turing
   "turtle" turtle
   "ttl" turtle
   "twig" twig
   "typescript" typescript
   "ts" typescript
   "tsx" typescript
   "mts" typescript
   "cts" typescript
   "typoscript" typoscript
   "typoscriptcssdata" typoscriptcssdata
   "typoscripthtmldata" typoscripthtmldata
   "typst" typst
   "typ" typst
   "ucode" ucode
   "uc" ucode
   "vlang" v
   "vv" v
   "v-shell" v-shell
   "vsh" v-shell
   "vshell" v-shell
   "vala" vala
   "vapi" vala
   "vb-net" vb-net
   "vb.net" vb-net
   "vbnet" vb-net
   "vb" vb-net
   "verilog" verilog
   "vhdl" vhdl
   "vhd" vhdl
   "vhs" vhs
   "tape" vhs
   "cassette" vhs
   "viml" viml
   "vim" viml
   "vimrc" viml
   "gvimrc" viml
   "vue" vue
   "vuejs" vue
   "wdte" wdte
   "webassembly-text-format" webassembly-text-format
   "wast" webassembly-text-format
   "wat" webassembly-text-format
   "webgpu-shading-language" webgpu-shading-language
   "wgsl" webgpu-shading-language
   "whiley" whiley
   "xml" xml
   "xsl" xml
   "rss" xml
   "xsd" xml
   "wsdl" xml
   "wsf" xml
   "svg" xml
   "qrc" xml
   "csproj" xml
   "vcxproj" xml
   "fsproj" xml
   "xorg" xorg
   "xorg.conf" xorg
   "yaml" yaml
   "yml" yaml
   "yang" yang
   "z80-assembly" z80-assembly
   "z80" z80-assembly
   "zed" zed
   "zig" zig
   "zon" zig})

(defn get-grammar
  "Look up a grammar by language name (case-insensitive). Returns nil if unknown."
  [lang]
  (when lang
    (get registry (-> lang .toLowerCase .trim))))
