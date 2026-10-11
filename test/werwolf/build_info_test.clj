(ns werwolf.build-info-test
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [werwolf.build-info :refer [build-commit package-version]]))

(deftest the-version-is-package-json's
  ;; Read independently of the macro's own regex, by a different route, so a
  ;; broken pattern cannot agree with itself.
  (let [line (->> (slurp "package.json")
                  str/split-lines
                  (filter #(str/includes? % "\"version\""))
                  first)
        expected (-> line (str/split #"\"") (nth 3))]
    (is (re-matches #"\d+\.\d+\.\d+.*" expected) "package.json has a semver version")
    (is (= expected (package-version)))))

(deftest the-commit-is-the-checked-out-one
  ;; WERWOLF_COMMIT wins when set (the nix build); otherwise git's own answer.
  (let [expected (or (not-empty (System/getenv "WERWOLF_COMMIT"))
                     (str/trim (:out (clojure.java.shell/sh "git" "rev-parse" "--short=7" "HEAD"))))]
    (is (re-matches #"[0-9a-f]{7,40}" (build-commit)))
    (is (= expected (build-commit)))))
