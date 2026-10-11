(ns werwolf.http-test
  "Caching headers on the static files. The bug these guard against: nix stamps
   every file 1970-01-01, ring sent that as Last-Modified, and browsers cached
   the bundle for years, so players kept the previous version after a deploy."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.http :as http]))

(def ^:private app (http/handler {}))

(defn- GET
  ([uri] (GET uri {}))
  ([uri headers] (app {:request-method :get :uri uri :headers headers})))

(defn- slurp-body [resp]
  (let [b (:body resp)] (when b (slurp b))))

(deftest the-page-is-revalidated-not-dated
  (let [resp (GET "/")]
    (slurp-body resp)
    (is (= 200 (:status resp)))
    (is (= "no-cache" (get-in resp [:headers "Cache-Control"]))
        "the browser must ask before reusing it")
    (is (re-matches #"\"[0-9a-f]{32}\"" (get-in resp [:headers "ETag"] ""))
        "a quoted content fingerprint")
    (is (not-any? #{"Last-Modified" "last-modified"} (keys (:headers resp)))
        "no modification date, which under nix is always 1970")))

(deftest an-unchanged-file-costs-only-a-304
  (let [tag  (get-in (doto (GET "/") slurp-body) [:headers "ETag"])
        resp (GET "/" {"if-none-match" tag})]
    (is (= 304 (:status resp)))
    (is (nil? (:body resp)))
    (is (= tag (get-in resp [:headers "ETag"])))
    (testing "a stale fingerprint gets the full file"
      (let [resp (GET "/" {"if-none-match" "\"0000\""})]
        (is (= 200 (:status resp)))
        (is (re-find #"<html" (slurp-body resp)))))))

(deftest static-files-share-the-rule
  (testing "the resource handler path, not just the index route"
    (let [resp (GET "/index.html")]
      (slurp-body resp)
      (is (= 200 (:status resp)))
      (is (= "no-cache" (get-in resp [:headers "Cache-Control"])))
      (is (= (get-in (doto (GET "/") slurp-body) [:headers "ETag"])
             (get-in resp [:headers "ETag"]))
          "same bytes, same fingerprint, whichever route served them")))
  (testing "a miss is left alone"
    (let [resp (GET "/no-such-file.js")]
      (is (not= 200 (:status resp)))
      (is (nil? (get-in resp [:headers "ETag"]))))))
