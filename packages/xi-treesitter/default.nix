# xi-treesitter — native tree-sitter parse CLI + grammar bundle.
#
# Builds:
#   $out/bin/xi-treesitter   — the parse CLI (main.c)
#   $out/grammars/<lang>.so  — grammar shared objects (tree-sitter.withPlugins)
#
# Install for Xi (see docs/treesitter.md):
#   nix-build packages/xi-treesitter -o ~/.config/xi/treesitter
#
# Xi discovers it at $XI_TREESITTER_DIR (default ~/.config/xi/treesitter),
# which must contain bin/xi-treesitter and grammars/<lang>.so.
{ pkgs ? import <nixpkgs> { } }:

let
  grammars = pkgs.tree-sitter.withPlugins (p: [
    p.tree-sitter-typescript
    p.tree-sitter-tsx
    p.tree-sitter-javascript
    p.tree-sitter-python
    p.tree-sitter-rust
    p.tree-sitter-go
    p.tree-sitter-nix
    p.tree-sitter-bash
    p.tree-sitter-clojure
    p.tree-sitter-css
  ]);
in
pkgs.stdenv.mkDerivation {
  pname = "xi-treesitter";
  version = "0.1.0";
  src = ./.;

  buildInputs = [ pkgs.tree-sitter ];

  buildPhase = ''
    cc -O2 -o xi-treesitter main.c -ltree-sitter
  '';

  installPhase = ''
    install -Dm755 xi-treesitter $out/bin/xi-treesitter
    mkdir -p $out
    ln -s ${grammars} $out/grammars
  '';
}
