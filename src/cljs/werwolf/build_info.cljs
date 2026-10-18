(ns ^:dev/always werwolf.build-info
  "The app version for the client. See the macro of the same name in src/clj.

   ^:dev/always because shadow-cljs caches compiled namespaces by their own
   source, and this one's output depends on package.json instead. Without it a
   release bump could ship a bundle still showing the previous version."
  (:require-macros [werwolf.build-info :refer [package-version]]))

;; The fallback while /api/build has not answered. The commit is only ever
;; the server's: the bundle is the same for every commit that does not change
;; it, so it cannot know which one it was deployed as.
(def version (package-version))
