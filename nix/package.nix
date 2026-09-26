# The werwolf server: one uberjar with the compiled frontend inside it, plus a
# wrapper script that runs it on a headless JDK 21.
#
# Built entirely offline. Maven artifacts come from deps-lock.json (clj-nix),
# npm packages from package-lock.json (importNpmLock), so the same three steps
# `bb uberjar` runs on the Mac run here with no network:
#
#   1. shadow-cljs release   -> resources/public/js
#   2. tailwind --minify     -> resources/public/css/main.css
#   3. tools.build uber      -> target/*-standalone.jar
{ lib
, mkCljBin
, importNpmLock
, nodejs_22
, jdk21_headless
, version
}:

let
  # Only what the build reads. Leaving out target/, node_modules/, data/ and the
  # rest matters twice: a local dev build lying around would otherwise end up
  # in the jar (the same 30 MB accident build:cljs guards against), and any
  # change to those files would needlessly rebuild.
  src = lib.fileset.toSource {
    root = ./..;
    fileset = lib.fileset.unions [
      ../deps.edn
      ../deps-lock.json
      ../shadow-cljs.edn
      ../package.json
      ../package-lock.json
      ../build
      ../src
      (lib.fileset.difference ../resources (lib.fileset.unions [
        (lib.fileset.maybeMissing ../resources/public/js)
        (lib.fileset.maybeMissing ../resources/public/css/main.css)
      ]))
    ];
  };

  nodeModules = importNpmLock.buildNodeModules {
    npmRoot = lib.fileset.toSource {
      root = ./..;
      fileset = lib.fileset.unions [ ../package.json ../package-lock.json ];
    };
    nodejs = nodejs_22;
    # @playwright/test is a devDependency for the browser suite. It would not
    # download browsers on install anyway, but say so rather than rely on it.
    derivationArgs.env.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1";
  };
in
mkCljBin {
  name = "werwolf/werwolf-clj";
  inherit version;
  projectSrc = src;
  main-ns = "werwolf.main";
  jdkRunner = jdk21_headless;

  nativeBuildInputs = [ nodejs_22 ];

  # Same memory cap the Dockerfile uses: a fraction of whatever the service is
  # allowed, so a systemd MemoryMax is respected instead of overrun.
  java-opts = [ "-XX:MaxRAMPercentage=75" ];

  buildCommand = ''
    ln -s ${nodeModules}/node_modules node_modules

    # Called directly rather than through the npm `shadow-cljs` launcher, which
    # would only compute this same classpath from shadow-cljs.edn's aliases.
    clj -M:cljs -m shadow.cljs.devtools.cli release app

    node node_modules/@tailwindcss/cli/dist/index.mjs \
      -i ./src/css/main.css -o ./resources/public/css/main.css --minify

    clj -T:build uber
  '';

  meta = {
    description = "Browser-based Werwolf game server";
    mainProgram = "werwolf-clj";
    platforms = lib.platforms.unix;
  };
}
