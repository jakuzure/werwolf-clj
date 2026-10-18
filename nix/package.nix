# The werwolf server: a small app jar plus a wrapper script that runs it on a
# headless JDK 25, with every library on the classpath as its own store path.
#
# Not an uberjar, deliberately. An uberjar is a fresh 38 MB store path per
# release of which over 90% never changes: sqlite-jdbc's native libraries alone
# are 25 MB of it, BouncyCastle another 14. Split up, a release builds and ships
# only what it changed, and lain substitutes each library once.
#
#   sqlite-jdbc     the sqlite-jdbc jar with only this platform's native library
#   libs            the libraries' AOT classes (build/build.clj `libs`) and the
#                   runtime classpath. Keyed on deps.edn, deps-lock.json and
#                   build/ only, so it is built once per dependency set.
#   frontend        shadow-cljs release + tailwind. Keyed on the frontend's own
#                   sources, so a server-only change does not rebuild it.
#   werwolf-clj     werwolf's own AOT classes, resources and the frontend in one
#                   small jar (build/build.clj `app`), and bin/werwolf-clj.
#
# The library jars themselves are the fixed-output fetches clj-nix makes from
# deps-lock.json: the wrapper names them directly, so the closure holds exactly
# the jars the server runs with, and a jar's store path only changes when its
# version does.
#
# Built entirely offline. Maven artifacts come from deps-lock.json (clj-nix's
# mk-deps-cache), npm packages from package-lock.json (importNpmLock).
{ lib
, stdenv
, runCommand
, fetchurl
, runtimeShell
, clojure
, mk-deps-cache
, importNpmLock
, nodejs_22
, jdk25_headless
, zip
, unzip
, version
}:

