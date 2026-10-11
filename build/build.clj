(ns build
  "Uberjar build. Run via: clojure -T:build uber
   Assumes the frontend is already compiled into resources/public (bb uberjar does that)."
  (:require [clojure.tools.build.api :as b]))

(def lib 'werwolf/werwolf-clj)

;; The version lives in package.json, the one file release-please bumps. Read
;; with a regex rather than a JSON library, which the :build basis doesn't have.
(def version
  (or (second (re-find #"\"version\"\s*:\s*\"([^\"]+)\"" (slurp "package.json")))
      (throw (ex-info "no version in package.json" {}))))
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src/clj" "src/cljc" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     (basis)
                  :src-dirs  ["src/clj" "src/cljc"]
                  :class-dir class-dir
                  :ns-compile '[werwolf.main]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     (basis)
           :main      'werwolf.main})
  (println "built" uber-file))
