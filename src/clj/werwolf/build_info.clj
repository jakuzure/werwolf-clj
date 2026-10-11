(ns werwolf.build-info
  "The app version, read from package.json at COMPILE time.

   package.json is the one file release-please bumps, so this is the single
   source of truth for both halves: the macro expands to a string literal, baked
   into the AOT-compiled server and into the JS bundle alike. Nothing reads the
   file at run time, which matters because neither the uberjar nor the browser
   has it.")

(defmacro package-version
  "The \"version\" field of package.json in the working directory, or \"dev\"
   when there is none. Every build in this project runs from the repository root:
   bb, shadow-cljs, tools.build and the nix sandbox."
  []
  (let [f (java.io.File. "package.json")]
    (or (when (.exists f)
          (second (re-find #"\"version\"\s*:\s*\"([^\"]+)\"" (slurp f))))
        "dev")))
