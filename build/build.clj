(ns build
  "Two ways to package the server.

     clojure -T:build uber   one standalone jar: what `bb uberjar` and the Docker
                             image use. Assumes the frontend is already compiled
                             into resources/public (bb uberjar does that).

     clojure -T:build libs   the nix package, in two halves: the libraries' AOT
     clojure -T:build app    classes in a jar of their own, and a small app jar
                             compiled against it. See nix/package.nix."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'werwolf/werwolf-clj)

;; The version lives in package.json, the one file release-please bumps. Read
;; with a regex rather than a JSON library, which the :build basis doesn't have.
;; A function rather than a def: `libs` must not need package.json at all, or in
;; nix every release bump would rebuild the library classes for nothing.
(defn- version []
  (or (second (re-find #"\"version\"\s*:\s*\"([^\"]+)\"" (slurp "package.json")))
      (throw (ex-info "no version in package.json" {}))))

(def class-dir "target/classes")

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_] (b/delete {:path "target"}))

(defn- compile-server
  "AOT-compile werwolf.main, and with it everything it requires that is not
   already compiled somewhere on `basis`'s classpath."
  [basis]
  (b/copy-dir {:src-dirs   ["src/clj" "src/cljc" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     basis
                  :src-dirs  ["src/clj" "src/cljc"]
                  :class-dir class-dir
                  :ns-compile '[werwolf.main]}))

;; sqlite-jdbc carries its JNI library for some twenty platforms: 25 MB of the
;; uberjar unpacked, two thirds of it. The image is eclipse-temurin on Ubuntu
;; (glibc), on amd64 or arm64, so those two are the only ones it can ever load —
;; sqlite-jdbc picks org/sqlite/native/<os>/<arch>/ at run time and never looks
;; at the others.
;;
;; The flip side: this jar no longer opens its database on a Mac, on Windows or
;; on Alpine. `bb serve`, `bb dev` and the REPL run from the classpath, not the
;; jar, so nothing local notices.
(def ^:private uber-exclude
  ["org/sqlite/native/(?!Linux/(?:x86_64|aarch64)/).+"])

(defn uber [_]
  (clean nil)
  (compile-server (basis))
  (let [uber-file (format "target/%s-%s-standalone.jar" (name lib) (version))]
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     (basis)
             :exclude   uber-exclude
             :main      'werwolf.main})
    (println "built" uber-file)))

;; ---------------------------------------------------------------------------
;; nix: libraries and app as separate artifacts

;; The library namespaces werwolf requires directly. `libs` compiles them, and
;; with them everything they require.
;;
;; Kept by hand because the point is for `libs` NOT to read src/: in nix that
;; derivation is keyed on deps.edn, deps-lock.json and this file alone, so it is
;; built once and reused by every release until a dependency changes.
;;
;; A stale list is not a broken build. A library namespace missing here is
;; compiled by `app` instead, into the app jar, and `app` names it so it can be
;; added. One listed but no longer used costs a few classes.
(def aot-libs
  '[aero.core
    buddy.sign.jwt
    clojure.tools.logging
    cognitect.transit
    integrant.core
    malli.core
    next.jdbc
    next.jdbc.result-set
    org.httpkit.server
    reitit.ring
    ring.websocket
    ring.websocket.protocols
    tongue.core])

(defn libs
  "AOT-compile `aot-libs` into target/libs.jar.

   Why not leave the libraries as the source they ship as: werwolf's own AOT
   classes point at classes the libraries' protocols and types generate, and
   loaded from source at run time those only exist in a class loader the app's
   classes cannot see. Tried: the server dies on boot with NoClassDefFoundError
   clojure/tools/logging/impl/LoggerFactory, from the first log/info. (Further
   down the same road: werwolf.ws reifies ring.websocket.protocols/Listener.)
   Compiling the libraries too is what the uberjar does implicitly; this keeps
   that, in a jar of its own.

   Same JDK caveat as the uberjar (see deploy/Dockerfile): http-kit decides at
   compile time whether to use virtual threads, so this jar must be built by the
   JDK 21 the server runs on, and is."
  [_]
  (b/delete {:path "target/libs"})
  (b/compile-clj {:basis      (basis)
                  :ns-compile aot-libs
                  :class-dir  "target/libs/classes"})
  (b/jar {:class-dir "target/libs/classes"
          :jar-file  "target/libs.jar"})
  (println "built target/libs.jar"))

(defn app
  "The app jar: werwolf's own classes, sources and resources, nothing else.
   :libs-jar is the jar `libs` built; it goes first on the compile classpath.

   Clojure loads a namespace from its class only when the class is NEWER than
   the source beside it, and compiles the source otherwise. libs.jar was
   compiled after the library jars were released, so its classes win, and
   nothing from it is compiled a second time into the app jar."
  [{:keys [libs-jar]}]
  (clean nil)
  (compile-server (update (basis) :classpath-roots #(cons (str libs-jar) %)))
  (let [strays (->> (file-seq (io/file class-dir))
                    (map #(.getPath (.relativize (.toURI (io/file class-dir)) (.toURI %))))
                    (keep #(second (re-matches #"(.+)__init\.class" %)))
                    (remove #(str/starts-with? % "werwolf/"))
                    (map #(-> % (str/replace "/" ".") (str/replace "_" "-")))
                    sort)]
    (when (seq strays)
      (println "NOTE: library code compiled into the app jar. Add these to"
               "aot-libs in build/build.clj so they are compiled once instead:")
      (run! #(println "  " %) strays)))
  (let [jar-file (format "target/%s-%s.jar" (name lib) (version))]
    (b/jar {:class-dir class-dir
            :jar-file  jar-file
            :main      'werwolf.main})
    (println "built" jar-file)))
