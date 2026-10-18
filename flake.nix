{
  # Werwolf as a nix package and a NixOS module, for running it as a systemd
  # service on a NixOS host (lain) rather than in Docker or k3s.
  #
  #   nix build             # the server: result/bin/werwolf-clj
  #   nix flake check       # the module evaluates
  #
  # The Docker/Compose path in deploy/ is untouched and still how the Mac runs it.
  description = "Werwölfe von Finsterbach: the social deduction game Werewolf, in the browser";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Builds Clojure offline from deps-lock.json. Regenerate that file whenever
    # deps.edn changes, or the build fails on a missing artifact:
    #   nix run --inputs-from . clj-nix#deps-lock -- --alias-exclude dev
    # --inputs-from, so the lock is written by the clj-nix pinned below. The
    # latest one writes a newer lock format, which this pin refuses to read.
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
          # package.json is the single source of the version; release-please
          # bumps it when a release PR is merged. No commit: the same code is
          # the same package on every commit (see nix/package.nix), and the
          # module below tells the service which commit it is.
          version = (builtins.fromJSON (builtins.readFile ./package.json)).version;
        };
        default = werwolf;
        # The PR preview controller (previews/, nix/previews-module.nix).
        werwolf-previews = pkgs.callPackage ./nix/previews-package.nix { };
      } // nixpkgs.lib.optionalAttrs (system == "x86_64-linux") {
        # What CI builds for every push-* branch and lain runs as a preview.
        preview-image = pkgs.callPackage ./nix/preview-image.nix {
          werwolf = self.packages.${system}.werwolf;
        };
      });

      checks.x86_64-linux.previews = import ./nix/previews-test.nix {
        pkgs = pkgsFor "x86_64-linux";
        previewsModule = self.nixosModules.werwolf-previews;
        previewImage = self.packages.x86_64-linux.preview-image;
      };

      # Pulls in the package from this flake by default, so a host only has to
      # import the module and set `services.werwolf.enable`.
      nixosModules.werwolf = { pkgs, lib, ... }: {
        imports = [ ./nix/module.nix ];
        services.werwolf.package =
          lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.werwolf;
        services.werwolf.commit = lib.mkDefault (self.shortRev or self.dirtyShortRev or null);
      };
      nixosModules.default = self.nixosModules.werwolf;

      # PR previews in the host's k3s; see nix/previews-module.nix.
      nixosModules.werwolf-previews = { pkgs, lib, ... }: {
        imports = [ ./nix/previews-module.nix ];
        services.werwolf.previews.package =
          lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.werwolf-previews;
      };
    };
}
