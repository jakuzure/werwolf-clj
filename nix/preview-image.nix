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
#
# The image is built with nix2container directly rather than nix-snapshotter's
# buildImage, which puts the image's own config.json, a FILE, into the mounted
# closure. Whether containerd can then start the image depends on where that
# file sorts among the closure's store paths: last, and it fails with "open
# …-config-werwolf.json: not a directory". The config lives in the image tar
# anyway; the closure here is the app's, and the build fails if any path in it
# is not a directory.
{ lib
, runCommand
, runtimeShell
, writeText
, closureInfo
, nix-snapshotter
, werwolf
}:

let
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
    exec "${jdk}/bin/java" -XX:MaxRAMPercentage=75 -Dwerwolf.commit=${werwolf.passthru.commit} \\
      -cp "$out/share/java/werwolf-clj.jar:$(paste -sd: ${deps}/classpath)" \\
      werwolf.main "\$@"
    EOF
    chmod +x $out/bin/werwolf
  '';

  # Only what is the same for every preview. TOKEN_SECRET, LiveKit and the
  # room prefix come from the controller's Deployment.
  config = writeText "config-werwolf.json" (builtins.toJSON {
    entrypoint = [ "${app}/bin/werwolf" ];
    env = [
      "HOST=0.0.0.0"
      "PORT=8080"
      "DB_PATH=/data/werwolf.db"
    ];
  });

  # What nix-snapshotter bind-mounts into the container: everything the
  # entrypoint needs, and nothing else.
  closure = closureInfo { rootPaths = [ app ]; };

  # Referenced as nix:0<this path>; without --ref, that is what nix2container
  # names the image.
  image = runCommand "nix-image-werwolf.tar" { }
    ''
      while read -r path; do
        [ -d "$path" ] || { echo "not a directory, nix-snapshotter cannot mount it: $path" >&2; exit 1; }
      done < ${closure}/store-paths
      echo '[]' > copy-to-root.json
      ${nix-snapshotter}/bin/nix2container build \
        --config ${config} \
        --closure ${closure}/store-paths \
        --copy-to-root copy-to-root.json \
        $out
    '';
in
lib.extendDerivation true { inherit deps app; } image
