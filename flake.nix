{
  # Werwolf as a nix package and a NixOS module, for running it as a systemd
  # service on a NixOS host (lain) rather than in Docker or k3s.
  #
  #   nix build             # the server: result/bin/werwolf-clj
  #   nix flake check       # the module evaluates
  #
  # The Docker/Compose path in deploy/ is untouched and still how the Mac runs it.
  description = "werwolf-clj: Die Werwölfe von Düsterwald in the browser";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Builds Clojure offline from deps-lock.json. Regenerate that file whenever
    # deps.edn changes, or the build fails on a missing artifact:
    #   nix run github:jlesquembre/clj-nix#deps-lock -- --alias-exclude dev
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, clj-nix }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      # clj-nix ships its builders as an overlay, not as per-system outputs.
      pkgsFor = system: import nixpkgs {
        inherit system;
        overlays = [ clj-nix.overlays.default ];
      };
      forAll = f: nixpkgs.lib.genAttrs systems (system: f system (pkgsFor system));
    in
    {
      packages = forAll (system: pkgs: rec {
        werwolf = pkgs.callPackage ./nix/package.nix {
          version = "0.1.0+${self.shortRev or self.dirtyShortRev or "unknown"}";
        };
        default = werwolf;
      });

      # Pulls in the package from this flake by default, so a host only has to
      # import the module and set `services.werwolf.enable`.
      nixosModules.werwolf = { pkgs, lib, ... }: {
        imports = [ ./nix/module.nix ];
        services.werwolf.package =
          lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.werwolf;
      };
      nixosModules.default = self.nixosModules.werwolf;
    };
}
