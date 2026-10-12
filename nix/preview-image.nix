# The PR preview image: werwolf as a nix-snapshotter image (`nix:0/nix/store/…`)
# that lain's k3s starts straight from its nix store, substituting the paths
# from Cachix. No registry, no layers: the image tar only names store paths,
# and nix-snapshotter bind-mounts them into the container.
#
# nix-snapshotter can only mount DIRECTORY store paths (nix-snapshotter#183),
# while the package puts every library jar in a file store path of its own. So
# the jars are copied into directories, split by how often they change:
#
#   deps   every library jar, in classpath order. Built from the package's
#          `libs` alone (deps.edn, deps-lock.json, build/), so a commit that
#          only touches src/ keeps the same store path and nothing is re-fetched.
#   app    werwolf's own small jar and bin/werwolf: the only thing a usual
#          commit changes, a few MB.
#
# Jars are copied WITHOUT their store hash: Nix's reference scanner finds a hash
# anywhere in an output, file names included, and would put every single-jar
# store path back into the closure.
{ lib
, runCommand
, runtimeShell
, callPackage
, nix-snapshotter
, globset
, werwolf
}:

let
  # nixpkgs' own nix-snapshotter.buildImage calls upstream's package.nix without
  # its globset argument and fails to evaluate; call it with one.
  inherit (callPackage "${nix-snapshotter.src}/package.nix" { inherit globset; }) buildImage;
  inherit (werwolf.passthru) libs jdk;

  deps = runCommand "werwolf-clj-deps" { } ''
    mkdir -p $out/lib
    i=0
    while read -r jar; do
      i=$((i + 1))
      name=$(basename "$jar")
      # a numbered name keeps the classpath order visible and two jars that
      # happen to share a file name apart
      dest=$out/lib/$(printf '%03d' "$i")-''${name#*-}
      cp -L "$jar" "$dest"
      echo "$dest" >> $out/classpath
    done < ${libs}/share/werwolf-clj/classpath
  '';

  app = runCommand "werwolf-clj-app-${werwolf.version}" { } ''
    install -Dm644 ${werwolf}/share/java/werwolf-clj.jar $out/share/java/werwolf-clj.jar
    # The same command as the package's bin/werwolf-clj, over the copies. An
    # explicit classpath rather than lib/*: libs.jar has to come first.
    mkdir -p $out/bin
    cat > $out/bin/werwolf <<EOF
    #!${runtimeShell}
    exec "${jdk}/bin/java" -XX:MaxRAMPercentage=75 \\
      -cp "$out/share/java/werwolf-clj.jar:$(paste -sd: ${deps}/classpath)" \\
      werwolf.main "\$@"
    EOF
    chmod +x $out/bin/werwolf
  '';

  image = buildImage {
    name = "werwolf";
    resolvedByNix = true;
    # Only what is the same for every preview. TOKEN_SECRET, LiveKit and the
    # room prefix come from the controller's Deployment.
    config = {
      entrypoint = [ "${app}/bin/werwolf" ];
      env = [
        "HOST=0.0.0.0"
        "PORT=8080"
        "DB_PATH=/data/werwolf.db"
      ];
    };
  };
in
lib.extendDerivation true { inherit deps app; } image
