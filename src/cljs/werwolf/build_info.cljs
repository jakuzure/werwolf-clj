(ns ^:dev/always werwolf.build-info
  "The app version for the client. See the macro of the same name in src/clj.

   ^:dev/always because shadow-cljs caches compiled namespaces by their own
   source, and this one's output depends on package.json instead. Without it a
   release bump could ship a bundle still showing the previous version."
  (:require-macros [werwolf.build-info :refer [package-version]]))

(def version (package-version))
