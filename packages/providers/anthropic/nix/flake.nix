{
  # The Claude CLI used by the Anthropic SDK runner (../runner.mjs).
  #
  # The CLI gates new model ids on its own version, and nixpkgs lags upstream
  # by days–weeks, so we track the release ourselves:
  # claude-code-manifest.json is the upstream release manifest and is fed into
  # nixpkgs' claude-code package via its `manifest` override.
  #
  # Nothing has to be built by hand: the runner builds this to the `../claude`
  # out-link whenever that is missing or not the pinned version. `bb
  # claude:update` bumps the manifest, `bb claude:build` forces a build.
  #
  # Kept in its own directory and used as a `path:` flake, so evaluating it
  # copies just these three files into the store — not node_modules, and not
  # the whole repo — and it works the same on hosts that have no git checkout.
  description = "Claude CLI pinned for xi's Anthropic runner";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system:
        f (import nixpkgs { inherit system; config.allowUnfree = true; }));
    in
    {
      packages = forAllSystems (pkgs: rec {
        claude-code = pkgs.claude-code.override {
          manifest = pkgs.lib.importJSON ./claude-code-manifest.json;
        };
        default = claude-code;
      });
    };
}
