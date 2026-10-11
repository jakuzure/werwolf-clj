(ns werwolf.build-info
  "The app version, read from package.json at COMPILE time.

   package.json is the one file release-please bumps, so this is the single
   source of truth for both halves: the macro expands to a string literal, baked
   into the AOT-compiled server and into the JS bundle alike. Nothing reads the
   file at run time, which matters because neither the uberjar nor the browser
   has it.

   The commit comes the same way, for the same reason: \"which build is this\"
   between two releases, when the version number alone has not changed yet."
  (:require [clojure.string :as str]))

(defmacro package-version
  "The \"version\" field of package.json in the working directory, or \"dev\"
   when there is none. Every build in this project runs from the repository root:
   bb, shadow-cljs, tools.build and the nix sandbox."
  []
  (let [f (java.io.File. "package.json")]
    (or (when (.exists f)
          (second (re-find #"\"version\"\s*:\s*\"([^\"]+)\"" (slurp f))))
        "dev")))

(defmacro build-commit
  "The short commit this build was made from.

   WERWOLF_COMMIT first, because the nix sandbox has no git history: the flake
   knows the revision and passes it in (and clj-nix puts a stand-in git on the
   PATH there, which must not be asked). Otherwise git itself, which in this
   jj-colocated repo answers with the last real commit, not the working copy.
   \"dev\" when neither is available."
  []
  (or (not-empty (System/getenv "WERWOLF_COMMIT"))
      (try
        (let [p   (.start (ProcessBuilder. ["git" "rev-parse" "--short=7" "HEAD"]))
              out (str/trim (slurp (.getInputStream p)))]
          (when (and (zero? (.waitFor p)) (re-matches #"[0-9a-f]{7,40}" out))
            out))
        (catch Exception _ nil))
      "dev"))
