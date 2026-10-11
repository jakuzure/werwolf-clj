(ns ^:dev/always werwolf.build-info
  "The app version for the client. See the macro of the same name in src/clj.

   ^:dev/always because shadow-cljs caches compiled namespaces by their own
   source, and this one's output depends on package.json instead. Without it a
   release bump could ship a bundle still showing the previous version."
  (:require-macros [werwolf.build-info :refer [package-version build-commit]]))

(def version (package-version))

(def commit (build-commit))

(def commit-url
  "A link to the commit, but only for a real hash: \"dev\" or a nix
   \"-dirty\" revision points at nothing on GitHub."
  (when (re-matches #"[0-9a-f]{7,40}" commit)
    (str "https://github.com/jakuzure/werwolf-clj/commit/" commit)))
