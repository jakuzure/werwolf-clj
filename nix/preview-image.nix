# The PR preview image: werwolf as a nix-snapshotter image (`nix:0/nix/store/…`)
# that lain's k3s starts straight from its nix store, substituting the paths
# from Cachix. No registry, no layers: the image tar only names store paths,
# and nix-snapshotter bind-mounts them into the container.
#
# The image runs the werwolf package itself, not a copy of it. nix-snapshotter
# can only mount DIRECTORY store paths (nix-snapshotter#183), and the package
# is built of directories for exactly that reason (see nix/package.nix): so
# what k3s pulls for a preview is what lain's werwolf service needs too, and a
# deploy after a preview has nothing left to fetch.
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
, writeText
, closureInfo
, nix-snapshotter
, werwolf
}:

let
  # Only what is the same for every preview. TOKEN_SECRET, LiveKit and the
  # room prefix come from the controller's Deployment.
  config = writeText "config-werwolf.json" (builtins.toJSON {
    entrypoint = [ (lib.getExe werwolf) ];
    env = [
      "HOST=0.0.0.0"
      "PORT=8080"
      "DB_PATH=/data/werwolf.db"
    ];
  });

  # What nix-snapshotter bind-mounts into the container: everything the
  # entrypoint needs, and nothing else.
  closure = closureInfo { rootPaths = [ werwolf ]; };

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
image