let
  # The build JDK is the run JDK on purpose: http-kit picks virtual threads or
  # not at COMPILE time (see deploy/Dockerfile), and `libs` compiles http-kit.
  #
  # 25 rather than nixpkgs's default 21: from 24 on, a virtual thread blocked in
  # `synchronized` no longer pins its carrier thread, and every room serialises
  # its commands with `locking` while http-kit runs handlers on virtual threads.
  # On 21 a busy room tied up a carrier per waiting request.
  jdk = jdk25_headless;
  clj = clojure.override { inherit jdk; };

  lockfile = ../deps-lock.json;

  # Every Maven artifact in deps-lock.json as a fake ~/.m2, one store path per
  # file. The builds below resolve deps.edn against it with no network.
  depsCache = mk-deps-cache { inherit lockfile; };

  # What mkCljBin's buildPhase sets up, which is all of mkCljBin these builds
  # need: it assumes one derivation that ends in one jar. (Its other step,
  # `clj-builder patch-git-sha`, is for git deps, and deps-lock.json has none.)
  cljEnv = ''
    export HOME="${depsCache}"
    export JAVA_TOOL_OPTIONS="-Duser.home=${depsCache}"
    export CLJ_CONFIG="$HOME/.clojure"
    export CLJ_CACHE="$TMPDIR/cp_cache"
    export GITLIBS="$HOME/.gitlibs"
  '';

  # Each derivation gets only what it reads, which is what keeps them apart: a
  # change outside a derivation's files cannot rebuild it. Leaving out target/,
  # node_modules/, data/ and the like matters besides: a local dev build lying
  # around would otherwise end up in the jar (the 30 MB accident build:cljs
  # guards against).
  source = files: lib.fileset.toSource {
    root = ./..;
    fileset = lib.fileset.unions files;
  };

  # resources/ minus what the frontend build writes into it.
  resources = lib.fileset.difference ../resources (lib.fileset.unions [
    (lib.fileset.maybeMissing ../resources/public/js)
    (lib.fileset.maybeMissing ../resources/public/css/main.css)
  ]);

  # ---------------------------------------------------------------------------

  # sqlite-jdbc ships its JNI library for some twenty platforms, 11 MB packed,
  # and loads exactly one: org/sqlite/native/<os>/<arch>/, chosen at run time.
  # Keep that one. The name carries the version and the system, so this is
  # rebuilt when either changes and never otherwise.
  sqlite-jdbc =
    let
      dep = lib.findFirst
        (d: builtins.match "org/xerial/sqlite-jdbc/.*\\.jar" d.mvn-path != null)
        (throw "org.xerial/sqlite-jdbc is not in deps-lock.json")
        (lib.importJSON lockfile).mvn-deps;
      # The same fixed-output fetch mk-deps-cache makes, so the same store path:
      # `libs` relies on that to find it on the resolved classpath.
      jar = fetchurl {
        url = "${lib.removeSuffix "/" dep.mvn-repo}/${dep.mvn-path}";
        inherit (dep) hash;
      };
      p = stdenv.hostPlatform;
      os =
        if p.isDarwin then "Mac"
        else if p.isLinux && p.isMusl then "Linux-Musl"
        else if p.isLinux then "Linux"
        else null;
      arch = { x86_64 = "x86_64"; aarch64 = "aarch64"; }.${p.parsed.cpu.name} or null;
      keep = "org/sqlite/native/${os}/${arch}/";
    in
    # A platform this does not know keeps the whole jar: bigger, but it runs.
    if os == null || arch == null then jar else
    runCommand (lib.removeSuffix ".jar" jar.name + "-${p.system}.jar")
      {
        nativeBuildInputs = [ zip unzip ];
        passthru.original = jar;
      }
      ''
        install -m 644 ${jar} $out
        unzip -Z1 $out | grep -q '^${keep}.' \
          || { echo "sqlite-jdbc has no ${keep} any more" >&2; exit 1; }
        # Everything under org/sqlite/native/ except the kept directory and its
        # parents.
        unzip -Z1 $out | grep '^org/sqlite/native/.' | grep -v '^${keep}' \
          | while read -r entry; do
              case '${keep}' in "$entry"*) ;; *) echo "$entry" ;; esac
            done \
          | zip -q -d $out -@
      '';

  libs = stdenv.mkDerivation {
    # No version: it would carry the commit, and rebuild this every release.
    name = "werwolf-clj-libs";
    src = source [ ../deps.edn ../build ];
    nativeBuildInputs = [ jdk clj ];

    buildPhase = ''
      runHook preBuild
      ${cljEnv}
      clj -T:build libs
      clj -Spath > classpath.raw
      runHook postBuild
    '';

    # The runtime classpath, one entry per line: libs.jar first, so its classes
    # are the ones found, then each library jar resolved out of the deps cache
    # to its own store path, with sqlite-jdbc swapped for the slim one.
    installPhase = ''
      runHook preInstall
      install -Dm644 target/libs.jar $out/share/java/werwolf-clj-libs.jar
      {
        echo $out/share/java/werwolf-clj-libs.jar
        tr ':' '\n' < classpath.raw | grep '\.jar$' | while read -r jar; do
          jar=$(realpath "$jar")
          if [ "$jar" = ${sqlite-jdbc.original or sqlite-jdbc} ]; then
            jar=${sqlite-jdbc}
          fi
          echo "$jar"
        done
      } > classpath
      grep -qxF ${sqlite-jdbc} classpath \
        || { echo "sqlite-jdbc was not found on the classpath to replace" >&2; exit 1; }
      install -Dm644 classpath $out/share/werwolf-clj/classpath
      runHook postInstall
    '';
  };

  nodeModules = importNpmLock.buildNodeModules {
    npmRoot = source [ ../package.json ../package-lock.json ];
    nodejs = nodejs_22;
    # @playwright/test is a devDependency for the browser suite. It would not
    # download browsers on install anyway, but say so rather than rely on it.
    derivationArgs.env.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1";
  };

  # Compiled into the app below in place of the commit, so the build is keyed
  # on the code alone: werwolf.build-info/running-commit never shows it.
  commitPlaceholder = "@werwolf-commit@";

  frontend = stdenv.mkDerivation {
    name = "werwolf-clj-frontend";
    src = source [
      ../deps.edn
      ../shadow-cljs.edn
      ../package.json
      ../package-lock.json
      ../src/cljs
      ../src/cljc
      ../src/css
      # The build-info macros. Only that one file from src/clj, so a server
      # change elsewhere does not rebuild the frontend.
      ../src/clj/werwolf/build_info.clj
      # Tailwind scans index.html for class names (see src/css/main.css).
      (lib.fileset.intersection resources ../resources/public)
    ];
    nativeBuildInputs = [ jdk clj nodejs_22 ];

    buildPhase = ''
      runHook preBuild
      ${cljEnv}
      ln -s ${nodeModules}/node_modules node_modules

      # Called directly rather than through the npm `shadow-cljs` launcher, which
      # would only compute this same classpath from shadow-cljs.edn's aliases.
      clj -M:cljs -m shadow.cljs.devtools.cli release app

      node node_modules/@tailwindcss/cli/dist/index.mjs \
        -i ./src/css/main.css -o ./resources/public/css/main.css --minify
      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall
      mkdir -p $out/public/css
      cp -r resources/public/js $out/public/js
      cp resources/public/css/main.css $out/public/css/main.css
      runHook postInstall
    '';
  };
  # werwolf's own AOT classes, resources and the frontend in one small jar,
  # keyed on the code alone. The commit used to be compiled in, and every
  # commit built its own: the preview of a branch, the PR's merge commit and
  # main after the merge each compiled the very same code again, because each
  # was a different revision.
  app = stdenv.mkDerivation {
    pname = "werwolf-clj-app";
    inherit version;

    src = source [
      ../deps.edn
      # build.clj reads the version from it, and so does werwolf.build-info.
      ../package.json
      ../build
      ../src/clj
      ../src/cljc
      resources
    ];
    nativeBuildInputs = [ jdk clj ];

    # Read by werwolf.build-info at compile time.
    WERWOLF_COMMIT = commitPlaceholder;

    buildPhase = ''
      runHook preBuild
      ${cljEnv}
      cp -r --no-preserve=mode ${frontend}/public/. resources/public/
      clj -T:build app :libs-jar '"${libs}/share/java/werwolf-clj-libs.jar"'
      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall
      install -Dm644 target/werwolf-clj-*.jar $out/share/java/werwolf-clj.jar
      runHook postInstall
    '';
  };
