{
  # Dev shell that pins the Claude CLI for the SDK runner (runner/runner.mjs
  # resolves `claude` from PATH). The CLI gates new model ids on its own
  # version, and nixpkgs lags upstream by days–weeks, so we track the release
  # ourselves: nix/claude-code-manifest.json is the upstream release manifest
  # and is fed into nixpkgs' claude-code package via its `manifest` override.
  # Bump with `bb claude:update` (fetches the latest manifest), then
  # `direnv reload` and `bb serve:restart`.
  description = "xi dev shell — Claude CLI pinned independently of the system";

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
          manifest = pkgs.lib.importJSON ./nix/claude-code-manifest.json;
        };
        default = claude-code;
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ self.packages.${pkgs.stdenv.hostPlatform.system}.claude-code ];
        };
      });
    };
}
