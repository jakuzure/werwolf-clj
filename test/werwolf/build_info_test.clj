(ns werwolf.build-info-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [werwolf.build-info :refer [package-version]]))

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