in
# The server: the app jar and the wrapper that runs it. Nothing in it names a
# commit, so a commit that changes no code — CI, docs, a rebase, a merge — is
# the very same store path, and is never built or fetched twice. Which commit
# is running is the deployment's business: the NixOS module sets WERWOLF_COMMIT
# in the service's environment, the preview controller in the pod's.
stdenv.mkDerivation {
  pname = "werwolf-clj";
  inherit version;

  dontUnpack = true;

  # Same memory cap the Dockerfile uses: a fraction of whatever the service is
  # allowed, so a systemd MemoryMax is respected instead of overrun.
  #
  # `-cp … werwolf.main` is what `-jar` did with the uberjar's Main-Class:
  # werwolf.main is AOT-compiled with :gen-class.
  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/java $out/bin
    ln -s ${app}/share/java/werwolf-clj.jar $out/share/java/werwolf-clj.jar
    classpath="${app}/share/java/werwolf-clj.jar:$(paste -sd: ${libs}/share/werwolf-clj/classpath)"

    cat > $out/bin/werwolf-clj <<EOF
    #!${runtimeShell}
    exec "${jdk}/bin/java" -XX:MaxRAMPercentage=75 \\
      -cp "$classpath" \\
      werwolf.main "\$@"
    EOF
    chmod +x $out/bin/werwolf-clj
    runHook postInstall
  '';

  # jdk: the preview image (nix/preview-image.nix) must run on the same one.
  passthru = { inherit app frontend libs sqlite-jdbc jdk; };

  meta = {
    description = "Browser-based Werwolf game server";
    mainProgram = "werwolf-clj";
    license = lib.licenses.mit;
    platforms = lib.platforms.unix;
  };
}
