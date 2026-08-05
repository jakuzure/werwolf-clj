(ns build
  "Uberjar build. Run via: clojure -T:build uber
   Assumes the frontend is already compiled into resources/public (bb uberjar does that)."
  (:require [clojure.tools.build.api :as b]))

(def lib 'werwolf/werwolf-clj)

;; jj-colocated repos can legitimately have zero git commits; don't fail the build.
(def revs (try (b/git-count-revs nil) (catch Exception _ "0")))
(def version (format "0.1.%s" revs))
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
